package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** A small archive that declares (and really holds) far more than the free space is not written out: each entry is capped by the free space less a reserve. */
public class ExtractSpaceTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static int run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        java.io.InputStream in = p.getInputStream(); byte[] b = new byte[4096]; while (in.read(b) > 0) { }
        return p.waitFor();
    }

    static boolean have(String tool) { try { return run(tool, "--help") >= 0; } catch (Exception e) { return false; } }

    static void zeros(ZipOutputStream z, String name, long bytes) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        byte[] buf = new byte[1 << 16];
        for (long done = 0; done < bytes; ) { int n = (int) Math.min(buf.length, bytes - done); z.write(buf, 0, n); done += n; }
        z.closeEntry();
    }

    static boolean noPartFiles(File dir) {
        String[] names = dir.list();
        if (names != null) for (String n : names) if (n.endsWith(".part")) return false;
        return true;
    }

    /** Sets the reserve so that about {@code room} bytes may still be written, restores it afterwards. */
    static long reserveFor(File dir, long room) { return Math.max(0, dir.getUsableSpace() - room); }

    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("extractspace").toFile();
        long saved = ZipTool.freeSpaceReserve;
        try {
            // --- a zip: 3 MB of zeros declared and held (a few KB deflated), and a small file
            File zip = new File(root, "bomb.zip");
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip))) {
                z.setLevel(Deflater.BEST_COMPRESSION);
                zeros(z, "big.bin", 3L << 20);
                zeros(z, "small.txt", 100);
            }
            check("the zip is tiny against what it holds (" + zip.length() + " bytes for 3 MB)", zip.length() < 20000, null);
            ZipTool.Archive a = ZipTool.open(zip);
            File out = new File(root, "out-zip"); out.mkdirs();
            ZipTool.freeSpaceReserve = reserveFor(out, 1 << 20);
            List<String> problems = new ArrayList<String>();
            ZipTool.extractTree(a, "", out, null, problems);
            check("with about 1 MB of room the 3 MB entry is refused in words and the small one is extracted: " + problems,
                !new File(out, "big.bin").exists() && new File(out, "small.txt").length() == 100 && problems.toString().contains("Not enough free space"), null);
            check("... and no partial file is left", noPartFiles(out), null);
            ZipTool.freeSpaceReserve = saved;
            File out2 = new File(root, "out-zip-ok"); out2.mkdirs();
            ZipTool.extractTree(a, "", out2, null, new ArrayList<String>());
            check("with the normal reserve both entries are extracted", new File(out2, "big.bin").length() == (3L << 20) && new File(out2, "small.txt").length() == 100, null);

            // --- the free space is read again for every entry: several entries together stop at the reserve
            File three = new File(root, "three.zip");
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(three))) {
                zeros(z, "a.bin", 700 << 10); zeros(z, "b.bin", 700 << 10); zeros(z, "c.bin", 700 << 10);
            }
            File out3 = new File(root, "out-three"); out3.mkdirs();
            ZipTool.freeSpaceReserve = reserveFor(out3, 1000 << 10);
            List<String> p3 = new ArrayList<String>();
            ZipTool.extractTree(ZipTool.open(three), "", out3, null, p3);
            int written = 0; for (String n : new String[] {"a.bin", "b.bin", "c.bin"}) if (new File(out3, n).exists()) written++;
            check("three 700 KB entries with 1000 KB of room: the first fits, the others are refused (" + written + " written, " + p3.size() + " notes)", written == 1 && p3.size() == 2, p3.toString());
            ZipTool.freeSpaceReserve = saved;

            // --- a GNU sparse tar: 600 MB declared, about 10 KB on disk
            if (have("tar") && run("truncate", "-s", "600M", new File(root, "sp.bin").getPath()) == 0
                && run("tar", "--format=gnu", "--sparse", "-C", root.getPath(), "-cf", new File(root, "sp.tar").getPath(), "sp.bin") == 0) {
                File tar = new File(root, "sp.tar");
                check("the sparse tar is tiny (" + tar.length() + " bytes) and declares 600 MB", tar.length() < 100000, null);
                ZipTool.Archive ta = ArchiveIoSource.open(tar, "tar", null);
                check("the listing shows the full size", ta.entries.get(0).size == (600L << 20), String.valueOf(ta.entries.get(0).size));
                File out4 = new File(root, "out-tar"); out4.mkdirs();
                ZipTool.freeSpaceReserve = reserveFor(out4, 1 << 20);
                List<String> p4 = new ArrayList<String>();
                long t0 = System.currentTimeMillis();
                ZipTool.extractTree(ta, "", out4, null, p4);
                check("with about 1 MB of room the sparse entry is refused before anything is written (" + (System.currentTimeMillis() - t0) + " ms): " + p4,
                    !new File(out4, "sp.bin").exists() && noPartFiles(out4) && p4.toString().contains("Not enough free space"), null);
                ZipTool.freeSpaceReserve = saved;
            } else System.out.println("SKIP sparse tar (no tar / truncate)");

            // --- a 7z of zeros
            if (have("7z")) {
                File z0 = new File(root, "zeros.bin");
                try (FileOutputStream f = new FileOutputStream(z0)) { byte[] b = new byte[1 << 16]; for (int i = 0; i < 320; i++) f.write(b); }       // 20 MB
                File sz = new File(root, "zeros.7z");
                if (run("7z", "a", "-mx=9", sz.getPath(), z0.getPath()) == 0) {
                    ZipTool.Archive za = ArchiveIoSource.open(sz, "7z", null);
                    File out5 = new File(root, "out-7z"); out5.mkdirs();
                    ZipTool.freeSpaceReserve = reserveFor(out5, 1 << 20);
                    List<String> p5 = new ArrayList<String>();
                    ZipTool.extractTree(za, "", out5, null, p5);
                    check("a 7z of 20 MB of zeros (" + sz.length() + " bytes) is refused with about 1 MB of room: " + p5, !new File(out5, "zeros.bin").exists() && noPartFiles(out5) && p5.toString().contains("Not enough free space"), null);
                    ZipTool.freeSpaceReserve = saved;
                }
            } else System.out.println("SKIP 7z (not installed)");
        } finally {
            ZipTool.freeSpaceReserve = saved;
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("ALL PASSED");
    }
}
