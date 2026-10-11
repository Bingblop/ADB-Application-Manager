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

        // a single entry (preview, open) cannot silently be empty either
        String msg = null;
        try { ZipTool.extractTo(a, b, new File(root, "single.txt")); } catch (java.io.IOException ex) { msg = ex.getMessage(); }
        check("extracting the hard link entry on its own is an error that names the target: " + msg, msg != null && msg.contains("hard link") && msg.contains("a.txt") && !new File(root, "single.txt").exists(), null);
        check("no partial file is left", !new File(out, ".b.txt.part").exists(), null);

        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("ALL PASSED");
    }
}
