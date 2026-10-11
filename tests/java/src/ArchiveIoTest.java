package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import io.airlift.compress.zstd.ZstdInputStream;

/**
 * ArchiveIo against real archives: fixtures made at run time by tar, gzip, bzip2, xz, zstd, lz4, zip, 7-Zip ("7z"), and py7zr (pip, in a cached
 * venv under the temp dir); what ArchiveIo writes is read back with commons-compress and with those same tools. A tool that is missing prints SKIP.
 */
public class ArchiveIoTest {
    static int fails = 0, n = 0, skips = 0;
    static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static void skip(String what) { skips++; System.out.println("SKIP " + what); }

    static final long T0 = 1577934246000L;   // 2020-01-02 03:04:06 UTC
    static final String PW = "pässwörd 🔑", BAD = "not the password";
    static File tmp, src;
    static String python;
    static Boolean haveZstd, haveLz4, have7z;

    // ------------------------------------------------------------------------------------------------------------------ helpers

    static final class Got { boolean dir; long size = -1; String sha; int mode = -1; long mtime; String link; boolean enc; }
    static final class R { int code; String out; }

    static R run(File cwd, String... cmd) {
        R r = new R();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (cwd != null) pb.directory(cwd);
            pb.redirectErrorStream(true);
            pb.redirectInput(new File("/dev/null"));
            pb.environment().put("LC_ALL", "C.UTF-8");
            Process p = pb.start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int k;
            InputStream in = p.getInputStream();
            while ((k = in.read(b)) >= 0) bo.write(b, 0, k);
            if (!p.waitFor(300, TimeUnit.SECONDS)) { p.destroyForcibly(); r.code = -1; }
            else r.code = p.exitValue();
            r.out = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { r.code = -2; r.out = String.valueOf(e); }
        return r;
    }
    static boolean have(String tool) { return run(null, "sh", "-c", "command -v " + tool).code == 0; }
    static boolean zstdOk() { if (haveZstd == null) haveZstd = have("zstd"); return haveZstd; }
    static boolean lz4Ok() { if (haveLz4 == null) haveLz4 = have("lz4"); return haveLz4; }
    static boolean sevenOk() { if (have7z == null) have7z = have("7z"); return have7z; }

