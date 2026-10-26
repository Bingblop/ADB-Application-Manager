package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Custom Fonts tab's bookkeeping (FontLibrary), against a real folder tree: ids and tokens, the folders, picked files (replaced, held
 * by a build, cleared), the build request with every field wrong in turn, meta.json both ways and the entries it refuses, an entry
 * committed, made again, cut short and swept up after a crash, the list order, export names, the font changer's and the emoji swap's
 * records (a swap from another boot is not in use), jobs and their Cancel, the progress throttle, and the JSON the page gets (info of real
 * fixture fonts, a library row, the emoji downloads below and above Android 13). Then a scan of MainActivity: every bridge method and
 * callback of design section 6, the Root / system-user gate, the executors stopped in onDestroy, network through HttpSafe only.
 */
public class FontLibraryTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static File tmp;
    static FontLibrary.Dirs dirs;
    static final String TOK1 = "0123456789abcdef0123456789abcdef", TOK2 = "fedcba9876543210fedcba9876543210", TOK3 = "00000000000000000000000000000001";
    static final String SHA_A = repeat("a", 64), SHA_B = repeat("b", 64), SHA_C = repeat("c", 64);

    static String repeat(String s, int k) { StringBuilder b = new StringBuilder(); for (int i = 0; i < k; i++) b.append(s); return b.toString(); }

    static void write(File f, int len) throws Exception {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[len]);
    }

    public static void main(String[] a) throws Exception {
        tmp = Files.createTempDirectory("fontlib-").toFile();
        dirs = new FontLibrary.Dirs(new File(tmp, "files"), new File(tmp, "cache"));
        idsAndDirs();
        picks();
        requests();
        meta();
        commitAndList();
        records();
        jobs();
        rebuilds();
        downloads();
        pageJson();
        files();
        bridgeScan();
        FontLibrary.deleteTree(tmp);
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " FontLibraryTest: " + (n - fails) + "/" + n + " checks");
        if (fails != 0) System.exit(1);
    }

    // ---------------------------------------------------------------------------------------------

    static void idsAndDirs() {
        Random r = new Random(7);
        String id = FontLibrary.newId(r), tok = FontLibrary.newToken(r);
        is("a new id is an id", FontLibrary.isId(id), true);
        eq("ids are 12 hex digits", id.length(), 12);
        is("a new token is a token", FontLibrary.isToken(tok), true);
        eq("tokens are 32 hex digits", tok.length(), 32);
        is("an id is not a token", FontLibrary.isToken(id), false);
        is("a token is not an id", FontLibrary.isId(tok), false);
        for (String bad : new String[] { null, "", "../../etc/x", "ABCDEF012345", "0123456789ab/", "0123456789a", "0123456789abc", "01234567 9ab", "0123456789a\n" })
            is("not an id: " + bad, FontLibrary.isId(bad), false);
        for (String bad : new String[] { null, "", "0123456789ABCDEF0123456789ABCDEF", "../" + TOK1, TOK1 + "0", TOK1.substring(1), TOK1.substring(0, 31) + "g" })
            is("not a token: " + bad, FontLibrary.isToken(bad), false);
        is("catalog id", FontLibrary.isCatalogId("noto"), true);
        for (String bad : new String[] { null, "", "Noto", "no to", "../x", "noto.ttf", repeat("a", 33) })
            is("not a catalog id: " + bad, FontLibrary.isCatalogId(bad), false);
        is("slots", FontLibrary.isSlot("regular") && FontLibrary.isSlot("bold") && FontLibrary.isSlot("emoji"), true);
        is("not a slot", FontLibrary.isSlot("italic") || FontLibrary.isSlot(null) || FontLibrary.isSlot("Regular"), false);

        eq("library folder", dirs.library, new File(new File(new File(tmp, "files"), "fontmaker"), "library"));
        eq("pick folder", dirs.in, new File(new File(new File(tmp, "cache"), "fontmaker"), "in"));
        eq("entry folder", dirs.entryDir("0123456789ab"), new File(dirs.library, "0123456789ab"));
        boolean threw = false;
        try { dirs.entryDir("../x"); } catch (IllegalArgumentException e) { threw = true; }
        is("an entry folder from a non-id is refused", threw, true);
        eq("emoji file", dirs.emojiFile("noto"), new File(dirs.emoji, "noto.ttf"));
        eq("no emoji file for a path", dirs.emojiFile("../../x"), null);
        eq("a window's pick folder", dirs.pickFolder("0123456789ab"), new File(dirs.in, "0123456789ab"));
        threw = false;
        try { dirs.pickFolder("../x"); } catch (IllegalArgumentException e) { threw = true; }
        is("a pick folder from a non-id is refused", threw, true);

        eq("user 0", FontLibrary.userIdOf(10234), 0);
        eq("user 10", FontLibrary.userIdOf(1010234), 10);
        eq("user 150", FontLibrary.userIdOf(15010234), 150);
        eq("no uid", FontLibrary.userIdOf(-1), 0);
    }

    // ---------------------------------------------------------------------------------------------

    static FontLibrary.Pick pick(FontLibrary.Picks ps, String slot, String token) throws Exception {
        File f = ps.fileFor(slot, token);
        write(f, 100);
        FontLibrary.Pick p = new FontLibrary.Pick(slot, token, "x.ttf", f, 100, null, "TrueType", false);
        ps.put(p);
        return p;
    }

    static void picks() throws Exception {
        File w1 = dirs.pickFolder("0000000000a1"), w2 = dirs.pickFolder("0000000000a2");
        write(new File(w1, "regular-" + TOK3 + ".font"), 10);
        FontLibrary.Picks ps = new FontLibrary.Picks(w1);
        is("copies left in the window's folder are removed", new File(w1, "regular-" + TOK3 + ".font").exists(), false);
        eq("a pick's copy is in the window's folder", ps.fileFor("bold", TOK1), new File(w1, "bold-" + TOK1 + ".font"));
        FontLibrary.Pick a = pick(ps, "regular", TOK1);
        eq("colour defaults to none", a.color, "none");
        is("the pick is known", ps.get(TOK1) == a, true);
        eq("an unknown token", ps.get(TOK2), null);
        eq("not a token", ps.get("../" + TOK1), null);
        FontLibrary.Pick b = pick(ps, "regular", TOK2);
        eq("a new pick of the slot forgets the old one", ps.get(TOK1), null);
        is("…and deletes its copy", a.file.exists(), false);
        is("the new one is known", ps.get(TOK2) == b, true);
        FontLibrary.Pick c = pick(ps, "bold", TOK1);
        is("another slot is separate", ps.get(TOK1) == c && ps.get(TOK2) == b, true);

        ps.hold(b);
        ps.hold(b);
        FontLibrary.Pick d = pick(ps, "regular", TOK3);
        eq("a held pick is no longer current", ps.get(TOK2), null);
        is("…but its copy stays while a build reads it", b.file.exists(), true);
        ps.release(b);
        is("…still held once", b.file.exists(), true);
        ps.release(b);
        is("…deleted when the last build lets go", b.file.exists(), false);
        ps.hold(d);
        ps.release(d);
        is("a current pick is kept after its build", d.file.exists() && ps.get(TOK3) == d, true);
        ps.release(d);
        ps.release(null);
        is("an extra release does nothing", d.file.exists(), true);

        // a second window (PKG-5): its own folder; starting and closing it leaves the first window's copies alone
        FontLibrary.Picks other = new FontLibrary.Picks(w2);
        is("a second window's picks leave the first one's copies", c.file.exists() && d.file.exists() && ps.get(TOK3) == d, true);
        FontLibrary.Pick o = pick(other, "regular", TOK2);
        other.clear();
        is("…and so does closing it", c.file.exists() && d.file.exists() && !o.file.exists(), true);

        // two choices of one slot copied side by side (DL-4): the copy of the earlier one ends last and is not kept
        int first = ps.ask("emoji"), second = ps.ask("emoji");
        is("choices are numbered in order", second > first, true);
        String t4 = repeat("9", 32), t5 = repeat("7", 32);
        File fb = ps.fileFor("emoji", TOK2), fa = ps.fileFor("emoji", t4);
        write(fb, 50);
        write(fa, 50);
        FontLibrary.Pick later = new FontLibrary.Pick("emoji", TOK2, "local.ttf", fb, 50, "COLRv0", "TrueType", false);
        FontLibrary.Pick earlier = new FontLibrary.Pick("emoji", t4, "cloud.ttf", fa, 50, "COLRv0", "TrueType", false);
        is("the later choice, copied first, is kept", ps.put(later, second), true);
        is("the earlier choice, copied last, is refused", ps.put(earlier, first), false);
        is("…its copy is deleted", fa.exists(), false);
        is("…and the later one stays the slot's", ps.get(TOK2) == later && fb.exists(), true);
        eq("…the earlier one is unknown", ps.get(t4), null);
        int third = ps.ask("emoji");
        File fc = ps.fileFor("emoji", t5);
        write(fc, 50);
        is("a newer choice still replaces it", ps.put(new FontLibrary.Pick("emoji", t5, "new.ttf", fc, 50, "COLRv0", "TrueType", false), third)
                && ps.get(TOK2) == null && !fb.exists(), true);

        ps.clear();
        is("clear deletes every copy", c.file.exists() || d.file.exists() || fc.exists(), false);
        eq("…and forgets them", ps.get(TOK3), null);
    }

    // ---------------------------------------------------------------------------------------------

    static String badCode(String json) {
        try {
            FontLibrary.parseRequest(json);
            return "accepted";
        } catch (FontLibrary.BadRequest e) {
            return e.code;
        }
    }

    static void requests() throws Exception {
        FontLibrary.Request r = FontLibrary.parseRequest("{\"name\":\"  My  Font \",\"regular\":{\"token\":\"" + TOK1 + "\",\"coords\":{\"wght\":400,\"opsz\":14.5}},"
                + "\"bold\":{\"token\":\"" + TOK2 + "\",\"coords\":null},\"emoji\":{\"catalogId\":\"noto\"},\"emojiScale\":1.1}");
        eq("name cleaned as the package will be", r.name, FontPackage.cleanName("  My  Font "));
        eq("regular token", r.regular.token, TOK1);
        eq("coords in order", new ArrayList<String>(r.regular.coords.keySet()), Arrays.asList("wght", "opsz"));
        eq("a coordinate", r.regular.coords.get("opsz"), 14.5);
        eq("bold token", r.bold.token, TOK2);
        eq("no coords for a static bold", r.bold.coords, null);
        eq("catalog emoji", r.emojiCatalogId, "noto");
        is("has emoji", r.hasEmoji(), true);
        eq("emoji size", r.emojiScale, 1.1);
        eq("no rebuild", r.rebuildId, null);

        FontLibrary.Request r2 = FontLibrary.parseRequest("{\"name\":\"A\",\"regular\":{\"token\":\"" + TOK1 + "\"},\"bold\":null,\"emoji\":{\"token\":\"" + TOK3 + "\"},\"rebuildId\":\"0123456789ab\"}");
        eq("own emoji file", r2.emojiToken, TOK3);
        eq("no bold", r2.bold, null);
        eq("emoji size default", r2.emojiScale, 1.0);
        eq("rebuild id", r2.rebuildId, "0123456789ab");
        FontLibrary.Request r3 = FontLibrary.parseRequest("{\"name\":\"A\",\"regular\":{\"token\":\"" + TOK1 + "\"},\"emoji\":null,\"emojiScale\":null,\"rebuildId\":null}");
        is("no emoji", r3.hasEmoji(), false);

        String reg = "\"regular\":{\"token\":\"" + TOK1 + "\"}";
        eq("not JSON", badCode("{name"), "bad_request");
        eq("null", badCode(null), "bad_request");
        eq("too long", badCode("{\"name\":\"" + repeat("a", 70000) + "\"}"), "bad_request");
        eq("no name", badCode("{" + reg + "}"), "bad_name");
        eq("name not a string", badCode("{\"name\":5," + reg + "}"), "bad_name");
        eq("empty name", badCode("{\"name\":\"   \"," + reg + "}"), "bad_name");
        eq("no regular", badCode("{\"name\":\"A\"}"), "no_regular");
        eq("regular null", badCode("{\"name\":\"A\",\"regular\":null}"), "no_regular");
        eq("regular not an object", badCode("{\"name\":\"A\",\"regular\":\"" + TOK1 + "\"}"), "bad_request");
        eq("a path for a token", badCode("{\"name\":\"A\",\"regular\":{\"token\":\"/sdcard/x.ttf\"}}"), "bad_request");
        eq("bold path", badCode("{\"name\":\"A\"," + reg + ",\"bold\":{\"token\":\"../x\"}}"), "bad_request");
        eq("axis tag too long", badCode("{\"name\":\"A\",\"regular\":{\"token\":\"" + TOK1 + "\",\"coords\":{\"wghts\":400}}}"), "bad_request");
        eq("axis value a string", badCode("{\"name\":\"A\",\"regular\":{\"token\":\"" + TOK1 + "\",\"coords\":{\"wght\":\"400\"}}}"), "bad_request");
        eq("axis value huge", badCode("{\"name\":\"A\",\"regular\":{\"token\":\"" + TOK1 + "\",\"coords\":{\"wght\":1e9}}}"), "bad_request");
        eq("coords an array", badCode("{\"name\":\"A\",\"regular\":{\"token\":\"" + TOK1 + "\",\"coords\":[400]}}"), "bad_request");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 70; i++) many.append(i == 0 ? "" : ",").append("\"a").append(String.format("%03d", i)).append("\":1");
        eq("too many axes", badCode("{\"name\":\"A\",\"regular\":{\"token\":\"" + TOK1 + "\",\"coords\":{" + many + "}}}"), "bad_request");
        eq("two emoji", badCode("{\"name\":\"A\"," + reg + ",\"emoji\":{\"catalogId\":\"noto\",\"token\":\"" + TOK2 + "\"}}"), "bad_request");
        eq("empty emoji", badCode("{\"name\":\"A\"," + reg + ",\"emoji\":{}}"), "bad_request");
        eq("emoji a path", badCode("{\"name\":\"A\"," + reg + ",\"emoji\":{\"catalogId\":\"../noto\"}}"), "bad_request");
        eq("emoji token a path", badCode("{\"name\":\"A\"," + reg + ",\"emoji\":{\"token\":\"/data/x\"}}"), "bad_request");
        eq("emoji a string", badCode("{\"name\":\"A\"," + reg + ",\"emoji\":\"noto\"}"), "bad_request");
        eq("emoji size small", badCode("{\"name\":\"A\"," + reg + ",\"emojiScale\":0.5}"), "bad_request");
        eq("emoji size big", badCode("{\"name\":\"A\"," + reg + ",\"emojiScale\":1.3}"), "bad_request");
        eq("emoji size a string", badCode("{\"name\":\"A\"," + reg + ",\"emojiScale\":\"1\"}"), "bad_request");
        eq("rebuild a path", badCode("{\"name\":\"A\"," + reg + ",\"rebuildId\":\"../../x\"}"), "bad_request");
        eq("edges of the emoji size", badCode("{\"name\":\"A\"," + reg + ",\"emojiScale\":0.8}") + badCode("{\"name\":\"A\"," + reg + ",\"emojiScale\":1.25}"), "acceptedaccepted");
    }

    // ---------------------------------------------------------------------------------------------

    static FontLibrary.Entry entry(String id, String name, boolean bold) throws Exception {
        FontLibrary.Entry e = new FontLibrary.Entry();
        e.id = id;
        e.name = name;
        e.suffix = FontPackage.suffixFor(name, id);
        e.pkg = FontPackage.packageName(e.suffix);
        e.versionCode = 1;
        e.created = 1000;
        e.updated = 1000;
        e.hasBold = bold;
        e.apkSize = 300;
        e.regularSize = 100;
        e.boldSize = bold ? 200 : 0;
        e.apkSha = SHA_A;
        e.regularSha = SHA_B;
        e.boldSha = bold ? SHA_C : null;
        e.emojiId = "noto";
        e.emojiName = "Noto Color Emoji";
        e.sources = new JSONObject().put("regular", new JSONObject().put("fileName", "Inter.ttf").put("coords", new JSONObject().put("wght", 400)));
        e.warnings.add("A warning.");
        return e;
    }

    static void meta() throws Exception {
        FontLibrary.Entry e = entry("0123456789ab", "My Font", true);
        JSONObject m = FontLibrary.toMeta(e);
        eq("meta version", m.getInt("v"), 1);
        eq("meta package", m.getString("package"), e.pkg);
        eq("meta sizes", m.getJSONObject("sizes").getLong("bold"), 200L);
        eq("meta hashes", m.getJSONObject("sha256").getString("bold"), SHA_C);
        FontLibrary.Entry back = FontLibrary.fromMeta(new JSONObject(m.toString()), "0123456789ab");
        is("round trip", back != null, true);
        eq("round trip name", back.name, "My Font");
        eq("round trip suffix", back.suffix, e.suffix);
        eq("round trip emoji", back.emojiId + "/" + back.emojiName, "noto/Noto Color Emoji");
        eq("round trip bold", back.boldSize + "/" + back.boldSha, "200/" + SHA_C);
        eq("round trip sources", back.sources.getJSONObject("regular").getString("fileName"), "Inter.ttf");
        eq("round trip warnings", back.warnings, Arrays.asList("A warning."));

        FontLibrary.Entry noBold = entry("0123456789ac", "Plain", false);
        noBold.emojiId = null;
        JSONObject nm = FontLibrary.toMeta(noBold);
        is("no bold hash written", nm.getJSONObject("sha256").has("bold"), false);
        is("emoji null", nm.isNull("emoji"), true);
        FontLibrary.Entry nb = FontLibrary.fromMeta(nm, "0123456789ac");
        is("no bold read", nb != null && !nb.hasBold && nb.boldSha == null && nb.emojiId == null, true);

        eq("another folder's meta", FontLibrary.fromMeta(m, "0123456789ac"), null);
        eq("a folder that is not an id", FontLibrary.fromMeta(m, "../x"), null);
        String[][] breaks = {
                { "package", "com.monotype.android.font.other" }, { "suffix", "../x" }, { "name", "" }, { "versionCode", "0" } };
        for (String[] b : breaks) {
            JSONObject c = new JSONObject(m.toString());
            if (b[0].equals("versionCode")) c.put(b[0], 0); else c.put(b[0], b[1]);
            eq("refused: " + b[0] + "=" + b[1], FontLibrary.fromMeta(c, "0123456789ab"), null);
        }
        JSONObject c = new JSONObject(m.toString());
        c.getJSONObject("sha256").put("apk", "zz");
        eq("refused: a broken hash", FontLibrary.fromMeta(c, "0123456789ab"), null);
        c = new JSONObject(m.toString());
        c.getJSONObject("sizes").put("regular", 0);
        eq("refused: a zero size", FontLibrary.fromMeta(c, "0123456789ab"), null);
        c = new JSONObject(m.toString());
        c.remove("sizes");
        eq("refused: no sizes", FontLibrary.fromMeta(c, "0123456789ab"), null);
        c = new JSONObject(m.toString());
        c.put("emoji", new JSONObject().put("id", "../x").put("name", "x"));
        FontLibrary.Entry be = FontLibrary.fromMeta(c, "0123456789ab");
        is("an emoji id that is not one reads as none", be != null && be.emojiId == null, true);
        c.put("emoji", new JSONObject().put("id", "own").put("name", "MyEmoji.ttf"));
        eq("own emoji", FontLibrary.fromMeta(c, "0123456789ab").emojiId, "own");
    }

    // ---------------------------------------------------------------------------------------------

    static File stageWith(String id, Random r, int apk, int reg, int bold) throws Exception {
        File s = FontLibrary.stageDir(dirs, id, r);
        write(new File(s, FontLibrary.APK), apk);
        write(new File(s, FontLibrary.REGULAR), reg);
        if (bold > 0) write(new File(s, FontLibrary.BOLD), bold);
        return s;
    }

    static void commitAndList() throws Exception {
        Random r = new Random(3);
        String id1 = "aaaaaaaaaaa1", id2 = "aaaaaaaaaaa2", id3 = "aaaaaaaaaaa3";
        FontLibrary.Entry e1 = entry(id1, "First", true);
        File s1 = stageWith(id1, r, 300, 100, 200);
        is("staged inside the library", s1.getParentFile().equals(dirs.library) && s1.getName().startsWith(".new-" + id1), true);
        FontLibrary.commit(dirs, s1, e1);
        is("committed", new File(dirs.entryDir(id1), FontLibrary.META).isFile(), true);
        is("the staged folder is gone", s1.exists(), false);
        FontLibrary.Entry read1 = FontLibrary.read(dirs, id1);
        is("read back", read1 != null && read1.name.equals("First"), true);

        FontLibrary.Entry e2 = entry(id2, "Second", false);
        e2.created = 2000; e2.updated = 2000;
        FontLibrary.commit(dirs, stageWith(id2, r, 300, 100, 0), e2);
        FontLibrary.Entry e3 = entry(id3, "Third", false);
        e3.created = 1500; e3.updated = 1500;
        FontLibrary.commit(dirs, stageWith(id3, r, 300, 100, 0), e3);
        List<String> order = new ArrayList<String>();
        for (FontLibrary.Entry e : FontLibrary.list(dirs)) order.add(e.id);
        eq("newest first", order, Arrays.asList(id2, id3, id1));
        eq("by suffix", FontLibrary.bySuffix(FontLibrary.list(dirs), e3.suffix).id, id3);
        eq("unknown suffix", FontLibrary.bySuffix(FontLibrary.list(dirs), "Nope0000"), null);

        // made again: the old folder is replaced only once the new one is complete
        FontLibrary.Entry e1b = entry(id1, "First", false);
        e1b.versionCode = 2; e1b.updated = 3000; e1b.apkSize = 400;
        FontLibrary.commit(dirs, stageWith(id1, r, 400, 100, 0), e1b);
        FontLibrary.Entry again = FontLibrary.read(dirs, id1);
        is("made again", again != null && again.versionCode == 2 && !again.hasBold, true);
        is("the old bold is gone with the old folder", new File(dirs.entryDir(id1), FontLibrary.BOLD).exists(), false);
        eq("made again is the newest", FontLibrary.list(dirs).get(0).id, id1);
        int asides = 0;
        for (File k : dirs.library.listFiles()) if (k.getName().startsWith(".")) asides++;
        eq("nothing left beside the entries", asides, 0);

        // a file that is not the size meta.json says hides the entry
        write(new File(dirs.entryDir(id3), FontLibrary.APK), 299);
        eq("cut-short file", FontLibrary.read(dirs, id3), null);
        eq("left out of the list", FontLibrary.list(dirs).size(), 2);
        write(new File(dirs.entryDir(id3), FontLibrary.APK), 300);

        // a crash mid-commit: a staged folder and a folder moved aside
        File staged = FontLibrary.stageDir(dirs, id2, r);
        write(new File(staged, FontLibrary.APK), 5);
        File aside = new File(dirs.library, ".old-" + id2 + "-123");
        is("move aside", dirs.entryDir(id2).renameTo(aside), true);
        File asideStale = new File(dirs.library, ".old-" + id3 + "-456");
        write(new File(asideStale, FontLibrary.APK), 1);
        new File(dirs.library, "not-an-id").mkdirs();
        FontLibrary.sweep(dirs);
        is("staged folder swept", staged.exists(), false);
        is("an entry moved aside is put back", FontLibrary.read(dirs, id2) != null, true);
        is("an aside of an entry that is there is deleted", asideStale.exists(), false);
        is("the entry itself stays", FontLibrary.read(dirs, id3) != null, true);
        eq("a folder that is not an id is not listed", FontLibrary.list(dirs).size(), 3);

        // a power cut right after a build (PKG-4): an entry whose files did not reach the disk is never listed and has no id the page could
        // delete it by, so the sweep deletes it; one made again whose new files did not get there is the old one again
        String id5 = "aaaaaaaaaaa5", id6 = "aaaaaaaaaaa6";
        FontLibrary.commit(dirs, stageWith(id5, r, 300, 100, 0), entry(id5, "Fifth", false));
        write(new File(dirs.entryDir(id5), FontLibrary.APK), 0);
        FontLibrary.commit(dirs, stageWith(id6, r, 300, 100, 0), entry(id6, "Sixth", false));
        File old6 = new File(dirs.library, ".old-" + id6 + "-789");
        is("the old folder moved aside", dirs.entryDir(id6).renameTo(old6), true);
        FontLibrary.Entry e6b = entry(id6, "Sixth", false);
        e6b.versionCode = 2;
        FontLibrary.commit(dirs, stageWith(id6, r, 300, 100, 0), e6b);
        write(new File(dirs.entryDir(id6), FontLibrary.REGULAR), 0);
        write(new File(dirs.entryDir("aaaaaaaaaaa7"), FontLibrary.APK), 10);    // no meta.json at all
        FontLibrary.sweep(dirs);
        is("an entry whose files are short is deleted", dirs.entryDir(id5).exists(), false);
        is("a folder named as an entry with no meta.json is deleted", dirs.entryDir("aaaaaaaaaaa7").exists(), false);
        FontLibrary.Entry back6 = FontLibrary.read(dirs, id6);
        is("a broken entry made again is the old one again", back6 != null && back6.versionCode == 1 && !old6.exists(), true);
        is("whole entries stay", FontLibrary.read(dirs, id1) != null && FontLibrary.read(dirs, id2) != null && FontLibrary.read(dirs, id3) != null, true);
        is("a folder that is not an id is left alone", new File(dirs.library, "not-an-id").isDirectory(), true);
        FontLibrary.delete(dirs, id6);

        // commit flushes every file it puts in place and the library folder's rename, not only meta.json (PKG-4)
        String commitBody = body(String.valueOf(src("src/com/bloatware/bingblop/FontLibrary.java")), "public static void commit(");
        is("commit syncs the staged files", commitBody.contains("syncFile(f)"), true);
        is("…before meta.json is written", commitBody.indexOf("syncFile(f)") < commitBody.indexOf("writeAtomic("), true);
        is("…and the rename", commitBody.contains("syncDir(d.library)"), true);
        File synced = new File(tmp, "synced.bin");
        write(synced, 10);
        FontLibrary.syncFile(synced);
        FontLibrary.syncDir(tmp);
        FontLibrary.syncDir(new File(tmp, "no-such-folder"));
        eq("a synced file is unchanged", synced.length(), 10L);

        // a commit that cannot rename leaves nothing staged
        File s4 = stageWith("aaaaaaaaaaa4", r, 1, 1, 0);
        FontLibrary.Entry bad = entry("aaaaaaaaaaa4", "Bad", false);
        bad.id = "not an id";
        boolean threw = false;
        try { FontLibrary.commit(dirs, s4, bad); } catch (IllegalArgumentException ex) { threw = true; }
        is("a commit for a non-id is refused", threw, true);
        FontLibrary.deleteTree(s4);

        is("delete", FontLibrary.delete(dirs, id3), true);
        is("deleted", dirs.entryDir(id3).exists(), false);
        is("delete again", FontLibrary.delete(dirs, id3), false);
        is("delete a path", FontLibrary.delete(dirs, "../library"), false);
        is("library still there", dirs.library.isDirectory(), true);

        eq("export name", FontLibrary.exportName("My Font"), "My Font.apk");
        eq("export name without slashes", FontLibrary.exportName("a/b\\c:d*e?f\"g<h>i|j"), "a b c d e f g h i j.apk");
        eq("export name hidden file", FontLibrary.exportName("..hidden"), "hidden.apk");
        eq("export name empty", FontLibrary.exportName("  "), "Font.apk");
        eq("export name null", FontLibrary.exportName(null), "Font.apk");
        eq("export name control", FontLibrary.exportName("a\nb\u0000c"), "a b c.apk");
        eq("export name long", FontLibrary.exportName(repeat("x", 200)).length(), 84);
    }

    // ---------------------------------------------------------------------------------------------

    static void records() throws Exception {
        eq("nothing applied yet", FontLibrary.readApplied(dirs), null);
        FontLibrary.writeApplied(dirs, "MyFont_1a2b3c", 5);
        eq("applied", FontLibrary.readApplied(dirs), "MyFont_1a2b3c");
        FontLibrary.writeApplied(dirs, null, 6);
        eq("default", FontLibrary.readApplied(dirs), "");
        FontLibrary.writeApplied(dirs, "../x", 7);
        eq("a bad suffix is written as default", FontLibrary.readApplied(dirs), "");
        Files.write(dirs.changerRecord().toPath(), "{\"suffix\":\"../../x\"}".getBytes("UTF-8"));
        eq("a damaged record is unknown", FontLibrary.readApplied(dirs), null);

        eq("no swap", FontLibrary.activeSwap(dirs, "boot-1"), null);
        FontLibrary.writeSwap(dirs, new FontLibrary.Swap("noto", "boot-1", SHA_A, Arrays.asList("/system/fonts/NotoColorEmoji.ttf", "/data/fonts/files/x/y.ttf")));
        FontLibrary.Swap s = FontLibrary.activeSwap(dirs, "boot-1\n");
        is("active this boot", s != null && s.id.equals("noto") && s.targets.size() == 2, true);
        eq("another boot: undone by the restart", FontLibrary.activeSwap(dirs, "boot-2"), null);
        eq("unknown boot is never the same boot", FontLibrary.activeSwap(dirs, ""), null);
        eq("null boot", FontLibrary.activeSwap(dirs, null), null);
        is("the record still knows the targets", FontLibrary.readSwap(dirs) != null, true);
        Files.write(dirs.swapRecord().toPath(), ("{\"id\":\"noto\",\"boot\":\"b\",\"sha256\":\"" + SHA_A + "\",\"targets\":[\"/ok.ttf\",\"rel.ttf\",\"/a/../b\",\"/x\\ny\"]}").getBytes("UTF-8"));
        eq("only absolute, plain targets", FontLibrary.readSwap(dirs).targets, Arrays.asList("/ok.ttf"));
        Files.write(dirs.swapRecord().toPath(), ("{\"id\":\"../x\",\"boot\":\"b\",\"sha256\":\"" + SHA_A + "\",\"targets\":[]}").getBytes("UTF-8"));
        eq("a damaged swap record", FontLibrary.readSwap(dirs), null);
        FontLibrary.clearSwap(dirs);
        eq("cleared", FontLibrary.readSwap(dirs), null);

        // each file's hash before the bind is kept for Undo: only for a file the swap names, and only as a hash
        java.util.Map<String, String> orig = new java.util.LinkedHashMap<String, String>();
        orig.put("/system/fonts/NotoColorEmoji.ttf", SHA_B);
        FontLibrary.writeSwap(dirs, new FontLibrary.Swap("noto", "boot-1", SHA_A, Arrays.asList("/system/fonts/NotoColorEmoji.ttf", "/product/fonts/NotoColorEmoji.ttf"), orig));
        FontLibrary.Swap withOrig = FontLibrary.activeSwap(dirs, "boot-1");
        eq("the hash before the bind", withOrig == null ? null : withOrig.originals.get("/system/fonts/NotoColorEmoji.ttf"), SHA_B);
        eq("a file without one", withOrig == null ? "" : withOrig.originals.get("/product/fonts/NotoColorEmoji.ttf"), null);
        FontLibrary.writeSwap(dirs, new FontLibrary.Swap("noto", "boot-1", SHA_A, Arrays.asList("/system/fonts/NotoColorEmoji.ttf")));
        eq("the record written before the bind has no hashes yet", FontLibrary.readSwap(dirs).originals.size(), 0);
        Files.write(dirs.swapRecord().toPath(), ("{\"id\":\"noto\",\"boot\":\"b\",\"sha256\":\"" + SHA_A + "\",\"targets\":[\"/ok.ttf\"],\"originals\":{\"/ok.ttf\":\"not-a-hash\",\"/other.ttf\":\""
                + SHA_B + "\"}}").getBytes("UTF-8"));
        eq("a damaged hash, and one for a file the swap does not name, are left out", FontLibrary.readSwap(dirs).originals.size(), 0);
        Files.write(dirs.swapRecord().toPath(), ("{\"id\":\"noto\",\"boot\":\"b\",\"sha256\":\"" + SHA_A + "\",\"targets\":[\"/ok.ttf\"],\"originals\":5}").getBytes("UTF-8"));
        is("originals that are no object: the record still reads", FontLibrary.readSwap(dirs) != null && FontLibrary.readSwap(dirs).originals.isEmpty(), true);
        FontLibrary.clearSwap(dirs);

        // what an Undo works on: this boot's record, then every other file the mount table shows a bind of the swap's own on, each once
        FontLibrary.Swap two = new FontLibrary.Swap("noto", "b", SHA_A, Arrays.asList("/a.ttf", "/b.ttf"));
        eq("undo targets: the record's, then the table's", FontLibrary.undoTargets(two, Arrays.asList("/b.ttf", "/c.ttf")), Arrays.asList("/a.ttf", "/b.ttf", "/c.ttf"));
        eq("undo targets: no record (lost), the table's", FontLibrary.undoTargets(null, Arrays.asList("/c.ttf", "/c.ttf", null)), Arrays.asList("/c.ttf"));
        eq("undo targets: nothing", FontLibrary.undoTargets(null, null), new ArrayList<String>());

        FontLibrary.Swap active = new FontLibrary.Swap("noto", "b", SHA_A, new ArrayList<String>());
        FontLibrary.Jobs j = new FontLibrary.Jobs();
        eq("refuse: the system emoji", FontLibrary.emojiDeleteRefusal("noto", active, j), "in_use");
        eq("another font may go", FontLibrary.emojiDeleteRefusal("twemoji", active, j), null);
        FontLibrary.Jobs.Job b = j.start("build", "only", "cat:twemoji", "tok:" + TOK1);
        eq("refuse: a build reads it", FontLibrary.emojiDeleteRefusal("twemoji", null, j), "building");
        j.finish(b);
        eq("after the build", FontLibrary.emojiDeleteRefusal("twemoji", null, j), null);
    }

    // ---------------------------------------------------------------------------------------------

    static void jobs() {
        FontLibrary.Jobs j = new FontLibrary.Jobs();
        FontLibrary.Jobs.Job a = j.start("build", "only", "id:aaaaaaaaaaa1", null);
        is("started", a != null, true);
        eq("busy: a second build", j.start("build", "only"), null);
        FontLibrary.Jobs.Job d = j.start("emoji", "noto");
        FontLibrary.Jobs.Job d2 = j.start("emoji", "twemoji");
        is("downloads of two fonts at once", d != null && d2 != null, true);
        eq("busy: the same download", j.start("emoji", "noto"), null);
        is("ids differ", a.id.equals(d.id) || d.id.equals(d2.id), false);
        is("job by id", j.get(a.id) == a, true);
        is("running", j.running("emoji", "noto") == d, true);
        is("in use", j.inUse("id:aaaaaaaaaaa1"), true);
        is("null use ignored", j.inUse(null), false);
        is("cancel", j.cancel(d.id), true);
        is("cancelled", d.cancelled(), true);
        is("the others go on", a.cancelled() || d2.cancelled(), false);
        is("cancel an unknown job", j.cancel("build999"), false);
        is("cancel null", j.cancel(null), false);
        j.finish(d);
        is("finished", j.get(d.id) == null && j.running("emoji", "noto") == null, true);
        is("may start again", j.start("emoji", "noto") != null, true);
        j.cancelAll();
        is("all cancelled", a.cancelled() && d2.cancelled(), true);
        eq("size", j.size(), 3);

        // one set of jobs for the process (PKG-5, DL-5): a window that closes stops only the jobs it started
        FontLibrary.Jobs w = new FontLibrary.Jobs();
        Object win1 = new Object(), win2 = new Object();
        FontLibrary.Jobs.Job b1 = w.startFor(win1, null, "build", "only", "tok:" + TOK1);
        eq("busy: a build from another window", w.startFor(win2, null, "build", "only"), null);
        FontLibrary.Jobs.Job dl2 = w.startFor(win2, null, "emoji", "noto");
        eq("busy: the same download from another window", w.startFor(win1, null, "emoji", "noto"), null);
        FontLibrary.Jobs.Job any = w.start("emoji", "twemoji");
        List<FontLibrary.Jobs.Job> stopped = w.cancelAll(win2);
        is("closing a window cancels its jobs", dl2.cancelled() && stopped.size() == 1 && stopped.get(0) == dl2, true);
        is("…and not the other window's, or one with no window", b1.cancelled() || any.cancelled(), false);
        eq("no window: nothing", w.cancelAll(null).size(), 0);

        // a font made again and its install, apply or save exclude each other, in one step ((b), (c))
        String id = "aaaaaaaaaaa1";
        FontLibrary.Jobs x = new FontLibrary.Jobs();
        FontLibrary.Jobs.Job inst = x.startFor(win1, "make:" + id, "install", id, "id:" + id);
        is("an install starts", inst != null, true);
        eq("refused: made again while it installs", x.startFor(win1, "id:" + id, "build", "only", "id:" + id, "make:" + id), null);
        is("…another font may be made", x.startFor(win1, "id:bbbbbbbbbbb1", "build", "only", "id:bbbbbbbbbbb1", "make:bbbbbbbbbbb1") != null, true);
        x.finish(x.running("build", "only"));
        x.finish(inst);
        FontLibrary.Jobs.Job make = x.startFor(win1, "id:" + id, "build", "only", "tok:" + TOK1, "id:" + id, "make:" + id);
        is("made again once nothing else uses it", make != null, true);
        eq("refused: an install while it is made again", x.startFor(win1, "make:" + id, "install", id, "id:" + id), null);
        eq("refused: an apply", x.startFor(win1, "make:" + id, "apply", "only", "id:" + id), null);
        eq("refused: a save", x.startFor(win1, "make:" + id, "export", "1", "id:" + id), null);
        is("an uninstall is not refused by busyIf null", x.startFor(win1, null, "uninstall", id, "id:" + id) != null, true);
        is("a new font being made does not hold make:", x.inUse("make:"), false);

        FontLibrary.Throttle t = new FontLibrary.Throttle(125);
        is("first", t.due("read", 0, 10, 1000), true);
        is("too soon", t.due("read", 1, 10, 1050), false);
        is("a new stage", t.due("instance", 1, 10, 1060), true);
        is("the end of a stage", t.due("instance", 10, 10, 1070), true);
        is("after the gap", t.due("instance", 5, 10, 1300), true);
        is("unknown total, too soon", t.due("instance", 5, 0, 1310), false);
    }

    // ---------------------------------------------------------------------------------------------

    /** Who a font being made is: a new one, or one made again (rebuildId) that keeps its id, suffix and package and goes up a version. */
    static void rebuilds() throws Exception {
        long now = 5000;
        FontLibrary.Entry fresh = FontLibrary.entryFor("My Font", null, "bbbbbbbbbbb1", now);
        eq("a new font: the fresh id", fresh.id, "bbbbbbbbbbb1");
        eq("…a suffix from its name and id", fresh.suffix, FontPackage.suffixFor("My Font", "bbbbbbbbbbb1"));
        eq("…its package", fresh.pkg, FontPackage.packageName(fresh.suffix));
        is("…made now", fresh.created == now && fresh.updated == now, true);
        eq("…version 1 with nothing installed", FontLibrary.versionCodeFor(null, 0), 1);
        eq("…above a package of that name on the phone", FontLibrary.versionCodeFor(null, 4), 5);
        boolean threw = false;
        try { FontLibrary.entryFor("My Font", null, "../x", now); } catch (IllegalArgumentException e) { threw = true; }
        is("a new font needs an id", threw, true);

        FontLibrary.Entry old = entry("bbbbbbbbbbb2", "Old Name", false);
        old.versionCode = 3;
        FontLibrary.Entry again = FontLibrary.entryFor("New Name", old, null, now);
        eq("made again: the same id", again.id, old.id);
        eq("…the same suffix, though the name changed", again.suffix, old.suffix);
        eq("…the same package", again.pkg, old.pkg);
        eq("…the new name", again.name, "New Name");
        is("…made then, updated now", again.created == old.created && again.updated == now, true);
        eq("…the next versionCode", FontLibrary.versionCodeFor(old, 0), 4);
        eq("…above the one installed", FontLibrary.versionCodeFor(old, 9), 10);
        eq("…an installed one below the entry's", FontLibrary.versionCodeFor(old, 2), 4);
        String code = "none";
        try { FontLibrary.versionCodeFor(old, Long.MAX_VALUE); } catch (Sfnt.FontException e) { code = e.code; }
        eq("…no version past the top", code, "bad_version");

        // in the library: the same folder, replaced whole
        Random r = new Random(11);
        FontLibrary.Entry first = entry("bbbbbbbbbbb2", "Old Name", true);
        FontLibrary.commit(dirs, stageWith(first.id, r, 300, 100, 200), first);
        FontLibrary.Entry second = FontLibrary.entryFor("New Name", FontLibrary.read(dirs, first.id), null, 9000);
        second.versionCode = FontLibrary.versionCodeFor(FontLibrary.read(dirs, first.id), 0);
        second.hasBold = false;
        second.apkSize = 310; second.regularSize = 100;
        second.apkSha = SHA_B; second.regularSha = SHA_C;
        second.sources = new JSONObject();
        FontLibrary.commit(dirs, stageWith(second.id, r, 310, 100, 0), second);
        FontLibrary.Entry back = FontLibrary.read(dirs, first.id);
        is("made again in place", back != null && back.versionCode == 2 && back.suffix.equals(first.suffix) && back.pkg.equals(first.pkg) && back.name.equals("New Name"), true);
        is("…the old files are gone", new File(dirs.entryDir(first.id), FontLibrary.BOLD).exists(), false);
        int extra = 0;
        for (File k : dirs.library.listFiles()) if (k.getName().startsWith(".")) extra++;
        eq("…nothing left beside it", extra, 0);
        FontLibrary.delete(dirs, first.id);
    }

    /** What a download that failed says (DL-2), and the Android a merged emoji font needs (PAGE-4). */
    static void downloads() throws Exception {
        JSONObject gone = FontLibrary.downloadError(404, "the server answered HTTP 404");
        is("404: not ok", gone.optBoolean("ok", true), false);
        eq("404: code http", gone.optString("code"), "http");
        eq("…with the status", gone.optInt("status"), 404);
        eq("…the file moved away", gone.optString("error"), "The file is no longer at its address (HTTP 404). Update the app.");
        eq("410 too", FontLibrary.downloadError(410, "").optString("error"), "The file is no longer at its address (HTTP 410). Update the app.");
        JSONObject later = FontLibrary.downloadError(429, "the server answered HTTP 429");
        is("429: try again later", later.optString("code").equals("http") && later.optInt("status") == 429
                && later.optString("error").equals("The server answered HTTP 429. Try again later."), true);
        JSONObject full = FontLibrary.downloadError(0, "write failed: ENOSPC (No space left on device)");
        is("a full disk is no_space", full.optString("code").equals("no_space") && full.optString("error").equals("There is not enough free space on this phone."), true);
        JSONObject net = FontLibrary.downloadError(0, "Read timed out");
        is("a timeout is the network", net.optString("code").equals("network") && net.optString("error").equals("The download failed: Read timed out") && !net.has("status"), true);
        eq("no detail", FontLibrary.downloadError(0, null).optString("error"), "The download failed: no answer");

        eq("COLRv1 inside a font needs Android 13", FontLibrary.minApiForEmoji("COLRv1"), 33);
        eq("COLRv0 from Android 8", FontLibrary.minApiForEmoji("COLRv0"), 26);
        eq("no colour: no limit of its own", FontLibrary.minApiForEmoji(null), 26);
    }

    // ---------------------------------------------------------------------------------------------

    static File fixture(String rel) {
        String p = System.getProperty("fixtures");
        File d = p != null ? new File(p) : new File("tests/java/fixtures");
        return new File(new File(d, "fonts"), rel);
    }

    static void pageJson() throws Exception {
        JSONObject err = FontLibrary.error("too_big", "Big.");
        eq("error", err.toString(), new JSONObject().put("ok", false).put("code", "too_big").put("error", "Big.").toString());
        eq("error without a code", FontLibrary.error(null, null).getString("code"), "bad_font");

        File vf = fixture("fontbuilder/Inter-var.ttf");
        File st = fixture("fontbuilder/NotoSans-Regular.ttf");
        if (!vf.isFile() || !st.isFile()) {
            is("the fixture fonts are found (tests/java/fixtures/fonts/fontbuilder)", false, true);
        } else {
            JSONObject v = FontLibrary.infoJson(FontInspect.inspect(Files.readAllBytes(vf.toPath())));
            for (String k : new String[] { "family", "subfamily", "fullName", "version", "weight", "width", "italic", "outlines", "glyphs", "upem",
                    "codepoints", "latin", "color", "variable", "axes", "instances", "guessRegular", "guessBold", "warnings" })
                is("info has " + k, v.has(k), true);
            is("variable", v.getBoolean("variable"), true);
            JSONArray axes = v.getJSONArray("axes");
            is("axes", axes.length() > 0, true);
            JSONObject wght = null;
            for (int i = 0; i < axes.length(); i++) if (axes.getJSONObject(i).getString("tag").equals("wght")) wght = axes.getJSONObject(i);
            is("a wght axis", wght != null, true);
            if (wght != null) {
                is("axis fields", wght.has("name") && wght.has("min") && wght.has("def") && wght.has("max") && wght.has("hidden"), true);
                is("whole numbers stay whole", wght.get("max") instanceof Long || wght.get("max") instanceof Integer, true);
            }
            is("a Regular guess", v.getJSONObject("guessRegular").has("wght"), true);
            is("a Bold guess", v.getJSONObject("guessBold").has("wght"), true);
            JSONObject s = FontLibrary.infoJson(FontInspect.inspect(Files.readAllBytes(st.toPath())));
            is("static", s.getBoolean("variable"), false);
            eq("no guesses for a static font", s.getJSONObject("guessRegular").length() + s.getJSONObject("guessBold").length(), 0);
            eq("no axes", s.getJSONArray("axes").length(), 0);
            eq("outlines", s.getString("outlines"), "TrueType");
        }
        File colr = fixture("fontinspect/Twemoji-COLRv0.ttf");
        if (colr.isFile()) eq("colour format", FontLibrary.infoJson(FontInspect.inspect(Files.readAllBytes(colr.toPath()))).getString("color"), "COLRv0");

        FontLibrary.Entry e = entry("0123456789ab", "Row", true);
        JSONObject row = FontLibrary.entryJson(e, 0, false);
        Set<String> keys = new HashSet<String>();
        for (java.util.Iterator<String> it = row.keys(); it.hasNext(); ) keys.add(it.next());
        eq("row keys", keys, new HashSet<String>(Arrays.asList("id", "name", "pkg", "versionCode", "created", "updated", "hasBold", "emoji", "sizes", "installed", "installedVersion", "applied")));
        is("not installed", row.getBoolean("installed"), false);
        eq("emoji", row.getJSONObject("emoji").getString("name"), "Noto Color Emoji");
        JSONObject row2 = FontLibrary.entryJson(e, 3, true);
        is("installed", row2.getBoolean("installed") && row2.getLong("installedVersion") == 3 && row2.getBoolean("applied"), true);
        e.emojiId = null;
        is("no emoji is null", FontLibrary.entryJson(e, 0, false).isNull("emoji"), true);

        EmojiCatalog.Item om = EmojiCatalog.byId("openmoji");
        JSONObject at34 = FontLibrary.catalogItemJson(om, 34, true);
        JSONObject at30 = FontLibrary.catalogItemJson(om, 30, false);
        eq("Android 14 gets COLRv1", at34.getString("format"), "COLRv1");
        eq("Android 11 gets COLRv0", at30.getString("format"), "COLRv0");
        eq("…with its own size", at30.getLong("size"), om.fallback.size);
        eq("…and hash", at30.getString("sha256"), om.fallback.sha256);
        is("usable on both", at34.getBoolean("usable") && at30.getBoolean("usable"), true);
        is("downloaded", at34.getBoolean("downloaded") && !at30.getBoolean("downloaded"), true);
        JSONObject noto30 = FontLibrary.catalogItemJson(EmojiCatalog.byId("noto"), 30, false);
        is("Noto cannot be merged below Android 13", noto30.getBoolean("usable"), false);
        eq("…and says from which", noto30.getInt("minApi"), 33);
        JSONObject apple = FontLibrary.catalogItemJson(EmojiCatalog.byId("apple"), 34, false);
        is("iOS emoji: not mergeable, still usable (a system swap)", !apple.getBoolean("mergeable") && apple.getBoolean("usable"), true);
        is("…with its note", apple.getString("note").length() > 0, true);
        for (String k : new String[] { "id", "name", "version", "format", "mergeable", "minApi", "usable", "size", "sha256", "license", "licenseUrl", "sourceUrl", "attribution", "note", "downloaded" })
            is("catalog row has " + k, at34.has(k), true);

        File kept = new File(tmp, "kept.ttf");
        Files.write(kept.toPath(), new byte[(int) om.fallback.size]);
        is("kept: the fallback build", FontLibrary.keptBuild(om, kept) == om.fallback, true);
        Files.write(kept.toPath(), new byte[10]);
        eq("kept: neither", FontLibrary.keptBuild(om, kept), null);
        eq("kept: missing", FontLibrary.keptBuild(om, new File(tmp, "none.ttf")), null);
    }

    // ---------------------------------------------------------------------------------------------

    static void files() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        eq("copy under the cap", FontLibrary.copyCapped(new ByteArrayInputStream(new byte[1000]), out, 1000), 1000L);
        String code = "none";
        try {
            FontLibrary.copyCapped(new ByteArrayInputStream(new byte[1001]), new ByteArrayOutputStream(), 1000);
        } catch (Sfnt.FontException e) {
            code = e.code;
        }
        eq("one byte over the cap", code, "too_big");
        File f = new File(tmp, "w/x.json");
        FontLibrary.writeAtomic(f, "one".getBytes("UTF-8"));
        FontLibrary.writeAtomic(f, "two".getBytes("UTF-8"));
        eq("atomic write", new String(Files.readAllBytes(f.toPath()), "UTF-8"), "two");
        eq("no temp files left", f.getParentFile().list().length, 1);
        eq("read", new String(FontLibrary.readFile(f, 10), "UTF-8"), "two");
        code = "none";
        try { FontLibrary.readFile(f, 2); } catch (Sfnt.FontException e) { code = e.code; }
        eq("read over the cap", code, "too_big");
        eq("sha256 of a file", FontLibrary.sha256(f), FontLibrary.sha256("two".getBytes("UTF-8")));
        eq("sha256", FontLibrary.sha256(new byte[0]), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        File outside = new File(tmp, "outside");
        write(new File(outside, "keep.txt"), 3);
        File tree = new File(tmp, "tree");
        write(new File(tree, "a/b.txt"), 3);
        try {
            Files.createSymbolicLink(new File(tree, "link").toPath(), outside.toPath());
            FontLibrary.deleteTree(tree);
            is("a link is removed, not followed", new File(outside, "keep.txt").exists() && !tree.exists(), true);
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            FontLibrary.deleteTree(tree);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // MainActivity, read as text: the bridge of design section 6 and the rules around it
    // ---------------------------------------------------------------------------------------------

    static String src(String rel) throws Exception {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 6 && d != null; i++, d = d.getParentFile()) {
            File f = new File(d, rel);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), "UTF-8");
        }
        return null;
    }

    /** The body of a method: from its signature to the matching close brace. */
    static String body(String s, String signature) {
        int at = s.indexOf(signature);
        if (at < 0) return "";
        int open = s.indexOf('{', at), depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return s.substring(at, i + 1);
        }
        return s.substring(at);
    }

    static void bridgeScan() throws Exception {
        String ma = src("src/com/bloatware/bingblop/MainActivity.java");
        if (ma == null) { is("MainActivity.java is found", false, true); return; }
        String[][] methods = {
                { "fontMakerPick", "String slot" }, { "fontMakerPreview", "String token" }, { "fontMakerBuild", "String requestJson" },
                { "fontMakerCancel", "String jobId" }, { "fontMakerLibrary", "" }, { "fontMakerFontData", "String id, String face" },
                { "fontMakerDelete", "String id" }, { "fontMakerExport", "String id" }, { "fontMakerInstall", "String id" },
                { "fontMakerUninstall", "String id" }, { "fontMakerChangerStatus", "" }, { "fontMakerApply", "String id" },
                { "fontMakerReset", "" }, { "emojiCatalog", "" }, { "emojiDownload", "String id" }, { "emojiCancel", "String id" },
                { "emojiDelete", "String id" }, { "sysEmojiStatus", "" }, { "sysEmojiApply", "String id" }, { "sysEmojiUndo", "" },
                { "softRestart", "" } };
        for (String[] m : methods) {
            Pattern p = Pattern.compile("@JavascriptInterface\\s+public\\s+String\\s+" + m[0] + "\\(" + (m[1].isEmpty() ? "" : "(final )?" + Pattern.quote(m[1])) + "\\)");
            is("bridge method " + m[0] + "(" + m[1] + ")", p.matcher(ma).find(), true);
        }
        for (String cb : new String[] { "onFontMakerPicked", "onFontMakerProgress", "onFontMakerBuilt", "onFontMakerInstalled", "onFontMakerUninstalled",
                "onFontMakerExported", "onFontMakerChangerStatus", "onFontMakerApplied", "onFontMakerReset", "onEmojiProgress", "onEmojiDone",
                "onSysEmojiStatus", "onSysEmojiApplied", "onSysEmojiUndone" })
            is("callback " + cb, ma.contains("window." + cb + " && window." + cb + "("), true);

        // the page calls exactly these (FONTS:JS) and the mock fakes them
        String page = src("assets/index.html");
        if (page != null) {
            int b = page.indexOf("// FONTS:JS:BEGIN"), e = page.indexOf("// FONTS:JS:END");
            String js = b >= 0 && e > b ? page.substring(b, e) : "";
            Matcher mm = Pattern.compile("(?:fntCall|fntB64)\\('(\\w+)'").matcher(js);
            Set<String> called = new HashSet<String>();
            while (mm.find()) called.add(mm.group(1));
            Matcher hm = Pattern.compile("fntHas\\('(\\w+)'\\)").matcher(js);
            while (hm.find()) called.add(hm.group(1));
            is("the page calls the bridge", called.size() >= 15, true);
            for (String c : called) is("the page's " + c + " is a bridge method", ma.contains("public String " + c + "("), true);
            // the codes the engine and the bridge send get the page's own (translated) sentence; not_emoji says what no_colr says
            int f1 = js.indexOf("const FNT_ERR = {"), f2 = f1 < 0 ? -1 : js.indexOf("};", f1);
            String err = f1 >= 0 && f2 > f1 ? js.substring(f1, f2) : "";
            for (String c : new String[] { "too_complex", "no_regular", "bad_request", "no_memory", "not_emoji", "cancelled", "too_big", "bitmap_emoji",
                    "sha_mismatch", "size_mismatch", "network", "not_samsung", "applied", "in_use", "busy", "building", "not_found", "changed", "emoji_changed" })
                is("FNT_ERR words the code " + c, err.contains("\n            " + c + ": "), true);
            Matcher nc = Pattern.compile("no_colr: ('[^']*')").matcher(err), ne = Pattern.compile("not_emoji: ('[^']*')").matcher(err);
            is("not_emoji shows the no_colr sentence", nc.find() && ne.find() && nc.group(1).equals(ne.group(1)), true);
            String et = body(js, "function fntErrText(");
            is("the engine's face prefix stays in front of the page's sentence", et.contains("(Regular|Bold|Emoji) font: ") && et.contains("txF('Bold font: {0}'")
                    && et.contains("txF('Emoji font: {0}'") && et.contains("txF('Regular font: {0}'"), true);
        }

        // the gates: the changer needs Root or Shizuku as the system user / root; the system emoji the root user
        String gate = body(ma, "private String fmkGate(");
        is("the changer gate is RootGate.unlocked", gate.contains("RootGate.unlocked("), true);
        is("the system emoji gate is RootGate.uid0", gate.contains("RootGate.uid0("), true);
        int a1 = ma.indexOf("// FONTMAKER:BEGIN"), a2 = ma.indexOf("// FONTMAKER:END"), b1 = ma.indexOf("// FONTMAKER:BRIDGE:BEGIN"), b2 = ma.indexOf("// FONTMAKER:BRIDGE:END");
        is("the tab's code is marked", a1 > 0 && a2 > a1 && b1 > 0 && b2 > b1, true);
        if (a1 < 0 || a2 < a1 || b1 < 0 || b2 < b1) return;
        String tab = ma.substring(a1, a2) + ma.substring(b1, b2);
        String low = tab.toLowerCase(java.util.Locale.ROOT);
        is("no Magisk anywhere in the tab", low.contains("magisk"), false);
        is("UID 1000 is the system user, never root", low.contains("root (uid 1000)") || low.contains("uid 1000 root") || low.contains("root user (uid 1000)") || !low.contains("the system user (uid 1000)"), false);
        is("the bridge gates the changer and the system emoji before it starts a job",
                body(ma, "public String fontMakerApply(").contains("fmkGate(false) == null") && body(ma, "public String fontMakerReset(").contains("fmkGate(false) == null")
                && body(ma, "public String sysEmojiApply(").contains("fmkGate(true) == null") && body(ma, "public String sysEmojiUndo(").contains("fmkGate(true) == null")
                && body(ma, "public String softRestart(").contains("fmkGate(true)"), true);
        is("…and every job reads the gate again when it runs", body(ma, "private JSONObject fmkApply(").contains("fmkGate(false)") && body(ma, "private JSONObject fmkReset(").contains("fmkGate(false)")
                && body(ma, "private JSONObject fmkSysApply(").contains("fmkGate(true)") && body(ma, "private JSONObject fmkSysUndo(").contains("fmkGate(true)"), true);
        is("network only through HttpSafe.open", tab.contains("HttpSafe.open(") && !tab.contains("openConnection(") && !tab.contains("new URL(") && !tab.contains("new java.net.URL("), true);
        Matcher runs = Pattern.compile("fmkRun\\(backend, ([^\\n]*)").matcher(tab);
        int nRuns = 0;
        while (runs.find()) {
            nRuns++;
            String arg = runs.group(1);
            is("a script built by SamsungFonts or SettingsDb, or quoted: " + arg, arg.startsWith("SamsungFonts.") || arg.startsWith("SettingsDb.writeScript(") || arg.contains("BackupScripts.quote("), true);
        }
        is("the scripts are found", nRuns >= 10, true);
        String apply = body(ma, "private JSONObject fmkApply(");
        is("the changer's success is read back (sans.loc and every hash), not taken from an exit text",
                apply.contains("want.equals(ar.sansLoc)") && apply.contains("shas[i].equals(f.sha256)") && apply.contains("shas[i].equals(w.sha256)"), true);
        is("the changer stages every file and puts them in place in one install; an Apply that stops clears what it staged",
                apply.contains("SamsungFonts.installScript(r, user, e.suffix, names, shas, drop)") && apply.contains("finally") && apply.contains("if (!placed) fmkDiscard(backend, r, user, e.suffix)")
                        && body(ma, "private void fmkDiscard(").contains("SamsungFonts.discardScript(") && !tab.contains("removeFontScript"), true);

        // the system emoji: never bound over an update in /data/fonts/files, only the swap's own binds taken off, the record before the bind
        String sysFiles = body(ma, "private FmkEmoji fmkEmojiFiles(");
        is("the swap's targets are the /system and /product files; the updated ones are only looked for",
                sysFiles.contains("SamsungFonts.emojiFiles(r, fontsXml, fallbackXml)") && sysFiles.contains("SamsungFonts.updatedEmojiFiles(r, fontsXml, fallbackXml, updatable)"), true);
        is("…each by its real path, as the mount table names it, and only in the font folders",
                sysFiles.contains("String real = f.getCanonicalPath()") && sysFiles.contains("SamsungFonts.isSwapTarget(r, real) && !targets.contains(real)"), true);
        String sysApply = body(ma, "private JSONObject fmkSysApply(");
        int upd = sysApply.indexOf("return FontLibrary.error(\"emoji_updated\", FMK_EMOJI_UPDATED)");
        is("an emoji update is refused before anything changes", upd > 0 && upd < sysApply.indexOf("fmkTakeOff(") && upd < sysApply.indexOf("SamsungFonts.stageEmojiScript("), true);
        is("Apply goes by this boot's swap, never a record from another boot", sysApply.contains("FontLibrary.activeSwap(d, boot)") && !sysApply.contains("readSwap("), true);
        is("…takes the swap's own binds off first and stops when one stays or the mount table cannot be read",
                sysApply.contains("fmkTakeOff(backend, prev)") && sysApply.contains("if (off == null) return FontLibrary.error(\"mounts_unreadable\", FMK_NO_MOUNTS)")
                        && sysApply.contains("if (b.own != 0)"), true);
        int rec = sysApply.indexOf("FontLibrary.writeSwap(");
        is("the swap is recorded before the bind, and again with the hashes before", rec > 0 && rec < sysApply.indexOf("SamsungFonts.emojiSwapScript(")
                && sysApply.contains("new FontLibrary.Swap(item.id, bootTag, sha, mounted, originals)"), true);
        is("…and when nothing was bound, the record and the copy go", sysApply.contains("if (mounted.isEmpty())") && sysApply.indexOf("fmkRemoveStaged(backend, Collections.singletonList(name))") > rec, true);
        String takeOff = body(ma, "private List<SamsungFonts.BindResult> fmkTakeOff(");
        is("taking the swap off reads the mount table first", takeOff.contains("fmkMounts(backend)") && takeOff.contains("if (!m.readable) return null")
                && takeOff.contains("FontLibrary.undoTargets(active, m.targets)") && takeOff.contains("SamsungFonts.emojiUndoScript("), true);
        String sysUndo = body(ma, "private JSONObject fmkSysUndo(");
        is("Undo: this boot's swap, only its own binds, each file back to its hash before", sysUndo.contains("FontLibrary.activeSwap(d, fmkBootId())") && !sysUndo.contains("readSwap(")
                && sysUndo.contains("fmkTakeOff(backend, s)") && sysUndo.contains("b.back(s == null ? null : s.originals.get(b.target))"), true);
        is("…then the record goes, and the staged copy is deleted and read back", sysUndo.indexOf("fmkRemoveStaged(") > sysUndo.indexOf("FontLibrary.clearSwap(d)")
                && sysUndo.contains(".put(\"copyRemoved\", removed)"), true);
        String sysStatus = body(ma, "private JSONObject fmkSysStatus(");
        is("status finds a swap without a record, says why a swap is blocked, and deletes the copies nothing is bound from",
                sysStatus.contains("fmkMounts(backend)") && sysStatus.contains("m.showing()") && sysStatus.contains(".put(\"blocked\"")
                        && sysStatus.contains("if (s == null && m.readable && m.targets.isEmpty()) fmkRemoveStaged("), true);
        is("deleting an emoji font deletes its staged copy, with the root user on", body(ma, "public String emojiDelete(").contains("fmkDropStaged(id)")
                && body(ma, "private void fmkDropStaged(").contains("fmkGate(true)") && body(ma, "private void fmkDropStaged(").contains("fmkRemoveStaged(backend"), true);
        is("the staged copies are removed by the script that checks the mount table", body(ma, "private boolean fmkRemoveStaged(").contains("SamsungFonts.removeStagedScript("), true);
        String dl = body(ma, "private void fmkDownloadJob(");
        is("a download is checked by size and SHA-256 (EmojiCatalog.download / extractEntry) into the emoji folder",
                dl.contains("EmojiCatalog.download(in, dest, b.size, b.sha256") && dl.contains("EmojiCatalog.extractEntry(zip, b.zipEntry, dest, b.size, b.sha256") && dl.contains("b.zipSize, b.zipSha256"), true);
        String install = body(ma, "private JSONObject fmkInstall(");
        is("a privileged install asks first (confirmInMode) and names Samsung's store as the installer", install.indexOf("confirmInMode(") > 0
                && install.indexOf("confirmInMode(") < install.indexOf("installApksOptions(") && ma.contains("FMK_INSTALL_FLAGS = \"-r -i com.sec.android.app.samsungapps\""), true);
        is("…without one, Android's installer", install.indexOf("launchSystemInstaller(apk)") > 0 && install.indexOf("launchSystemInstaller(apk)") < install.indexOf("confirmInMode("), true);
        is("the build catches OutOfMemoryError as no_memory", body(ma, "private void fmkBuildJob(").contains("catch (OutOfMemoryError oom)") && body(ma, "private void fmkBuildJob(").contains("\"no_memory\""), true);
        is("the base64 answers are capped", body(ma, "private String fmkBase64(").contains("FontLibrary.MAX_PREVIEW_BYTES"), true);
        is("a delete waits for an install, an uninstall or an apply of the font, and for a swap of the emoji font",
                body(ma, "private String fmkLibraryJob(").contains("kind, id, \"id:\" + id)") && body(ma, "private String fmkPrivJob(").contains("\"sys:\" : \"id:\"")
                        && body(ma, "private String fmkDelete(").contains("fmkJobs.inUse(\"id:\" + id)") && body(ma, "public String emojiDelete(").contains("fmkJobs.inUse(\"sys:\" + id)"), true);
        is("the request codes reach the tab", ma.contains("requestCode == REQ_FONTMAKER_PICK") && ma.contains("requestCode == REQ_FONTMAKER_EXPORT"), true);
        String pick = body(ma, "private JSONObject fmkCopyPick(");
        is("an emoji pick with PostScript outlines is refused at pick with code emoji_outlines (the merge takes only TrueType ones)",
                pick.contains("if (!\"TrueType\".equals(info.outlines))") && pick.contains("new Sfnt.FontException(\"emoji_outlines\""), true);
        int emojiSlot = pick.indexOf("if (\"emoji\".equals(slot)) {");
        is("…and the emoji slot drops the no-Latin warning, which the Regular and Bold keep (an emoji font there changes almost no text)",
                emojiSlot > 0 && pick.indexOf("info.warnings.remove(FontInspect.W_NO_LATIN)", emojiSlot) > emojiSlot
                        && pick.indexOf("info.warnings.remove(FontInspect.W_NO_LATIN)", emojiSlot) < pick.indexOf("picks.put(", emojiSlot), true);

        // onDestroy stops what the tab started
        String destroy = body(ma, "protected void onDestroy()");
        is("onDestroy stops the font executor", destroy.contains("fontExec.shutdownNow()"), true);
        is("onDestroy stops the privileged font executor", destroy.contains("fontPrivExec.shutdown()"), true);
        is("onDestroy cancels this window's font jobs and drops their downloads", destroy.contains("fmkDisconnect(fmkJobs.cancelAll(this))"), true);
        is("onDestroy deletes the picked copies", destroy.contains("fmkPicks.clear()"), true);
        is("…not another window's jobs", destroy.contains("fmkJobs.cancelAll()"), false);

        // one set of jobs, downloads and folders for the process; a window's picks in a folder of its own (PKG-5, DL-5, PKG-7, DL-3)
        is("the jobs belong to the process", ma.contains("private static final FontLibrary.Jobs fmkJobs = new FontLibrary.Jobs();"), true);
        is("…and the open downloads", ma.contains("private static final java.util.concurrent.ConcurrentHashMap<String, java.net.HttpURLConnection> fmkConns"), true);
        String dirsBody = body(ma, "private FontLibrary.Dirs fmkDirs()");
        is("the folders are tidied once per process", dirsBody.contains("synchronized (fmkSharedLock)") && dirsBody.contains("if (fmkSharedDirs == null)"), true);
        is("…the library swept", dirsBody.contains("FontLibrary.sweep(d)"), true);
        is("…a download's leftovers swept", dirsBody.contains("EmojiCatalog.sweep(d.emoji)"), true);
        is("…the picked copies of a process that ended removed", dirsBody.contains("FontLibrary.deleteTree(d.in)"), true);
        is("a window's picks are in its own folder", body(ma, "private FontLibrary.Picks fmkPicks()").contains("new FontLibrary.Picks(d.pickFolder("), true);

        // a choice copied after a later one of the slot does not replace it (DL-4)
        is("the choices are numbered as they come in", body(ma, "private void fmkPickResult(").contains("fmkPicks().ask(slot)"), true);
        is("…and a copy of an older one is not kept", body(ma, "private JSONObject fmkCopyPick(").contains("info.variable), ask))"), true);

        // one file chooser and one save screen at a time; a save that does not finish leaves no file (PKG-6, DL-6)
        String chooser = body(ma, "public String fontMakerPick(");
        is("a second chooser is refused", chooser.contains("if (fmkPickSlot != null) return fmkErr(\"busy\""), true);
        String export = body(ma, "public String fontMakerExport(");
        is("a second save screen is refused", export.contains("if (fmkExportId != null) return fmkErr(\"busy\""), true);
        is("…and a save of a font being made again", export.contains("fmkJobs.inUse(\"make:\" + id)"), true);
        is("the open save screen's font is kept when Android ends the process", body(ma, "protected void onSaveInstanceState(").contains("outState.putString(STATE_FMK_EXPORT, fmkExportId)")
                && body(ma, "protected void onCreate(").contains("savedInstanceState.getString(STATE_FMK_EXPORT)"), true);
        String exResult = body(ma, "private void fmkExportResult(");
        is("a save with no font waiting deletes the empty file", exResult.indexOf("if (uri != null) fmkDropDocument(uri)") > 0
                && exResult.indexOf("if (uri != null) fmkDropDocument(uri)") < exResult.indexOf("fontExec.submit("), true);
        is("a save holds the font while it copies", exResult.contains("fmkJobs.startFor(this, \"make:\" + id, \"export\"") && exResult.contains("\"id:\" + id)"), true);
        String exTo = body(ma, "private JSONObject fmkExportTo(");
        is("a save that is not whole is deleted", exTo.contains("whole = true;") && exTo.contains("if (!whole) fmkDeleteDocument(uri)"), true);

        // Cancel reaches a download that is still connecting or waiting; what failed is said (DL-1, DL-2)
        is("each hop of a download can be disconnected", dl.contains("new HttpSafe.Hops()") && dl.contains("fmkConns.put(job.id, hop)"), true);
        is("…and a Cancel that came before it is honoured", dl.indexOf("fmkConns.put(job.id, hop)") < dl.indexOf("if (p.cancelled()) throw new EmojiCatalog.VerifyException(\"cancelled\""), true);
        is("an HTTP answer and a full disk are told apart from the network", dl.contains("FontLibrary.downloadError(status, errMsg(t))") && dl.contains("status = code;"), true);

        // an own COLRv1 emoji file is refused below Android 13 (PAGE-4)
        String start = body(ma, "private String fmkStartBuild(");
        is("own COLRv1 emoji below 13 is not_usable", start.contains("FontLibrary.minApiForEmoji(emo.color) > Build.VERSION.SDK_INT") && start.contains("return fmkErr(\"not_usable\""), true);

        // a font made again: same id, suffix and package, a higher versionCode, and never while it is installed, applied or saved ((b), (c))
        is("a font made again is refused while it is installed, applied or saved", start.contains("fmkJobs.startFor(this, rebuild == null ? null : \"id:\" + rebuild.id, \"build\", \"only\"")
                && start.contains("rebuild == null ? null : \"make:\" + rebuild.id"), true);
        is("…and they are refused while it is made again", body(ma, "private String fmkLibraryJob(").contains("\"install\".equals(kind) ? \"make:\" + id : null")
                && body(ma, "private String fmkPrivJob(").contains("\"apply\".equals(kind) && id != null ? \"make:\" + id : null"), true);
        String build = body(ma, "private void fmkBuildJob(");
        is("the build takes who the font is from FontLibrary", build.contains("FontLibrary.entryFor(req.name, rebuild, rebuild != null ? null : fmkNewId(d), now)")
                && build.contains("FontLibrary.versionCodeFor(rebuild, installed)"), true);

        // the install question says the file was checked against the recorded SHA-256, and the file pm gets is the one checked ((a))
        is("the install question is the font package's", install.contains("InstallConfirm.messageFontPackage(") && !install.contains("InstallConfirm.messageLocalFile("), true);
        is("…after the copy pm installs is checked", install.indexOf("FontLibrary.sha256(copy)") > 0 && install.indexOf("FontLibrary.sha256(copy)") < install.indexOf("confirmInMode("), true);

        // the update check leaves the app's own font packages out (PKG-3)
        String updRun = body(ma, "private void runUpdateCheck(");
        is("the update check skips the font packages made here", updRun.contains("Set<String> ownFonts = fmkMadeHere();") && updRun.indexOf("if (ownFonts.contains(ai.packageName)) continue;") > 0
                && updRun.indexOf("if (ownFonts.contains(ai.packageName)) continue;") < updRun.indexOf("isGalaxyStoreCandidate(ai, inst)"), true);
        String made = body(ma, "private Set<String> fmkMadeHere()");
        is("…told by My fonts and the app's key", made.contains("FontLibrary.list(fmkDirs())") && made.contains("SigningKey.existing()") && made.contains("signerDigests(pi, true)")
                && made.contains("FontPackage.madeHere(pkg, system, suffixes, signerDigests(pi, true), key)"), true);
    }
}
