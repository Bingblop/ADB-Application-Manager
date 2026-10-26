package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * SamsungFonts, the font changer's and the system-emoji swap's rules and privileged scripts. The One UI rule and the style index are
 * checked on their own; the scripts are run through a real {@code sh} against a fake root (the four absolute roots point into a temp
 * folder whose name has a space and an apostrophe, to prove the quoting) with {@code chown}, {@code chcon}, {@code restorecon},
 * {@code nsenter}, {@code mount}, {@code umount}, {@code setprop} and {@code settings} stubbed on {@code PATH}, the way SettingsDbTest runs
 * a real sh with a fake {@code settings}. A seeded fuzz feeds the parsers and the XML scanner truncated, bit-flipped, random and huge input.
 */
public class SamsungFontsTest {

    static int n = 0, fails = 0;
    static void check(String name, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + name); } }
    static void eq(String name, Object got, Object want) { n++; if (!java.util.Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got <" + got + "> want <" + want + ">"); } }
    static void iae(String name, Runnable r) { n++; try { r.run(); fails++; System.out.println("FAIL " + name + ": no exception"); } catch (IllegalArgumentException e) { /* ok */ } }

    static File root, bin;
    static SamsungFonts.Roots R;

    public static void main(String[] a) throws Exception {
        if (a.length >= 3 && a[0].equals("--fuzz")) { fuzz(Long.parseLong(a[1]), Integer.parseInt(a[2])); report(); return; }

        oneUiRule();
        styleIndexRule();
        parsersUnit();
        xmlScanner();
        realFontConfigs();

        setupFakeRoot();
        changerRoundTrip();
        changerMismatch();
        resetAndStatus();
        emojiSwap();
        pathConfinement();

        fuzz(1234567, 4000);
        report();
    }

    static void report() {
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " SamsungFontsTest: " + (n - fails) + "/" + n + " checks");
        if (fails != 0) System.exit(1);
    }

    // ---------------------------------------------------------------------------------------------
    // The One UI rule
    // ---------------------------------------------------------------------------------------------

    static void oneUiRule() {
        // oneUiTimes100 from the raw ro.build.version.oneui
        eq("oneUi 80500 -> 850", SamsungFonts.oneUiTimes100(80500), 850);
        eq("oneUi 80000 -> 800", SamsungFonts.oneUiTimes100(80000), 800);
        eq("oneUi 90000 -> 900", SamsungFonts.oneUiTimes100(90000), 900);
        eq("oneUi 70000 -> 700", SamsungFonts.oneUiTimes100(70000), 700);
        eq("oneUi 60101 -> 610", SamsungFonts.oneUiTimes100(60101), 610);
        eq("oneUi unknown 0", SamsungFonts.oneUiTimes100(0), 0);
        eq("oneUi garbage", SamsungFonts.oneUiTimes100(5), 0);

        // 7.0: older One UI always applies a self-signed package, patch or no patch
        check("7.0 applies, old patch", SamsungFonts.appliesSelfSigned(700, "2024-01-01"));
        check("7.0 applies, new patch", SamsungFonts.appliesSelfSigned(700, "2026-09-01"));
        check("7.0 applies, no patch", SamsungFonts.appliesSelfSigned(700, null));
        // 8.0..8.4: applies before March 2026, refused from March 2026
        check("8.0 applies before March 2026", SamsungFonts.appliesSelfSigned(800, "2026-02-01"));
        check("8.0 applies Feb 2026 exact", SamsungFonts.appliesSelfSigned(800, "2026-02-28"));
        check("8.0 refused March 2026", !SamsungFonts.appliesSelfSigned(800, "2026-03-01"));
        check("8.0 refused after March 2026", !SamsungFonts.appliesSelfSigned(800, "2026-07-05"));
        check("8.4 refused 2027", !SamsungFonts.appliesSelfSigned(840, "2027-01-01"));
        check("8.0 unknown patch -> refused (honest)", !SamsungFonts.appliesSelfSigned(800, null));
        check("8.0 garbage patch -> refused", !SamsungFonts.appliesSelfSigned(800, "nope"));
        check("8.0 applies 2025", SamsungFonts.appliesSelfSigned(800, "2025-12-31"));
        // 8.5 and 9.0: never
        check("8.5 refused new", !SamsungFonts.appliesSelfSigned(850, "2026-09-01"));
        check("8.5 refused old patch too", !SamsungFonts.appliesSelfSigned(850, "2024-01-01"));
        check("9.0 refused", !SamsungFonts.appliesSelfSigned(900, "2026-09-01"));
        // unknown One UI: do not promise it applies
        check("unknown refused", !SamsungFonts.appliesSelfSigned(0, "2024-01-01"));

        // knownBeforeMarch2026 edges
        check("before: 2026-02", SamsungFonts.knownBeforeMarch2026("2026-02-15"));
        check("not before: 2026-03", !SamsungFonts.knownBeforeMarch2026("2026-03-01"));
        check("before: 2025-11", SamsungFonts.knownBeforeMarch2026("2025-11-05"));
        check("not before: null", !SamsungFonts.knownBeforeMarch2026(null));
        check("not before: short", !SamsungFonts.knownBeforeMarch2026("2026-3"));
        check("not before: non-numeric", !SamsungFonts.knownBeforeMarch2026("abcd-ef-gh"));
        check("not before: month 00", !SamsungFonts.knownBeforeMarch2026("2026-00-10"));
    }

    // ---------------------------------------------------------------------------------------------
    // The style index
    // ---------------------------------------------------------------------------------------------

    static void styleIndexRule() {
        String foundation = "com.monotype.android.font.foundation";
        String roboto = "com.monotype.android.font.roboto";
        String samsungone = "com.monotype.android.font.samsungone";
        String inter = "com.monotype.android.font.Inter_aa11bb";
        String lato = "com.monotype.android.font.Lato_cc22dd";
        String zed = "com.monotype.android.font.Zebra_ff00aa";
        List<String> installed = new ArrayList<String>(Arrays.asList(samsungone, lato, foundation, inter, roboto, zed));

        eq("default is 0", SamsungFonts.styleIndex(installed, "default"), 0);
        eq("default#default is 0", SamsungFonts.styleIndex(installed, "default#default"), 0);
        eq("null is 0", SamsungFonts.styleIndex(installed, null), 0);
        eq("unknown target is 0", SamsungFonts.styleIndex(installed, "com.monotype.android.font.Nope_000000"), 0);
        // preloaded first in foundation, roboto, samsungone order, then the rest alphabetically: Inter, Lato, Zebra
        eq("foundation = 1", SamsungFonts.styleIndex(installed, foundation), 1);
        eq("roboto = 2", SamsungFonts.styleIndex(installed, roboto), 2);
        eq("samsungone = 3", SamsungFonts.styleIndex(installed, samsungone), 3);
        eq("Inter = 4", SamsungFonts.styleIndex(installed, inter), 4);
        eq("Lato = 5", SamsungFonts.styleIndex(installed, lato), 5);
        eq("Zebra = 6", SamsungFonts.styleIndex(installed, zed), 6);
        // when a preloaded one is not installed it is left out and the rest shift up
        List<String> noPre = new ArrayList<String>(Arrays.asList(lato, inter));
        eq("no preloaded: Inter = 1", SamsungFonts.styleIndex(noPre, inter), 1);
        eq("no preloaded: Lato = 2", SamsungFonts.styleIndex(noPre, lato), 2);
        // duplicates do not double-count
        List<String> dup = new ArrayList<String>(Arrays.asList(inter, inter, lato));
        eq("dup Inter = 1", SamsungFonts.styleIndex(dup, inter), 1);
        eq("dup Lato = 2", SamsungFonts.styleIndex(dup, lato), 2);
        eq("empty list, target -> 0", SamsungFonts.styleIndex(new ArrayList<String>(), inter), 0);

        // invalid script-builder arguments are refused (they never reach the shell)
        SamsungFonts.Roots sys = SamsungFonts.Roots.system();
        iae("bad suffix with ;", () -> SamsungFonts.writeFontScript(sys, 0, "a;rm -rf /", "DroidSans.ttf", 10, "0".repeat(64)));
        iae("bad suffix with quote", () -> SamsungFonts.writeFontScript(sys, 0, "a'b", "DroidSans.ttf", 10, "0".repeat(64)));
        iae("bad suffix with slash", () -> SamsungFonts.writeFontScript(sys, 0, "a/b", "DroidSans.ttf", 10, "0".repeat(64)));
        iae("bad file name", () -> SamsungFonts.writeFontScript(sys, 0, "Inter_aa11bb", "../../evil", 10, "0".repeat(64)));
        iae("bad file ext", () -> SamsungFonts.writeFontScript(sys, 0, "Inter_aa11bb", "DroidSans.sh", 10, "0".repeat(64)));
        iae("bad sha", () -> SamsungFonts.writeFontScript(sys, 0, "Inter_aa11bb", "DroidSans.ttf", 10, "xyz"));
        iae("bad user", () -> SamsungFonts.statusScript(sys, -1));
        iae("install mismatched arrays", () -> SamsungFonts.installScript(sys, 0, "Inter_aa11bb", new String[]{"DroidSans.ttf"}, new String[]{}));
        iae("staged outside adbmgr", () -> SamsungFonts.emojiSwapScript(sys, "/system/fonts/NotoColorEmoji.ttf", Arrays.asList("/system/fonts/NotoColorEmoji.ttf")));
    }

    // ---------------------------------------------------------------------------------------------
    // Parsers on crafted output
    // ---------------------------------------------------------------------------------------------

    static void parsersUnit() {
        String sha = "a".repeat(64), shb = "b".repeat(64);
        String loc = "/data/app_fonts/0/Inter_aa11bb";
        String out = SamsungFonts.M_LOC + "\n" + loc + "#Inter_aa11bb\n"
                + SamsungFonts.M_LS + "\n"
                + "-rw-r--r-- 1 system system u:object_r:app_fonts_data_file:s0 656000 2026-10-26 12:00 DroidSans.ttf\n"
                + "-rw-r--r-- 1 system system u:object_r:app_fonts_data_file:s0 512000 2026-10-26 12:00 DroidSans-Bold.ttf\n"
                + SamsungFonts.M_SHA + "\n"
                + sha + "  " + loc + "/DroidSans.ttf\n"
                + shb + "  " + loc + "/DroidSans-Bold.ttf\n"
                + SamsungFonts.M_END + "0\n";
        SamsungFonts.ApplyResult r = SamsungFonts.parseApply(out);
        check("apply ok", r.ok);
        eq("apply error empty", r.error, "");
        eq("apply sansLoc", r.sansLoc, loc + "#Inter_aa11bb");
        eq("apply files", r.files.size(), 2);
        eq("apply file0 name", r.files.get(0).name, "DroidSans.ttf");
        eq("apply file0 size", r.files.get(0).size, 656000L);
        eq("apply file0 sha", r.files.get(0).sha256, sha);
        eq("apply file0 label", r.files.get(0).label, "u:object_r:app_fonts_data_file:s0");
        eq("apply file1 sha", r.files.get(1).sha256, shb);

        SamsungFonts.ApplyResult err = SamsungFonts.parseApply("mkdir...\nERROR: this is not a Samsung phone (no /data/app_fonts).\n");
        check("apply error not ok", !err.ok);
        eq("apply error text", err.error, "this is not a Samsung phone (no /data/app_fonts).");

        SamsungFonts.ApplyResult half = SamsungFonts.parseApply(SamsungFonts.M_LOC + "\n" + loc + "#x\n");  // no end marker
        check("apply incomplete not ok", !half.ok);

        // reset read-back
        SamsungFonts.ApplyResult reset = SamsungFonts.parseApply(SamsungFonts.M_LOC + "\ndefault#default\n" + SamsungFonts.M_END + "0\n");
        check("reset ok", reset.ok);
        eq("reset loc", reset.sansLoc, "default#default");

        // status
        SamsungFonts.Status st = SamsungFonts.parseStatus(SamsungFonts.M_AVAIL + " yes\n" + SamsungFonts.M_LOC + "\n" + loc + "#Inter_aa11bb\n"
                + SamsungFonts.M_LS + "\nInter_aa11bb\nsans.loc\n" + SamsungFonts.M_END + "\n");
        check("status available", st.available);
        eq("status suffix", st.currentSuffix, "Inter_aa11bb");
        eq("status entries", st.entries.size(), 2);
        SamsungFonts.Status noavail = SamsungFonts.parseStatus(SamsungFonts.M_AVAIL + " no\n" + SamsungFonts.M_END + "\n");
        check("status not available", !noavail.available);
        SamsungFonts.Status def = SamsungFonts.parseStatus(SamsungFonts.M_AVAIL + " yes\n" + SamsungFonts.M_LOC + "\ndefault#default\n" + SamsungFonts.M_END + "\n");
        eq("status default suffix null", def.currentSuffix, null);
        eq("currentSuffixOf default", SamsungFonts.currentSuffixOf("default#default"), null);
        eq("currentSuffixOf plain", SamsungFonts.currentSuffixOf("/a/b/Inter_x#Inter_x"), "Inter_x");
        eq("currentSuffixOf empty", SamsungFonts.currentSuffixOf(""), null);

        // write result
        SamsungFonts.WriteResult w = SamsungFonts.parseWrite(SamsungFonts.M_WROTE + " DroidSans.ttf 656000 " + sha + "\n", "DroidSans.ttf");
        check("write ok", w.ok); eq("write sha", w.sha256, sha); eq("write size", w.size, 656000L);
        SamsungFonts.WriteResult we = SamsungFonts.parseWrite("ERROR: the font did not match its checksum\n", "DroidSans.ttf");
        check("write error", !we.ok); eq("write error text", we.error, "the font did not match its checksum");
        SamsungFonts.WriteResult wn = SamsungFonts.parseWrite("nothing useful\n", "DroidSans.ttf");
        check("write no answer", !wn.ok);

        // swap result
        String swapOut = SamsungFonts.M_BIND + " ok /system/fonts/NotoColorEmoji.ttf\n" + SamsungFonts.M_SHA + "\n" + sha + "  /system/fonts/NotoColorEmoji.ttf\n"
                + SamsungFonts.M_BIND + " missing /product/fonts/NotoColorEmoji.ttf\n"
                + SamsungFonts.M_END + "\n";
        List<SamsungFonts.BindResult> sw = SamsungFonts.parseSwap(swapOut);
        eq("swap count", sw.size(), 2);
        eq("swap0 state", sw.get(0).state, "ok");
        eq("swap0 target", sw.get(0).target, "/system/fonts/NotoColorEmoji.ttf");
        eq("swap0 sha", sw.get(0).sha256, sha);
        eq("swap1 state", sw.get(1).state, "missing");
        eq("swap1 sha null", sw.get(1).sha256, null);

        List<SamsungFonts.BindResult> undo = SamsungFonts.parseSwap(SamsungFonts.M_UNDO + " /system/fonts/NotoColorEmoji.ttf\n" + SamsungFonts.M_SHA + "\n" + shb + "  /system/fonts/NotoColorEmoji.ttf\n" + SamsungFonts.M_END + "\n");
        eq("undo count", undo.size(), 1);
        eq("undo state", undo.get(0).state, "undone");
        eq("undo sha", undo.get(0).sha256, shb);
    }

    // ---------------------------------------------------------------------------------------------
    // The und-Zsye XML scanner
    // ---------------------------------------------------------------------------------------------

    static void xmlScanner() {
        String fonts = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<familyset>\n"
                + "  <family lang=\"und-Latn\"><font weight=\"400\" style=\"normal\">Roboto.ttf</font></family>\n"
                + "  <family lang=\"und-Zsye\" ignore=\"true\"><font weight=\"400\" style=\"normal\">NotoColorEmojiLegacy.ttf</font></family>\n"
                + "  <family lang=\"und-Zsye\"><font weight=\"400\" style=\"normal\">NotoColorEmoji.ttf</font></family>\n"
                + "  <family lang=\"und-Zsye\"><font weight=\"400\" style=\"normal\">NotoColorEmojiFlags.ttf</font></family>\n"
                + "  <family lang=\"und-Zsym\"><font weight=\"400\" style=\"normal\">NotoSansSymbols.ttf</font></family>\n"
                + "</familyset>\n";
        List<String> f = SamsungFonts.emojiFamilyFiles(fonts);
        eq("zsye count (legacy ignored)", f.size(), 2);
        check("has NotoColorEmoji", f.contains("NotoColorEmoji.ttf"));
        check("has NotoColorEmojiFlags", f.contains("NotoColorEmojiFlags.ttf"));
        check("legacy excluded", !f.contains("NotoColorEmojiLegacy.ttf"));
        check("non-zsye excluded", !f.contains("Roboto.ttf"));

        // a comma/space list of langs, single quotes
        List<String> multi = SamsungFonts.emojiFamilyFiles("<family lang='und-Zsye,und-Zsym'><font>A.ttf</font></family>");
        check("lang list matches", multi.contains("A.ttf"));
        List<String> nomatch = SamsungFonts.emojiFamilyFiles("<family lang='xund-Zsye'><font>B.ttf</font></family>");
        check("substring lang does not match", !nomatch.contains("B.ttf"));

        SamsungFonts.Roots sys = SamsungFonts.Roots.system();
        List<String> targets = SamsungFonts.emojiFiles(sys, fonts, null,
                Arrays.asList("/data/fonts/files/NotoColorEmoji--1/NotoColorEmoji.ttf", "/data/fonts/files/Roboto--1/Roboto.ttf", "/etc/passwd"));
        check("system path", targets.contains("/system/fonts/NotoColorEmoji.ttf"));
        check("product path", targets.contains("/product/fonts/NotoColorEmoji.ttf"));
        check("updatable emoji included", targets.contains("/data/fonts/files/NotoColorEmoji--1/NotoColorEmoji.ttf"));
        check("updatable non-emoji excluded", !targets.contains("/data/fonts/files/Roboto--1/Roboto.ttf"));
        check("outside dataFonts excluded", !targets.contains("/etc/passwd"));

        // hostile / malformed XML must not throw or hang
        String[] nasty = {
                null, "", "<family", "<family lang=\"und-Zsye\"", "<family lang=\"und-Zsye\"><font>", "<family lang=\"und-Zsye\"><font>x",
                "<family lang=\"und-Zsye\"/>", "<!DOCTYPE x [<!ENTITY a \"a\">]><family lang=\"und-Zsye\"><font>&a;.ttf</font></family>",
                "<<<<>>>>", "<family lang=\"\u0000und-Zsye\">",
        };
        for (String x : nasty) {
            List<String> r = SamsungFonts.emojiFamilyFiles(x);
            check("nasty XML safe: " + (x == null ? "null" : x.length()), r != null);
        }
        // a hostile billion-laughs-style blow up: many nested families; must finish quickly and bounded
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300000; i++) big.append("<family lang=\"und-Zsye\"><font>f").append(i % 7).append(".ttf</font></family>");
        long t0 = System.currentTimeMillis();
        List<String> capped = SamsungFonts.emojiFamilyFiles(big.toString());
        long ms = System.currentTimeMillis() - t0;
        check("huge XML finishes fast", ms < 4000);
        check("huge XML bounded unique set", capped.size() <= 16);
    }

    // ---------------------------------------------------------------------------------------------
    // The real (trimmed) AOSP fonts.xml / font_fallback.xml fixtures
    // ---------------------------------------------------------------------------------------------

    static void realFontConfigs() throws Exception {
        File dir = fixturesDir();
        if (dir == null || !new File(dir, "expected.txt").isFile()) {
            System.out.println("NOTE realFontConfigs: fixtures not found (tests/java/fixtures/fonts/samsungfonts); skipped");
            return;
        }
        SamsungFonts.Roots sys = SamsungFonts.Roots.system();
        for (String line : new String(Files.readAllBytes(new File(dir, "expected.txt").toPath()), StandardCharsets.UTF_8).split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String file = line.substring(0, c).trim();
            List<String> want = new ArrayList<String>();
            for (String w : line.substring(c + 1).trim().split("\\s+")) if (!w.isEmpty()) want.add(w);
            File f = new File(dir, file);
            if (!f.isFile()) { check("fixture present: " + file, false); continue; }
            String xml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<String> got = SamsungFonts.emojiFamilyFiles(xml);
            eq("emoji files of " + file, got, want);
            check(file + ": legacy ignored", !got.contains("NotoColorEmojiLegacy.ttf"));
            // emojiFiles resolves each to /system/fonts and /product/fonts
            List<String> targets = SamsungFonts.emojiFiles(sys, xml, null, null);
            for (String name : want) {
                check(file + ": system target for " + name, targets.contains("/system/fonts/" + name));
                check(file + ": product target for " + name, targets.contains("/product/fonts/" + name));
            }
        }
    }

    /** The fixtures folder, found by walking up to the repo root like the other font suites do. */
    static File fixturesDir() {
        String p = System.getProperty("fixtures");
        if (p != null && new File(p, "fonts/samsungfonts").isDirectory()) return new File(p, "fonts/samsungfonts");
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; d != null && i < 8; i++, d = d.getParentFile())
            if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return new File(d, "tests/java/fixtures/fonts/samsungfonts");
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // The scripts, run for real against a fake root
    // ---------------------------------------------------------------------------------------------

    static void setupFakeRoot() throws Exception {
        File base = Files.createTempDirectory("sf-").toFile();
        root = new File(base, "fake root's dir");          // a space and an apostrophe to prove the quoting
        bin = new File(base, "bin");
        bin.mkdirs();
        new File(root, "data/app_fonts/0").mkdirs();
        new File(root, "data/adb").mkdirs();
        new File(root, "system/fonts").mkdirs();
        new File(root, "product/fonts").mkdirs();
        new File(root, "data/fonts/files").mkdirs();
        R = new SamsungFonts.Roots(
                new File(root, "data/app_fonts").getAbsolutePath(),
                new File(root, "data/adb").getAbsolutePath(),
                new File(root, "system/fonts").getAbsolutePath(),
                new File(root, "product/fonts").getAbsolutePath(),
                new File(root, "data/fonts/files").getAbsolutePath());

        // stubs: succeed silently (and the ones the swap needs actually move bytes)
        for (String cmd : new String[]{"chown", "chcon", "restorecon", "setprop", "settings"})
            writeStub(cmd, "#!/bin/sh\nexit 0\n");
        // nsenter -t 1 -m -- CMD...  ->  run CMD
        writeStub("nsenter", "#!/bin/sh\nwhile [ $# -gt 0 ] && [ \"$1\" != '--' ]; do shift; done\n[ \"$1\" = '--' ] && shift\nexec \"$@\"\n");
        // mount -o bind SRC DST -> copy SRC over DST (simulates the bind), and log the target
        writeStub("mount", "#!/bin/sh\nwhile [ \"$1\" = '-o' ]; do shift 2; done\nsrc=\"$1\"; dst=\"$2\"\necho \"mount $dst\" >> \"$FAKE_LOG\"\ncp \"$src\" \"$dst\"\n");
        writeStub("umount", "#!/bin/sh\necho \"umount $1\" >> \"$FAKE_LOG\"\nexit 0\n");
    }

    static void writeStub(String name, String body) throws Exception {
        File f = new File(bin, name);
        Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8));
        f.setExecutable(true);
    }

    /** Runs a script through a real sh with the stub bin on PATH and stdin (may be null). Returns merged stdout+stderr. */
    static String sh(String script, byte[] stdin) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
        pb.environment().put("PATH", bin.getAbsolutePath() + ":" + System.getenv("PATH"));
        pb.environment().put("FAKE_LOG", new File(root, "mount.log").getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        OutputStream os = p.getOutputStream();
        if (stdin != null) os.write(stdin);
        os.close();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[8192]; int k;
        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        p.waitFor();
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    static byte[] bytesOf(int len, int seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    static String sha256(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return EmojiCatalog.hex(md.digest(b));
    }

    static void changerRoundTrip() throws Exception {
        String suffix = "Inter_aa11bb";
        byte[] reg = bytesOf(4096, 1), bold = bytesOf(3000, 2);
        String regSha = sha256(reg), boldSha = sha256(bold);

        String o1 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg.length, regSha), reg);
        SamsungFonts.WriteResult w1 = SamsungFonts.parseWrite(o1, "DroidSans.ttf");
        check("write regular ok", w1.ok);
        eq("write regular sha", w1.sha256, regSha);
        check("regular file exists", new File(R.appFonts + "/0/" + suffix + "/DroidSans.ttf").isFile());
        check("regular .part gone", !new File(R.appFonts + "/0/" + suffix + "/DroidSans.ttf.part").exists());

        String o2 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans-Bold.ttf", bold.length, boldSha), bold);
        check("write bold ok", SamsungFonts.parseWrite(o2, "DroidSans-Bold.ttf").ok);

        String o3 = sh(SamsungFonts.installScript(R, 0, suffix, new String[]{"DroidSans.ttf", "DroidSans-Bold.ttf"}, new String[]{regSha, boldSha}), null);
        SamsungFonts.ApplyResult r = SamsungFonts.parseApply(o3);
        check("install ok", r.ok);
        if (!r.ok) System.out.println("  install out: " + o3.replace("\n", " | "));
        eq("install sansLoc", r.sansLoc, R.appFonts + "/0/" + suffix + "#" + suffix);
        // the read-back hashes match what we wrote
        String got = "";
        for (SamsungFonts.FileInfo fi : r.files) if (fi.name.equals("DroidSans.ttf")) got = fi.sha256;
        eq("install read-back sha", got, regSha);
        // sans.loc on disk really holds that content, no trailing newline
        byte[] locBytes = Files.readAllBytes(new File(R.appFonts + "/0/sans.loc").toPath());
        eq("sans.loc bytes", new String(locBytes, StandardCharsets.UTF_8), R.appFonts + "/0/" + suffix + "#" + suffix);
    }

    static void changerMismatch() throws Exception {
        String suffix = "Mis_112233";
        byte[] reg = bytesOf(4096, 3);
        String regSha = sha256(reg);
        File dest = new File(R.appFonts + "/0/" + suffix + "/DroidSans.ttf");

        // wrong size: declare one byte more than we send
        String o1 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg.length + 1, regSha), reg);
        SamsungFonts.WriteResult w1 = SamsungFonts.parseWrite(o1, "DroidSans.ttf");
        check("size mismatch refused", !w1.ok);
        check("size mismatch names size", w1.error.contains("size"));
        check("size mismatch: no file placed", !dest.exists());
        check("size mismatch: no .part left", !new File(dest.getParentFile(), "DroidSans.ttf.part").exists());

        // wrong checksum: right size, wrong expected hash
        String o2 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg.length, "f".repeat(64)), reg);
        SamsungFonts.WriteResult w2 = SamsungFonts.parseWrite(o2, "DroidSans.ttf");
        check("sha mismatch refused", !w2.ok);
        check("sha mismatch names checksum", w2.error.contains("checksum"));
        check("sha mismatch: no file placed", !dest.exists());

        // install refuses when a staged file's hash no longer matches
        byte[] good = bytesOf(2048, 4);
        String goodSha = sha256(good);
        sh(SamsungFonts.writeFontScript(R, 0, "Chg_445566", "DroidSans.ttf", good.length, goodSha), good);
        String o3 = sh(SamsungFonts.installScript(R, 0, "Chg_445566", new String[]{"DroidSans.ttf"}, new String[]{"0".repeat(64)}), null);
        SamsungFonts.ApplyResult r3 = SamsungFonts.parseApply(o3);
        check("install refuses a changed file", !r3.ok);
        check("install: sans.loc not written for a changed file", !new File(R.appFonts + "/0/sans.loc").exists()
                || !new String(Files.readAllBytes(new File(R.appFonts + "/0/sans.loc").toPath()), StandardCharsets.UTF_8).contains("Chg_445566"));
    }

    static void resetAndStatus() throws Exception {
        String reset = sh(SamsungFonts.resetScript(R, 0), null);
        SamsungFonts.ApplyResult rr = SamsungFonts.parseApply(reset);
        check("reset ok", rr.ok);
        eq("reset sans.loc", rr.sansLoc, "default#default");
        eq("reset on disk", new String(Files.readAllBytes(new File(R.appFonts + "/0/sans.loc").toPath()), StandardCharsets.UTF_8), "default#default");

        // apply a font again, then status should report it
        byte[] reg = bytesOf(1500, 7);
        String regSha = sha256(reg);
        sh(SamsungFonts.writeFontScript(R, 0, "Stat_778899", "DroidSans.ttf", reg.length, regSha), reg);
        sh(SamsungFonts.installScript(R, 0, "Stat_778899", new String[]{"DroidSans.ttf"}, new String[]{regSha}), null);
        SamsungFonts.Status st = SamsungFonts.parseStatus(sh(SamsungFonts.statusScript(R, 0), null));
        check("status available", st.available);
        eq("status suffix", st.currentSuffix, "Stat_778899");
        check("status lists the suffix dir", st.entries.contains("Stat_778899"));
        check("status lists sans.loc", st.entries.contains("sans.loc"));

        // remove the app's own dir only
        String rm = sh(SamsungFonts.removeSuffixScript(R, 0, "Stat_778899"), null);
        check("remove reports gone", rm.contains("gone"));
        check("suffix dir removed", !new File(R.appFonts + "/0/Stat_778899").exists());

        // a phone without /data/app_fonts: install says so
        SamsungFonts.Roots noFonts = new SamsungFonts.Roots(new File(root, "nope/app_fonts").getAbsolutePath(),
                R.adb, R.systemFonts, R.productFonts, R.dataFonts);
        String o = sh(SamsungFonts.installScript(noFonts, 0, "X_000000", new String[]{"DroidSans.ttf"}, new String[]{"0".repeat(64)}), null);
        SamsungFonts.ApplyResult r = SamsungFonts.parseApply(o);
        check("no app_fonts: not ok", !r.ok);
        check("no app_fonts: Samsung message", r.error.contains("Samsung"));
    }

    static void emojiSwap() throws Exception {
        // the targets to bind over exist on the fake root
        File sysEmoji = new File(R.systemFonts + "/NotoColorEmoji.ttf");
        File sysFlags = new File(R.systemFonts + "/NotoColorEmojiFlags.ttf");
        Files.write(sysEmoji.toPath(), bytesOf(500, 11));
        Files.write(sysFlags.toPath(), bytesOf(400, 12));

        byte[] emoji = bytesOf(6000, 20);
        String emojiSha = sha256(emoji);
        String stageOut = sh(SamsungFonts.stageEmojiScript(R, "NotoColorEmoji.ttf", emoji.length, emojiSha), emoji);
        check("stage ok", SamsungFonts.parseWrite(stageOut, "NotoColorEmoji.ttf").ok);
        String staged = R.adbmgrFonts() + "/NotoColorEmoji.ttf";
        check("staged file is under adbmgr_fonts", new File(staged).isFile());

        List<String> targets = Arrays.asList(sysEmoji.getAbsolutePath(), sysFlags.getAbsolutePath(), R.productFonts + "/NotoColorEmoji.ttf");
        String swapOut = sh(SamsungFonts.emojiSwapScript(R, staged, targets), null);
        List<SamsungFonts.BindResult> sw = SamsungFonts.parseSwap(swapOut);
        eq("swap results", sw.size(), 3);
        eq("swap sys emoji ok", sw.get(0).state, "ok");
        eq("swap read-back sha is the staged font", sw.get(0).sha256, emojiSha);
        eq("swap product missing", sw.get(2).state, "missing");
        // the bind really replaced the target with the staged font on the fake root
        eq("target now the staged font", sha256(Files.readAllBytes(sysEmoji.toPath())), emojiSha);

        String undoOut = sh(SamsungFonts.emojiUndoScript(targets), null);
        List<SamsungFonts.BindResult> undo = SamsungFonts.parseSwap(undoOut);
        eq("undo results", undo.size(), 3);
        eq("undo state", undo.get(0).state, "undone");

        eq("soft restart script", SamsungFonts.softRestartScript().trim(), "setprop ctl.restart zygote");
    }

    // every absolute path the emoji-swap scripts touch is under adbmgr_fonts or is a declared target; nothing else
    static void pathConfinement() {
        String staged = R.adbmgrFonts() + "/NotoColorEmoji.ttf";
        List<String> targets = Arrays.asList("/system/fonts/NotoColorEmoji.ttf", "/product/fonts/NotoColorEmoji.ttf");
        String stage = SamsungFonts.stageEmojiScript(R, "NotoColorEmoji.ttf", 10, "0".repeat(64));
        String swap = SamsungFonts.emojiSwapScript(R, staged, targets);
        String undo = SamsungFonts.emojiUndoScript(targets);
        for (String abs : quotedAbsPaths(stage)) check("stage path confined: " + abs, abs.startsWith(R.adbmgrFonts() + "/") || abs.equals(R.adbmgrFonts()));
        for (String abs : quotedAbsPaths(swap)) check("swap path confined: " + abs, abs.startsWith(R.adbmgrFonts() + "/") || targets.contains(abs));
        for (String abs : quotedAbsPaths(undo)) check("undo path confined: " + abs, targets.contains(abs));
        // and the font-changer scripts never leave /data/app_fonts
        String inst = SamsungFonts.installScript(R, 0, "Inter_aa11bb", new String[]{"DroidSans.ttf"}, new String[]{"0".repeat(64)});
        for (String abs : quotedAbsPaths(inst)) check("install path confined: " + abs, abs.startsWith(R.appFonts + "/") || abs.equals(R.appFonts));
    }

    /** Every single-quoted token that looks like an absolute path (handles the '\'' escape BackupScripts.quote uses). */
    static List<String> quotedAbsPaths(String script) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (i < script.length()) {
            if (script.charAt(i) != '\'') { i++; continue; }
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < script.length()) {
                char c = script.charAt(i);
                if (c == '\'') {
                    // BackupScripts.quote encodes an inner ' as: ' \ ' '  -> close, escaped quote, reopen
                    if (i + 3 < script.length() && script.charAt(i + 1) == '\\' && script.charAt(i + 2) == '\'' && script.charAt(i + 3) == '\'') { sb.append('\''); i += 4; continue; }
                    break;
                }
                sb.append(c); i++;
            }
            i++;
            String tok = sb.toString();
            if (tok.startsWith("/")) {
                if (tok.endsWith(".tmp")) tok = tok.substring(0, tok.length() - 4);
                if (tok.endsWith(".part")) tok = tok.substring(0, tok.length() - 5);
                out.add(tok);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // Fuzz: the parsers and the XML scanner never throw, hang or run away on hostile input
    // ---------------------------------------------------------------------------------------------

    static void fuzz(long seed, int rounds) {
        Random rnd = new Random(seed);
        String alphabet = "@SF-LOC LS SHA END AVAIL BIND UNDO WROTE ok fail missing undone /data/app_fonts/0/x#y default#default "
                + "\n0123456789abcdef  object_r: : DroidSans.ttf -rw-r--r-- system <family lang=\"und-Zsye\"> <font> </font> </family> ignore=true /";
        long t0 = System.currentTimeMillis();
        for (int r = 0; r < rounds; r++) {
            String s = randomString(rnd, alphabet, rnd.nextInt(400));
            try {
                SamsungFonts.parseApply(s);
                SamsungFonts.parseStatus(s);
                SamsungFonts.parseWrite(s, "DroidSans.ttf");
                SamsungFonts.parseSwap(s);
                SamsungFonts.emojiFamilyFiles(s);
                SamsungFonts.currentSuffixOf(s);
                SamsungFonts.knownBeforeMarch2026(s);
            } catch (Throwable t) {
                fails++; n++;
                System.out.println("FAIL fuzz threw on <" + s.replace("\n", "\\n") + ">: " + t);
                return;
            }
            // random bytes as a whole (truncation / bit flips of a realistic read-back)
            byte[] b = new byte[rnd.nextInt(300)];
            rnd.nextBytes(b);
            String bs = new String(b, StandardCharsets.ISO_8859_1);
            try {
                SamsungFonts.parseApply(bs); SamsungFonts.parseStatus(bs); SamsungFonts.parseSwap(bs); SamsungFonts.emojiFamilyFiles(bs);
            } catch (Throwable t) { fails++; n++; System.out.println("FAIL fuzz(bytes) threw: " + t); return; }
        }
        // a huge count in a read-back line must not allocate
        try { SamsungFonts.parseApply(SamsungFonts.M_LS + "\n" + repeat("-rw-r--r-- 1 x x ? 99999999999999999999 d n\n", 2000) + SamsungFonts.M_END + "0\n"); }
        catch (Throwable t) { fails++; n++; System.out.println("FAIL fuzz(huge) threw: " + t); }
        n++;
        check("fuzz finished in seconds", System.currentTimeMillis() - t0 < 10000);
    }

    static String randomString(Random rnd, String alphabet, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }
}
