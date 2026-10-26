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
import java.util.Map;
import java.util.Random;

/**
 * SamsungFonts, the font changer's and the system-emoji swap's rules and privileged scripts. The One UI rule and the style index are
 * checked on their own; the scripts are run through a real {@code sh} against a fake root (the absolute roots point into a temp folder
 * whose name has a space and an apostrophe, to prove the quoting) with {@code chown}, {@code chcon}, {@code restorecon}, {@code nsenter},
 * {@code mount}, {@code umount}, {@code setprop} and {@code settings} stubbed on {@code PATH}, the way SettingsDbTest runs a real sh with a
 * fake {@code settings}. The fake {@code mount} and {@code umount} keep a mount table in init's format (proc/1/mountinfo under the fake
 * root), and the fake {@code nsenter} reads a bound file as the file its top mount shows, so a swap stacked on another, a root module's
 * bind and a bind the record lost are real cases. A seeded fuzz feeds the parsers and the XML scanner truncated, bit-flipped, random and
 * huge input.
 */
public class SamsungFontsTest {

    static int n = 0, fails = 0;
    static void check(String name, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + name); } }
    static void eq(String name, Object got, Object want) { n++; if (!java.util.Objects.equals(got, want)) { fails++; System.out.println("FAIL " + name + ": got <" + got + "> want <" + want + ">"); } }
    static void iae(String name, Runnable r) { n++; try { r.run(); fails++; System.out.println("FAIL " + name + ": no exception"); } catch (IllegalArgumentException e) { /* ok */ } }

    static File root, bin, mi, log, busy;
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
        foreignMounts();
        lostRecords();
        busyUndo();
        stagedCopies();
        blindMountTable();
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
        check("an undo that did not say how many of the swap's binds are left is not back", !undo.get(0).back(null));

        // the hash a file had before the bind (what Undo must bring back), and a file that carries a bind of the swap's own already
        String sw2 = SamsungFonts.M_BIND + " ok /system/fonts/A.ttf\n" + SamsungFonts.M_ORIG + " " + shb + "\n" + SamsungFonts.M_SHA + "\n" + sha + "  /system/fonts/A.ttf\n"
                + SamsungFonts.M_BIND + " stacked /system/fonts/B.ttf\n"
                + SamsungFonts.M_BIND + " ok /system/fonts/C.ttf\n" + SamsungFonts.M_ORIG + " not-a-hash\n" + SamsungFonts.M_SHA + "\n" + sha + "  /system/fonts/C.ttf\n"
                + SamsungFonts.M_END + "\n";
        List<SamsungFonts.BindResult> s2 = SamsungFonts.parseSwap(sw2);
        eq("swap with originals: count", s2.size(), 3);
        eq("the hash before the bind", s2.get(0).original, shb);
        eq("the hash after the bind", s2.get(0).sha256, sha);
        eq("stacked", s2.get(1).state, "stacked");
        eq("stacked: no hash read", s2.get(1).sha256, null);
        eq("a damaged hash before is no hash", s2.get(2).original, null);

        // after an undo: how many binds of the swap's own are left at each file
        String un = SamsungFonts.M_UNDO + " /system/fonts/A.ttf\n" + SamsungFonts.M_OWN + " 0\n" + SamsungFonts.M_SHA + "\n" + shb + "  /system/fonts/A.ttf\n"
                + SamsungFonts.M_UNDO + " /system/fonts/B.ttf\n" + SamsungFonts.M_OWN + " 1\n" + SamsungFonts.M_SHA + "\n" + sha + "  /system/fonts/B.ttf\n"
                + SamsungFonts.M_UNDO + " /system/fonts/C.ttf\n" + SamsungFonts.M_OWN + " unknown\n" + SamsungFonts.M_SHA + "\n" + shb + "  /system/fonts/C.ttf\n"
                + SamsungFonts.M_UNDO + " /system/fonts/D.ttf\n" + SamsungFonts.M_OWN + " 0\n" + SamsungFonts.M_SHA + "\n"
                + SamsungFonts.M_END + "\n";
        List<SamsungFonts.BindResult> u = SamsungFonts.parseSwap(un);
        eq("undo with own counts", u.size(), 4);
        eq("none of the swap's binds left", u.get(0).own, 0);
        eq("one left", u.get(1).own, 1);
        eq("not known", u.get(2).own, -1);
        check("back: none left and the hash from before", u.get(0).back(shb));
        check("back: none left, no hash recorded", u.get(0).back(null));
        check("not back: another hash than before", !u.get(0).back(sha));
        check("not back: a bind of the swap's own is left", !u.get(1).back(null));
        check("not back: the mount table was not read", !u.get(2).back(null));
        check("not back: no hash read back", !u.get(3).back(null));

        // the swap's own binds as the mount table lists them (escaped as the table writes them)
        String mnt = SamsungFonts.M_MNT + " yes\n"
                + SamsungFonts.M_MOUNT + " /system/fonts/NotoColorEmoji.ttf /adb/adbmgr_fonts/noto.ttf\n"
                + SamsungFonts.M_MOUNT + " /my\\040fonts/E.ttf /adb/adbmgr_fonts/twemoji.ttf\\040(deleted)\n"
                + SamsungFonts.M_MOUNT + " /system/fonts/NotoColorEmoji.ttf /adb/adbmgr_fonts/apple.ttf\n"
                + SamsungFonts.M_MOUNT + " /system/fonts/X.ttf /adb/modules/emoji/system/fonts/X.ttf\n"
                + SamsungFonts.M_MOUNT + " ../x /adb/adbmgr_fonts/y.ttf\n"
                + SamsungFonts.M_MOUNT + " /only-one-field\n"
                + SamsungFonts.M_END + "\n";
        SamsungFonts.Mounts m = SamsungFonts.parseMounts(mnt);
        check("mounts readable", m.readable);
        eq("mount points, each once, unescaped", m.targets, Arrays.asList("/system/fonts/NotoColorEmoji.ttf", "/my fonts/E.ttf"));
        eq("staged files in the table's order, (deleted) taken off", m.staged, Arrays.asList("noto.ttf", "twemoji.ttf", "apple.ttf"));
        eq("the newest bind shows", m.showing(), "apple");
        SamsungFonts.Mounts blind = SamsungFonts.parseMounts(SamsungFonts.M_MNT + " no\n" + SamsungFonts.M_MOUNT + " /a /adb/adbmgr_fonts/z.ttf\n" + SamsungFonts.M_END + "\n");
        check("mounts not readable", !blind.readable);
        check("not readable: nothing listed", blind.targets.isEmpty() && blind.staged.isEmpty() && blind.showing() == null);
        check("no answer: not readable", !SamsungFonts.parseMounts("").readable && !SamsungFonts.parseMounts(null).readable);
        SamsungFonts.Mounts none = SamsungFonts.parseMounts(SamsungFonts.M_MNT + " yes\n" + SamsungFonts.M_END + "\n");
        check("no bind of the swap's own", none.readable && none.targets.isEmpty() && none.showing() == null);

        // the staged copies read back
        Map<String, Boolean> stg = SamsungFonts.parseStaged(SamsungFonts.M_STAGED + " gone noto.ttf\n" + SamsungFonts.M_STAGED + " kept apple.ttf\njunk\n" + SamsungFonts.M_STAGED + " gone\n");
        eq("staged: two named", stg.size(), 2);
        eq("staged: gone", stg.get("noto.ttf"), Boolean.TRUE);
        eq("staged: kept", stg.get("apple.ttf"), Boolean.FALSE);

        // the mount table's escapes, both ways
        eq("escape", SamsungFonts.mountinfoEscape("/a b\\c\td\ne'f"), "/a\\040b\\134c\\011d\\012e'f");
        eq("unescape", SamsungFonts.mountinfoUnescape("/a\\040b\\134c\\011d\\012e'f"), "/a b\\c\td\ne'f");
        eq("unescape: a short escape stays", SamsungFonts.mountinfoUnescape("/a\\04"), "/a\\04");
        eq("unescape: not octal stays", SamsungFonts.mountinfoUnescape("/a\\089"), "/a\\089");
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
        List<String> targets = SamsungFonts.emojiFiles(sys, fonts, null);
        check("system path", targets.contains("/system/fonts/NotoColorEmoji.ttf"));
        check("product path", targets.contains("/product/fonts/NotoColorEmoji.ttf"));
        eq("each emoji file in /system/fonts and /product/fonts, nothing else", targets, Arrays.asList("/system/fonts/NotoColorEmoji.ttf",
                "/product/fonts/NotoColorEmoji.ttf", "/system/fonts/NotoColorEmojiFlags.ttf", "/product/fonts/NotoColorEmojiFlags.ttf"));

        // the updated emoji fonts the phone draws from instead: found, so the swap can refuse, and never a target (PRIV-1)
        String upEmoji = "/data/fonts/files/~~qK7mVYLC0ixC8b1R7QYs5A==/~~9KnkPwQ2T3AjJ1DQ0w8Ftg==/NotoColorEmoji.ttf";
        String upFlags = "/data/fonts/files/~~e/~~f/NotoColorEmojiFlags.ttf";
        List<String> listed = Arrays.asList(upEmoji, "/data/fonts/files/~~a/~~b/Roboto-Regular.ttf", "/etc/passwd", "/data/fonts/files/../../etc/NotoColorEmoji.ttf",
                "/data/fonts/files/~~c/~~d/NotoColorEmojiLegacy.ttf", "/data/fonts/filesX/NotoColorEmoji.ttf", null, "", upFlags, upEmoji);
        eq("updated emoji fonts: the emoji and the flags, each once", SamsungFonts.updatedEmojiFiles(sys, fonts, null, listed), Arrays.asList(upEmoji, upFlags));
        eq("updated emoji fonts: through font_fallback.xml too", SamsungFonts.updatedEmojiFiles(sys, null, fonts, listed), Arrays.asList(upEmoji, upFlags));
        eq("no updated emoji font", SamsungFonts.updatedEmojiFiles(sys, fonts, null, Arrays.asList("/data/fonts/files/~~a/~~b/Roboto-Regular.ttf")), new ArrayList<String>());
        eq("nothing listed", SamsungFonts.updatedEmojiFiles(sys, fonts, null, null), new ArrayList<String>());
        eq("no fonts.xml", SamsungFonts.updatedEmojiFiles(sys, null, null, listed), new ArrayList<String>());
        // the font map keys an update by PostScript name, which an entry can give apart from its file name
        String ps = "<family lang=\"und-Zsye\"><font weight=\"400\" style=\"normal\" postScriptName=\"NotoColorEmoji\">NotoColorEmoji-v2.ttf</font></family>";
        eq("matched by postScriptName", SamsungFonts.updatedEmojiFiles(sys, ps, null, Arrays.asList(upEmoji)), Arrays.asList(upEmoji));
        eq("…and by the file name", SamsungFonts.updatedEmojiFiles(sys, ps, null, Arrays.asList("/data/fonts/files/~~g/~~h/NotoColorEmoji-v2.ttf")),
                Arrays.asList("/data/fonts/files/~~g/~~h/NotoColorEmoji-v2.ttf"));
        eq("the fonts of that family, file name and PostScript name", SamsungFonts.emojiFamilyFonts(ps).get(0)[1], "NotoColorEmoji");

        // what the swap binds over: a file directly in /system/fonts or /product/fonts, nothing else
        check("a target in /system/fonts", SamsungFonts.isSwapTarget(sys, "/system/fonts/NotoColorEmoji.ttf"));
        check("a target in /product/fonts", SamsungFonts.isSwapTarget(sys, "/product/fonts/NotoColorEmoji.ttf"));
        for (String bad : new String[] { upEmoji, "/data/fonts/files/NotoColorEmoji.ttf", "/system/fonts/sub/NotoColorEmoji.ttf", "/system/fonts/../etc/hosts",
                "/etc/hosts", "/system/fonts/", "/system/fonts", "/system/fontsX/a.ttf", "", null })
            check("not a target: " + bad, !SamsungFonts.isSwapTarget(sys, bad));
        iae("the swap never binds under /data/fonts/files", () -> SamsungFonts.emojiSwapScript(sys, "/data/adb/adbmgr_fonts/noto.ttf",
                Arrays.asList("/system/fonts/NotoColorEmoji.ttf", upEmoji)));
        iae("…nor outside the font folders", () -> SamsungFonts.emojiSwapScript(sys, "/data/adb/adbmgr_fonts/noto.ttf", Arrays.asList("/etc/hosts")));
        check("a swap over /system/fonts alone is built", SamsungFonts.emojiSwapScript(sys, "/data/adb/adbmgr_fonts/noto.ttf",
                Arrays.asList("/system/fonts/NotoColorEmoji.ttf")).contains("mount -o bind"));

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
            List<String> targets = SamsungFonts.emojiFiles(sys, xml, null);
            for (String name : want) {
                check(file + ": system target for " + name, targets.contains("/system/fonts/" + name));
                check(file + ": product target for " + name, targets.contains("/product/fonts/" + name));
            }
            // an update of the emoji font under /data/fonts/files is found by its name in the real file
            String up = "/data/fonts/files/~~qK7mVYLC0ixC8b1R7QYs5A==/~~9KnkPwQ2T3AjJ1DQ0w8Ftg==/NotoColorEmoji.ttf";
            eq(file + ": the updated emoji font", SamsungFonts.updatedEmojiFiles(sys, xml, null, Arrays.asList(up, "/data/fonts/files/~~a/~~b/Roboto-Regular.ttf")),
                    Arrays.asList(up));
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
        new File(root, "proc/1").mkdirs();
        mi = new File(root, "proc/1/mountinfo");
        log = new File(root, "mount.log");
        busy = new File(root, "busy");
        resetMounts();
        R = new SamsungFonts.Roots(
                new File(root, "data/app_fonts").getAbsolutePath(),
                new File(root, "data/adb").getAbsolutePath(),
                new File(root, "system/fonts").getAbsolutePath(),
                new File(root, "product/fonts").getAbsolutePath(),
                new File(root, "data/fonts/files").getAbsolutePath(),
                mi.getAbsolutePath());

        // stubs: succeed silently (and the ones the swap needs keep a mount table)
        for (String cmd : new String[]{"chown", "chcon", "restorecon", "setprop", "settings"})
            writeStub(cmd, "#!/bin/sh\nexit 0\n");
        // the table's escapes, and the source of the top mount at a path ("" when nothing is mounted there)
        String fns = "esc() { printf '%s' \"$1\" | sed -e 's/\\\\/\\\\134/g' -e 's/ /\\\\040/g'; }\n"
                + "unesc() { printf '%s' \"$1\" | sed -e 's/\\\\040/ /g' -e 's/\\\\134/\\\\/g'; }\n"
                + "top() { E=\"$(esc \"$1\")\" awk 'BEGIN { e = ENVIRON[\"E\"] } $5 == e { s = $4 } END { print s }' \"$FAKE_MI\"; }\n";
        // nsenter -t 1 -m -- CMD...  ->  run CMD; a bound file reads as the file its top mount shows, as it does on the phone
        writeStub("nsenter", "#!/bin/sh\n" + fns
                + "while [ $# -gt 0 ] && [ \"$1\" != '--' ]; do shift; done\n[ \"$1\" = '--' ] && shift\n"
                + "if [ \"$1\" = sha256sum ] && [ -n \"$(top \"$2\")\" ]; then\n"
                + "  h=$(sha256sum \"$(unesc \"$(top \"$2\")\")\" 2>/dev/null | cut -d' ' -f1)\n"
                + "  [ -n \"$h\" ] && echo \"$h  $2\"\n  exit 0\nfi\nexec \"$@\"\n");
        // mount -o bind SRC DST -> a line at the end of the table (the newest mount is on top), and the target logged
        writeStub("mount", "#!/bin/sh\n" + fns
                + "while [ \"$1\" = '-o' ]; do shift 2; done\necho \"mount $2\" >> \"$FAKE_LOG\"\n"
                + "if [ ! -e \"$1\" ] || [ ! -e \"$2\" ]; then echo \"mount: $2: No such file or directory\" >&2; exit 1; fi\n"
                + "n=$(wc -l < \"$FAKE_MI\" | tr -d ' ')\n"
                + "printf '%s 1 0:2 %s %s rw - none none rw\\n' \"$((n + 100))\" \"$(esc \"$1\")\" \"$(esc \"$2\")\" >> \"$FAKE_MI\"\n");
        // umount [-l] DST -> the top mount at DST leaves the table; while FAKE_BUSY exists only a lazy one succeeds
        writeStub("umount", "#!/bin/sh\n" + fns
                + "echo \"umount $*\" >> \"$FAKE_LOG\"\n"
                + "lazy=0; if [ \"$1\" = '-l' ]; then lazy=1; shift; fi\n"
                + "if [ -e \"$FAKE_BUSY\" ] && [ $lazy = 0 ]; then echo 'umount: Device or resource busy' >&2; exit 1; fi\n"
                + "k=$(E=\"$(esc \"$1\")\" awk 'BEGIN { e = ENVIRON[\"E\"] } $5 == e { k = NR } END { print k + 0 }' \"$FAKE_MI\")\n"
                + "if [ \"$k\" = 0 ]; then echo \"umount: $1: not mounted\" >&2; exit 1; fi\n"
                + "awk -v k=\"$k\" 'NR != k' \"$FAKE_MI\" > \"$FAKE_MI.t\" && mv \"$FAKE_MI.t\" \"$FAKE_MI\"\n");
    }

    /** The fake init mount table with nothing but the root and /data, as after a restart. */
    static void resetMounts() throws Exception {
        Files.write(mi.toPath(), "1 0 0:1 / / rw - rootfs rootfs rw\n20 1 253:4 / /data rw,nosuid - f2fs /dev/block/dm-4 rw\n".getBytes(StandardCharsets.UTF_8));
    }

    static void clearLog() throws Exception { Files.write(log.toPath(), new byte[0]); }

    static String logText() throws Exception { return log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : ""; }

    /** The fake table's rows split into fields: [3] the source, [4] the mount point, both escaped. */
    static List<String[]> mountRows() throws Exception {
        List<String[]> out = new ArrayList<String[]>();
        for (String line : new String(Files.readAllBytes(mi.toPath()), StandardCharsets.UTF_8).split("\n")) if (!line.isEmpty()) out.add(line.split(" "));
        return out;
    }

    /** How many mounts are at a path, and how many of them are the swap's own (their source in adbmgr_fonts). */
    static int mountsAt(String path) throws Exception {
        int k = 0;
        for (String[] f : mountRows()) if (f.length > 4 && f[4].equals(SamsungFonts.mountinfoEscape(path))) k++;
        return k;
    }

    static int ownAt(String path) throws Exception {
        int k = 0;
        for (String[] f : mountRows()) if (f.length > 4 && f[4].equals(SamsungFonts.mountinfoEscape(path)) && f[3].contains("/adbmgr_fonts/")) k++;
        return k;
    }

    /** What the phone shows at a path: the file its top mount binds, else the file itself. */
    static byte[] seen(String path) throws Exception {
        String src = path;
        for (String[] f : mountRows()) if (f.length > 4 && f[4].equals(SamsungFonts.mountinfoEscape(path))) src = SamsungFonts.mountinfoUnescape(f[3]);
        return Files.readAllBytes(new File(src).toPath());
    }

    /** Another tool's bind (a root module's emoji), or a swap whose record was lost: through the same fake mount. */
    static void bind(String src, String dst) throws Exception {
        sh("mount -o bind " + BackupScripts.quote(src) + " " + BackupScripts.quote(dst), null);
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
        pb.environment().put("FAKE_LOG", log.getAbsolutePath());
        pb.environment().put("FAKE_MI", mi.getAbsolutePath());
        pb.environment().put("FAKE_BUSY", busy.getAbsolutePath());
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

    static String shaOf(String path) throws Exception { return sha256(Files.readAllBytes(new File(path).toPath())); }

    static void changerRoundTrip() throws Exception {
        String suffix = "Inter_aa11bb";
        String dir = R.appFonts + "/0/" + suffix;
        byte[] reg = bytesOf(4096, 1), bold = bytesOf(3000, 2);
        String regSha = sha256(reg), boldSha = sha256(bold);

        String o1 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg.length, regSha), reg);
        SamsungFonts.WriteResult w1 = SamsungFonts.parseWrite(o1, "DroidSans.ttf");
        check("write regular ok", w1.ok);
        eq("write regular sha", w1.sha256, regSha);
        check("the regular waits as .new", new File(dir + "/DroidSans.ttf.new").isFile());
        check("the regular is not in place before the install", !new File(dir + "/DroidSans.ttf").exists());
        check("regular .part gone", !new File(dir + "/DroidSans.ttf.part").exists());

        String o2 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans-Bold.ttf", bold.length, boldSha), bold);
        check("write bold ok", SamsungFonts.parseWrite(o2, "DroidSans-Bold.ttf").ok);

        String o3 = sh(SamsungFonts.installScript(R, 0, suffix, new String[]{"DroidSans.ttf", "DroidSans-Bold.ttf"}, new String[]{regSha, boldSha}), null);
        SamsungFonts.ApplyResult r = SamsungFonts.parseApply(o3);
        check("install ok", r.ok);
        if (!r.ok) System.out.println("  install out: " + o3.replace("\n", " | "));
        eq("install sansLoc", r.sansLoc, dir + "#" + suffix);
        // the read-back hashes match what we wrote
        String got = "";
        for (SamsungFonts.FileInfo fi : r.files) if (fi.name.equals("DroidSans.ttf")) got = fi.sha256;
        eq("install read-back sha", got, regSha);
        check("the install moved both files into place", new File(dir + "/DroidSans.ttf").isFile() && new File(dir + "/DroidSans-Bold.ttf").isFile()
                && !new File(dir + "/DroidSans.ttf.new").exists() && !new File(dir + "/DroidSans-Bold.ttf.new").exists());
        // sans.loc on disk really holds that content, no trailing newline
        byte[] locBytes = Files.readAllBytes(new File(R.appFonts + "/0/sans.loc").toPath());
        eq("sans.loc bytes", new String(locBytes, StandardCharsets.UTF_8), dir + "#" + suffix);

        // a new build of the font in use whose Bold arrives cut short: the font in use stays whole, and what was staged goes (PRIV-2)
        byte[] reg2 = bytesOf(4096, 21), bold2 = bytesOf(3000, 22);
        check("rebuild: the new Regular arrives", SamsungFonts.parseWrite(sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg2.length, sha256(reg2)), reg2), "DroidSans.ttf").ok);
        SamsungFonts.WriteResult cut = SamsungFonts.parseWrite(sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans-Bold.ttf", bold2.length, sha256(bold2)),
                Arrays.copyOf(bold2, 1000)), "DroidSans-Bold.ttf");
        check("rebuild: the cut-short Bold is refused", !cut.ok && cut.error.contains("size"));
        eq("rebuild cut short: the Regular in use is still the old one", shaOf(dir + "/DroidSans.ttf"), regSha);
        eq("rebuild cut short: the Bold in use is still the old one", shaOf(dir + "/DroidSans-Bold.ttf"), boldSha);
        String d1 = sh(SamsungFonts.discardScript(R, 0, suffix, new String[]{"DroidSans.ttf", "DroidSans-Bold.ttf"}), null);
        check("discard reads back each staged file gone", d1.contains(SamsungFonts.M_BIND + " gone DroidSans.ttf") && d1.contains(SamsungFonts.M_BIND + " gone DroidSans-Bold.ttf")
                && d1.contains(SamsungFonts.M_END));
        check("discard: no .new or .part left", !new File(dir + "/DroidSans.ttf.new").exists() && !new File(dir + "/DroidSans-Bold.ttf.part").exists());
        eq("discard: the Regular in use stays", shaOf(dir + "/DroidSans.ttf"), regSha);
        eq("discard: the Bold in use stays", shaOf(dir + "/DroidSans-Bold.ttf"), boldSha);
        // and an install now finds nothing staged: it stops before it changes anything
        SamsungFonts.ApplyResult none = SamsungFonts.parseApply(sh(SamsungFonts.installScript(R, 0, suffix, new String[]{"DroidSans.ttf"}, new String[]{sha256(reg2)}), null));
        check("install with nothing staged refuses", !none.ok && none.error.contains("was not staged"));
        eq("…and the Regular in use stays", shaOf(dir + "/DroidSans.ttf"), regSha);

        // made again without a Bold: the install takes the old Bold away as it puts the new Regular in place, and the read-back lists the Regular alone
        byte[] reg3 = bytesOf(4000, 23);
        String reg3Sha = sha256(reg3);
        sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg3.length, reg3Sha), reg3);
        check("before the install the old Bold is still there", new File(dir + "/DroidSans-Bold.ttf").isFile());
        SamsungFonts.ApplyResult r2 = SamsungFonts.parseApply(sh(SamsungFonts.installScript(R, 0, suffix, new String[]{"DroidSans.ttf"}, new String[]{reg3Sha},
                new String[]{"DroidSans-Bold.ttf"}), null));
        boolean boldListed = false;
        for (SamsungFonts.FileInfo fi : r2.files) if (fi.name.equals("DroidSans-Bold.ttf")) boldListed = true;
        check("install again ok, the read-back without a Bold", r2.ok && !boldListed);
        check("the old Bold is removed", !new File(dir + "/DroidSans-Bold.ttf").exists());
        eq("the new Regular is in place", shaOf(dir + "/DroidSans.ttf"), reg3Sha);

        // a staged file that changed after it was written: the install stops before the old Bold goes or the Regular moves
        Files.write(new File(dir + "/DroidSans-Bold.ttf").toPath(), bold);
        byte[] reg4 = bytesOf(4000, 24);
        sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg4.length, sha256(reg4)), reg4);
        Files.write(new File(dir + "/DroidSans.ttf.new").toPath(), bytesOf(4000, 25));
        SamsungFonts.ApplyResult r4 = SamsungFonts.parseApply(sh(SamsungFonts.installScript(R, 0, suffix, new String[]{"DroidSans.ttf"}, new String[]{sha256(reg4)},
                new String[]{"DroidSans-Bold.ttf"}), null));
        check("a changed staged file is refused", !r4.ok && r4.error.contains("changed after it was written"));
        check("…the old Bold is not taken away", new File(dir + "/DroidSans-Bold.ttf").isFile());
        eq("…and the Regular in use stays", shaOf(dir + "/DroidSans.ttf"), reg3Sha);
        sh(SamsungFonts.discardScript(R, 0, suffix, new String[]{"DroidSans.ttf", "DroidSans-Bold.ttf"}), null);
        Files.delete(new File(dir + "/DroidSans-Bold.ttf").toPath());

        iae("a file both written and removed", () -> SamsungFonts.installScript(R, 0, suffix, new String[]{"DroidSans.ttf"}, new String[]{regSha}, new String[]{"DroidSans.ttf"}));
        iae("a bad name to remove", () -> SamsungFonts.installScript(R, 0, suffix, new String[]{"DroidSans.ttf"}, new String[]{regSha}, new String[]{"../sans.loc"}));

        // a font that was never applied: its folder goes with what was staged in it
        String fresh = "Fresh_010203";
        sh(SamsungFonts.writeFontScript(R, 0, fresh, "DroidSans.ttf", reg.length, regSha), reg);
        check("fresh: staged", new File(R.appFonts + "/0/" + fresh + "/DroidSans.ttf.new").isFile());
        sh(SamsungFonts.discardScript(R, 0, fresh, new String[]{"DroidSans.ttf", "DroidSans-Bold.ttf"}), null);
        check("fresh: the folder is gone", !new File(R.appFonts + "/0/" + fresh).exists());

        String path = SamsungFonts.discardScript(R, 0, suffix, new String[]{"DroidSans.ttf", "DroidSans-Bold.ttf"});
        for (String abs : quotedAbsPaths(path)) check("discard path confined to the suffix folder: " + abs, abs.startsWith(dir + "/") || abs.equals(dir));
        for (String bad : new String[] { "../sans.loc", "a/b.ttf", "x.ttf;rm", ".ttf", "" }) {
            boolean refused = false;
            try { SamsungFonts.discardScript(R, 0, suffix, new String[]{bad}); } catch (IllegalArgumentException e) { refused = true; }
            check("discard refuses the file name " + bad, refused);
        }
    }

    static void changerMismatch() throws Exception {
        String suffix = "Mis_112233";
        byte[] reg = bytesOf(4096, 3);
        String regSha = sha256(reg);
        File dest = new File(R.appFonts + "/0/" + suffix + "/DroidSans.ttf");
        File staged = new File(R.appFonts + "/0/" + suffix + "/DroidSans.ttf.new");

        // wrong size: declare one byte more than we send
        String o1 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg.length + 1, regSha), reg);
        SamsungFonts.WriteResult w1 = SamsungFonts.parseWrite(o1, "DroidSans.ttf");
        check("size mismatch refused", !w1.ok);
        check("size mismatch names size", w1.error.contains("size"));
        check("size mismatch: no file placed or staged", !dest.exists() && !staged.exists());
        check("size mismatch: no .part left", !new File(dest.getParentFile(), "DroidSans.ttf.part").exists());

        // wrong checksum: right size, wrong expected hash
        String o2 = sh(SamsungFonts.writeFontScript(R, 0, suffix, "DroidSans.ttf", reg.length, "f".repeat(64)), reg);
        SamsungFonts.WriteResult w2 = SamsungFonts.parseWrite(o2, "DroidSans.ttf");
        check("sha mismatch refused", !w2.ok);
        check("sha mismatch names checksum", w2.error.contains("checksum"));
        check("sha mismatch: no file placed or staged", !dest.exists() && !staged.exists());

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

    static File sysEmoji, sysFlags;
    static String staged, origEmojiSha, origFlagsSha, emojiSha;

    static void emojiSwap() throws Exception {
        // the targets to bind over exist on the fake root
        sysEmoji = new File(R.systemFonts + "/NotoColorEmoji.ttf");
        sysFlags = new File(R.systemFonts + "/NotoColorEmojiFlags.ttf");
        Files.write(sysEmoji.toPath(), bytesOf(500, 11));
        Files.write(sysFlags.toPath(), bytesOf(400, 12));
        origEmojiSha = shaOf(sysEmoji.getPath());
        origFlagsSha = shaOf(sysFlags.getPath());
        String e = sysEmoji.getAbsolutePath(), fl = sysFlags.getAbsolutePath();

        byte[] emoji = bytesOf(6000, 20);
        emojiSha = sha256(emoji);
        String stageOut = sh(SamsungFonts.stageEmojiScript(R, "NotoColorEmoji.ttf", emoji.length, emojiSha), emoji);
        check("stage ok", SamsungFonts.parseWrite(stageOut, "NotoColorEmoji.ttf").ok);
        staged = R.adbmgrFonts() + "/NotoColorEmoji.ttf";
        check("staged file is under adbmgr_fonts", new File(staged).isFile());

        // an emoji update under /data/fonts/files on the fake root is found, and the swap refuses to bind over it (PRIV-1)
        File upd = new File(R.dataFonts + "/~~qK7mVYLC0ixC8b1R7QYs5A==/~~9KnkPwQ2T3AjJ1DQ0w8Ftg==/NotoColorEmoji.ttf");
        upd.getParentFile().mkdirs();
        Files.write(upd.toPath(), bytesOf(300, 13));
        List<String> listed = new ArrayList<String>();
        try (java.util.stream.Stream<java.nio.file.Path> w = Files.walk(new File(R.dataFonts).toPath())) {
            w.filter(Files::isRegularFile).forEach(p -> listed.add(p.toString()));
        }
        String xml = "<family lang=\"und-Zsye\"><font weight=\"400\" style=\"normal\">NotoColorEmoji.ttf</font></family>";
        eq("the update under /data/fonts/files is found", SamsungFonts.updatedEmojiFiles(R, xml, null, listed), Arrays.asList(upd.getAbsolutePath()));
        check("…and is no target", !SamsungFonts.emojiFiles(R, xml, null).contains(upd.getAbsolutePath()));
        iae("the swap refuses to bind over it", () -> SamsungFonts.emojiSwapScript(R, staged, Arrays.asList(e, upd.getAbsolutePath())));

        resetMounts();
        clearLog();
        List<String> targets = Arrays.asList(e, fl, R.productFonts + "/NotoColorEmoji.ttf");
        List<SamsungFonts.BindResult> sw = SamsungFonts.parseSwap(sh(SamsungFonts.emojiSwapScript(R, staged, targets), null));
        eq("swap results", sw.size(), 3);
        eq("swap sys emoji ok", sw.get(0).state, "ok");
        eq("swap read-back sha is the staged font", sw.get(0).sha256, emojiSha);
        eq("swap reads the hash before the bind", sw.get(0).original, origEmojiSha);
        eq("…of each file", sw.get(1).original, origFlagsSha);
        eq("swap product missing", sw.get(2).state, "missing");
        eq("the phone shows the staged font", sha256(seen(e)), emojiSha);
        eq("the file itself is not written", shaOf(e), origEmojiSha);
        eq("one bind of the swap's own on the emoji file", ownAt(e), 1);
        eq("…and on the flags", ownAt(fl), 1);

        // a second swap over the first is refused at each file: Undo would otherwise take the top one off and leave the first (PRIV-4)
        List<SamsungFonts.BindResult> again = SamsungFonts.parseSwap(sh(SamsungFonts.emojiSwapScript(R, staged, Arrays.asList(e, fl)), null));
        eq("stacked: both", again.size(), 2);
        check("stacked: not bound again", "stacked".equals(again.get(0).state) && "stacked".equals(again.get(1).state));
        eq("stacked: still one bind of the swap's own", ownAt(e), 1);

        // the swap's binds as the mount table lists them, found without any record (PRIV-4)
        SamsungFonts.Mounts m = SamsungFonts.parseMounts(sh(SamsungFonts.emojiMountsScript(R), null));
        check("mounts readable", m.readable);
        eq("mounts: both files, with the space and the apostrophe", m.targets, Arrays.asList(e, fl));
        eq("mounts: the font they show", m.showing(), "NotoColorEmoji");

        // Undo takes them off, and each file reads as it did before the swap
        List<SamsungFonts.BindResult> undo = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(R, m.targets), null));
        eq("undo results", undo.size(), 2);
        eq("undo state", undo.get(0).state, "undone");
        eq("undo: none of the swap's binds left", undo.get(0).own, 0);
        eq("undo: the hash from before", undo.get(0).sha256, origEmojiSha);
        check("undo: back", undo.get(0).back(sw.get(0).original) && undo.get(1).back(sw.get(1).original));
        eq("no bind of the swap's own is left", ownAt(e) + ownAt(fl), 0);
        eq("the phone shows its own emoji again", sha256(seen(e)), origEmojiSha);
        // a second Undo finds nothing to take off and still reads back
        clearLog();
        List<SamsungFonts.BindResult> undo2 = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(R, Arrays.asList(e)), null));
        check("a second undo: back, nothing taken off", undo2.size() == 1 && undo2.get(0).back(origEmojiSha) && !logText().contains("umount"));

        eq("soft restart script", SamsungFonts.softRestartScript().trim(), "setprop ctl.restart zygote");
    }

    // a root module's emoji bound over the same file: Undo never takes it off, only the swap's own bind (PRIV-3)
    static void foreignMounts() throws Exception {
        String e = sysEmoji.getAbsolutePath();
        File mod = new File(root, "data/adb/modules/emoji/system/fonts/NotoColorEmoji.ttf");
        mod.getParentFile().mkdirs();
        Files.write(mod.toPath(), bytesOf(700, 31));
        String modSha = shaOf(mod.getPath());
        resetMounts();
        bind(mod.getAbsolutePath(), e);
        eq("the module's emoji show", sha256(seen(e)), modSha);

        // a record from before a restart names this file: an Undo there leaves the module's emoji alone
        clearLog();
        List<SamsungFonts.BindResult> u = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(R, Arrays.asList(e)), null));
        eq("the module's bind is never taken off", mountsAt(e), 1);
        check("…not even tried", !logText().contains("umount"));
        check("…and the file reads as the module's", u.size() == 1 && u.get(0).own == 0 && modSha.equals(u.get(0).sha256));

        // a swap over the module's emoji, then Undo: only the swap's own bind goes, and the module's emoji show again
        List<SamsungFonts.BindResult> sw = SamsungFonts.parseSwap(sh(SamsungFonts.emojiSwapScript(R, staged, Arrays.asList(e)), null));
        check("swap over a module: bound", sw.size() == 1 && "ok".equals(sw.get(0).state) && emojiSha.equals(sw.get(0).sha256));
        eq("swap over a module: the hash before is the module's", sw.get(0).original, modSha);
        u = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(R, Arrays.asList(e)), null));
        check("undo over a module: back to the module's emoji", u.size() == 1 && u.get(0).back(modSha));
        eq("undo over a module: the module's bind stays", mountsAt(e), 1);
        eq("undo over a module: the module's emoji show", sha256(seen(e)), modSha);

        // a module bound on top of the swap: Undo stops at it, and says a bind of the swap's own is left below
        sh(SamsungFonts.emojiSwapScript(R, staged, Arrays.asList(e)), null);
        File mod2 = new File(root, "data/adb/modules/emoji2/NotoColorEmoji.ttf");
        mod2.getParentFile().mkdirs();
        Files.write(mod2.toPath(), bytesOf(800, 32));
        bind(mod2.getAbsolutePath(), e);
        clearLog();
        u = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(R, Arrays.asList(e)), null));
        check("a module on top: nothing taken off", mountsAt(e) == 3 && !logText().contains("umount"));
        check("a module on top: one of the swap's binds left, so not back", u.size() == 1 && u.get(0).own == 1 && !u.get(0).back(null));
        resetMounts();
    }

    // binds the record lost (the app killed after the bind, or its data cleared): the mount table finds them, and Undo takes all off (PRIV-4)
    static void lostRecords() throws Exception {
        String e = sysEmoji.getAbsolutePath();
        File other = new File(R.adbmgrFonts() + "/twemoji.ttf");
        Files.write(other.toPath(), bytesOf(900, 33));
        resetMounts();
        bind(staged, e);
        bind(other.getAbsolutePath(), e);
        SamsungFonts.Mounts m = SamsungFonts.parseMounts(sh(SamsungFonts.emojiMountsScript(R), null));
        eq("two stacked swaps: the file once", m.targets, Arrays.asList(e));
        eq("…both staged fonts, the newest last", m.staged, Arrays.asList("NotoColorEmoji.ttf", "twemoji.ttf"));
        eq("…the newest shows", m.showing(), "twemoji");
        List<SamsungFonts.BindResult> u = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(R, m.targets), null));
        check("undo takes both off", u.size() == 1 && u.get(0).own == 0 && u.get(0).back(origEmojiSha));
        eq("…nothing mounted there now", mountsAt(e), 0);
        SamsungFonts.Mounts after = SamsungFonts.parseMounts(sh(SamsungFonts.emojiMountsScript(R), null));
        check("…and the table lists none of the swap's binds", after.readable && after.targets.isEmpty());
        Files.delete(other.toPath());
    }

    // a file still in use: the plain umount fails as busy, the lazy one follows
    static void busyUndo() throws Exception {
        String e = sysEmoji.getAbsolutePath();
        resetMounts();
        sh(SamsungFonts.emojiSwapScript(R, staged, Arrays.asList(e)), null);
        Files.write(busy.toPath(), new byte[0]);
        clearLog();
        List<SamsungFonts.BindResult> u = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(R, Arrays.asList(e)), null));
        String ul = logText();
        check("busy: a lazy umount follows the plain one", ul.contains("umount " + e) && ul.contains("umount -l " + e));
        check("busy: back", u.size() == 1 && u.get(0).back(origEmojiSha));
        Files.delete(busy.toPath());
        sh(SamsungFonts.emojiSwapScript(R, staged, Arrays.asList(e)), null);
        clearLog();
        sh(SamsungFonts.emojiUndoScript(R, Arrays.asList(e)), null);
        check("not busy: no lazy umount", logText().contains("umount " + e) && !logText().contains("umount -l"));
        resetMounts();
    }

    // the staged copies go once nothing is bound from them, and are read back
    static void stagedCopies() throws Exception {
        String m = R.adbmgrFonts();
        File apple = new File(m + "/apple.ttf"), blob = new File(m + "/blob.ttf"), tw = new File(m + "/twemoji.ttf");
        for (File f : new File[] { apple, blob, tw }) Files.write(f.toPath(), bytesOf(100, f.getName().length()));
        Files.write(new File(m + "/blob.ttf.part").toPath(), bytesOf(10, 1));
        resetMounts();
        bind(apple.getAbsolutePath(), sysEmoji.getAbsolutePath());
        // a copy deleted and staged again while a bind still holds the old file: the table writes its source with " (deleted)"
        Files.write(mi.toPath(), ("300 1 0:2 " + SamsungFonts.mountinfoEscape(tw.getAbsolutePath() + " (deleted)") + " " + SamsungFonts.mountinfoEscape(sysFlags.getAbsolutePath())
                + " rw - none none rw\n").getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.APPEND);
        List<String> names = Arrays.asList("apple.ttf", "blob.ttf", "twemoji.ttf", "noto.ttf", "apple.ttf");
        Map<String, Boolean> st = SamsungFonts.parseStaged(sh(SamsungFonts.removeStagedScript(R, names), null));
        eq("staged: each name once", st.size(), 4);
        eq("a copy still bound is kept", st.get("apple.ttf"), Boolean.FALSE);
        eq("a copy nothing is bound from goes", st.get("blob.ttf"), Boolean.TRUE);
        eq("a copy whose bind holds the deleted file is kept", st.get("twemoji.ttf"), Boolean.FALSE);
        eq("a copy never staged reads gone", st.get("noto.ttf"), Boolean.TRUE);
        check("on disk: the bound copies stay, the other goes with its .part", apple.isFile() && tw.isFile() && !blob.exists() && !new File(m + "/blob.ttf.part").exists());
        check("the folder stays while a copy is in it", new File(m).isDirectory());

        // the mount table cannot be read: every copy stays
        SamsungFonts.Roots blind = new SamsungFonts.Roots(R.appFonts, R.adb, R.systemFonts, R.productFonts, R.dataFonts, new File(root, "proc/none/mountinfo").getAbsolutePath());
        resetMounts();
        Map<String, Boolean> kept = SamsungFonts.parseStaged(sh(SamsungFonts.removeStagedScript(blind, Arrays.asList("apple.ttf")), null));
        check("no mount table: kept", Boolean.FALSE.equals(kept.get("apple.ttf")) && apple.isFile());

        // the binds gone (Undo, or a restart): every copy goes, and the folder with them
        Map<String, Boolean> all = SamsungFonts.parseStaged(sh(SamsungFonts.removeStagedScript(R, Arrays.asList("apple.ttf", "twemoji.ttf", "NotoColorEmoji.ttf")), null));
        check("unbound: every copy gone", all.size() == 3 && !all.containsValue(Boolean.FALSE));
        check("unbound: the staging folder is gone", !new File(m).exists());

        iae("a staged name with a path", () -> SamsungFonts.removeStagedScript(R, Arrays.asList("../x.ttf")));
        iae("a staged name that is no font", () -> SamsungFonts.removeStagedScript(R, Arrays.asList("x.sh")));
    }

    // init's mount table cannot be read: nothing is bound, nothing taken off, and it says so
    static void blindMountTable() throws Exception {
        SamsungFonts.Roots blind = new SamsungFonts.Roots(R.appFonts, R.adb, R.systemFonts, R.productFonts, R.dataFonts, new File(root, "proc/none/mountinfo").getAbsolutePath());
        String e = sysEmoji.getAbsolutePath();
        byte[] emoji = bytesOf(6000, 20);
        sh(SamsungFonts.stageEmojiScript(R, "NotoColorEmoji.ttf", emoji.length, emojiSha), emoji);
        resetMounts();
        clearLog();
        String out = sh(SamsungFonts.emojiSwapScript(blind, staged, Arrays.asList(e)), null);
        check("no mount table: the swap refuses", out.contains("ERROR: the list of mounts could not be read."));
        check("no mount table: nothing bound", SamsungFonts.parseSwap(out).isEmpty() && !logText().contains("mount ") && mountsAt(e) == 0);
        bind(staged, e);
        clearLog();
        List<SamsungFonts.BindResult> u = SamsungFonts.parseSwap(sh(SamsungFonts.emojiUndoScript(blind, Arrays.asList(e)), null));
        check("no mount table: nothing taken off", !logText().contains("umount") && mountsAt(e) == 1);
        check("no mount table: not known, so not back", u.size() == 1 && u.get(0).own == -1 && !u.get(0).back(null));
        SamsungFonts.Mounts m = SamsungFonts.parseMounts(sh(SamsungFonts.emojiMountsScript(blind), null));
        check("no mount table: not readable", !m.readable && m.targets.isEmpty());
        resetMounts();
        sh(SamsungFonts.removeStagedScript(R, Arrays.asList("NotoColorEmoji.ttf")), null);
    }

    // every absolute path the emoji-swap scripts touch is under adbmgr_fonts, a declared target (as it is or as the mount table writes it)
    // or the mount table; nothing else
    static void pathConfinement() {
        String staged = R.adbmgrFonts() + "/NotoColorEmoji.ttf";
        List<String> targets = Arrays.asList(R.systemFonts + "/NotoColorEmoji.ttf", R.productFonts + "/NotoColorEmoji.ttf");
        List<String> allowed = new ArrayList<String>(targets);
        for (String t : targets) allowed.add(SamsungFonts.mountinfoEscape(t));
        allowed.add(R.mountinfo);
        String stage = SamsungFonts.stageEmojiScript(R, "NotoColorEmoji.ttf", 10, "0".repeat(64));
        String swap = SamsungFonts.emojiSwapScript(R, staged, targets);
        String undo = SamsungFonts.emojiUndoScript(R, targets);
        String mounts = SamsungFonts.emojiMountsScript(R);
        String removeStaged = SamsungFonts.removeStagedScript(R, Arrays.asList("NotoColorEmoji.ttf", "apple.ttf"));
        for (String abs : quotedAbsPaths(stage)) check("stage path confined: " + abs, abs.startsWith(R.adbmgrFonts() + "/") || abs.equals(R.adbmgrFonts()));
        for (String abs : quotedAbsPaths(swap)) check("swap path confined: " + abs, abs.startsWith(R.adbmgrFonts() + "/") || allowed.contains(abs));
        for (String abs : quotedAbsPaths(undo)) check("undo path confined: " + abs, allowed.contains(abs));
        for (String abs : quotedAbsPaths(mounts)) check("mounts path confined: " + abs, abs.equals(R.mountinfo));
        for (String abs : quotedAbsPaths(removeStaged)) check("staged removal path confined: " + abs, abs.startsWith(R.adbmgrFonts() + "/") || abs.equals(R.adbmgrFonts()) || abs.equals(R.mountinfo));
        check("the swap never names /data/fonts/files", !swap.contains(R.dataFonts) && !undo.contains(R.dataFonts));
        // and the font-changer scripts never leave /data/app_fonts
        String inst = SamsungFonts.installScript(R, 0, "Inter_aa11bb", new String[]{"DroidSans.ttf"}, new String[]{"0".repeat(64)}, new String[]{"DroidSans-Bold.ttf"});
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
                + "\n0123456789abcdef  object_r: : DroidSans.ttf -rw-r--r-- system <family lang=\"und-Zsye\"> <font> </font> </family> ignore=true /"
                + " @@SF-ORIG@@ @@SF-OWN@@ @@SF-MNT@@ yes no @@SF-MOUNT@@ @@SF-STAGED@@ gone kept stacked unknown \\040 \\134 \\0 adbmgr_fonts/ (deleted)"
                + " postScriptName=\"x\" /data/fonts/files/";
        long t0 = System.currentTimeMillis();
        for (int r = 0; r < rounds; r++) {
            String s = randomString(rnd, alphabet, rnd.nextInt(400));
            try {
                SamsungFonts.parseApply(s);
                SamsungFonts.parseStatus(s);
                SamsungFonts.parseWrite(s, "DroidSans.ttf");
                for (SamsungFonts.BindResult b : SamsungFonts.parseSwap(s)) b.back(null);
                SamsungFonts.parseMounts(s).showing();
                SamsungFonts.parseStaged(s);
                SamsungFonts.mountinfoUnescape(s);
                SamsungFonts.emojiFamilyFiles(s);
                SamsungFonts.updatedEmojiFiles(SamsungFonts.Roots.system(), s, s, Arrays.asList("/data/fonts/files/" + s, s));
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
                SamsungFonts.parseMounts(bs).showing(); SamsungFonts.parseStaged(bs); SamsungFonts.mountinfoUnescape(bs);
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
