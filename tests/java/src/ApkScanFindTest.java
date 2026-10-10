package com.bloatware.bingblop;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * The shell search for package files (ApkScan.FIND_PREDICATE) run for real against a folder tree that holds names with line breaks: the
 * output is read one path per line, so a folder named "d" + LF with data/app/victim.apk inside it must not produce the line /data/app/victim.apk.
 * Needs sh and find; without them the test says so and passes.
 */
public class ApkScanFindTest {
    static int fails = 0, n = 0;
    static void is(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }

    static void touch(File f) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[]{1});
    }

    static String run(String cmd) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(false).start();
        byte[] out = p.getInputStream().readAllBytes();
        p.waitFor();
        return new String(out, "UTF-8");
    }

    public static void main(String[] a) throws Exception {
        if (new File("/bin/sh").exists() == false || !run("command -v find").trim().endsWith("find")) {
            System.out.println("skipped: sh or find is not available");
            System.out.println("0 checks, 0 failed");
            return;
        }
        File root = Files.createTempDirectory("apkscan").toFile();
        try {
            touch(new File(root, "ok.apk"));
            touch(new File(root, "sub dir/b.XAPK"));
            touch(new File(root, "not a package.txt"));
            touch(new File(root, "d\n/data/app/victim.apk"));   // a folder whose name ends in a line break: the next printed line looks like /data/app/victim.apk
            touch(new File(root, "n\nx.apk"));                  // a file name with a line break in it
            touch(new File(root, "e\n/system/app/y.apks"));

            String base = "find '" + root.getAbsolutePath() + "' -maxdepth 12 ";
            String oldOut = run(base + "-type f \\( -iname '*.apk' -o -iname '*.apks' -o -iname '*.apkm' -o -iname '*.xapk' \\) 2>/dev/null");
            List<String> oldPaths = ApkScan.parseFindOutput(oldOut, 100);
            is("the old test line is fooled (the check below would otherwise prove nothing)", oldPaths.contains("/data/app/victim.apk") && oldPaths.contains("/system/app/y.apks"), oldPaths.toString());

            String out = run(base + ApkScan.FIND_PREDICATE + " 2>/dev/null");
            List<String> paths = ApkScan.parseFindOutput(out, 100);
            String ok = root.getAbsolutePath() + "/ok.apk", sub = root.getAbsolutePath() + "/sub dir/b.XAPK";
            is("the normal package files are still found (including a folder with a space)", paths.contains(ok) && paths.contains(sub), paths.toString());
            is("no phantom path from a folder name that holds a line break", !paths.contains("/data/app/victim.apk") && !paths.contains("/system/app/y.apks"), paths.toString());
            is("a file name with a line break is left out", paths.size() == 2, paths.toString());
            is("every path found exists as printed", paths.stream().allMatch(p -> new File(p).isFile()), paths.toString());
            is("the predicate holds no raw line break (it travels as one line through every shell mode)", ApkScan.FIND_PREDICATE.indexOf('\n') < 0 && ApkScan.FIND_PREDICATE.indexOf('\r') < 0, "has a line break");
        } finally {
            deleteTree(root);
        }
        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