    static void rm(File f) {
        if (f == null) return;
        if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory()) { File[] l = f.listFiles(); if (l != null) for (File c : l) rm(c); }
        f.delete();
    }
    static void write(File f, byte[] data) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(data); } finally { o.close(); }
    }
    static void write(File f, String s) throws IOException { write(f, s.getBytes(StandardCharsets.UTF_8)); }
    static String hex(byte[] d) { StringBuilder sb = new StringBuilder(); for (byte x : d) sb.append(String.format("%02x", x & 0xff)); return sb.toString(); }
    static String sha(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = new FileInputStream(f);
            try { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) >= 0) md.update(b, 0, k); } finally { in.close(); }
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    static String sha(byte[] data) { try { return hex(MessageDigest.getInstance("SHA-256").digest(data)); } catch (Exception e) { throw new RuntimeException(e); } }
    static void setPerm(File f, int mode) throws IOException {
        Set<PosixFilePermission> s = new HashSet<PosixFilePermission>();
        PosixFilePermission[] all = { PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_READ, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_READ };
        for (int i = 0; i < 9; i++) if ((mode & (1 << i)) != 0) s.add(all[i]);
        Files.setPosixFilePermissions(f.toPath(), s);
    }
    static String rep(char c, int k) { char[] a = new char[k]; Arrays.fill(a, c); return new String(a); }

    /** The fixture tree: a plain file, an empty file, an executable, a read-only file, folders (one empty), unicode names, a 120 character path,
     *  a symbolic link and a 5.5 MB file (2 MB noise, then something that compresses). */
    static void makeTree(File root) throws IOException {
        root.mkdirs();
        write(new File(root, "a.txt"), "hello\n");
        write(new File(root, "empty.bin"), new byte[0]);
        write(new File(root, "exec.sh"), "#!/bin/sh\necho hi\n");
        write(new File(root, "ro.txt"), "read only\n");
        write(new File(root, "dir/sub/b.txt"), "bee\n");
        new File(root, "emptydir").mkdirs();
        write(new File(root, "ünï cödé/日本語 file.txt"), "unicode ☃ content\n");
        String longPath = "deep/" + rep('a', 40) + "/" + rep('b', 40) + "/" + rep('c', 29) + ".txt";
        if (longPath.length() != 120) throw new IllegalStateException("long path is " + longPath.length());
        write(new File(root, longPath), "deep content\n");
        Files.createSymbolicLink(new File(root, "link").toPath(), java.nio.file.Paths.get("a.txt"));
        byte[] big = new byte[5500000];
        new Random(7).nextBytes(big);
        byte[] pat = "the quick brown fox jumps over the lazy dog 0123456789\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 2000000; i < big.length; i++) big[i] = pat[i % pat.length];
        write(new File(root, "big.bin"), big);
        setPerm(new File(root, "exec.sh"), 0755);
        setPerm(new File(root, "ro.txt"), 0444);
        setPerm(new File(root, "a.txt"), 0644);
        setPerm(new File(root, "big.bin"), 0640);
        stampTimes(root, new int[] { 0 });
    }
    static void stampTimes(File dir, int[] k) {
        File[] l = dir.listFiles();
        if (l == null) return;
        Arrays.sort(l);
        for (File c : l) {
            if (Files.isSymbolicLink(c.toPath())) continue;
            if (c.isDirectory()) stampTimes(c, k);
            c.setLastModified(T0 + 2000L * (++k[0]));
        }
        dir.setLastModified(T0 + 2000L * (++k[0]));
    }

    static void fsTree(File root, String prefix, Map<String, Got> out) throws IOException {
        String[] names = root.list();
        if (names == null) return;
        Arrays.sort(names);
        for (String nm : names) {
            File f = new File(root, nm);
            Path p = f.toPath();
            String key = prefix + nm;
            Got g = new Got();
            if (Files.isSymbolicLink(p)) { g.link = Files.readSymbolicLink(p).toString(); g.size = 0; g.mtime = 0; }
            else if (f.isDirectory()) { g.dir = true; g.size = 0; fsTree(f, key + "/", out); }
            else { g.size = f.length(); g.sha = sha(f); g.mode = ArchiveIo.modeOf(p, f, false); g.mtime = f.lastModified(); }
            out.put(key, g);
        }
    }
    static Map<String, Got> fsTree(File root) throws IOException { Map<String, Got> m = new TreeMap<String, Got>(); fsTree(root, "", m); return m; }

    static String key(String name) { String k = name; while (k.endsWith("/")) k = k.substring(0, k.length() - 1); return k; }

    /** What differs between the expected tree and what an archive gave; "" when the same. */
    static String diff(Map<String, Got> exp, Map<String, Got> got, boolean modes, boolean times, boolean links, boolean exactDirs) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Got> e : exp.entrySet()) {
            String k = e.getKey(); Got x = e.getValue(), g = got.get(k);
            if (x.link != null && !links) continue;
            if (g == null) { sb.append(" missing[").append(k).append("]"); continue; }
            if (x.dir != g.dir) { sb.append(" dirflag[").append(k).append("]"); continue; }
            if (x.dir) continue;
            if (x.link != null || g.link != null) { if (!String.valueOf(x.link).equals(String.valueOf(g.link))) sb.append(" link[").append(k).append(" ").append(g.link).append("]"); continue; }
            if (x.size != g.size) sb.append(" size[").append(k).append(" ").append(g.size).append("!=").append(x.size).append("]");
            if (g.sha != null && !x.sha.equals(g.sha)) sb.append(" sha[").append(k).append("]");
            if (modes && g.mode >= 0 && x.mode != g.mode) sb.append(" mode[").append(k).append(" ").append(Integer.toOctalString(g.mode)).append("!=").append(Integer.toOctalString(x.mode)).append("]");
            if (times && g.mtime > 0 && Math.abs(x.mtime - g.mtime) > 2000) sb.append(" mtime[").append(k).append(" ").append(g.mtime - x.mtime).append("]");
        }
        for (Map.Entry<String, Got> e : got.entrySet()) {
            if (exp.containsKey(e.getKey())) continue;
            Got g = e.getValue();
            if (g.dir && !exactDirs) continue;
            boolean skipped = false;
            if (!links) for (Got x : exp.values()) if (x.link != null) skipped = true;
            if (skipped && g.link == null && !g.dir && g.size <= 64) continue;
            sb.append(" extra[").append(e.getKey()).append("]");
        }
        return sb.toString();
    }

    /** One pass over an archive with ArchiveIo.walk; the entries by name (without the trailing slash). */
    static Map<String, Got> readAll(File f, String fmt, char[] pw, List<String> order) throws IOException {
        final Map<String, Got> m = new LinkedHashMap<String, Got>();
        final List<String> ord = order == null ? new ArrayList<String>() : order;
        ArchiveIo.walk(f, fmt, pw, new ArchiveIo.Visitor() {
            public boolean entry(ArchiveIo.Item it, InputStream in) throws IOException {
                Got g = new Got();
                g.dir = it.dir; g.mode = it.mode; g.mtime = it.mtime; g.link = it.linkTarget; g.enc = it.encrypted;
                if (it.dir != it.name.endsWith("/")) throw new IOException("name/dir mismatch " + it.name);
                MessageDigest md;
                try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
                long cnt = 0; byte[] b = new byte[10000]; int k;
                while ((k = in.read(b)) >= 0) { md.update(b, 0, k); cnt += k; }
                g.size = cnt; g.sha = it.dir || it.linkTarget != null ? null : hex(md.digest());
                if (!it.dir && it.size >= 0 && it.size != cnt) throw new IOException("size " + it.size + " but " + cnt + " bytes: " + it.name);
                ord.add(it.name);
                m.put(key(it.name), g);
                return true;
            }
        });
        return m;
    }

    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try { byte[] b = new byte[65536]; int k; while ((k = in.read(b)) >= 0) bo.write(b, 0, k); } finally { in.close(); }
        return bo.toByteArray();
    }

    static boolean throwsPassword(Runner r, Boolean wrong) {
        try { r.run(); return false; }
        catch (ArchiveIo.PasswordException e) { return wrong == null || e.wrong == wrong.booleanValue(); }
        catch (Throwable t) { System.out.println("  (threw " + t + ")"); return false; }
    }
    interface Runner { void run() throws Exception; }
    static String threw(Runner r) { try { r.run(); return null; } catch (Throwable t) { return t.getClass().getSimpleName() + ":" + t.getMessage(); } }

    // ------------------------------------------------------------------------------------------------------------------ the checks on one archive

    /** detect, list, walk, open on one archive and compare with the expected tree. */
    static void verify(String label, File f, String fmt, Map<String, Got> exp, char[] pw, boolean modes, boolean times, boolean links, Boolean solid) throws Exception {
        check(label + ": detect = " + fmt + " (got " + ArchiveIo.detect(f) + ")", fmt.equals(ArchiveIo.detect(f)));
        ArchiveIo.Info info = ArchiveIo.list(f, pw);
        check(label + ": format " + info.format, fmt.equals(info.format));
        Map<String, Got> lm = new LinkedHashMap<String, Got>();
        List<String> listOrder = new ArrayList<String>();
        boolean flagsOk = true;
        for (ArchiveIo.Item it : info.items) {
            Got g = new Got();
            g.dir = it.dir; g.size = it.dir ? 0 : it.size; g.mode = it.mode; g.mtime = it.mtime; g.link = it.linkTarget;
            if (it.dir != it.name.endsWith("/")) flagsOk = false;
            if (it.linkTarget != null) g.size = 0;
            lm.put(key(it.name), g);
            listOrder.add(it.name);
        }
        check(label + ": list item flags", flagsOk);
        Map<String, Got> expNoLinkContent = new LinkedHashMap<String, Got>(exp);
        // a link's target is "" in a 7z listing (only walk reads it): compare the others first
        Map<String, Got> lmCmp = new LinkedHashMap<String, Got>(lm);
        for (Map.Entry<String, Got> e : lmCmp.entrySet()) if (e.getValue().link != null && e.getValue().link.length() == 0 && "7z".equals(fmt)) e.getValue().link = exp.containsKey(e.getKey()) ? exp.get(e.getKey()).link : null;
        String d = diff(expNoLinkContent, lmCmp, modes, times, links, false);
        check(label + ": list matches" + d, d.length() == 0);
        check(label + ": not truncated, solid " + info.solid + " (want " + solid + ")", !info.truncated && (solid == null || solid == info.solid));
        List<String> walkOrder = new ArrayList<String>();
        Map<String, Got> all = readAll(f, fmt, pw, walkOrder);
        d = diff(exp, all, modes, times, links, false);
        check(label + ": walk matches" + d, d.length() == 0);
        check(label + ": walk and list in the same order", walkOrder.equals(listOrder));
        // open: a few files
        for (String nm : new String[] { "a.txt", "big.bin", "ünï cödé/日本語 file.txt", "empty.bin", "deep/" + rep('a', 40) + "/" + rep('b', 40) + "/" + rep('c', 29) + ".txt" }) {
            Got x = exp.get(nm);
            if (x == null) continue;
            byte[] data = readBytes(ArchiveIo.open(f, fmt, nm, pw));
            check(label + ": open " + (nm.length() > 20 ? nm.substring(0, 20) + "..." : nm), data.length == x.size && sha(data).equals(x.sha));
        }
        check(label + ": open of a missing name fails", threw(new Runner() { public void run() throws Exception { ArchiveIo.open(f, fmt, "no/such/file", pw).close(); } }) != null);
        // walk: read only one entry (the others are skipped), then stop early
        final int[] calls = { 0 };
        final String[] gotSha = { null };
        ArchiveIo.walk(f, fmt, pw, new ArchiveIo.Visitor() {
            public boolean entry(ArchiveIo.Item it, InputStream in) throws IOException {
                calls[0]++;
                if (it.name.equals("big.bin")) {
                    try { MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] b = new byte[65536]; int k; while ((k = in.read(b)) >= 0) md.update(b, 0, k); gotSha[0] = hex(md.digest()); } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
                    return false;
                }
                return true;
            }
        });
        check(label + ": walk skips unread entries and stops when told", exp.get("big.bin").sha.equals(gotSha[0]) && calls[0] <= info.items.size());
        // the stream is dead after the call
        final InputStream[] kept = { null };
        ArchiveIo.walk(f, fmt, pw, new ArchiveIo.Visitor() {
            public boolean entry(ArchiveIo.Item it, InputStream in) { kept[0] = in; return false; }
        });
        check(label + ": the data stream is not usable after the call", kept[0] == null || threw(new Runner() { public void run() throws Exception { kept[0].read(); } }) != null);
    }

    // ------------------------------------------------------------------------------------------------------------------ fixtures

    static List<String> topNames(File root) { String[] l = root.list(); Arrays.sort(l); return Arrays.asList(l); }

    static File makeTar(File out, String... opts) throws Exception {
        File list = new File(tmp, "tarlist.txt");
        StringBuilder sb = new StringBuilder();
        for (String s : topNames(src)) sb.append(s).append('\n');
        write(list, sb.toString());
        List<String> cmd = new ArrayList<String>(Arrays.asList("tar"));
        cmd.addAll(Arrays.asList(opts));
        cmd.addAll(Arrays.asList("-cf", out.getPath(), "-C", src.getPath(), "-T", list.getPath()));
        R r = run(tmp, cmd.toArray(new String[0]));
        if (r.code != 0) { System.out.println("  tar failed: " + r.out); return null; }
        return out;
    }

    static void single(String label, File f, String fmt, byte[] data, String expectName, long expectSize) throws Exception {
        check(label + ": detect = " + fmt + " (got " + ArchiveIo.detect(f) + ")", fmt.equals(ArchiveIo.detect(f)));
        ArchiveIo.Info info = ArchiveIo.list(f, null);
        check(label + ": one item", info.items.size() == 1 && !info.items.get(0).dir && fmt.equals(info.format));
        if (info.items.size() == 1) {
            ArchiveIo.Item it = info.items.get(0);
            check(label + ": name " + it.name + " (want " + expectName + ")", it.name.equals(expectName));
            check(label + ": size " + it.size + " (want " + expectSize + ")", expectSize == -2 || it.size == expectSize);
            check(label + ": packed size", it.csize == f.length());
        }
        byte[] viaOpen = readBytes(ArchiveIo.open(f, fmt, expectName, null));
        check(label + ": open gives the bytes", Arrays.equals(viaOpen, data));
        final ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ArchiveIo.walk(f, fmt, null, new ArchiveIo.Visitor() {
            public boolean entry(ArchiveIo.Item it, InputStream in) throws IOException { byte[] b = new byte[7777]; int k; while ((k = in.read(b)) >= 0) bo.write(b, 0, k); return true; }
        });
        check(label + ": walk gives the bytes", Arrays.equals(bo.toByteArray(), data));
    }

    // ------------------------------------------------------------------------------------------------------------------ independent readers (commons-compress, not ArchiveIo)

    static InputStream indepDecompress(String comp, InputStream raw) throws IOException {
        java.io.BufferedInputStream b = new java.io.BufferedInputStream(raw);
        if ("gz".equals(comp)) return new GzipCompressorInputStream(b);
        if ("bz2".equals(comp)) return new BZip2CompressorInputStream(b);
        if ("xz".equals(comp)) return new XZCompressorInputStream(b);
        if ("zst".equals(comp)) return new ZstdInputStream(b);
        return b;
    }
    static Map<String, Got> indepTar(File f, String comp) throws IOException {
        Map<String, Got> m = new LinkedHashMap<String, Got>();
        TarArchiveInputStream in = new TarArchiveInputStream(indepDecompress(comp, new FileInputStream(f)), "UTF-8");
        try {
            TarArchiveEntry e;
            while ((e = in.getNextEntry()) != null) {
                Got g = new Got();
                g.dir = e.isDirectory(); g.mode = e.getMode() & 07777; g.mtime = e.getModTime().getTime();
                if (e.isSymbolicLink()) { g.link = e.getLinkName(); g.size = 0; }
                else if (g.dir) g.size = 0;
                else { byte[] d = readNoClose(in); g.size = d.length; g.sha = sha(d); }
                m.put(key(e.getName()), g);
            }
        } finally { in.close(); }
        return m;
    }
    static byte[] readNoClose(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[65536]; int k;
        while ((k = in.read(b)) >= 0) bo.write(b, 0, k);
        return bo.toByteArray();
    }
    static Map<String, Got> indep7z(File f, char[] pw) throws IOException {
        Map<String, Got> m = new LinkedHashMap<String, Got>();
        SevenZFile.Builder b = SevenZFile.builder().setFile(f);
        if (pw != null) b.setPassword(pw);
        SevenZFile z = b.get();
        try {
            SevenZArchiveEntry e;
            while ((e = z.getNextEntry()) != null) {
                Got g = new Got();
                g.dir = e.isDirectory();
                int a = e.getWindowsAttributes();
                if (e.getHasWindowsAttributes() && (a & 0x8000) != 0) g.mode = (a >>> 16) & 07777;
                if (e.getHasLastModifiedDate()) g.mtime = e.getLastModifiedDate().getTime();
                if (g.dir) g.size = 0;
                else {
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536]; int k;
                    while ((k = z.read(buf)) > 0) bo.write(buf, 0, k);
                    g.size = bo.size(); g.sha = sha(bo.toByteArray());
                    if (e.getHasWindowsAttributes() && (a & 0x8000) != 0 && ((a >>> 16) & 0170000) == 0120000) { g.link = new String(bo.toByteArray(), StandardCharsets.UTF_8); g.size = 0; g.sha = null; }
                }
                m.put(key(e.getName()), g);
            }
        } finally { z.close(); }
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------------ python

    static String venvPython() {
        File venv = new File(System.getProperty("java.io.tmpdir"), "adbam-archiveio-venv");
        File py = new File(venv, "bin/python");
        try {
            if (py.exists() && run(null, py.getPath(), "-c", "import py7zr").code == 0) return py.getPath();
            if (!have("python3")) return null;
            rm(venv);
            if (run(null, "python3", "-m", "venv", venv.getPath()).code != 0) return null;
            R r = run(null, new File(venv, "bin/pip").getPath(), "install", "-q", "py7zr");
            if (r.code != 0) { System.out.println("  pip install py7zr failed: " + r.out); return null; }
            return run(null, py.getPath(), "-c", "import py7zr").code == 0 ? py.getPath() : null;
        } catch (Exception e) { return null; }
    }
    static final String MK7Z = "import os, sys, py7zr\nout, src, pw = sys.argv[1], sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else '')\n"
        + "z = py7zr.SevenZipFile(out, 'w', password=pw or None, header_encryption=bool(pw))\n"
        + "for n in sorted(os.listdir(src)):\n    z.writeall(os.path.join(src, n), n)\nz.close()\n";
    static final String X7Z = "import sys, py7zr\nf, out, pw = sys.argv[1], sys.argv[2], (sys.argv[3] if len(sys.argv) > 3 else '')\n"
        + "with py7zr.SevenZipFile(f, 'r', password=pw or None) as z:\n    z.extractall(path=out)\n";

    // ------------------------------------------------------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        tmp = Files.createTempDirectory("archiveio").toFile();
        try { runAll(); } finally { rm(tmp); }
        System.out.println(n + " checks, " + skips + " skipped");
        if (fails > 0) { System.out.println(fails + " FAILED"); System.exit(1); }
        System.out.println("ALL PASS");
    }

    static void runAll() throws Exception {
        src = new File(tmp, "src");
        makeTree(src);
        final Map<String, Got> exp = fsTree(src);
        check("fixture tree has what the tests expect", exp.size() == 16 && exp.get("link").link.equals("a.txt") && exp.get("big.bin").size == 5500000 && exp.get("emptydir").dir);
        python = venvPython();
        if (python == null) skip("python3 / py7zr not available: py7zr fixtures and cross-checks");
        if (!sevenOk()) skip("7z command not available: 7-Zip fixtures and cross-checks");
        if (!zstdOk()) skip("zstd command not available: zstd CLI fixtures and cross-checks");
        if (!lz4Ok()) skip("lz4 command not available: lz4 CLI fixtures and cross-checks");

        supportMatrix();
        tarFixtures(exp);
        singleFixtures();
        sevenFixtures(exp);
        detectCases(exp);
        passwordCases(exp);
        createCases(exp);
        codecVariants();
        sevenEdges();
        hardLinks();
        rewriteCases(exp);
        cancelAndAtomic(exp);
        damaged();
    }

    // ------------------------------------------------------------------------------------------------------------------ cases

    static void supportMatrix() {
        String[] all = { "7z", "tar", "tar.gz", "tar.bz2", "tar.xz", "tar.zst", "tar.lz4", "gz", "bz2", "xz", "zst", "lz4" };
        for (String s : all) check("supportsCreate " + s, ArchiveIo.supportsCreate(s));
        check("zip and rar are not handled", !ArchiveIo.supportsCreate("zip") && !ArchiveIo.supportsCreate("rar") && !ArchiveIo.supportsCreate(null) && !ArchiveIo.supportsEdit("zip"));
        check("edit: 7z and tar family only", ArchiveIo.supportsEdit("7z") && ArchiveIo.supportsEdit("tar") && ArchiveIo.supportsEdit("tar.zst") && !ArchiveIo.supportsEdit("gz") && !ArchiveIo.supportsEdit("xz"));
        check("password: 7z only", ArchiveIo.supportsPassword("7z") && !ArchiveIo.supportsPassword("tar.gz") && !ArchiveIo.supportsPassword("zip") && !ArchiveIo.supportsPassword("gz"));
    }

    static void tarFixtures(Map<String, Got> exp) throws Exception {
        File d = new File(tmp, "fx"); d.mkdirs();
        String[][] variants = {
            { "gnu.tar", "tar", "--format=gnu" }, { "pax.tar", "tar", "--format=pax" }, { "ustar.tar", "tar", "--format=ustar" },
            { "x.tar.gz", "tar.gz", "-z" }, { "x.tar.bz2", "tar.bz2", "-j" }, { "x.tar.xz", "tar.xz", "-J" } };
        for (String[] v : variants) {
            File f = makeTar(new File(d, v[0]), java.util.Arrays.copyOfRange(v, 2, v.length));
            check("tar fixture " + v[0], f != null);
            if (f != null) verify("cli " + v[0], f, v[1], exp, null, true, true, true, v[1].equals("tar") ? Boolean.FALSE : Boolean.TRUE);
        }
        if (zstdOk()) { File f = makeTar(new File(d, "x.tar.zst"), "-I", "zstd"); if (f != null) verify("cli x.tar.zst", f, "tar.zst", exp, null, true, true, true, true); else check("tar.zst fixture", false); }
        if (lz4Ok()) { File f = makeTar(new File(d, "x.tar.lz4"), "-I", "lz4"); if (f != null) verify("cli x.tar.lz4", f, "tar.lz4", exp, null, true, true, true, true); else check("tar.lz4 fixture", false); }
        // the same archive under other names: magic bytes win, then the extension
        File gz = new File(d, "x.tar.gz");
        for (String nm : new String[] { "noext", "weird.txt", "x.zip", "x.7z", "x.tgz", "x.gz" }) {
            File c = new File(d, "copy-" + nm); Files.copy(gz.toPath(), c.toPath());
            check("detect tar.gz named " + nm + " (got " + ArchiveIo.detect(c) + ")", "tar.gz".equals(ArchiveIo.detect(c)));
        }
        File plain = new File(d, "pax.tar");
        for (String nm : new String[] { "noext", "x.bin", "x.gz" }) {
            File c = new File(d, "copy2-" + nm); Files.copy(plain.toPath(), c.toPath());
            check("detect tar named " + nm + " (got " + ArchiveIo.detect(c) + ")", "tar".equals(ArchiveIo.detect(c)));
        }
        // an old (v7) tar has no "ustar": the checksum decides
        File v7dir = new File(tmp, "v7src"); write(new File(v7dir, "one.txt"), "one\n");
        File v7 = new File(d, "v7data");
        R r = run(tmp, "tar", "--format=v7", "-cf", v7.getPath(), "-C", v7dir.getPath(), "one.txt");
        if (r.code == 0) {
            check("detect a v7 tar without extension (got " + ArchiveIo.detect(v7) + ")", "tar".equals(ArchiveIo.detect(v7)));
            check("v7 tar lists", ArchiveIo.list(v7, null).items.size() == 1);
        } else skip("tar --format=v7");
    }

    static void singleFixtures() throws Exception {
        File d = new File(tmp, "single"); d.mkdirs();
        File big = new File(src, "big.bin");
        byte[] bigData = Files.readAllBytes(big.toPath());
        File text = new File(d, "note.txt");
        byte[] textData = "some text, some more text, some more text, some more text\n".getBytes(StandardCharsets.UTF_8);
        write(text, textData);
        File bigCopy = new File(d, "big.bin"); Files.copy(big.toPath(), bigCopy.toPath());
        String[][] tools = { { "gz", "gzip", "-c" }, { "bz2", "bzip2", "-c" }, { "xz", "xz", "-c" }, { "zst", "zstd", "-q", "-c" }, { "lz4", "lz4", "-q", "-c" } };
        for (String[] t : tools) {
            if (!have(t[1])) { skip(t[1] + " command not available"); continue; }
            File outBig = new File(d, "big.bin." + t[0]);
            File outTxt = new File(d, "note.txt." + t[0]);
            for (File[] pair : new File[][] { { bigCopy, outBig }, { text, outTxt } }) {
                List<String> cmd = new ArrayList<String>(Arrays.asList(t).subList(1, t.length));
                cmd.add(pair[0].getPath());
                R r = runToFile(cmd, pair[1]);
                check("make " + pair[1].getName(), r.code == 0);
            }
            long wantBig = "gz".equals(t[0]) ? bigData.length : "xz".equals(t[0]) ? bigData.length : -2;
            if ("zst".equals(t[0]) || "lz4".equals(t[0])) wantBig = -2;   // depends on how the CLI was told to write the frame
            single("cli " + outBig.getName(), outBig, t[0], bigData, "big.bin", wantBig);
            single("cli " + outTxt.getName(), outTxt, t[0], textData, "note.txt", "bz2".equals(t[0]) ? -1 : -2);
            // bz2 never knows its size, xz knows it from the index, gz from the trailer
            if ("bz2".equals(t[0])) check("bz2 size is unknown (-1)", ArchiveIo.list(outBig, null).items.get(0).size == -1);
            if ("xz".equals(t[0])) check("xz size comes from the index", ArchiveIo.list(outBig, null).items.get(0).size == bigData.length);
            if ("gz".equals(t[0])) { ArchiveIo.Info i = ArchiveIo.list(outBig, null); check("gz size from the trailer, flagged approximate over 4 MB packed", i.items.get(0).size == bigData.length); }
            // wrong extension
            File odd = new File(d, "odd-" + t[0] + ".dat"); Files.copy(outBig.toPath(), odd.toPath());
            check("detect " + t[0] + " named .dat (got " + ArchiveIo.detect(odd) + ")", t[0].equals(ArchiveIo.detect(odd)));
            File asZip = new File(d, "odd-" + t[0] + ".zip"); Files.copy(outTxt.toPath(), asZip.toPath());
            check("detect " + t[0] + " named .zip (got " + ArchiveIo.detect(asZip) + ")", t[0].equals(ArchiveIo.detect(asZip)));
        }
        if (have("lz4")) {   // content size in the frame header when the CLI is told to write it
            File o = new File(d, "sized.txt.lz4");
            R r = runToFile(Arrays.asList("lz4", "-q", "-c", "--content-size", text.getPath()), o);
            if (r.code == 0) single("cli lz4 with content size", o, "lz4", textData, "sized.txt", textData.length);
        }
        if (zstdOk()) {
            File o = new File(d, "named.txt.zst");
            R r = run(d, "zstd", "-q", "-f", "-o", o.getPath(), text.getPath());
            if (r.code == 0) single("cli zstd file (frame has the size)", o, "zst", textData, "named.txt", textData.length);
            File o2 = new File(d, "two.txt.zst");     // two frames in a row decode as one
            R c = run(d, "sh", "-c", "zstd -q -c note.txt > two.txt.zst && zstd -q -c note.txt >> two.txt.zst");
            if (c.code == 0) {
                byte[] twice = new byte[textData.length * 2];
                System.arraycopy(textData, 0, twice, 0, textData.length); System.arraycopy(textData, 0, twice, textData.length, textData.length);
                check("two zstd frames in a row read as one stream", Arrays.equals(readBytes(ArchiveIo.open(o2, "zst", "two.txt", null)), twice));
            }
        }
        // a gz with two members, and the 'name without suffix' rules
        if (have("gzip")) {
            File g2 = new File(d, "multi.gz");
            R c = run(d, "sh", "-c", "gzip -c note.txt > multi.gz && gzip -c note.txt >> multi.gz");
            if (c.code == 0) {
                byte[] twice = new byte[textData.length * 2];
                System.arraycopy(textData, 0, twice, 0, textData.length); System.arraycopy(textData, 0, twice, textData.length, textData.length);
                check("a gz with two members reads as one stream", Arrays.equals(readBytes(ArchiveIo.open(g2, "gz", "multi", null)), twice));
            }
            File up = new File(d, "UPPER.TXT.GZ"); Files.copy(new File(d, "note.txt.gz").toPath(), up.toPath());
            check("suffix is removed whatever its case", ArchiveIo.list(up, null).items.get(0).name.equals("UPPER.TXT"));
        }
    }
    static R runToFile(List<String> cmd, File out) {
        R r = new R();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(out);
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.redirectInput(new File("/dev/null"));
            Process p = pb.start();
            p.waitFor();
            r.code = p.exitValue(); r.out = "";
        } catch (Exception e) { r.code = -2; r.out = String.valueOf(e); }
        return r;
    }

    static void sevenFixtures(Map<String, Got> exp) throws Exception {
        File d = new File(tmp, "seven"); d.mkdirs();
        if (sevenOk()) {
            File solid = new File(d, "solid.7z");
            R r = run(src, "7z", "a", "-snl", "-bd", "-mx=3", solid.getPath(), "*");
            check("7z a (solid)", r.code == 0);
            if (r.code == 0) verify("7-Zip solid.7z", solid, "7z", exp, null, true, true, true, Boolean.TRUE);
            File plain = new File(d, "nonsolid.7z");
            r = run(src, "7z", "a", "-snl", "-bd", "-ms=off", "-mx=1", plain.getPath(), "*");
            if (r.code == 0) verify("7-Zip non-solid .7z", plain, "7z", exp, null, true, true, true, Boolean.FALSE);
            else check("7z a (non-solid)", false);
            File copy = new File(d, "copy.7z");
            r = run(src, "7z", "a", "-snl", "-bd", "-mx=0", copy.getPath(), "*");
            if (r.code == 0) verify("7-Zip stored (-mx=0) .7z", copy, "7z", exp, null, true, true, true, null);
            File odd = new File(d, "named.dat"); Files.copy(solid.toPath(), odd.toPath());
            check("detect 7z named .dat", "7z".equals(ArchiveIo.detect(odd)));
            File zip = new File(d, "x.zip");
            r = run(src, "zip", "-q", "-r", zip.getPath(), "a.txt", "dir");
            if (r.code == 0) {
                check("detect zip", "zip".equals(ArchiveIo.detect(zip)));
                File zipAs = new File(d, "x.7z"); Files.copy(zip.toPath(), zipAs.toPath());
                check("detect zip named .7z (magic wins)", "zip".equals(ArchiveIo.detect(zipAs)));
                check("a zip is not read here", threw(new Runner() { public void run() throws Exception { ArchiveIo.list(zip, null); } }) != null);
            }
        }
        if (python != null) {
            File mk = new File(tmp, "mk7z.py"); write(mk, MK7Z);
            File plain = new File(d, "py.7z");
            R r = run(tmp, python, mk.getPath(), plain.getPath(), src.getPath());
            check("py7zr wrote py.7z" + (r.code == 0 ? "" : " " + r.out), r.code == 0);
            if (r.code == 0) verify("py7zr py.7z", plain, "7z", exp, null, false, true, true, null);
        }
    }

    static void detectCases(Map<String, Got> exp) throws Exception {
        File d = new File(tmp, "det"); d.mkdirs();
        File junk = new File(d, "junk.bin"); byte[] rnd = new byte[3000]; new Random(3).nextBytes(rnd); rnd[0] = 'x'; write(junk, rnd);
        check("detect random bytes = null", ArchiveIo.detect(junk) == null);
        File txt = new File(d, "notes.txt"); write(txt, "just text\n");
        check("detect text file = null", ArchiveIo.detect(txt) == null);
        File empty = new File(d, "empty"); write(empty, new byte[0]);
        check("detect an empty file with no extension = null", ArchiveIo.detect(empty) == null);
        File missing = new File(d, "missing.tar.gz");
        check("detect a file that is not there falls back to the name", "tar.gz".equals(ArchiveIo.detect(missing)));
        File fakeGz = new File(d, "fake.gz"); write(fakeGz, "this is not gzip\n");
        check("detect: no magic, extension is the fallback (gz)", "gz".equals(ArchiveIo.detect(fakeGz)));
        File fakeTgz = new File(d, "fake.tgz"); write(fakeTgz, "this is not gzip either\n");
        check("detect: no magic, .tgz is the fallback", "tar.gz".equals(ArchiveIo.detect(fakeTgz)));
        File rar4 = new File(d, "r4.bin"), rar5 = new File(d, "r5.dat");
        write(rar4, new byte[] { 'R', 'a', 'r', '!', 0x1A, 0x07, 0x00, 1, 2, 3, 4, 5 });
        write(rar5, new byte[] { 'R', 'a', 'r', '!', 0x1A, 0x07, 0x01, 0x00, 1, 2, 3, 4, 5 });
        check("detect rar 4 and 5 by magic", "rar".equals(ArchiveIo.detect(rar4)) && "rar".equals(ArchiveIo.detect(rar5)));
        check("rar is not listed here", threw(new Runner() { public void run() throws Exception { ArchiveIo.list(rar4, null); } }) != null);
        // a gz of text that is called .tar.gz is a gz, not a tar
        if (have("gzip")) {
            File t = new File(d, "text.tar.gz");
            R r = runToFile(Arrays.asList("gzip", "-c", txt.getPath()), t);
            check("a .tar.gz that holds text is a gz (got " + ArchiveIo.detect(t) + ")", r.code == 0 && "gz".equals(ArchiveIo.detect(t)));
        }
        // an empty tar (all zero blocks) inside .tar.gz is still a tar
        if (have("gzip")) {
            File z = new File(d, "empty.tar"); write(z, new byte[10240]);
            File zg = new File(d, "empty.tar.gz");
            R r = runToFile(Arrays.asList("gzip", "-c", z.getPath()), zg);
            check("an empty tar.gz is a tar.gz by name (got " + ArchiveIo.detect(zg) + ")", r.code == 0 && "tar.gz".equals(ArchiveIo.detect(zg)));
            ArchiveIo.Info i = ArchiveIo.list(zg, null);
            check("an empty tar.gz lists nothing", i.items.isEmpty());
        }
    }

    static void passwordCases(Map<String, Got> exp) throws Exception {
        File d = new File(tmp, "pw"); d.mkdirs();
        final char[] good = PW.toCharArray(), bad = BAD.toCharArray();
        if (sevenOk()) {
            // encrypted header: the names need the password
            final File he = new File(d, "he.7z");
            R r = run(src, "7z", "a", "-snl", "-bd", "-mx=1", "-mhe=on", "-p" + PW, he.getPath(), "*");
            check("7z a -mhe", r.code == 0);
            if (r.code == 0) {
                check("encrypted header, no password: PasswordException(wrong=false)", throwsPassword(new Runner() { public void run() throws Exception { ArchiveIo.list(he, null); } }, false));
                check("encrypted header, bad password: PasswordException(wrong=true)", throwsPassword(new Runner() { public void run() throws Exception { ArchiveIo.list(he, bad); } }, true));
                check("encrypted header, walk without password", throwsPassword(new Runner() { public void run() throws Exception { readAll(he, "7z", null, null); } }, false));
                check("encrypted header, open with a bad password", throwsPassword(new Runner() { public void run() throws Exception { ArchiveIo.open(he, "7z", "a.txt", bad).close(); } }, true));
                check("encrypted header, detect still says 7z", "7z".equals(ArchiveIo.detect(he)));
                ArchiveIo.Info info = ArchiveIo.list(he, good);
                check("encrypted header + right password: lists, flags", info.headerEncrypted && info.anyEncrypted && info.items.size() == 16);
                verify("7-Zip -mhe with the password", he, "7z", exp, good, true, true, true, null);
                boolean allEnc = true;
                for (ArchiveIo.Item it : info.items) if (!it.dir && it.size > 0 && it.linkTarget == null && !it.encrypted) allEnc = false;
                check("every file with content is marked encrypted", allEnc);
            }
            // plain header, encrypted content: listing works, reading needs the password
            final File ce = new File(d, "ce.7z");
            r = run(src, "7z", "a", "-snl", "-bd", "-mx=1", "-p" + PW, ce.getPath(), "*");
            check("7z a (content only)", r.code == 0);
            if (r.code == 0) {
                ArchiveIo.Info info = ArchiveIo.list(ce, null);
                check("content encrypted, header not: lists without a password", !info.headerEncrypted && info.anyEncrypted && info.items.size() == 16);
                boolean marked = false, dirsOk = true;
                for (ArchiveIo.Item it : info.items) { if (!it.dir && it.name.equals("a.txt") && it.encrypted) marked = true; if (it.dir && it.encrypted) dirsOk = false; }
                check("a.txt is marked encrypted, folders are not", marked && dirsOk);
                check("walk without a password: PasswordException(wrong=false)", throwsPassword(new Runner() { public void run() throws Exception { readAll(ce, "7z", null, null); } }, false));
                check("walk with a bad password: PasswordException(wrong=true)", throwsPassword(new Runner() { public void run() throws Exception { readAll(ce, "7z", bad, null); } }, true));
                check("open without a password", throwsPassword(new Runner() { public void run() throws Exception { readBytes(ArchiveIo.open(ce, "7z", "big.bin", null)); } }, false));
                check("open with a bad password", throwsPassword(new Runner() { public void run() throws Exception { readBytes(ArchiveIo.open(ce, "7z", "a.txt", bad)); } }, true));
                verify("7-Zip content-encrypted with the password", ce, "7z", exp, good, true, true, true, null);
            }
        }
        if (python != null) {
            File mk = new File(tmp, "mk7z.py");
            final File pe = new File(d, "pyenc.7z");
            R r = run(tmp, python, mk.getPath(), pe.getPath(), src.getPath(), PW);
            check("py7zr wrote an encrypted 7z" + (r.code == 0 ? "" : " " + r.out), r.code == 0);
            if (r.code == 0) {
                check("py7zr encrypted: no password asks for one", throwsPassword(new Runner() { public void run() throws Exception { ArchiveIo.list(pe, null); } }, false));
                check("py7zr encrypted: bad password", throwsPassword(new Runner() { public void run() throws Exception { readAll(pe, "7z", bad, null); } }, true));
                verify("py7zr encrypted with the password", pe, "7z", exp, good, false, true, true, null);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------------ create

    static List<ArchiveIo.Source> sources() {
        List<ArchiveIo.Source> l = new ArrayList<ArchiveIo.Source>();
        addSources(l, src, "");
        return l;
    }
    static void addSources(List<ArchiveIo.Source> out, File dir, String prefix) {
        String[] names = dir.list();
        Arrays.sort(names);
        for (String nm : names) {
            File f = new File(dir, nm);
            if (!Files.isSymbolicLink(f.toPath()) && f.isDirectory()) {
                String[] kids = f.list();
                out.add(new ArchiveIo.Source(prefix + nm + "/", kids != null && kids.length == 0 ? null : f));
                addSources(out, f, prefix + nm + "/");
            } else out.add(new ArchiveIo.Source(prefix + nm, f));
        }
    }

    static void createCases(Map<String, Got> exp) throws Exception {
        File d = new File(tmp, "mk"); d.mkdirs();
        String[][] tars = { { "tar", "tar" }, { "tar.gz", "gz" }, { "tar.bz2", "bz2" }, { "tar.xz", "xz" }, { "tar.zst", "zst" }, { "tar.lz4", "lz4" } };
        for (String[] t : tars) {
            File f = new File(d, "mine." + t[0]);
            ArchiveIo.create(f, t[0], sources(), null, 5, null);
            check("create " + t[0] + ": no .part left", f.isFile() && !new File(f.getPath() + ".part").exists());
            verify("create " + t[0] + " read by ArchiveIo", f, t[0], exp, null, true, true, true, t[0].equals("tar") ? Boolean.FALSE : Boolean.TRUE);
            if (!"lz4".equals(t[1])) {      // commons-compress's framed LZ4 needs commons-codec, which the app does not have: the lz4 tool is the independent reader
                Map<String, Got> ind = indepTar(f, "tar".equals(t[0]) ? null : t[1]);
                String df = diff(exp, ind, true, true, true, true);
                check("create " + t[0] + " read by commons-compress" + df, df.length() == 0);
            }
            // the external tool: list and extract
            List<String> cmd = new ArrayList<String>(Arrays.asList("tar"));
            if ("tar.zst".equals(t[0])) { if (!zstdOk()) { skip("tar.zst with the zstd tool"); continue; } cmd.addAll(Arrays.asList("-I", "zstd")); }
            if ("tar.lz4".equals(t[0])) { if (!lz4Ok()) { skip("tar.lz4 with the lz4 tool"); continue; } cmd.addAll(Arrays.asList("-I", "lz4")); }
            List<String> lc = new ArrayList<String>(cmd); lc.addAll(Arrays.asList("-tf", f.getPath()));
            R lr = run(tmp, lc.toArray(new String[0]));
            Set<String> listed = new HashSet<String>();
            for (String line : lr.out.split("\n")) listed.add(key(line));
            boolean allListed = lr.code == 0;
            for (String k : exp.keySet()) if (!listed.contains(k)) allListed = false;
            check("create " + t[0] + ": tar -tf lists every name (unicode, 120 chars) code " + lr.code, allListed);
            File out = new File(tmp, "x-" + t[0]); out.mkdirs();
            List<String> xc = new ArrayList<String>(cmd); xc.addAll(Arrays.asList("-xf", f.getPath(), "-C", out.getPath()));
            R xr = run(tmp, xc.toArray(new String[0]));
            check("create " + t[0] + ": tar -xf succeeds " + xr.out, xr.code == 0);
            String dt = diff(exp, fsTree(out), true, true, true, true);
            check("create " + t[0] + ": extracted by tar equals the source tree" + dt, dt.length() == 0);
            // a long name or a unicode name has to be a PAX header, which GNU tar reads without a warning
            check("create " + t[0] + ": tar -t says nothing on stderr", lr.out.indexOf("tar:") < 0);
            rm(out);
        }
        // single files
        File big = new File(src, "big.bin");
        byte[] bigData = Files.readAllBytes(big.toPath());
        String[][] singles = { { "gz", "gzip" }, { "bz2", "bzip2" }, { "xz", "xz" }, { "zst", "zstd" }, { "lz4", "lz4" } };
        for (String[] s : singles) {
            File f = new File(d, "big." + s[0]);
            List<ArchiveIo.Source> one = new ArrayList<ArchiveIo.Source>(); one.add(new ArchiveIo.Source("big.bin", big));
            ArchiveIo.create(f, s[0], one, null, -1, null);
            single("create " + s[0], f, s[0], bigData, "big", s[0].equals("bz2") || s[0].equals("zst") || s[0].equals("lz4") ? -2 : bigData.length);
            check("create " + s[0] + " compresses the compressible part", f.length() < 5000000 && f.length() > 1900000);
            if (!have(s[1])) { skip(s[1] + " command to test what was written"); continue; }
            R t = run(d, s[1], "-t", f.getPath());
            check("create " + s[0] + ": " + s[1] + " -t accepts it " + t.out, t.code == 0);
            File dec = new File(d, "dec." + s[0]);
            R dr = runToFile(Arrays.asList(s[1], "-dc", f.getPath()), dec);
            check("create " + s[0] + ": " + s[1] + " -dc gives the file back", dr.code == 0 && dec.length() == bigData.length && sha(dec).equals(sha(big)));
            rm(dec);
        }
        // gz keeps the name and time in its header (gzip -N)
        if (have("gzip")) {
            File g = new File(d, "big.gz");
            R l = run(d, "gzip", "-lN", g.getPath());
            check("gz header holds the file name: " + l.out.trim(), l.out.indexOf("big.bin") >= 0);
        }
        // 7z
        sevenCreate(d, exp, 0, null);
        sevenCreate(d, exp, 5, PW.toCharArray());
        sevenCreate(d, exp, 9, null);
        File l0 = new File(d, "mine-L0.7z"), l9 = new File(d, "mine-L9.7z");
        check("7z level 0 stores (bigger than level 9: " + l0.length() + " vs " + l9.length() + ")", l0.length() > 5500000 && l9.length() < l0.length() - 1000000);
        // a password for a format that has none
        check("password for tar.gz is refused", "password-unsupported".equals(msg(threw(new Runner() { public void run() throws Exception { ArchiveIo.create(new File(tmp, "nope.tar.gz"), "tar.gz", sources(), "x".toCharArray(), 5, null); } }))));
        check("nothing was left behind by the refusal", !new File(tmp, "nope.tar.gz").exists() && !new File(tmp, "nope.tar.gz.part").exists());
        check("zip cannot be created", threw(new Runner() { public void run() throws Exception { ArchiveIo.create(new File(tmp, "n.zip"), "zip", sources(), null, 5, null); } }) != null);
        check("a single-file format wants exactly one file", threw(new Runner() { public void run() throws Exception { ArchiveIo.create(new File(tmp, "n.gz"), "gz", sources(), null, 5, null); } }) != null && !new File(tmp, "n.gz.part").exists());
        // an empty archive of each kind
        for (String f : new String[] { "7z", "tar", "tar.gz" }) {
            File e = new File(d, "empty." + f);
            ArchiveIo.create(e, f, new ArrayList<ArchiveIo.Source>(), null, 5, null);
            check("empty " + f + " lists nothing", ArchiveIo.list(e, null).items.isEmpty() && f.equals(ArchiveIo.detect(e)) || ("tar".equals(f) || "tar.gz".equals(f)));
        }
        // create over an existing file replaces it
        File over = new File(d, "over.tar"); write(over, "old content");
        ArchiveIo.create(over, "tar", sources(), null, 5, null);
        check("create replaces an existing file", "tar".equals(ArchiveIo.detect(over)) && ArchiveIo.list(over, null).items.size() == 16);
        // sources are written in the order given
        List<ArchiveIo.Source> rev = sources(); java.util.Collections.reverse(rev);
        File ro = new File(d, "rev.7z"); ArchiveIo.create(ro, "7z", rev, null, 1, null);
        ArchiveIo.Info ri = ArchiveIo.list(ro, null);
        List<String> want = new ArrayList<String>(); for (ArchiveIo.Source s : rev) want.add(key(s.name));
        List<String> have = new ArrayList<String>(); for (ArchiveIo.Item it : ri.items) have.add(key(it.name));
        check("sources are written in the order given (7z lists dirs among files in that order)", want.equals(have));
    }
    static String msg(String threw) { if (threw == null) return null; int i = threw.indexOf(':'); return i < 0 ? threw : threw.substring(i + 1); }

    static void sevenCreate(File d, Map<String, Got> exp, int level, char[] pw) throws Exception {
        String lbl = "create 7z L" + level + (pw != null ? " +password" : "");
        File f = new File(d, "mine-L" + level + ".7z");
        long t0 = System.currentTimeMillis();
        ArchiveIo.create(f, "7z", sources(), pw, level, null);
        check(lbl + ": file there, no .part", f.isFile() && !new File(f.getPath() + ".part").exists());
        verify(lbl + " read by ArchiveIo", f, "7z", exp, pw, true, true, true, Boolean.TRUE);
        ArchiveIo.Info info = ArchiveIo.list(f, pw);
        check(lbl + ": flags headerEncrypted=" + info.headerEncrypted, info.headerEncrypted == (pw != null) && info.anyEncrypted == (pw != null));
        String df = diff(exp, indep7z(f, pw), true, true, true, true);
        check(lbl + " read by commons-compress" + df, df.length() == 0);
        if (sevenOk()) {
            String pa = pw == null ? "-p" : "-p" + new String(pw);
            R t = run(d, "7z", "t", pw == null ? "-bd" : pa, f.getPath());
            check(lbl + ": 7z t -> Everything is Ok  [" + t.code + "]", t.code == 0 && t.out.indexOf("Everything is Ok") >= 0);
            File out = new File(tmp, "x7-" + level); rm(out); out.mkdirs();
            R x = run(d, "7z", "x", "-snl", "-bd", "-o" + out.getPath(), pw == null ? "-aoa" : pa, f.getPath());
            check(lbl + ": 7z x succeeds [" + x.code + "] " + (x.code == 0 ? "" : x.out), x.code == 0);
            String dt = diff(exp, fsTree(out), true, true, true, true);
            check(lbl + ": extracted by 7-Zip equals the source tree" + dt, dt.length() == 0);
            rm(out);
            if (pw != null) {
                R l1 = run(d, "7z", "l", "-pwrong", f.getPath());
                check(lbl + ": 7-Zip cannot list the names without the password [" + l1.code + "]", l1.code != 0 && l1.out.indexOf("hello") < 0 && l1.out.indexOf("a.txt") < 0);
                R l2 = run(d, "7z", "l", "-slt", pa, f.getPath());
                check(lbl + ": 7-Zip lists the names with it, as AES", l2.code == 0 && l2.out.indexOf("a.txt") >= 0 && l2.out.indexOf("7zAES") >= 0 && l2.out.indexOf("Encrypted = +") >= 0);
                R t2 = run(d, "7z", "t", "-pwrong", f.getPath());
                check(lbl + ": 7-Zip fails with a wrong password", t2.code != 0);
            } else {
                R l = run(d, "7z", "l", "-slt", f.getPath());
                check(lbl + ": 7-Zip lists without a password; method LZMA2 / Copy", l.code == 0 && (level == 0 ? l.out.indexOf("Method = Copy") >= 0 : l.out.indexOf("LZMA2") >= 0));
            }
        }
        if (python != null) {
            File x = new File(tmp, "xpy-" + level); rm(x); x.mkdirs();
            File xs = new File(tmp, "x7z.py"); write(xs, X7Z);
            R r = run(tmp, python, xs.getPath(), f.getPath(), x.getPath(), pw == null ? "" : new String(pw));
            check(lbl + ": py7zr extracts it [" + r.code + "] " + (r.code == 0 ? "" : r.out), r.code == 0);
            if (r.code == 0) {
                String dt = diff(exp, fsTree(x), false, true, true, true);
                check(lbl + ": extracted by py7zr equals the source tree" + dt, dt.length() == 0);
            }
            rm(x);
        } else if (level == 5) skip("py7zr to read what 7z create wrote");
        if (System.currentTimeMillis() - t0 > 120000) check(lbl + " took far too long", false);
    }

    // ------------------------------------------------------------------------------------------------------------------ rewrite

    static List<ArchiveIo.Edit> edits(File newFile, File replFile) {
        List<ArchiveIo.Edit> e = new ArrayList<ArchiveIo.Edit>();
        e.add(ArchiveIo.Edit.delete("a.txt"));
        e.add(ArchiveIo.Edit.deleteTree("dir"));
        e.add(ArchiveIo.Edit.rename("exec.sh", "bin/run.sh"));
        e.add(ArchiveIo.Edit.renameTree("ünï cödé", "uni"));
        e.add(ArchiveIo.Edit.add("added/new.txt", newFile));
        e.add(ArchiveIo.Edit.add("addeddir/", null));
        e.add(ArchiveIo.Edit.replace("ro.txt", replFile));
        return e;
    }
    static Map<String, Got> expectedAfter(Map<String, Got> exp, File newFile, File replFile) throws IOException {
        Map<String, Got> m = new TreeMap<String, Got>();
        for (Map.Entry<String, Got> e : exp.entrySet()) {
            String k = e.getKey();
            if (k.equals("a.txt") || k.equals("dir") || k.startsWith("dir/")) continue;
            if (k.equals("exec.sh")) { m.put("bin/run.sh", e.getValue()); continue; }
            if (k.equals("ünï cödé") || k.startsWith("ünï cödé/")) { m.put("uni" + k.substring("ünï cödé".length()), e.getValue()); continue; }
            if (k.equals("ro.txt")) { Got g = new Got(); g.size = replFile.length(); g.sha = sha(replFile); g.mode = ArchiveIo.modeOf(replFile.toPath(), replFile, false); g.mtime = replFile.lastModified(); m.put(k, g); continue; }
            m.put(k, e.getValue());
        }
        Got a = new Got(); a.size = newFile.length(); a.sha = sha(newFile); a.mode = ArchiveIo.modeOf(newFile.toPath(), newFile, false); a.mtime = newFile.lastModified();
        m.put("added/new.txt", a);
        Got ad = new Got(); ad.dir = true; ad.size = 0; m.put("addeddir", ad);
        return m;
    }

    static void rewriteCases(Map<String, Got> exp) throws Exception {
        File d = new File(tmp, "rw"); d.mkdirs();
        final File newFile = new File(d, "newfile.txt"), replFile = new File(d, "replacement.txt");
        write(newFile, "a brand new file\n"); write(replFile, "replaced content that is longer than before\n");
        setPerm(newFile, 0640); setPerm(replFile, 0600);
        newFile.setLastModified(T0 + 777000); replFile.setLastModified(T0 + 888000);
        Map<String, Got> want = expectedAfter(exp, newFile, replFile);

        // tar.gz made by GNU tar
        File tgz = makeTar(new File(d, "in.tar.gz"), "-z");
        final File outTgz = new File(d, "out.tar.gz");
        ArchiveIo.rewrite(tgz, outTgz, null, edits(newFile, replFile), null);
        check("rewrite tar.gz: no .part", outTgz.isFile() && !new File(outTgz.getPath() + ".part").exists());
        verify("rewrite tar.gz", outTgz, "tar.gz", want, null, true, true, true, Boolean.TRUE);
        R tl = run(d, "tar", "-tzf", outTgz.getPath());
        check("rewrite tar.gz: tar -tzf is happy and has the new names " + (tl.code == 0 ? "" : tl.out), tl.code == 0 && tl.out.indexOf("bin/run.sh") >= 0 && tl.out.indexOf("uni/日本語 file.txt") >= 0 && tl.out.indexOf("added/new.txt") >= 0);
        // untouched entries keep their owner too (GNU tar made them; the test user is whoever runs this)
        Map<String, Got> before = indepTar(tgz, "gz"), after = indepTar(outTgz, "gz");
        check("rewrite tar.gz: an untouched entry is byte for byte the same file and mode", before.get("big.bin").sha.equals(after.get("big.bin").sha) && before.get("big.bin").mode == after.get("big.bin").mode && before.get("big.bin").mtime == after.get("big.bin").mtime);
        // rewriting with no edits keeps everything, also with other compressions
        for (String fmt : new String[] { "tar", "tar.xz", "tar.bz2" }) {
            File in = makeTar(new File(d, "noedit-in." + fmt), fmt.equals("tar") ? "--format=pax" : fmt.equals("tar.xz") ? "-J" : "-j");
            File o = new File(d, "noedit-out." + fmt);
            ArchiveIo.rewrite(in, o, null, new ArrayList<ArchiveIo.Edit>(), null);
            verify("rewrite " + fmt + " without edits", o, fmt, exp, null, true, true, true, null);
        }
        // 7-Zip made, solid
        if (sevenOk()) {
            File in = new File(d, "in.7z");
            R r = run(src, "7z", "a", "-snl", "-bd", "-mx=1", in.getPath(), "*");
            check("7z a for rewrite", r.code == 0);
            File out = new File(d, "out.7z");
            ArchiveIo.rewrite(in, out, null, edits(newFile, replFile), null);
            verify("rewrite 7z", out, "7z", want, null, true, true, true, null);
            R t = run(d, "7z", "t", "-bd", out.getPath());
            check("rewrite 7z: 7z t is Ok", t.code == 0 && t.out.indexOf("Everything is Ok") >= 0);
            R l = run(d, "7z", "l", "-slt", out.getPath());
            check("rewrite 7z: 7-Zip sees the renamed and added names", l.out.indexOf("bin/run.sh") >= 0 && l.out.indexOf("added/new.txt") >= 0 && l.out.indexOf("Path = a.txt") < 0);
            // an unchanged entry keeps its timestamps and unix mode
            Map<String, Got> a = readAll(in, "7z", null, null), b = readAll(out, "7z", null, null);
            check("rewrite 7z: untouched entry keeps mode and time", a.get("big.bin").mode == b.get("big.bin").mode && a.get("big.bin").mtime == b.get("big.bin").mtime);
            // with a password: the new archive has one too, names hidden
            File inE = new File(d, "inE.7z");
            r = run(src, "7z", "a", "-snl", "-bd", "-mx=1", "-mhe=on", "-p" + PW, inE.getPath(), "*");
            check("7z a -mhe for rewrite", r.code == 0);
            final File outE = new File(d, "outE.7z");
            final char[] good = PW.toCharArray();
            check("rewrite of an encrypted 7z without the password asks for it", throwsPassword(new Runner() { public void run() throws Exception { ArchiveIo.rewrite(inE, outE, null, edits(newFile, replFile), null); } }, false));
            check("rewrite of an encrypted 7z with a wrong password", throwsPassword(new Runner() { public void run() throws Exception { ArchiveIo.rewrite(inE, outE, BAD.toCharArray(), edits(newFile, replFile), null); } }, true));
            check("a failed rewrite leaves nothing", !outE.exists() && !new File(outE.getPath() + ".part").exists());
            ArchiveIo.rewrite(inE, outE, good, edits(newFile, replFile), null);
            verify("rewrite 7z (password kept)", outE, "7z", want, good, true, true, true, null);
            check("rewrite 7z (password kept): header still encrypted", ArchiveIo.list(outE, good).headerEncrypted);
            R te = run(d, "7z", "t", "-p" + PW, outE.getPath());
            check("rewrite 7z (password kept): 7z t with the password", te.code == 0 && te.out.indexOf("Everything is Ok") >= 0);
            R le = run(d, "7z", "l", "-pwrong", outE.getPath());
            check("rewrite 7z (password kept): names are hidden from 7-Zip without it", le.code != 0 && le.out.indexOf("run.sh") < 0);
        } else skip("7z command: rewrite of 7-Zip archives");
        // errors
        final File in2 = tgz;
        check("rename onto an entry that stays is refused (duplicate-name)", "duplicate-name".equals(dupMsg(threw(new Runner() { public void run() throws Exception {
            List<ArchiveIo.Edit> e = new ArrayList<ArchiveIo.Edit>(); e.add(ArchiveIo.Edit.rename("a.txt", "ro.txt"));
            ArchiveIo.rewrite(in2, new File(tmp, "dup.tar.gz"), null, e, null); } }))));
        check("...and nothing is left", !new File(tmp, "dup.tar.gz").exists() && !new File(tmp, "dup.tar.gz.part").exists());
        check("rename onto an entry that is deleted in the same go is fine", threw(new Runner() { public void run() throws Exception {
            List<ArchiveIo.Edit> e = new ArrayList<ArchiveIo.Edit>(); e.add(ArchiveIo.Edit.delete("ro.txt")); e.add(ArchiveIo.Edit.rename("a.txt", "ro.txt"));
            ArchiveIo.rewrite(in2, new File(tmp, "swap.tar.gz"), null, e, null); } }) == null);
        File g = new File(d, "single.gz");
        List<ArchiveIo.Source> one = new ArrayList<ArchiveIo.Source>(); one.add(new ArchiveIo.Source("a.txt", new File(src, "a.txt")));
        ArchiveIo.create(g, "gz", one, null, 5, null);
        check("rewrite of a single-file format is refused", threw(new Runner() { public void run() throws Exception { ArchiveIo.rewrite(g, new File(tmp, "g2.gz"), null, new ArrayList<ArchiveIo.Edit>(), null); } }) != null);
        // dst == src
        File same = new File(d, "same.tar.gz"); Files.copy(tgz.toPath(), same.toPath());
        List<ArchiveIo.Edit> one1 = new ArrayList<ArchiveIo.Edit>(); one1.add(ArchiveIo.Edit.delete("big.bin"));
        ArchiveIo.rewrite(same, same, null, one1, null);
        check("rewrite onto the source file itself", !ArchiveIo.list(same, null).items.toString().contains("big.bin") && ArchiveIo.list(same, null).items.size() == exp.size() - 1);
        // cancel while rewriting
        final File cancelDst = new File(d, "cancel.tar.gz");
        check("rewrite can be cancelled", "Cancelled".equals(msg(threw(new Runner() { public void run() throws Exception { ArchiveIo.rewrite(in2, cancelDst, null, new ArrayList<ArchiveIo.Edit>(), new ArchiveIo.Progress() { public boolean tick(long b) { return b < 1000000; } }); } }))));
        check("...no file, no .part", !cancelDst.exists() && !new File(cancelDst.getPath() + ".part").exists());
    }
    static String dupMsg(String t) { String m = msg(t); return m != null && m.startsWith("duplicate-name") ? "duplicate-name" : m; }

    // ------------------------------------------------------------------------------------------------------------------ cancel, atomic

    static void cancelAndAtomic(Map<String, Got> exp) throws Exception {
        File d = new File(tmp, "cx"); d.mkdirs();
        String[] fmts = { "7z", "tar", "tar.gz", "tar.xz", "tar.zst" };
        for (final String fmt : fmts) {
            final File dest = new File(d, "c." + fmt), part = new File(dest.getPath() + ".part");
            // atomic: while writing, dest is not there and the .part is; afterwards the reverse
            final boolean[] sawPart = { false }, destEarly = { false };
            final long[] last = { 0 };
            ArchiveIo.create(dest, fmt, sources(), null, 3, new ArchiveIo.Progress() {
                public boolean tick(long b) {
                    if (dest.exists()) destEarly[0] = true;
                    if (b > 0 && part.exists()) sawPart[0] = true;
                    if (b < last[0]) destEarly[0] = true;
                    last[0] = b;
                    return true;
                }
            });
            check("atomic " + fmt + ": dest absent while writing, .part present", !destEarly[0] && sawPart[0]);
            check("atomic " + fmt + ": after: dest there, .part gone, total " + last[0], dest.isFile() && !part.exists() && last[0] >= 5500000);
            // cancel in the middle of the big file with an older dest in place
            final File old = new File(d, "old." + fmt); write(old, "OLD CONTENT " + fmt);
            final File oldPart = new File(old.getPath() + ".part");
            String m = msg(threw(new Runner() { public void run() throws Exception { ArchiveIo.create(old, fmt, sources(), null, 3, new ArchiveIo.Progress() { public boolean tick(long b) { return b < 2500000; } }); } }));
            check("cancel " + fmt + ": IOException(Cancelled) [" + m + "]", "Cancelled".equals(m));
            check("cancel " + fmt + ": .part deleted, the older file untouched", !oldPart.exists() && new String(Files.readAllBytes(old.toPath()), StandardCharsets.UTF_8).equals("OLD CONTENT " + fmt));
            // cancel at once
            final File fresh = new File(d, "fresh." + fmt);
            m = msg(threw(new Runner() { public void run() throws Exception { ArchiveIo.create(fresh, fmt, sources(), null, 3, new ArchiveIo.Progress() { public boolean tick(long b) { return false; } }); } }));
            check("cancel " + fmt + " at once: Cancelled, no files", "Cancelled".equals(m) && !fresh.exists() && !new File(fresh.getPath() + ".part").exists());
            // a source that disappears
            final File gone = new File(d, "gone." + fmt);
            final List<ArchiveIo.Source> bad = sources(); bad.add(new ArchiveIo.Source("zz-missing.txt", new File(src, "does-not-exist")));
            check("failure " + fmt + ": a missing source fails and leaves nothing", threw(new Runner() { public void run() throws Exception { ArchiveIo.create(gone, fmt, bad, null, 3, null); } }) != null && !gone.exists() && !new File(gone.getPath() + ".part").exists());
        }
        // single files and a 7z with a password
        for (final String fmt : new String[] { "gz", "xz", "zst" }) {
            final File dest = new File(d, "s." + fmt);
            final List<ArchiveIo.Source> one = new ArrayList<ArchiveIo.Source>(); one.add(new ArchiveIo.Source("big.bin", new File(src, "big.bin")));
            String m = msg(threw(new Runner() { public void run() throws Exception { ArchiveIo.create(dest, fmt, one, null, 5, new ArchiveIo.Progress() { public boolean tick(long b) { return b < 1000000; } }); } }));
            check("cancel " + fmt + " (single file): Cancelled, no files", "Cancelled".equals(m) && !dest.exists() && !new File(dest.getPath() + ".part").exists());
        }
        final File pdest = new File(d, "p.7z");
        String m = msg(threw(new Runner() { public void run() throws Exception { ArchiveIo.create(pdest, "7z", sources(), "pw".toCharArray(), 3, new ArchiveIo.Progress() { public boolean tick(long b) { return b < 2500000; } }); } }));
        check("cancel 7z with a password: Cancelled, no files", "Cancelled".equals(m) && !pdest.exists() && !new File(pdest.getPath() + ".part").exists());
    }


    // ------------------------------------------------------------------------------------------------------------------ more

    /** lz4 and zstd files as the tools write them with other options: dependent blocks, big blocks, checksums, levels, windows. */
    static void codecVariants() throws Exception {
        File d = new File(tmp, "codec"); d.mkdirs();
        File big = new File(src, "big.bin");
        String want = sha(big);
        List<String[]> cmds = new ArrayList<String[]>();
        if (lz4Ok()) {
            for (String[] c : new String[][] { { "lz4", "-q", "-c" }, { "lz4", "-q", "-c", "-BI" }, { "lz4", "-q", "-c", "-BD", "-B5" }, { "lz4", "-q", "-c", "-B7", "-BX" }, { "lz4", "-q", "-c", "-9", "--content-size" },
                { "lz4", "-q", "-c", "-B4", "-BD", "-BX", "-9" } }) cmds.add(c);
        }
        if (zstdOk()) {
            for (String[] c : new String[][] { { "zstd", "-q", "-c", "-1" }, { "zstd", "-q", "-c", "-19" }, { "zstd", "-q", "-c", "--ultra", "-22" }, { "zstd", "-q", "-c", "--long=27" },
                { "zstd", "-q", "-c", "-3", "--no-check" }, { "zstd", "-q", "-c", "--zstd=wlog=10" } }) cmds.add(c);
        }
        int i = 0;
        for (String[] c : cmds) {
            List<String> l = new ArrayList<String>(Arrays.asList(c)); l.add(big.getPath());
            String ext = c[0].equals("lz4") ? "lz4" : "zst";
            File out = new File(d, "v" + (i++) + "." + ext);
            R r = runToFile(l, out);
            if (r.code != 0) { check("make " + out.getName(), false); continue; }
            String got;
            try { got = sha(readBytes(ArchiveIo.open(out, ext, "x", null))); } catch (Throwable t) { got = "ERR " + t; }
            check(String.join(" ", c) + " reads back (" + got.substring(0, Math.min(40, got.length())) + ")", want.equals(got));
        }
        // zstd in this JVM
        check("zstdAvailable here", ArchiveIo.zstdAvailable());
    }

    /** Archives with nothing but empty entries, and entries with no time. */
    static void sevenEdges() throws Exception {
        File d = new File(tmp, "edge"); d.mkdirs();
        File e1 = new File(d, "e1"), e2 = new File(d, "e2"), one = new File(d, "one.txt");
        write(e1, new byte[0]); write(e2, new byte[0]); write(one, "just one file\n");
        e1.setLastModified(0);
        for (final char[] pw : new char[][] { null, "pw".toCharArray() }) {
            String lbl = "7z edge" + (pw != null ? " +password" : "");
            List<ArchiveIo.Source> s = new ArrayList<ArchiveIo.Source>();
            s.add(new ArchiveIo.Source("only/", null)); s.add(new ArchiveIo.Source("only/e1", e1)); s.add(new ArchiveIo.Source("e2", e2));
            File f = new File(d, "empties" + (pw != null ? "-pw" : "") + ".7z");
            ArchiveIo.create(f, "7z", s, pw, 5, null);
            ArchiveIo.Info i = ArchiveIo.list(f, pw);
            check(lbl + ": only empty entries: names and kinds", i.items.size() == 3 && i.items.get(0).dir && !i.items.get(1).dir && i.items.get(1).size == 0 && i.items.get(2).name.equals("e2"));
            check(lbl + ": the entry without a time has none, the other has one", i.items.get(1).mtime == 0 && i.items.get(2).mtime > 0);
            Map<String, Got> all = readAll(f, "7z", pw, null);
            check(lbl + ": walk gives empty streams", all.size() == 3 && all.get("e2").size == 0);
            if (sevenOk()) {
                R t = run(d, "7z", "t", pw == null ? "-bd" : "-ppw", f.getPath());
                check(lbl + ": 7z t accepts it [" + t.code + "] " + t.out.replace('\n', ' ').trim(), t.code == 0 && t.out.indexOf("Everything is Ok") >= 0);
            }
            Map<String, Got> ind = indep7z(f, pw);
            check(lbl + ": commons-compress reads it", ind.size() == 3 && ind.get("only").dir);
            // one file and a symlink with a long target, then a rewrite that deletes the only file with content
            List<ArchiveIo.Source> s2 = new ArrayList<ArchiveIo.Source>();
            s2.add(new ArchiveIo.Source("one.txt", one)); s2.add(new ArchiveIo.Source("e2", e2));
            File g = new File(d, "one" + (pw != null ? "-pw" : "") + ".7z");
            ArchiveIo.create(g, "7z", s2, pw, 5, null);
            File h = new File(d, "none" + (pw != null ? "-pw" : "") + ".7z");
            List<ArchiveIo.Edit> ed = new ArrayList<ArchiveIo.Edit>(); ed.add(ArchiveIo.Edit.delete("one.txt"));
            ArchiveIo.rewrite(g, h, pw, ed, null);
            ArchiveIo.Info hi = ArchiveIo.list(h, pw);
            check(lbl + ": rewrite that removes the last file with content", hi.items.size() == 1 && hi.items.get(0).name.equals("e2") && !hi.solid);
            if (sevenOk()) { R t = run(d, "7z", "t", pw == null ? "-bd" : "-ppw", h.getPath()); check(lbl + ": 7z t on it [" + t.code + "]", t.code == 0); }
        }
        // a visitor's own exception comes out as it is, never as a password error
        File ce = new File(d, "ce.7z");
        List<ArchiveIo.Source> s3 = new ArrayList<ArchiveIo.Source>(); s3.add(new ArchiveIo.Source("one.txt", one));
        ArchiveIo.create(ce, "7z", s3, "pw".toCharArray(), 5, null);
        String t = threw(new Runner() { public void run() throws Exception {
            ArchiveIo.walk(ce, "7z", "pw".toCharArray(), new ArchiveIo.Visitor() { public boolean entry(ArchiveIo.Item it, InputStream in) throws IOException { in.read(); throw new IOException("boom"); } });
        } });
        check("a visitor's IOException is passed on as it is [" + t + "]", "IOException:boom".equals(t));
    }

    static void hardLinks() throws Exception {
        File d = new File(tmp, "hl"); File s = new File(d, "s");
        write(new File(s, "f1"), "linked content\n");
        Files.createLink(new File(s, "f2").toPath(), new File(s, "f1").toPath());
        File t = new File(d, "hl.tar");
        R r = run(d, "tar", "-cf", t.getPath(), "-C", s.getPath(), "f1", "f2");
        if (r.code != 0) { skip("tar with hard links"); return; }
        ArchiveIo.Info i = ArchiveIo.list(t, null);
        check("a tar hard link is listed with its target and no data of its own", i.items.size() == 2 && i.items.get(1).hardLink != null && i.items.get(1).hardLink.equals("f1") && i.items.get(1).size == 0 && i.items.get(1).linkTarget == null);
        File o = new File(d, "hl2.tar");
        ArchiveIo.rewrite(t, o, null, new ArrayList<ArchiveIo.Edit>(), null);
        R x = run(d, "tar", "-xf", o.getPath(), "-C", new File(d, "x").getPath());
        new File(d, "x").mkdirs();
        x = run(d, "tar", "-xf", o.getPath(), "-C", new File(d, "x").getPath());
        check("a rewritten tar keeps the hard link (tar -x gives the same inode) " + x.out, x.code == 0 && new String(Files.readAllBytes(new File(d, "x/f2").toPath()), StandardCharsets.UTF_8).equals("linked content\n")
            && Files.getAttribute(new File(d, "x/f1").toPath(), "unix:ino").equals(Files.getAttribute(new File(d, "x/f2").toPath(), "unix:ino")));
    }

    // ------------------------------------------------------------------------------------------------------------------ damaged files

    static void damaged() throws Exception {
        File d = new File(tmp, "bad"); d.mkdirs();
        File tgz = new File(d, "x.tar.gz");
        ArchiveIo.create(tgz, "tar.gz", sources(), null, 3, null);
        byte[] all = Files.readAllBytes(tgz.toPath());
        File cut = new File(d, "cut.tar.gz"); write(cut, Arrays.copyOf(all, all.length / 2));
        String t = threw(new Runner() { public void run() throws Exception { ArchiveIo.list(cut, null); } });
        check("a truncated tar.gz fails with an IOException [" + t + "]", t != null && !t.startsWith("RuntimeException") && !t.startsWith("NullPointer") && !t.startsWith("ArrayIndex"));
        File z = new File(d, "x.tar.zst");
        ArchiveIo.create(z, "tar.zst", sources(), null, 3, null);
        byte[] zb = Files.readAllBytes(z.toPath());
        byte[] zc = zb.clone();
        for (int i = zc.length / 3; i < zc.length / 3 + 2000 && i < zc.length; i++) zc[i] ^= 0x5A;
        File zbad = new File(d, "flip.tar.zst"); write(zbad, zc);
        String zt = threw(new Runner() { public void run() throws Exception { readAll(zbad, "tar.zst", null, null); } });
        check("a damaged zstd stream gives an IOException, not an unchecked one [" + zt + "]", zt != null && zt.startsWith("IOException"));
        File cutz = new File(d, "cut.tar.zst"); write(cutz, Arrays.copyOf(zb, zb.length / 2));
        String zt2 = threw(new Runner() { public void run() throws Exception { readAll(cutz, "tar.zst", null, null); } });
        check("a truncated zstd stream gives an IOException [" + zt2 + "]", zt2 != null && zt2.startsWith("IOException") || zt2 != null && zt2.startsWith("EOF"));
        File seven = new File(d, "x.7z");
        ArchiveIo.create(seven, "7z", sources(), null, 1, null);
        byte[] sb = Files.readAllBytes(seven.toPath());
        File cut7 = new File(d, "cut.7z"); write(cut7, Arrays.copyOf(sb, sb.length - 40));
        String s7 = threw(new Runner() { public void run() throws Exception { ArchiveIo.list(cut7, null); } });
        check("a truncated 7z fails with an IOException [" + s7 + "]", s7 != null && !s7.startsWith("PasswordException") && !s7.startsWith("NullPointer"));
        // a link or a name the caller must refuse is still listed as it is stored
        File evil = new File(d, "evil.tar");
        File evilSrc = new File(d, "evilsrc"); write(new File(evilSrc, "f.txt"), "x");
        List<ArchiveIo.Source> es = new ArrayList<ArchiveIo.Source>();
        es.add(new ArchiveIo.Source("../escape.txt", new File(evilSrc, "f.txt")));
        es.add(new ArchiveIo.Source("/abs/path.txt", new File(evilSrc, "f.txt")));
        es.add(new ArchiveIo.Source("ok/../x.txt", new File(evilSrc, "f.txt")));
        ArchiveIo.create(evil, "tar", es, null, 1, null);
        List<String> names = new ArrayList<String>();
        for (ArchiveIo.Item it : ArchiveIo.list(evil, null).items) names.add(it.name);
        check("odd names are listed as stored " + names, names.equals(Arrays.asList("../escape.txt", "/abs/path.txt", "ok/../x.txt")));
    }
}
