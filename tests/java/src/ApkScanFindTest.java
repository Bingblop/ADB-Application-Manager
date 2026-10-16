package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The shell search for package files (ApkScan.FIND_PREDICATE) run for real against a folder tree that holds names with line breaks: the
 * output is read one path per line, so a folder named "d" + LF with data/app/victim.apk inside it must not produce the line /data/app/victim.apk.
 * Also how a search that did not run to its end is told apart (its end line, ApkScan.FIND_END), and a scan of how runApkScan runs it.
 * The real search needs sh and find; without them that part says so and passes.
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

    static File srcDir() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
            File f = new File(d, "src/com/bloatware/bingblop");
            if (f.isDirectory()) return f;
        }
        return null;
    }

    /** The body of the method declared by {@code sig}, braces matched; null when it is not there. */
    static String bodyOf(String src, String sig) {
        int i = src.indexOf(sig);
        if (i < 0) return null;
        int open = src.indexOf('{', src.indexOf(')', i)), depth = 0;
        for (int j = open; j >= 0 && j < src.length(); j++) {
            char c = src.charAt(j);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(open, j + 1);
        }
        return null;
    }

    /**
     * Review MA2-1: the privileged search ran through executeShell, whose 8-10 s timeout killed find and kept what it had printed so far; the
     * timeout note was dropped as a non-path line and the partial list was reported as complete ("truncated": false), or as "no files found".
     */
    static void endLineTests() throws Exception {
        is("a search that printed its end line ran to the end", ApkScan.findComplete("/sdcard/a.apk\n/sdcard/b.apk\n" + ApkScan.FIND_END), "false");
        is("...also with no file found, and with the carriage returns of an old adb", ApkScan.findComplete(ApkScan.FIND_END + "\n") && ApkScan.findComplete("/sdcard/a.apk\r\n" + ApkScan.FIND_END + "\r\n"), "false");
        String cut = "/storage/emulated/0/Android/data/com.a/files/x.apk\n/storage/emulated/0/Android/obb/com.b/y.xapk\n[Process timed out after 8000ms]";
        is("a search killed by the timeout did not run to the end", !ApkScan.findComplete(cut), "true");
        is("...and the paths it printed before are still read", ApkScan.parseFindOutput(cut, 2500).size() == 2, ApkScan.parseFindOutput(cut, 2500).toString());
        is("a dropped link, an error answer, nothing at all: not the end", !ApkScan.findComplete("error: device '127.0.0.1:5555' not found") && !ApkScan.findComplete("Error: Shizuku is not authorized")
                && !ApkScan.findComplete("") && !ApkScan.findComplete(null), "true");
        is("a path that holds the end word is not the end line", !ApkScan.findComplete("/sdcard/" + ApkScan.FIND_END + "\n/sdcard/" + ApkScan.FIND_END + ".apk"), "true");
        is("the end word is not a path, so the list never shows it", ApkScan.parseFindOutput("/sdcard/a.apk\n" + ApkScan.FIND_END, 2500).size() == 1, "listed");

        File sdir = srcDir();
        if (sdir == null) { System.out.println("SKIP the runApkScan source scan: src/com/bloatware/bingblop not found"); return; }
        String m = new String(Files.readAllBytes(new File(sdir, "MainActivity.java").toPath()), StandardCharsets.UTF_8);
        String b = bodyOf(m, "private void runApkScan(");
        is("runApkScan found", b != null, "missing");
        if (b == null) return;
        int find = b.indexOf("\"find \" + where"), from = find < 0 ? -1 : b.lastIndexOf("String out =", find), to = find < 0 ? -1 : b.indexOf(");\n", find);
        String call = from < 0 || to < 0 ? "" : b.substring(from, to + 2);
        is("the search runs through runPrivilegedShell with a time of its own, not through executeShell's 8-10 s", call.contains("runPrivilegedShell(mode,") && call.contains(", 25000)") && !call.contains("executeShell"), call);
        is("...and prints the end line after find", call.contains("; echo \" + ApkScan.FIND_END"), call);
        is("a search without its end line is reported as stopped early", b.contains("if (!ApkScan.findComplete(out)) lim.hitLimit = true;"), "no hitLimit");
    }

    public static void main(String[] a) throws Exception {
        endLineTests();
        if (new File("/bin/sh").exists() == false || !run("command -v find").trim().endsWith("find")) {
            System.out.println("skipped: sh or find is not available (the real search)");
            System.out.println(n + " checks, " + fails + " failed");
            if (fails != 0) System.exit(1);
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
            touch(new File(root, "cr\r/data/app/victim_cr.apk")); // folder with carriage return
            touch(new File(root, "r\rx.apk"));                  // file with carriage return

            String base = "find '" + root.getAbsolutePath() + "' -maxdepth 12 ";
            String oldOut = run(base + "-type f \\( -iname '*.apk' -o -iname '*.apks' -o -iname '*.apkm' -o -iname '*.xapk' \\) 2>/dev/null");
            List<String> oldPaths = ApkScan.parseFindOutput(oldOut, 100);
            is("the old test line is fooled (the check below would otherwise prove nothing)", oldPaths.contains("/data/app/victim.apk") && oldPaths.contains("/system/app/y.apks"), oldPaths.toString());

            String out = run(base + ApkScan.FIND_PREDICATE + " 2>/dev/null");
            List<String> paths = ApkScan.parseFindOutput(out, 100);
            String ok = root.getAbsolutePath() + "/ok.apk", sub = root.getAbsolutePath() + "/sub dir/b.XAPK";
            is("the normal package files are still found (including a folder with a space)", paths.contains(ok) && paths.contains(sub), paths.toString());
            is("no phantom path from a folder name that holds a line break", !paths.contains("/data/app/victim.apk") && !paths.contains("/system/app/y.apks") && !paths.contains("/data/app/victim_cr.apk"), paths.toString());
            is("a file name with a line break is left out", paths.size() == 2, paths.toString());
            is("every path found exists as printed", paths.stream().allMatch(p -> new File(p).isFile()), paths.toString());
            is("the predicate holds no raw line break (it travels as one line through every shell mode)", ApkScan.FIND_PREDICATE.indexOf('\n') < 0 && ApkScan.FIND_PREDICATE.indexOf('\r') < 0, "has a line break");

            // the command runApkScan sends, run for real: the end line comes after find's output and the paths read the same
            String whole = run(base + ApkScan.FIND_PREDICATE + " 2>/dev/null | head -n 2500; echo " + ApkScan.FIND_END);
            List<String> wholePaths = ApkScan.parseFindOutput(whole, 2500);
            is("the real search with its end line ran to the end", ApkScan.findComplete(whole), whole);
            is("...and finds the same files", wholePaths.size() == 2 && wholePaths.contains(ok) && wholePaths.contains(sub), wholePaths.toString());
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
