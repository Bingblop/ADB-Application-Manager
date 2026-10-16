package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Hard links in a tar are extracted as a copy of the file they share, or skipped in words; they are never written as empty files. */
public class ExtractLinksTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static boolean python(File dir, String script) throws Exception {
        File py = new File(dir, "mk.py");
        Files.write(py.toPath(), script.getBytes(StandardCharsets.UTF_8));
        Process p = new ProcessBuilder("python3", "-I", py.getPath()).directory(dir).redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream(); byte[] b = new byte[4096]; while (in.read(b) > 0) { }
        return p.waitFor() == 0;
    }

    static String text(File f) throws Exception { return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null; }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("extractlinks").toFile();
        String mk =
            "import tarfile, io\n" +
            "def add(t, name, data=None, link=None, kind=None):\n" +
            "    ti = tarfile.TarInfo(name)\n" +
            "    if kind is not None:\n" +
            "        ti.type = kind; ti.linkname = link\n" +
            "        t.addfile(ti)\n" +
            "    else:\n" +
            "        ti.size = len(data); t.addfile(ti, io.BytesIO(data))\n" +
            "with tarfile.open('links.tar', 'w', format=tarfile.GNU_FORMAT) as t:\n" +
            "    add(t, 'a.txt', b'twenty bytes of text')\n" +
            "    add(t, 'b.txt', link='a.txt', kind=tarfile.LNKTYPE)\n" +
            "    add(t, 'sub/c.txt', link='./a.txt', kind=tarfile.LNKTYPE)\n" +
            "    add(t, 'sym.txt', link='a.txt', kind=tarfile.SYMTYPE)\n" +
            "    add(t, 'lonely.txt', link='missing.txt', kind=tarfile.LNKTYPE)\n" +
            "    add(t, 'early.txt', link='late.txt', kind=tarfile.LNKTYPE)\n" +
            "    add(t, 'late.txt', b'late data')\n";
        if (!python(root, mk)) { System.out.println("SKIP (python3 is not available)"); return; }
        File tar = new File(root, "links.tar");
        ZipTool.Archive a = ArchiveIoSource.open(tar, "tar", null);
        ZipTool.Entry b = null;
        for (ZipTool.Entry e : a.entries) if (e.name.equals("b.txt")) b = e;
        check("the listing knows b.txt is a hard link to a.txt", b != null && "a.txt".equals(b.hardLink) && b.size == 0, b == null ? "no b.txt" : String.valueOf(b.hardLink));

        File out = new File(root, "out"); out.mkdirs();
        List<String> problems = new ArrayList<String>();
        long[] r = ZipTool.extractTree(a, "", out, null, problems);
        check("a.txt is extracted", "twenty bytes of text".equals(text(new File(out, "a.txt"))), null);
        check("b.txt (hard link) is a copy of a.txt, not an empty file", "twenty bytes of text".equals(text(new File(out, "b.txt"))), text(new File(out, "b.txt")));
        check("sub/c.txt (hard link named ./a.txt) is a copy too", "twenty bytes of text".equals(text(new File(out, "sub/c.txt"))), text(new File(out, "sub/c.txt")));
        check("a symbolic link is still not extracted", !new File(out, "sym.txt").exists() && problems.toString().contains("symbolic link"), problems.toString());
        check("a hard link to a name that is not in the archive is skipped in words, not written empty", !new File(out, "lonely.txt").exists() && problems.toString().contains("hard link to missing.txt"), problems.toString());
        check("a hard link met before its target is skipped in words", !new File(out, "early.txt").exists() && problems.toString().contains("hard link to late.txt") && "late data".equals(text(new File(out, "late.txt"))), problems.toString());
        check("counts: 4 files written (a, b, c, late), 3 skipped (sym, lonely, early)", r[0] == 4 && r[2] == 3, java.util.Arrays.toString(r));
        check("bytes written are the real ones (3 x 20 + 9)", r[1] == 3 * 20 + 9, java.util.Arrays.toString(r));

        // only the link: its target is not part of this extraction
        File out2 = new File(root, "out2"); out2.mkdirs();
        List<String> p2 = new ArrayList<String>();
        ZipTool.extractTree(a, "b.txt", out2, null, p2);
        check("extracting only the hard link does not write an empty file and says why: " + p2, !new File(out2, "b.txt").exists() && p2.toString().contains("hard link to a.txt"), null);

        // again with Skip, a.txt there and b.txt gone (a resumed extraction): a.txt is left as it was, and b.txt is not copied from it (it may
        // not be this archive's a.txt). The reason given is that, not "not part of this extraction"
        Files.write(new File(out, "a.txt").toPath(), "the user's a".getBytes(StandardCharsets.UTF_8));
        new File(out, "b.txt").delete();
        List<String> p3 = new ArrayList<String>();
        long[] r3 = ZipTool.extractTree(a, "", out, null, p3, null, FileOps.SKIP);
        String b3 = null;
        for (String s : p3) if (s.startsWith("b.txt:")) b3 = s;
        check("Skip: the hard link to a kept file says the file was left as it was: " + b3, !new File(out, "b.txt").exists() && b3 != null && b3.contains("hard link to a.txt")
            && b3.contains("left as it was") && !b3.contains("not part of this extraction") && "the user's a".equals(text(new File(out, "a.txt"))), p3.toString());
        check("Skip: a.txt, sub/c.txt and late.txt are counted as left alone", r3[0] == 0 && r3[3] == 3, java.util.Arrays.toString(r3));
        // a.txt can't be written (a folder of that name is in the way): its hard link says so
        File out3 = new File(root, "out3"); new File(out3, "a.txt/inside").mkdirs();
        List<String> p4 = new ArrayList<String>();
        ZipTool.extractTree(a, "", out3, null, p4);
        String b4 = null;
        for (String s : p4) if (s.startsWith("b.txt:")) b4 = s;
        check("the hard link to a file that failed says it could not be extracted: " + b4, !new File(out3, "b.txt").exists() && b4 != null && b4.contains("could not be extracted")
            && !b4.contains("not part of this extraction") && p4.toString().contains("a.txt: \"a.txt\": a folder named a.txt is in the way"), p4.toString());

        // a single entry (preview, open) cannot silently be empty either
        String msg = null;
        try { ZipTool.extractTo(a, b, new File(root, "single.txt")); } catch (java.io.IOException ex) { msg = ex.getMessage(); }
        check("extracting the hard link entry on its own is an error that names the target: " + msg, msg != null && msg.contains("hard link") && msg.contains("a.txt") && !new File(root, "single.txt").exists(), null);
        check("no partial file is left", !new File(out, ".b.txt.part").exists(), null);

        // a symbolic link on its own (the archive browser's Extract, a preview, Open) is refused in words: never an empty file, never over one that is there
        ZipTool.Entry sym = null;
        for (ZipTool.Entry e : a.entries) if (e.name.equals("sym.txt")) sym = e;
        String smsg = null;
        try { ZipTool.extractTo(a, sym, new File(root, "single-sym.txt")); } catch (java.io.IOException ex) { smsg = ex.getMessage(); }
        check("extracting the symbolic link entry on its own is an error that says so: " + smsg, smsg != null && smsg.contains("symbolic link") && smsg.contains("a.txt") && !new File(root, "single-sym.txt").exists(), null);
        File mine = new File(root, "mine.txt");
        Files.write(mine.toPath(), "the user's own text".getBytes(StandardCharsets.UTF_8));
        String rmsg = null;
        try { ZipTool.extractTo(a, sym, mine, null); } catch (java.io.IOException ex) { rmsg = ex.getMessage(); }
        check("with Replace it does not write over the file that is there: " + text(mine), rmsg != null && "the user's own text".equals(text(mine)) && !new File(root, ".mine.txt.part").exists(), rmsg);
        // a RAR5 link (listed with the target's length, no data) was refused as "damaged"; it is named for what it is
        File rar = null;
        for (File p = new File(System.getProperty("user.dir")); p != null && rar == null; p = p.getParentFile()) {
            File c = new File(p, "tests/java/fixtures/rar/libarchive-rar5-symlink.rar");
            if (c.isFile()) rar = c;
        }
        if (rar == null) System.out.println("SKIP no RAR5 link fixture (tests/java/fixtures/rar)");
        else {
            ZipTool.Archive ra = RarSource.open(rar, null);
            ZipTool.Entry rl = null;
            for (ZipTool.Entry e : ra.entries) if (e.linkTarget != null && !e.dir && rl == null) rl = e;
            String rlmsg = null;
            if (rl != null) try { ZipTool.extractTo(ra, rl, new File(root, "rar-link")); } catch (java.io.IOException ex) { rlmsg = ex.getMessage(); }
            check("a RAR5 link entry on its own is refused as a symbolic link: " + rlmsg, rl != null && rlmsg != null && rlmsg.contains("symbolic link") && !new File(root, "rar-link").exists(), null);
        }

        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("ALL PASSED");
    }
}
