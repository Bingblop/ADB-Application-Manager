package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The exclusion manager of the SD Maid SE tab: models and matching (app, path, segment), tool tags and their collapse, the three default exclusions with
 * removal and restore, a user exclusion shadowing a default, the nested rule, the creation of exclusions with undo, the file, import / export in the upstream
 * container format (and SD Maid 1 files), the editor's JSON and the speed of the hash lookup with 20,000 exclusions.
 */
public class SdmExclusionsTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static final Sdm.Tool SC = Sdm.Tool.SYSTEMCLEANER, AC = Sdm.Tool.APPCLEANER, CF = Sdm.Tool.CORPSEFINDER, DD = Sdm.Tool.DEDUPLICATOR;

    static File tmp() throws Exception { return Files.createTempDirectory("sdmex").toFile(); }

    static List<String> l(String... s) { return new ArrayList<String>(Arrays.asList(s)); }

    public static void main(String[] args) throws Exception {
        // ---------- the defaults
        SdmExclusions ex = new SdmExclusions(tmp());
        eq("three defaults", ex.list().size(), 3);
        is("default pkg for AppCleaner", ex.excludesPackage(AC, "com.starfinanz.mobile.android.pushtan"), true);
        is("default pkg 2", ex.excludesPackage(AC, "de.zollsoft.impfapp"), true);
        is("default pkg is not for SystemCleaner", ex.excludesPackage(SC, "de.zollsoft.impfapp"), false);
        is("default path for SystemCleaner", ex.excludesPath(SC, "/data/rootfs"), true);
        is("default path below", ex.excludesPath(SC, "/data/rootfs/a/b"), true);
        is("default path is not for AppCleaner", ex.excludesPath(AC, "/data/rootfs/a"), false);
        is("a sibling is not it", ex.excludesPath(SC, "/data/rootfs2"), false);
        for (SdmExclusions.Exclusion e : ex.list()) is("default flag " + e.id(), e.isDefault, true);
        eq("default id", ex.get("PathExclusion-/data/rootfs").id(), "PathExclusion-/data/rootfs");
        is("default has a reason", ex.get("PkgExclusion-de.zollsoft.impfapp").reason.contains("sdmaid-se/issues/618"), true);

        // ---------- path exclusions
        ex.save(SdmExclusions.Exclusion.path("/storage/emulated/0/Keep/", Arrays.asList("systemcleaner")));
        eq("trailing slash is cut", ex.get("PathExclusion-/storage/emulated/0/Keep") != null, true);
        is("the path", ex.excludesPath(SC, "/storage/emulated/0/Keep"), true);
        is("below the path", ex.excludesPath(SC, "/storage/emulated/0/Keep/a/b.txt"), true);
        is("a name that only starts the same", ex.excludesPath(SC, "/storage/emulated/0/Keeper/a"), false);
        is("the parent is not excluded", ex.excludesPath(SC, "/storage/emulated/0"), false);
        is("tagged for one tool only", ex.excludesPath(AC, "/storage/emulated/0/Keep/a"), false);
        is("paths() holds it", ex.paths(SC).contains("/storage/emulated/0/Keep"), true);
        is("paths() does not hold it for another tool", ex.paths(AC).contains("/storage/emulated/0/Keep"), false);

        // ---------- tags: general, collapse, merge
        ex.save(SdmExclusions.Exclusion.path("/g/all", Arrays.asList("general")));
        for (Sdm.Tool t : Sdm.Tool.values()) is("general reaches " + t, ex.excludesPath(t, "/g/all/x"), true);
        ex.save(SdmExclusions.Exclusion.path("/g/four", Arrays.asList("systemcleaner", "appcleaner", "corpsefinder", "deduplicator")));
        eq("all four collapse to general", ex.get("PathExclusion-/g/four").tags.toString(), "[general]");
        ex.save(SdmExclusions.Exclusion.path("/g/none", new ArrayList<String>()));
        eq("none collapses to general", ex.get("PathExclusion-/g/none").tags.toString(), "[general]");
        ex.save(SdmExclusions.Exclusion.path("/g/m", Arrays.asList("systemcleaner")));
        ex.save(SdmExclusions.Exclusion.path("/g/m", Arrays.asList("appcleaner")));
        eq("saving the same path merges the tags", ex.get("PathExclusion-/g/m").tags.toString(), "[systemcleaner, appcleaner]");
        is("merged: SystemCleaner", ex.excludesPath(SC, "/g/m/a"), true);
        is("merged: AppCleaner", ex.excludesPath(AC, "/g/m/a"), true);
        is("merged: not CorpseFinder", ex.excludesPath(CF, "/g/m/a"), false);
        boolean threw = false;
        try { SdmExclusions.Exclusion.path("/x", Arrays.asList("swiper")); } catch (IllegalArgumentException e) { threw = true; }
        is("an unknown tag is refused", threw, true);
        threw = false;
        try { SdmExclusions.Exclusion.path("relative/path", Arrays.asList("general")); } catch (IllegalArgumentException e) { threw = true; }
        is("a relative path is refused", threw, true);

        // ---------- app exclusions
        ex.save(SdmExclusions.Exclusion.pkg("com.example.app", Arrays.asList("corpsefinder", "appcleaner")));
        is("pkg: CorpseFinder", ex.excludesPackage(CF, "com.example.app"), true);
        is("pkg: AppCleaner", ex.excludesPackage(AC, "com.example.app"), true);
        is("pkg: not SystemCleaner", ex.excludesPackage(SC, "com.example.app"), false);
        ex.save(SdmExclusions.Exclusion.pkg("com.example.all", Arrays.asList("general")));
        is("a general pkg exclusion never applies to the Deduplicator", ex.excludesPackage(DD, "com.example.all"), false);
        is("but to the others", ex.excludesPackage(SC, "com.example.all"), true);
        is("a prefix is not a match", ex.excludesPackage(AC, "com.example"), false);

        // ---------- segment exclusions
        ex.save(SdmExclusions.Exclusion.segment(new String[] { "DCIM", "Camera" }, false, true, Arrays.asList("general")));
        is("segment: example of the spec", ex.excludesPath(SC, "/sdcard/DCIM/Camera/photo.png"), true);
        is("segment: ignores case", ex.excludesPath(SC, "/storage/emulated/0/dcim/camera/a.jpg"), true);
        is("segment: the segments themselves", ex.excludesPath(SC, "/x/DCIM/Camera"), true);
        is("segment: not a partial name", ex.excludesPath(SC, "/x/DCIM/Cameras/a"), false);
        is("segment: needs both, in a row", ex.excludesPath(SC, "/x/DCIM/Other/Camera/a"), false);
        ex.save(SdmExclusions.Exclusion.segment(new String[] { "Thumb" }, true, true, Arrays.asList("deduplicator")));
        is("segment partial: no substring, no match", ex.excludesPath(DD, "/x/pics/a"), false);
        is("segment partial: substring of the path", ex.excludesPath(DD, "/x/My thumbs/a"), true);
        is("segment exclusions are not in paths()", ex.paths(DD).contains("Thumb"), false);
        eq("segment id", SdmExclusions.Exclusion.segment(new String[] { "DCIM", "Camera" }, false, true, Arrays.asList("general")).id(), "SegmentExclusion-DCIM/Camera");
        is("containsSegments: case sensitive", SdmExclusions.containsSegments(new String[] { "", "a", "B" }, new String[] { "b" }, false, false), false);
        is("containsSegments: case sensitive hit", SdmExclusions.containsSegments(new String[] { "", "a", "B" }, new String[] { "a", "B" }, false, false), true);
        is("containsSegments: longer pattern", SdmExclusions.containsSegments(new String[] { "a" }, new String[] { "a", "b" }, true, true), false);

        // ---------- nested rule
        SdmExclusions nx = new SdmExclusions(tmp());
        nx.save(SdmExclusions.Exclusion.path("/a/b/c", Arrays.asList("systemcleaner")));
        eq("nested: excluded and its ancestors go", nx.excludeNested(SC, l("/a", "/a/b", "/a/b/c", "/a/b/c/d", "/z", "/a/x")).toString(), "[/z, /a/x]");
        eq("nested: other tool is untouched", nx.excludeNested(AC, l("/a", "/a/b/c")).toString(), "[/a, /a/b/c]");
        eq("nested: order is kept", nx.excludeNested(SC, l("/q", "/p", "/a/b/c/e", "/o")).toString(), "[/q, /p, /o]");
        eq("nested: an ancestor of an exclusion that is not in the list is a conflict too", nx.excludeNested(SC, l("/a", "/a/b", "/a/q")).toString(), "[/a/q]");
        is("blocksDelete: the path", nx.blocksDelete(SC, "/a/b/c"), true);
        is("blocksDelete: below", nx.blocksDelete(SC, "/a/b/c/d"), true);
        is("blocksDelete: an ancestor", nx.blocksDelete(SC, "/a/b"), true);
        is("blocksDelete: the root", nx.blocksDelete(SC, "/"), true);
        is("blocksDelete: a sibling", nx.blocksDelete(SC, "/a/b/d"), false);
        is("blocksDelete: another tool", nx.blocksDelete(AC, "/a/b"), false);
        eq("nested: nothing excluded keeps everything", new SdmExclusions(tmp()).excludeNested(CF, l("/a", "/a/b")).toString(), "[/a, /a/b]");
        eq("nested: empty", nx.excludeNested(SC, new ArrayList<String>()).toString(), "[]");

        // ---------- create + undo
        SdmExclusions ux = new SdmExclusions(tmp());
        ux.save(SdmExclusions.Exclusion.path("/old", Arrays.asList("appcleaner")));
        SdmExclusions.Created c = ux.create(SC, l("/new/one", "/old"), l("com.p.q"));
        eq("create: three ids", c.ids.size(), 3);
        is("create: new path is excluded for the tool", ux.excludesPath(SC, "/new/one/x"), true);
        is("create: not for others", ux.excludesPath(AC, "/new/one/x"), false);
        is("create: the package", ux.excludesPackage(SC, "com.p.q"), true);
        eq("create: the old exclusion got the tag", ux.get("PathExclusion-/old").tags.toString(), "[appcleaner, systemcleaner]");
        ux.revert(c);
        is("undo: new path gone", ux.excludesPath(SC, "/new/one/x"), false);
        is("undo: package gone", ux.excludesPackage(SC, "com.p.q"), false);
        eq("undo: the old exclusion has its old tags again", ux.get("PathExclusion-/old").tags.toString(), "[appcleaner]");
        is("undo: the old exclusion still works", ux.excludesPath(AC, "/old/a"), true);
        threw = false;
        try { ux.create(DD, l(), l("com.p.q")); } catch (IllegalArgumentException e) { threw = true; }
        is("the Deduplicator has no app exclusions", threw, true);
        threw = false;
        try { ux.create(SC, l("/ok"), l("not a package")); } catch (IllegalArgumentException e) { threw = true; }
        is("a bad package name is refused", threw, true);
        is("and nothing was created before the failure", ux.excludesPath(SC, "/ok"), false);

        // ---------- remove, defaults, restore, shadow
        SdmExclusions rx = new SdmExclusions(tmp());
        rx.remove(Arrays.asList("PathExclusion-/data/rootfs"));
        is("a removed default is gone", rx.excludesPath(SC, "/data/rootfs/a"), false);
        eq("two defaults left", rx.list().size(), 2);
        eq("defaultsRemoved", rx.defaultsRemoved(), 1);
        rx.restoreDefaults();
        is("restored", rx.excludesPath(SC, "/data/rootfs/a"), true);
        eq("defaultsRemoved after restore", rx.defaultsRemoved(), 0);
        rx.save(SdmExclusions.Exclusion.pkg("de.zollsoft.impfapp", Arrays.asList("systemcleaner")));
        eq("a user exclusion with the same id shadows the default", rx.get("PkgExclusion-de.zollsoft.impfapp").isDefault, false);
        is("the shadow's tags count, not the default's", rx.excludesPackage(AC, "de.zollsoft.impfapp"), false);
        is("shadow", rx.excludesPackage(SC, "de.zollsoft.impfapp"), true);
        eq("shadowed default is not listed twice", rx.list().size(), 3);
        rx.remove(Arrays.asList("PkgExclusion-de.zollsoft.impfapp"));
        is("removing the shadow takes the user exclusion", rx.excludesPackage(SC, "de.zollsoft.impfapp"), false);
        rx.remove(Arrays.asList("PathExclusion-/nothing"));
        eq("removing an unknown id is harmless", rx.list().size() >= 2, true);

        // ---------- the file
        File dir = tmp();
        SdmExclusions p1 = new SdmExclusions(dir);
        p1.save(SdmExclusions.Exclusion.path("/p/q", Arrays.asList("deduplicator")));
        p1.save(SdmExclusions.Exclusion.segment(new String[] { "a", "b" }, true, false, Arrays.asList("general")));
        p1.save(SdmExclusions.Exclusion.pkg("com.keep.me", Arrays.asList("appcleaner")));
        p1.remove(Arrays.asList("PathExclusion-/data/rootfs"));
        SdmExclusions p2 = new SdmExclusions(dir);
        is("reloaded path", p2.excludesPath(DD, "/p/q/x"), true);
        is("reloaded segment", p2.excludesPath(SC, "/z/a/b/c"), true);
        SdmExclusions.Exclusion seg = p2.get("SegmentExclusion-a/b");
        is("reloaded segment flags", seg != null && seg.allowPartial && !seg.ignoreCase, true);
        is("reloaded pkg", p2.excludesPackage(AC, "com.keep.me"), true);
        is("the removed default stays removed", p2.excludesPath(SC, "/data/rootfs"), false);
        String stored = new String(Files.readAllBytes(new File(dir, "exclusions.json").toPath()), "UTF-8");
        is("the file holds the upstream shapes", stored.contains("\"pkgId\"") && stored.contains("\"file\"") && stored.contains("\"allowPartial\""), true);
        Files.write(new File(dir, "exclusions.json").toPath(), "{ damaged".getBytes("UTF-8"));
        SdmExclusions p3 = new SdmExclusions(dir);
        eq("a damaged file gives the defaults", p3.list().size(), 3);

        // ---------- export / import
        SdmExclusions xe = new SdmExclusions(tmp());
        xe.save(SdmExclusions.Exclusion.pkg("test.pkg", Arrays.asList("general")));
        xe.save(SdmExclusions.Exclusion.segment("/test/path".split("/", -1), true, true, Arrays.asList("appcleaner")));
        xe.save(SdmExclusions.Exclusion.path("/test/path", Arrays.asList("appcleaner")));
        String json = xe.export();
        JSONObject cont = new JSONObject(json);
        eq("container version", cont.getInt("version"), 1);
        is("exclusionRaw is the upstream text (as JSON)", new JSONArray(cont.getString("exclusionRaw")).similar(new JSONArray(
                "[{\"pkgId\":{\"name\":\"test.pkg\"},\"tags\":[\"GENERAL\"]},{\"segments\":[\"\",\"test\",\"path\"],\"allowPartial\":true,\"ignoreCase\":true,\"tags\":[\"APPCLEANER\"]},{\"path\":{\"file\":\"/test/path\"},\"tags\":[\"APPCLEANER\"]}]")), true);
        SdmExclusions xi = new SdmExclusions(tmp());
        eq("import count", xi.importJson(json), 3);
        is("imported pkg", xi.excludesPackage(SC, "test.pkg"), true);
        is("imported path", xi.excludesPath(AC, "/test/path/a"), true);
        eq("importing again merges (same count, no duplicates)", xi.importJson(json), 3);
        eq("no duplicates", xi.list().size(), 3 + 3);
        String upstream = "{\"exclusionRaw\": \"[{\\\"pkgId\\\":{\\\"name\\\":\\\"a.b\\\"},\\\"tags\\\":[\\\"SWIPER\\\"]},{\\\"path\\\":{\\\"file\\\":\\\"/u\\\"},\\\"tags\\\":[\\\"SWIPER\\\",\\\"CORPSEFINDER\\\"]}]\", \"version\": 1}";
        SdmExclusions xu = new SdmExclusions(tmp());
        eq("tags of dropped tools: a swiper-only entry is skipped, a mixed one keeps its known tags", xu.importJson(upstream), 1);
        eq("the mixed one", xu.get("PathExclusion-/u").tags.toString(), "[corpsefinder]");
        String legacy = "{\"version\":6,\"exclusions\":[{\"contains_string\":\"com.legacy.app\",\"tags\":[\"GLOBAL\"],\"timestamp\":1,\"type\":\"SIMPLE_CONTAINS\"},"
                + "{\"contains_string\":\"Some/Folder\",\"tags\":[\"APPCLEANER\"],\"timestamp\":1,\"type\":\"SIMPLE_CONTAINS\"},{\"regex_string\":\"x.*\",\"tags\":[\"GLOBAL\"],\"timestamp\":1,\"type\":\"REGEX\"}]}";
        SdmExclusions xl = new SdmExclusions(tmp());
        eq("SD Maid 1 import skips regexes", xl.importJson(legacy), 2);
        is("legacy package", xl.excludesPackage(CF, "com.legacy.app"), true);
        is("legacy segment is partial and ignores case", xl.excludesPath(AC, "/x/some/folders/y"), true);
        for (String bad : new String[] { "", "   ", "{}", "not json", "{\"exclusionRaw\":[],\"version\":1}", "{\"exclusionRaw\":\"[]\",\"version\":2}", "{\"version\":3,\"exclusions\":[]}" }) {
            threw = false;
            try { new SdmExclusions(tmp()).importJson(bad); } catch (IllegalArgumentException e) { threw = true; }
            is("bad import refused: " + bad, threw, true);
        }
        eq("empty import is fine", new SdmExclusions(tmp()).importJson("{\"exclusionRaw\":\"[]\",\"version\":1}"), 0);

        // ---------- the editor's JSON
        JSONObject page = new JSONObject("{\"kind\":\"segment\",\"segments\":\"DCIM/Camera\",\"allowPartial\":false,\"ignoreCase\":true,\"tags\":[\"systemcleaner\",\"appcleaner\"]}");
        SdmExclusions.Exclusion pe = SdmExclusions.fromPage(page);
        eq("editor segment id", pe.id(), "SegmentExclusion-DCIM/Camera");
        JSONObject back = SdmExclusions.toPage(pe, null);
        eq("toPage kind", back.getString("kind"), "segment");
        eq("toPage tags are lower case", back.getJSONArray("tags").toString(), "[\"systemcleaner\",\"appcleaner\"]");
        eq("toPage label", back.getString("label"), "DCIM/Camera");
        eq("toPage not default", back.getBoolean("isDefault"), false);
        eq("editor with an array", SdmExclusions.fromPage(new JSONObject("{\"kind\":\"segment\",\"segments\":[\"a\",\"b\"],\"tags\":[]}")).id(), "SegmentExclusion-a/b");
        for (String bad : new String[] { "{\"kind\":\"pkg\",\"pkg\":\"nope!\"}", "{\"kind\":\"path\",\"path\":\"rel\"}", "{\"kind\":\"segment\",\"segments\":\"\"}", "{\"kind\":\"what\"}", "{\"kind\":\"path\",\"path\":\"/a\",\"tags\":[\"x\"]}" }) {
            threw = false;
            try { SdmExclusions.fromPage(new JSONObject(bad)); } catch (IllegalArgumentException e) { threw = true; }
            is("bad editor input refused: " + bad, threw, true);
        }
        JSONObject dflt = SdmExclusions.toPage(new SdmExclusions(tmp()).get("PathExclusion-/data/rootfs"), null);
        is("a default is flagged for the page", dflt.getBoolean("isDefault") && dflt.has("reason"), true);

        // ---------- speed: 20,000 exclusions, 200,000 lookups
        SdmExclusions big = new SdmExclusions(null);
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 20000; i++) many.add("/storage/emulated/0/Android/data/pkg" + i + "/cache");
        big.create(AC, many, new ArrayList<String>());
        long t0 = System.currentTimeMillis();
        int hits = 0;
        for (int i = 0; i < 200000; i++) if (big.excludesPath(AC, "/storage/emulated/0/Android/data/pkg" + (i % 40000) + "/cache/deep/file" + i)) hits++;
        long ms = System.currentTimeMillis() - t0;
        eq("hash lookup: half of the candidates are below an exclusion", hits, 100000);
        is("hash lookup is fast (" + ms + " ms)", ms < 5000, true);

        System.out.println((fails == 0 ? "PASS" : "FAIL") + " SdmExclusionsTest: " + n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
