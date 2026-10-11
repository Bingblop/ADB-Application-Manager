package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * A small tar.gz cannot run the app out of memory. A GNU long-name or pax header record is read whole into memory, sized by the number the
 * header declares: a 1.5 MB tar.gz declaring (and holding, as zeros) 1.5 GiB of header gave OutOfMemoryError on open. Such a record over a sane
 * cap is refused in words, and normal long names and pax headers still list and extract. The hostile fixtures are made here, streamed, a few MB
 * at most on disk. The suite runs with a 256 MB heap (run.js), the size the limits are measured against.
 */
public class ArchiveBoundsTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    // ------------------------------------------------------------------------------------------------------------ tar and gzip writers

    /** One 512-byte tar header (ustar, magic "ustar  " as GNU tar writes it). */
    static byte[] header(String name, long size, char type) {
        byte[] b = new byte[512];
        byte[] nm = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nm, 0, b, 0, Math.min(nm.length, 100));
        put(b, 100, "0000644"); put(b, 108, "0000000"); put(b, 116, "0000000");
        put(b, 124, String.format("%011o", size));
        put(b, 136, "00000000000");
        put(b, 148, "        ");
        b[156] = (byte) type;
        put(b, 257, "ustar  ");
        int sum = 0;
        for (byte x : b) sum += x & 0xff;
        put(b, 148, String.format("%06o", sum)); b[154] = 0; b[155] = ' ';
        return b;
    }

    static void put(byte[] b, int off, String s) { byte[] x = s.getBytes(StandardCharsets.US_ASCII); System.arraycopy(x, 0, b, off, x.length); }

    static GZIPOutputStream gz(File f) throws IOException {
        return new GZIPOutputStream(new FileOutputStream(f), 1 << 16) { { def.setLevel(Deflater.BEST_COMPRESSION); } };
    }

    static void zeros(OutputStream o, long n) throws IOException {
        byte[] z = new byte[1 << 20];
        for (long left = n; left > 0; ) { int k = (int) Math.min(z.length, left); o.write(z, 0, k); left -= k; }
    }

    /** Pads the data of an entry to a 512-byte boundary. */
    static void pad(OutputStream o, long size) throws IOException { zeros(o, (512 - size % 512) % 512); }

    /** Ends a tar: two empty blocks. */
    static void end(OutputStream o) throws IOException { zeros(o, 1024); }

    static void entry(OutputStream o, String name, String text) throws IOException {
        byte[] d = text.getBytes(StandardCharsets.UTF_8);
        o.write(header(name, d.length, '0')); o.write(d); pad(o, d.length);
    }

    /**
     * A tar.gz whose first record is a header of {@code type} (L, K, x, g) declaring {@code declared} bytes. The bytes really follow: for a
     * pax record it is one valid "N key=value\n" record of exactly that length, for a name it is zeros then a NUL.
     */
    static File withRecord(File dir, String file, char type, long declared) throws IOException {
        File f = new File(dir, file);
        try (OutputStream o = gz(f)) {
            o.write(header(type == 'x' || type == 'g' ? "PaxHeader/x" : "././@LongLink", declared, type));
            if (type == 'x' || type == 'g') {
                String pre = declared + " comment=";
                o.write(pre.getBytes(StandardCharsets.US_ASCII));
                zeros(o, declared - pre.length() - 1);
                o.write('\n');
            } else {
                byte[] nm = new byte[(int) Math.min(declared, 1 << 20)];
                long left = declared;
                // a name of 'a' for up to 1 MiB; beyond that zeros (no real producer makes it; the fixture only has to hold the bytes)
                java.util.Arrays.fill(nm, (byte) 'a');
                for (long n = left; n > 0; ) { int k = (int) Math.min(nm.length, n); if (n - k == 0) nm[k - 1] = 0; o.write(nm, 0, k); n -= k; java.util.Arrays.fill(nm, (byte) 0); }
            }
            pad(o, declared);
            entry(o, "after.txt", "hello");
            end(o);
        }
        return f;
    }

    /** A pax record "N path=value\n" where N counts the whole record. */
    static byte[] paxRecord(String key, String value) {
        byte[] kv = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
        int len = kv.length + 1;
        while ((len + "").length() + kv.length != len) len = (len + "").length() + kv.length;
        return ((len) + new String(kv, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------------------ helpers for the checks

    static String rep(char c, int n) { char[] a = new char[n]; Arrays.fill(a, c); return new String(a); }

    static String slurp(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }

    /** What list() did: the item names, or the throwable. */
    static final class Outcome {
        List<String> names; Throwable error; long ms;
        String msg() { return error == null ? "" : String.valueOf(error.getMessage()); }
        boolean refused() { return error instanceof IOException && msg().contains("hostile") && !(error instanceof ArchiveIo.PasswordException); }
        boolean notOom() { return !(error instanceof OutOfMemoryError); }
    }

    static Outcome list(File f, String format) {
        Outcome r = new Outcome();
        long t = System.nanoTime();
        try {
            ArchiveIo.Info info = ArchiveIo.list(f, format, null);
            r.names = new ArrayList<String>();
            for (ArchiveIo.Item it : info.items) r.names.add(it.name);
        } catch (Throwable e) { r.error = e; }
        r.ms = (System.nanoTime() - t) / 1000000;
        return r;
    }

    static boolean python(File dir, String script) throws Exception {
        File py = new File(dir, "mk.py");
        Files.write(py.toPath(), script.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder("python3", "-I", py.getPath()).directory(dir).redirectErrorStream(true).start();
        InputStream in = p.getInputStream(); byte[] b = new byte[4096]; while (in.read(b) > 0) { }
        return p.waitFor() == 0;
    }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("archivebounds").toFile();
        long heap = Runtime.getRuntime().maxMemory();
        check("the suite runs with a small heap (" + (heap >> 20) + " MB), so a big allocation is an OutOfMemoryError (run.js passes -Xmx256m)", heap <= 300L << 20, null);
        zBound3(root);
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("ALL PASSED");
    }

    // ------------------------------------------------------------------------------------------------------------ Z-3: huge header records

    static void zBound3(File root) throws Exception {
        long big = 1500L << 20;      // 1.5 GiB, as in the audit; a few MB of gzip

        // normal archives first: they also warm the code up for the timing checks below
        String longName = rep('a', 120) + "/" + rep('b', 120) + "/" + rep('c', 57) + ".txt";         // 300 characters, no part over 255 (a limit of the file system)
        File mine = new File(root, "long-hand.tar.gz");
        try (OutputStream o = gz(mine)) {
            o.write(header("././@LongLink", longName.length() + 1, 'L'));
            o.write((longName + "\0").getBytes(StandardCharsets.UTF_8)); pad(o, longName.length() + 1);
            entry(o, longName, "long body");
            entry(o, "short.txt", "short body");
            end(o);
        }
        Outcome n1 = list(mine, null);
        check("a hand-made tar.gz with a 300 character GNU long name lists", n1.names != null && n1.names.contains(longName) && n1.names.contains("short.txt"), n1.msg());
        ZipTool.Archive a1 = ArchiveIoSource.open(mine, "tar.gz", null);
        File out1 = new File(root, "out-long"); out1.mkdirs();
        List<String> pr = new ArrayList<String>();
        ZipTool.extractTree(a1, "", out1, null, pr);
        check("... and extracts with its body", pr.isEmpty() && "long body".equals(text(new File(out1, longName))) && "short body".equals(text(new File(out1, "short.txt"))), pr.toString());

        // what real tools write: python's tarfile (GNU long names, pax with a long path, unicode and a float time)
        String mk =
            "import tarfile, io\n" +
            "def add(t, name, data):\n" +
            "    ti = tarfile.TarInfo(name); ti.size = len(data); ti.mtime = 1700000000.5\n" +
            "    t.addfile(ti, io.BytesIO(data))\n" +
            "ln = 'a' * 120 + '/' + 'b' * 120 + '/' + 'c' * 57 + '.txt'\n" +
            "with tarfile.open('real-gnu.tar.gz', 'w:gz', format=tarfile.GNU_FORMAT) as t:\n" +
            "    add(t, ln, b'gnu long')\n    add(t, 'b.txt', b'b')\n" +
            "with tarfile.open('real-pax.tar.gz', 'w:gz', format=tarfile.PAX_FORMAT) as t:\n" +
            "    add(t, ln, b'pax long')\n    add(t, '\\u00e9\\u4e2d/' + 'y' * 200 + '/' + 'z' * 100, b'pax unicode')\n" +
            "    ti = tarfile.TarInfo('x.txt'); ti.size = 1; ti.pax_headers = {'comment': 'c' * 5000}\n" +
            "    t.addfile(ti, io.BytesIO(b'x'))\n";
        if (python(root, mk)) {
            Outcome g = list(new File(root, "real-gnu.tar.gz"), null);
            check("python tarfile GNU format, 300 character name: lists", g.names != null && g.names.contains(longName) && g.names.contains("b.txt"), g.msg());
            Outcome p = list(new File(root, "real-pax.tar.gz"), null);
            check("python tarfile pax format, 300 character names, unicode, a 5000 byte pax comment: lists", p.names != null && p.names.size() == 3 && p.names.contains("x.txt"), p.msg() + " " + p.names);
            ZipTool.Archive pa = ArchiveIoSource.open(new File(root, "real-pax.tar.gz"), "tar.gz", null);
            File outp = new File(root, "out-pax"); outp.mkdirs();
            List<String> pp = new ArrayList<String>();
            long[] r = ZipTool.extractTree(pa, "", outp, null, pp);
            check("... and extracts all 3 files", r[0] == 3 && pp.isEmpty(), Arrays.toString(r) + pp);
        } else System.out.println("SKIP python tarfile fixtures (python3 is not available)");

        // exactly at the caps
        File nameMax = withRecord(root, "name-max.tar.gz", 'L', ArchiveIo.MAX_LONGNAME_RECORD);
        Outcome nm = list(nameMax, null);
        check("a long name record of exactly " + ArchiveIo.MAX_LONGNAME_RECORD + " bytes (the cap) is still read", nm.names != null && nm.names.size() == 1 && nm.names.get(0).length() == ArchiveIo.MAX_LONGNAME_RECORD - 1, nm.msg());
        File nameOver = withRecord(root, "name-over.tar.gz", 'L', ArchiveIo.MAX_LONGNAME_RECORD + 1);
        Outcome no = list(nameOver, null);
        check("one byte over the cap is refused in words: " + no.msg(), no.refused() && no.msg().contains("header of " + (ArchiveIo.MAX_LONGNAME_RECORD + 1) + " bytes"), no.error == null ? "listed" : no.error.toString());
        File paxMax = withRecord(root, "pax-max.tar.gz", 'x', ArchiveIo.MAX_PAX_RECORD);
        Outcome pm = list(paxMax, null);
        check("a pax record of exactly " + ArchiveIo.MAX_PAX_RECORD + " bytes (the cap) is still read", pm.names != null && pm.names.contains("after.txt"), pm.msg());
        File paxOver = withRecord(root, "pax-over.tar.gz", 'x', ArchiveIo.MAX_PAX_RECORD + 512);
        Outcome po = list(paxOver, null);
        check("a pax record over the cap is refused in words: " + po.msg(), po.refused(), po.error == null ? "listed" : po.error.toString());

        // the hostile ones of the audit: 1.5 GiB declared and held (as zeros: a few MB of gzip)
        for (char type : new char[] { 'L', 'x' }) {
            File h = withRecord(root, "hostile-" + type + ".tar.gz", type, big);
            check("hostile " + type + " fixture is small (" + h.length() + " bytes for 1.5 GiB)", h.length() < 4_000_000, null);
            Outcome o = list(h, null);
            check("1.5 GiB " + (type == 'L' ? "GNU long name" : "pax") + " header: list() throws an IOException, not an OutOfMemoryError: " + (o.error == null ? "listed" : o.error.toString()), o.error instanceof IOException && o.notOom(), null);
            check("... and says why in words: " + o.msg(), o.refused() && o.msg().contains("header of " + big + " bytes"), null);
            check("... in under a second (" + o.ms + " ms)", o.ms < 1000, null);
        }
        // the other record types and the other entry points
        for (char type : new char[] { 'K', 'g' }) {
            Outcome o = list(withRecord(root, "hostile-" + type + ".tar.gz", type, 64L << 20), null);
            check("a 64 MiB '" + type + "' header record is refused in words: " + (o.error == null ? "listed" : o.msg()), o.refused(), null);
        }
        String werr = null;
        try { ArchiveIo.walk(new File(root, "hostile-L.tar.gz"), null, null, new ArchiveIo.Visitor() { public boolean entry(ArchiveIo.Item it, InputStream d) { return true; } }); }
        catch (IOException e) { werr = e.getMessage(); } catch (Throwable t) { werr = t.toString(); }
        check("walk() (extraction) refuses the 1.5 GiB long name too: " + werr, werr != null && werr.contains("hostile"), null);
        String oerr = null;
        try { ArchiveIo.open(new File(root, "hostile-x.tar.gz"), null, "after.txt", null).close(); }
        catch (IOException e) { oerr = e.getMessage(); } catch (Throwable t) { oerr = t.toString(); }
        check("open() of one entry refuses the 1.5 GiB pax header too: " + oerr, oerr != null && oerr.contains("hostile"), null);
        String serr = null;
        try { ArchiveIoSource.open(new File(root, "hostile-L.tar.gz"), "tar.gz", null); } catch (IOException e) { serr = e.getMessage(); } catch (Throwable t) { serr = t.toString(); }
        check("the file manager's way in (ArchiveIoSource.open) gets the same IOException: " + serr, serr != null && serr.contains("hostile"), null);

        // a plain tar (no compression) with a lying header: the size is not read into memory either
        File plain = new File(root, "lie.tar");
        try (OutputStream o = new FileOutputStream(plain)) { o.write(header("././@LongLink", big, 'L')); zeros(o, 4096); }
        Outcome pl = list(plain, null);
        check("an uncompressed tar whose long name header lies about 1.5 GiB (and is cut short) is refused as hostile: " + pl.msg(), pl.refused(), null);

        for (File f : root.listFiles()) if (f.isFile()) f.delete();
        for (File d : root.listFiles()) deleteTree(d);
        root.delete();
    }

    static void deleteTree(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) deleteTree(c); f.delete(); }

    static String text(File f) throws Exception { return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null; }
}
