package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * CorpseFinder (SdmCorpseFinder + SdmMarkers) against a real temporary folder tree with fake data areas, apps, exclusions and a fake shell: remnants of an
 * uninstalled app in every area type, folders of installed apps left alone, the marker database (the real file of the repository and a small own one), risk
 * chips, exclusions (owner package, remnant path, the nested rule), cancel, the progress strings, the result lines, deleting whole remnants and chosen content
 * paths with distinct roots and a look at every path before it goes, and what the result shows afterwards.
 */
public class SdmCorpseFinderTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    // ---------------------------------------------------------------------------------------------------------- fakes

    static class FakeAreas implements Sdm.Areas {
        final List<Sdm.AreaInfo> list = new ArrayList<Sdm.AreaInfo>();
        @Override public List<Sdm.AreaInfo> all() { return list; }
        @Override public Sdm.AreaInfo get(Sdm.Area a) { for (Sdm.AreaInfo i : list) if (i.area == a) return i; return null; }
        @Override public Sdm.AreaInfo areaOf(String path) { Sdm.AreaInfo best = null; for (Sdm.AreaInfo i : list) if (Sdm.isInside(path, i.root) && (best == null || i.root.length() > best.root.length())) best = i; return best; }
    }

    static class FakePkgs implements Sdm.Packages {
        final Map<String, Sdm.Pkg> map = new HashMap<String, Sdm.Pkg>();
        Sdm.Pkg add(String name, int uid, boolean uninstalled) { Sdm.Pkg p = new Sdm.Pkg(); p.pkg = name; p.label = name; p.uid = uid; p.uninstalled = uninstalled; map.put(name, p); return p; }
        @Override public List<Sdm.Pkg> installed() { return new ArrayList<Sdm.Pkg>(map.values()); }
        @Override public Sdm.Pkg get(String pkg) { return map.get(pkg); }
        @Override public Set<String> running() { return new HashSet<String>(); }
        @Override public long cacheBytes(String pkg) { return -1; }
    }

    static class FakeExcl implements Sdm.Exclusions {
        final Set<String> paths = new HashSet<String>(), pkgs = new HashSet<String>();
        @Override public boolean excludesPath(Sdm.Tool t, String path) { for (String p : paths) if (p.equals(path) || SdmSieve.isAncestorOf(p, path)) return true; return false; }
        @Override public boolean excludesPackage(Sdm.Tool t, String pkg) { return pkgs.contains(pkg); }
        @Override public List<String> paths(Sdm.Tool t) { return new ArrayList<String>(paths); }
    }

    static class FakeShell implements Sdm.Shell {
        final int uid;
        FakeShell(int uid) { this.uid = uid; }
        @Override public int uid() { return uid; }
        @Override public String run(String script, int timeoutMs) { return ""; }
        @Override public void stream(String script, int timeoutMs, Sdm.LineSink sink, Sdm.Cancel cancel) {}
    }

    static class Rec implements Sdm.Progress {
        final List<String> lines = new ArrayList<String>();
        @Override public synchronized void update(String p, String s, long d, long t, long b) { lines.add(p + "|" + s); }
        synchronized boolean has(String line) { return lines.contains(line); }
    }

    /** SdmFsJava that knows the owner uid of some paths and records the deleteAll calls. */
    static class TestFs extends SdmFsJava {
        final Map<String, Integer> uids = new HashMap<String, Integer>();
        final List<List<String>> deleteCalls = new ArrayList<List<String>>();
        @Override public Sdm.Entry stat(String path) {
            Sdm.Entry e = super.stat(path);
            if (e == null) return null;
            Integer u = uids.get(path);
            return u == null ? e : new Sdm.Entry(e.path, e.size, e.mtime, e.type, u);
        }
        @Override public Set<String> deleteAll(Collection<String> paths, Sdm.Cancel cancel) { deleteCalls.add(new ArrayList<String>(paths)); return super.deleteAll(paths, cancel); }
    }

    // ---------------------------------------------------------------------------------------------------------- the tree

    static File root;
    static String sd, data, media, obb, priv;

    static void write(String rel, int len) throws Exception {
        File f = new File(root, rel);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), new byte[len]);
    }

    static final String DB = "[" +
            "{\"pkgs\":[\"android\"],\"mrks\":[{\"loc\":\"SDCARD\",\"path\":\"DCIM\",\"flags\":[\"custodian\",\"common\"]},{\"loc\":\"SDCARD\",\"path\":\"DCIM/Camera\",\"flags\":[\"custodian\"]}," +
            "{\"loc\":\"SDCARD\",\"path\":\"Download\",\"flags\":[\"custodian\",\"common\"]},{\"loc\":\"SDCARD\",\"path\":\"Android\",\"flags\":[\"custodian\",\"common\"]}," +
            "{\"loc\":\"SDCARD\",\"path\":\"Android/data\",\"flags\":[\"custodian\",\"common\"]}]}," +
            "{\"pkgs\":[\"com.dead.app\"],\"mrks\":[{\"loc\":\"SDCARD\",\"path\":\"DeadApp\"},{\"loc\":\"SDCARD\",\"path\":\"DeadApp/cache\"}," +
            "{\"loc\":\"SDCARD\",\"path\":\"Documents/DeadNotes\",\"flags\":[\"keeper\"]},{\"loc\":\"SDCARD\",\"path\":\"Notes\",\"flags\":[\"common\"]}," +
            "{\"loc\":\"SDCARD\",\"path\":\"Blocked\"}]}," +
            "{\"pkgs\":[\"com.keep.app\"],\"mrks\":[{\"loc\":\"SDCARD\",\"path\":\"KeepApp\"},{\"loc\":\"SDCARD\",\"path\":\"Blocked/KeepSub\"}," +
            "{\"loc\":\"PUBLIC_DATA\",\"path\":\"legacy.keep.dir\"}]}," +
            "{\"regexPkgs\":[\"^(?:com\\\\.reg\\\\.app.*?)$\"],\"mrks\":[{\"loc\":\"SDCARD\",\"path\":\"RegApp\"}]}," +
            "{\"pkgs\":[\"com.dead.yahoo\"],\"mrks\":[{\"loc\":\"SDCARD\",\"path\":\"yahoo/atom\"}]}," +
            "{\"pkgs\":[\"com.dead.rx\"],\"mrks\":[{\"loc\":\"SDCARD\",\"path\":\".tmp\",\"regex\":\"^(?:\\\\.tmp[a-f0-9]{8})$\"}]}," +
            "{\"pkgs\":[\"com.dead.obb\"],\"mrks\":[{\"loc\":\"PUBLIC_OBB\",\"path\":\"obb.marker\"}]}," +
            "{\"pkgs\":[\"com.dead.priv\"],\"mrks\":[{\"loc\":\"PRIVATE_DATA\",\"path\":\"legacy_dir\"}]}" +
            "]";

    static void buildTree() throws Exception {
        // SDCARD
        write("sd/DCIM/Camera/a.jpg", 100);
        write("sd/Download/file.txt", 100);
        write("sd/DeadApp/a.txt", 10);
        write("sd/DeadApp/cache/b.txt", 20);
        write("sd/KeepApp/k.txt", 10);
        write("sd/RegApp/r.txt", 30);
        write("sd/Notes/n.txt", 40);
        write("sd/Documents/DeadNotes/d.txt", 50);
        write("sd/Unknown/u.txt", 60);
        write("sd/yahoo/atom/z.bin", 70);
        write("sd/.tmpabcdef12/q.bin", 80);
        write("sd/Blocked/x.txt", 90);
        write("sd/Blocked/KeepSub/k.txt", 90);
        write("sd/bmwgroup/com.dead.bmw/x", 5);
        // PUBLIC_DATA
        write("sd/Android/data/com.dead.app/files/big.bin", 1000);
        write("sd/Android/data/com.dead.app/cache/x", 100);
        write("sd/Android/data/com.keep.app/files/k", 100);
        write("sd/Android/data/.com.keep.app/h", 10);
        write("sd/Android/data/com.keep.app:remote/r", 10);
        write("sd/Android/data/com.uninst.keepdata/u", 10);
        write("sd/Android/data/_com.hidden.dead/a", 25);
        write("sd/Android/data/legacy.keep.dir/l", 10);
        write("sd/Android/data/.nomedia", 0);
        write("sd/Android/data/hosts", 3);
        // PUBLIC_MEDIA
        write("sd/Android/media/com.dead.app/m", 200);
        write("sd/Android/media/com.keep.app/m", 200);
        // PUBLIC_OBB
        write("sd/Android/obb/com.dead.game/main.obb", 500);
        write("sd/Android/obb/com.keep.app/main.obb", 500);
        write("sd/Android/obb/obb.marker/x.obb", 15);
        // PRIVATE_DATA
        write("priv/com.dead.priv.dir/f", 11);
        write("priv/com.renamed.svc/f", 12);
        write("priv/com.shared.uid.dir/f", 13);
        write("priv/com.unknown.uid.dir/f", 14);
        write("priv/com.root.owned.dir/f", 15);
        write("priv/.com.keep.app/f", 16);
        write("priv/com.keep.app/f", 17);
        write("priv/hosts", 1);
    }

    static FakeAreas areas(boolean withPrivate) {
        FakeAreas a = new FakeAreas();
        a.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd, true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, data, true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_MEDIA, media, true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_OBB, obb, true, "java", ""));
        if (withPrivate) a.list.add(new Sdm.AreaInfo(Sdm.Area.PRIVATE_DATA, priv, true, "shell", ""));
        else a.list.add(new Sdm.AreaInfo(Sdm.Area.PRIVATE_DATA, priv, true, "", "needs root"));
        return a;
    }

    static FakePkgs pkgs() {
        FakePkgs p = new FakePkgs();
        p.add("android", 1000, false);
        p.add("com.keep.app", 10123, false);
        p.add("com.other.app", 10124, false);
        p.add("com.shared.one", 1000, false);
        p.add("com.shared.two", 1000, false);
        p.add("com.uninst.keepdata", 10200, true);       // removed for the user, data kept
        return p;
    }

    static class Env {
        TestFs fs = new TestFs();
        FakeAreas areas = areas(false);
        FakePkgs pkgs = pkgs();
        FakeExcl ex = new FakeExcl();
        FakeShell shell = new FakeShell(2000);
        Rec rec = new Rec();
        JSONObject settings = new JSONObject();
        final boolean[] cancel = { false };
        Sdm.Ctx ctx() {
            return new Sdm.Ctx(fs, areas, pkgs, ex, shell, settings, rec, new Sdm.Cancel() { @Override public boolean cancelled() { return cancel[0]; } }, 1700000000L);
        }
    }

    static SdmCorpseFinder tool() throws Exception { return new SdmCorpseFinder(SdmMarkers.parse(DB)); }

    static Set<String> paths(Sdm.Result r) throws Exception {
        Set<String> s = new HashSet<String>();
        JSONArray g = r.groups(0, 10000);
        for (int i = 0; i < g.length(); i++) s.add(g.getJSONObject(i).getString("path").substring(root.getPath().length() + 1));
        return s;
    }

    static boolean exists(String rel) { return new File(root, rel).exists(); }

    static Set<String> set(String... s) { return new HashSet<String>(Arrays.asList(s)); }

    static Set<String> defaultCorpses() {
        return set("sd/DeadApp", "sd/RegApp", "sd/yahoo/atom", "sd/.tmpabcdef12", "sd/Android/data/com.dead.app", "sd/Android/data/_com.hidden.dead", "sd/Android/media/com.dead.app");
    }

    // ---------------------------------------------------------------------------------------------------------- main

    public static void main(String[] a) throws Exception {
        root = Files.createTempDirectory("sdmcf").toFile().getCanonicalFile();
        sd = new File(root, "sd").getPath(); data = sd + "/Android/data"; media = sd + "/Android/media"; obb = sd + "/Android/obb"; priv = new File(root, "priv").getPath();
        try {
            markers();
            scanDefault();
            risk();
            exclusions();
            cancel();
            progressAndLines();
            privateData();
            deferred();
            safetyScan();
            deleting();
            resultApi();
        } finally {
            new SdmFsJava().delete(root.getPath());
        }
        System.out.println((fails == 0 ? "PASS " : "FAIL ") + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------------------------------------------------- the marker database

    static void markers() throws Exception {
        // the real database of the repository
        SdmMarkers.reset();
        SdmMarkers real = SdmMarkers.shared();
        eq("real db groups", real.groupCount(), 3335);
        eq("real db markers", real.markerCount(), 4395);
        SdmMarkers.Index idx = real.index(Arrays.asList("android", "com.keep.app"));
        List<SdmMarkers.Match> m = idx.match(Sdm.Area.SDCARD, new String[] { "DCIM", "Camera" });
        is("DCIM/Camera is claimed", !m.isEmpty(), true);
        is("…by the pseudo package android", m.get(0).pkgs.contains("android"), true);
        is("…as a custodian", m.get(0).has(SdmMarkers.CUSTODIAN), true);
        is("DCIM alone is common", idx.match(Sdm.Area.SDCARD, new String[] { "DCIM" }).get(0).has(SdmMarkers.COMMON), true);
        is("public areas ignore case", !idx.match(Sdm.Area.SDCARD, new String[] { "dcim", "camera" }).isEmpty(), true);
        is("an area of another type does not match", idx.match(Sdm.Area.PUBLIC_DATA, new String[] { "DCIM" }).isEmpty(), true);
        is("an empty path matches nothing", idx.match(Sdm.Area.SDCARD, new String[0]).isEmpty(), true);
        is("an unknown folder matches nothing", idx.match(Sdm.Area.SDCARD, new String[] { "NoSuchFolderAnywhere" }).isEmpty(), true);
        // regexPkgs: an installed app that matches is the owner, else the regex itself stands in
        List<SdmMarkers.Match> rt = idx.match(Sdm.Area.SDCARD, new String[] { "runtastic" });
        is("regex package without an installed app: the regex is the pseudo package", rt.get(0).pkgs.iterator().next().startsWith("^"), true);
        SdmMarkers.Index idx2 = real.index(Arrays.asList("android", "com.runtastic.android.pro"));
        eq("regex package with an installed app: that app", idx2.match(Sdm.Area.SDCARD, new String[] { "runtastic" }).get(0).pkgs.iterator().next(), "com.runtastic.android.pro");
        // dynamic markers
        List<SdmMarkers.Match> bmw = idx.match(Sdm.Area.SDCARD, new String[] { "bmwgroup", "com.bmw.app" });
        eq("dynamic marker: base/package", bmw.size() == 1 ? bmw.get(0).pkgs.iterator().next() : "none", "com.bmw.app");
        is("dynamic marker: a name without a dot is no package", idx.match(Sdm.Area.SDCARD, new String[] { "bmwgroup", "nodot" }).isEmpty(), true);
        is("dynamic marker: .nomedia is a bad match", idx.match(Sdm.Area.SDCARD, new String[] { ".backups", ".nomedia" }).isEmpty(), true);
        eq("dynamic marker msflogs", idx.match(Sdm.Area.SDCARD, new String[] { "tencent", "msflogs", "com", "tencent", "mm" }).get(0).pkgs.iterator().next(), "com.tencent.mm");
        // the markers of a package
        is("markers of a package", !idx.forPackage("android").isEmpty(), true);
        is("dynamic package marker", idx.forPackage("com.x.y").size() >= 10, true);
        // own database: case rules, a regex with a path, groups that are skipped
        SdmMarkers own = SdmMarkers.parse("[{\"pkgs\":[\"a.b\"],\"mrks\":[{\"loc\":\"PRIVATE_DATA\",\"path\":\"Name\"},{\"loc\":\"SDCARD\",\"path\":\"Name\"}]}," +
                "{\"pkgs\":[],\"mrks\":[{\"loc\":\"SDCARD\",\"path\":\"x\"}]},{\"pkgs\":[\"c.d\"],\"mrks\":[]}]");
        eq("a group without packages or markers is skipped", own.groupCount(), 1);
        SdmMarkers.Index oi = own.index(new ArrayList<String>());
        is("private data is case sensitive", oi.match(Sdm.Area.PRIVATE_DATA, new String[] { "name" }).isEmpty(), true);
        is("…and matches the exact case", oi.match(Sdm.Area.PRIVATE_DATA, new String[] { "Name" }).size() == 1, true);
        is("public storage is not", oi.match(Sdm.Area.SDCARD, new String[] { "NAME" }).size() == 1, true);
        is("the file stays loaded", SdmMarkers.shared() == real, true);
    }

    // ---------------------------------------------------------------------------------------------------------- scan

    static void scanDefault() throws Exception {
        buildTree();
        Env e = new Env();
        Sdm.Result r = tool().scan(e.ctx());
        Set<String> got = paths(r);
        eq("remnants of an uninstalled app in every area type", got, defaultCorpses());
        is("installed app folder in public data is not flagged", got.contains("sd/Android/data/com.keep.app"), false);
        is("hidden folder of an installed app (.pkg)", got.contains("sd/Android/data/.com.keep.app"), false);
        is("pkg:remote of an installed app", got.contains("sd/Android/data/com.keep.app:remote"), false);
        is("uninstalled for the user, data kept = installed", got.contains("sd/Android/data/com.uninst.keepdata"), false);
        is("a marker whose owner is installed", got.contains("sd/Android/data/legacy.keep.dir"), false);
        is("installed app folder in public media", got.contains("sd/Android/media/com.keep.app"), false);
        is(".nomedia and hosts are skipped", got.contains("sd/Android/data/.nomedia") || got.contains("sd/Android/data/hosts"), false);
        is("system folders are alive (custodian)", got.contains("sd/DCIM") || got.contains("sd/DCIM/Camera") || got.contains("sd/Download"), false);
        is("an installed app's SD card folder", got.contains("sd/KeepApp"), false);
        is("a whitelist folder nobody claims is not a remnant", got.contains("sd/Unknown"), false);
        is("a folder that holds something still owned is blocked", got.contains("sd/Blocked"), false);
        is("a nested remnant is found without its unowned parent", got.contains("sd/yahoo") || !got.contains("sd/yahoo/atom"), false);
        is("a nested marker below a dead folder is covered by the folder", got.contains("sd/DeadApp/cache"), false);
        is("dynamic markers do not make top-level remnants", got.contains("sd/bmwgroup"), false);
        is("OBB is off by default", got.contains("sd/Android/obb/com.dead.game"), false);
        is("private data is off without root", got.contains("priv/com.dead.priv.dir"), false);
        is("keeper is hidden by default", got.contains("sd/Documents/DeadNotes"), false);
        is("common is hidden by default", got.contains("sd/Notes"), false);

        // the rows
        JSONArray g = r.groups(0, 100);
        JSONObject dead = null;
        for (int i = 0; i < g.length(); i++) if (g.getJSONObject(i).getString("path").equals(sd + "/DeadApp")) dead = g.getJSONObject(i);
        is("row of DeadApp", dead != null, true);
        eq("row id is the path", dead.getString("id"), sd + "/DeadApp");
        eq("row label is the name", dead.getString("label"), "DeadApp");
        eq("row sub is the parent", dead.getString("sub"), sd);
        eq("row count is the content", dead.getInt("count"), 3);
        is("row bytes = own entry + content", dead.getLong("bytes") >= 30, true);
        eq("row filter", dead.getString("filter"), "sdcard");
        eq("row filter label", dead.getString("filterLabel"), "SD card");
        eq("row risk", dead.getString("risk"), "normal");
        eq("row owners", dead.getJSONArray("owners").getString(0), "com.dead.app");
        JSONArray it = r.items(sd + "/DeadApp", 0, 100);
        eq("items = the content", it.length(), 3);
        eq("items sorted by path", it.getJSONObject(0).getString("path"), sd + "/DeadApp/a.txt");
        eq("item type of a folder", it.getJSONObject(1).getInt("type"), Sdm.DIR);
        eq("item has name and size", it.getJSONObject(2).getString("name") + it.getJSONObject(2).getLong("size"), "b.txt20");
        eq("paging", r.items(sd + "/DeadApp", 1, 1).getJSONObject(0).getString("path"), sd + "/DeadApp/cache");
        eq("items of a file remnant", r.items(sd + "/Android/data/com.dead.app", 0, 100).length(), 4);
        // sorted by size descending
        long prev = Long.MAX_VALUE;
        boolean sorted = true;
        for (int i = 0; i < g.length(); i++) { long b = g.getJSONObject(i).getLong("bytes"); if (b > prev) sorted = false; prev = b; }
        is("rows are sorted by size, largest first", sorted, true);
        eq("first row is the biggest remnant", g.getJSONObject(0).getString("path"), data + "/com.dead.app");
        eq("group count", r.groupCount(), 7);
        eq("item count is the number of remnants", r.itemCount(), 7);
        JSONObject sum = r.summary();
        eq("summary primary", sum.getString("primary"), "7 app remnants discovered");
        eq("summary secondary", sum.getString("secondary"), SdmCorpseFinder.fmtSize(r.bytes()) + " can be freed");
        eq("summary count", sum.getInt("itemCount"), 7);
        // sizes
        long total = 0;
        for (int i = 0; i < g.length(); i++) total += g.getJSONObject(i).getLong("bytes");
        eq("result bytes = sum of the rows", r.bytes(), total);
        eq("one remnant has a plural of one", SdmCorpseFinder.foundLine(1), "1 app remnant discovered");
        eq("zero", SdmCorpseFinder.foundLine(0), "0 app remnants discovered");
        eq("size format", SdmCorpseFinder.fmtSize(1536), "1.5 KB");
        eq("size format bytes", SdmCorpseFinder.fmtSize(100), "100 B");

        // the real database finds the same kind of thing
        SdmMarkers.reset();
        Env e2 = new Env();
        write("sd2real/runtastic/x", 10);
        e2.areas.list.set(0, new Sdm.AreaInfo(Sdm.Area.SDCARD, new File(root, "sd2real").getPath(), true, "java", ""));
        Sdm.Result rr = new SdmCorpseFinder().scan(e2.ctx());
        eq("real marker database: a folder of a removed app", paths(rr).contains("sd2real/runtastic"), true);
        new SdmFsJava().delete(new File(root, "sd2real").getPath());
        e2.areas.list.set(0, new Sdm.AreaInfo(Sdm.Area.SDCARD, new File(root, "sd2real").getPath(), true, "java", ""));
        write("sd2real/runtastic/x", 10);
        write("sd2real/DCIM/Camera/a.jpg", 10);
        write("sd2real/Android/obb/whatever/f", 10);
        e2.pkgs.add("com.runtastic.android.pro", 10300, false);
        Sdm.Result rr2 = new SdmCorpseFinder().scan(e2.ctx());
        is("real marker database: the installed app's folder stays", paths(rr2).contains("sd2real/runtastic"), false);
        is("real marker database: DCIM/Camera never", paths(rr2).contains("sd2real/DCIM") || paths(rr2).contains("sd2real/DCIM/Camera"), false);
        new SdmFsJava().delete(new File(root, "sd2real").getPath());
    }

    static void risk() throws Exception {
        Env e = new Env();
        e.settings.put("risk.include.keeper", true);
        Set<String> got = paths(tool().scan(e.ctx()));
        Set<String> want = defaultCorpses();
        want.add("sd/Documents/DeadNotes");
        eq("keeper included", got, want);
        Sdm.Result r = tool().scan(e.ctx());
        JSONArray g = r.groups(0, 100);
        String risk = "";
        for (int i = 0; i < g.length(); i++) if (g.getJSONObject(i).getString("path").endsWith("DeadNotes")) risk = g.getJSONObject(i).getString("risk") + "/" + g.getJSONObject(i).getString("riskLabel");
        eq("keeper chip", risk, "keeper/Desirable");

        Env e2 = new Env();
        e2.settings.put("risk.include.common", true);
        Set<String> got2 = paths(tool().scan(e2.ctx()));
        Set<String> want2 = defaultCorpses();
        want2.add("sd/Notes");
        eq("common included", got2, want2);
        Sdm.Result r2 = tool().scan(e2.ctx());
        String risk2 = "";
        JSONArray g2 = r2.groups(0, 100);
        for (int i = 0; i < g2.length(); i++) if (g2.getJSONObject(i).getString("path").endsWith("/Notes")) risk2 = g2.getJSONObject(i).getString("risk") + "/" + g2.getJSONObject(i).getString("riskLabel");
        eq("common chip", risk2, "common/Common");

        Env e3 = new Env();
        e3.settings.put("risk.include.common", true).put("risk.include.keeper", true);
        eq("both", paths(tool().scan(e3.ctx())).size(), 9);

        // filters off
        Env e4 = new Env();
        e4.settings.put("filter.sdcard.enabled", false);
        eq("sdcard filter off", paths(tool().scan(e4.ctx())), set("sd/Android/data/com.dead.app", "sd/Android/data/_com.hidden.dead", "sd/Android/media/com.dead.app"));
        Env e5 = new Env();
        e5.settings.put("filter.publicdata.enabled", false).put("filter.publicmedia.enabled", false);
        eq("public data and media off", paths(tool().scan(e5.ctx())), set("sd/DeadApp", "sd/RegApp", "sd/yahoo/atom", "sd/.tmpabcdef12"));
        Env e6 = new Env();
        e6.settings.put("filter.publicobb.enabled", true).put("filter.sdcard.enabled", false).put("filter.publicdata.enabled", false).put("filter.publicmedia.enabled", false);
        eq("obb on: no name fallback, an unknown folder is still a remnant", paths(tool().scan(e6.ctx())), set("sd/Android/obb/com.dead.game", "sd/Android/obb/obb.marker"));
        // an area that cannot be read is skipped
        Env e7 = new Env();
        e7.areas.list.set(1, new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, data, true, "", "needs root or ADB"));
        Set<String> g7 = paths(tool().scan(e7.ctx()));
        is("unavailable area is not scanned", g7.contains("sd/Android/data/com.dead.app"), false);
        is("…the others are", g7.contains("sd/DeadApp"), true);
        // a second volume
        Env e8 = new Env();
        write("vol2/DeadApp/z", 5);
        write("vol2/Unknown/z", 5);
        e8.areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, new File(root, "vol2").getPath(), false, "java", ""));
        Set<String> g8 = paths(tool().scan(e8.ctx()));
        is("a remnant on the SD card volume", g8.contains("vol2/DeadApp"), true);
        is("…not the unknown folder", g8.contains("vol2/Unknown"), false);
        new SdmFsJava().delete(new File(root, "vol2").getPath());
    }

    static void exclusions() throws Exception {
        Env e = new Env();
        e.ex.paths.add(sd + "/DeadApp");
        Set<String> got = paths(tool().scan(e.ctx()));
        is("path exclusion hides the remnant", got.contains("sd/DeadApp"), false);
        eq("…and nothing else", got.size(), 6);

        Env e2 = new Env();
        e2.ex.paths.add(sd + "/DeadApp/cache/b.txt");
        is("nested rule: a folder that holds an excluded path is not a remnant", paths(tool().scan(e2.ctx())).contains("sd/DeadApp"), false);

        Env e3 = new Env();
        e3.ex.paths.add(sd);
        eq("an excluded ancestor hides everything below", paths(tool().scan(e3.ctx())).size(), 0);

        Env e4 = new Env();
        e4.ex.pkgs.add("com.dead.app");
        Set<String> g4 = paths(tool().scan(e4.ctx()));
        eq("owner exclusion hides the remnants of the package in every area", g4, set("sd/RegApp", "sd/yahoo/atom", "sd/.tmpabcdef12", "sd/Android/data/_com.hidden.dead"));

        Env e5 = new Env();
        e5.ex.paths.add(data + "/com.dead.app");
        is("exclusion of a public data remnant", paths(tool().scan(e5.ctx())).contains("sd/Android/data/com.dead.app"), false);
        Env e6 = new Env();
        e6.ex.paths.add(sd + "/yahoo/atom");
        is("exclusion of a nested remnant", paths(tool().scan(e6.ctx())).contains("sd/yahoo/atom"), false);
        Env e7 = new Env();
        e7.ex.pkgs.add("com.hidden.dead");
        is("owner exclusion by the cleaned name", paths(tool().scan(e7.ctx())).contains("sd/Android/data/_com.hidden.dead"), false);
    }

    static void cancel() throws Exception {
        Env e = new Env();
        e.cancel[0] = true;
        boolean thrown = false;
        try { tool().scan(e.ctx()); } catch (CancellationException x) { thrown = true; }
        is("cancelled before the start", thrown, true);

        // cancelled after some polls
        final Env e2 = new Env();
        final int[] polls = { 0 };
        Sdm.Ctx ctx = new Sdm.Ctx(e2.fs, e2.areas, e2.pkgs, e2.ex, e2.shell, e2.settings, e2.rec, new Sdm.Cancel() { @Override public boolean cancelled() { return ++polls[0] > 25; } }, 1700000000L);
        thrown = false;
        try { tool().scan(ctx); } catch (CancellationException x) { thrown = true; }
        is("cancelled in the middle", thrown, true);
        is("it polled more than once", polls[0] > 25, true);

        // cancelled at several points of a whole scan (count the polls of a full run first)
        final Env e3 = new Env();
        final int[] n3 = { 0 };
        tool().scan(new Sdm.Ctx(e3.fs, e3.areas, e3.pkgs, e3.ex, e3.shell, e3.settings, e3.rec, new Sdm.Cancel() { @Override public boolean cancelled() { n3[0]++; return false; } }, 1700000000L));
        final int all = n3[0];
        is("a scan polls the cancel flag often", all > 40, true);
        for (final int at : new int[] { 1, 3, all / 4, all / 2 }) {
            final int[] k = { 0 };
            Env e4 = new Env();
            thrown = false;
            try {
                tool().scan(new Sdm.Ctx(e4.fs, e4.areas, e4.pkgs, e4.ex, e4.shell, e4.settings, e4.rec, new Sdm.Cancel() { @Override public boolean cancelled() { return ++k[0] >= at; } }, 1700000000L));
            } catch (CancellationException x) { thrown = true; }
            is("cancel at poll " + at + " of " + all, thrown, true);
        }
    }

    static void progressAndLines() throws Exception {
        Env e = new Env();
        Sdm.Result r = tool().scan(e.ctx());
        is("SD card: Loading", e.rec.has("SD card|Loading"), true);
        is("SD card: Processing <path>", e.rec.has("SD card|Processing " + sd), true);
        is("SD card: Filtering", e.rec.has("SD card|Filtering"), true);
        is("Public media: Processing <area label>", e.rec.has("Public media|Processing Public app media"), true);
        is("Public app data: Processing <area label>", e.rec.has("Public app data|Processing Public app data"), true);
        is("the OBB filter reports nothing when it is off", e.rec.has("OBB resources|Processing Public app resources"), false);
        eq("area label", SdmCorpseFinder.areaLabel(Sdm.Area.PRIVATE_DATA), "Private app data");
        eq("area label", SdmCorpseFinder.areaLabel(Sdm.Area.SDCARD), "Public storage");

        // deleting
        TestFs fs = e.fs;
        Rec rec = new Rec();
        Sdm.Ctx ctx = new Sdm.Ctx(fs, e.areas, e.pkgs, e.ex, e.shell, e.settings, rec, null, 1700000000L);
        Sdm.DeleteReport rep = tool().delete(r, new Sdm.Selection(), ctx);
        is("Deleting <name> with its path", rec.has("Deleting com.dead.app|" + data + "/com.dead.app") || rec.lines.toString().contains("Deleting "), true);
        is("Loading at the end", rec.lines.get(rec.lines.size() - 1).startsWith("Loading|"), true);
        eq("delete line", SdmCorpseFinder.deleteLines(rep).getString("primary"), "7 app remnants deleted");
        eq("delete line secondary", SdmCorpseFinder.deleteLines(rep).getString("secondary"), "Freed " + SdmCorpseFinder.fmtSize(rep.bytes()) + " space.");
        eq("delete line singular", SdmCorpseFinder.deleteLines(1, 1024).getString("primary"), "1 app remnant deleted");
        eq("scan lines", SdmCorpseFinder.scanLines(2, 2048).getString("secondary"), "2.0 KB can be freed");
        buildTree();        // put the tree back for the next checks
    }

    static void privateData() throws Exception {
        TestFs fs = new TestFs();
        fs.uids.put(priv + "/com.dead.priv.dir", 10999);       // nobody has this uid
        fs.uids.put(priv + "/com.renamed.svc", 10123);         // the uid of com.keep.app
        fs.uids.put(priv + "/com.shared.uid.dir", 1000);       // a shared uid: two packages
        fs.uids.put(priv + "/com.root.owned.dir", 0);          // root: no package
        // com.unknown.uid.dir: no uid known
        Env e = new Env();
        e.fs = fs;
        e.areas = areas(true);
        e.shell = new FakeShell(0);
        e.settings.put("filter.sdcard.enabled", false).put("filter.publicdata.enabled", false).put("filter.publicmedia.enabled", false);
        Sdm.Result r = tool().scan(e.ctx());
        Set<String> got = paths(r);
        eq("private data: remnants by name and by uid", got, set("priv/com.dead.priv.dir", "priv/com.root.owned.dir"));
        is("owner by uid (a renamed service)", got.contains("priv/com.renamed.svc"), false);
        is("a shared uid means owned by something", got.contains("priv/com.shared.uid.dir"), false);
        is("an unknown uid fails safe", got.contains("priv/com.unknown.uid.dir"), false);
        is("hidden name of an installed app", got.contains("priv/.com.keep.app"), false);
        is("installed app", got.contains("priv/com.keep.app"), false);
        is("hosts is skipped", got.contains("priv/hosts"), false);
        JSONArray g = r.groups(0, 10);
        eq("private data filter label", g.getJSONObject(0).getString("filterLabel"), "Private app data");
        is("Private app data progress", e.rec.has("Private app data|Processing Private app data"), true);

        // no root: skipped even when the area looks available
        Env e2 = new Env();
        e2.areas = areas(true);
        e2.shell = new FakeShell(2000);
        e2.settings.put("filter.sdcard.enabled", false).put("filter.publicdata.enabled", false).put("filter.publicmedia.enabled", false);
        eq("private data needs root", paths(tool().scan(e2.ctx())).size(), 0);
        Env e3 = new Env();
        e3.areas = areas(true);
        e3.shell = null;
        e3.settings.put("filter.sdcard.enabled", false).put("filter.publicdata.enabled", false).put("filter.publicmedia.enabled", false);
        eq("no shell at all", paths(tool().scan(e3.ctx())).size(), 0);

        // the marker for a private dir with an uninstalled owner and a known uid of an installed app
        write("priv/legacy_dir/f", 4);
        TestFs fs2 = new TestFs();
        fs2.uids.put(priv + "/legacy_dir", 10124);      // com.other.app
        Env e4 = new Env();
        e4.fs = fs2;
        e4.areas = areas(true);
        e4.shell = new FakeShell(0);
        e4.settings.put("filter.sdcard.enabled", false).put("filter.publicdata.enabled", false).put("filter.publicmedia.enabled", false);
        is("a marked folder whose uid belongs to an installed app is owned", paths(tool().scan(e4.ctx())).contains("priv/legacy_dir"), false);
        new SdmFsJava().delete(priv + "/legacy_dir");
    }

    static void deferred() throws Exception {
        Env e = new Env();
        e.settings.put("filter.dalvikcache.enabled", true).put("filter.appsource.enabled", true);
        Sdm.Result r = tool().scan(e.ctx());
        eq("the not yet ported filters change nothing", paths(r), defaultCorpses());
        JSONArray s = r.summary().getJSONArray("skippedFilters");
        eq("…but the result says which were left out", s.getString(0) + "," + s.getString(1), "Dalvik cache,App sources");
        is("no extra field when none", tool().scan(new Env().ctx()).summary().has("skippedFilters"), false);
    }

    static void safetyScan() throws Exception {
        Env e = new Env();
        e.pkgs.map.clear();
        boolean thrown = false;
        try { tool().scan(e.ctx()); } catch (IllegalStateException x) { thrown = true; }
        is("an empty package list stops the scan", thrown, true);

        // android is installed even when the phone's list lacks it
        Env e2 = new Env();
        e2.pkgs.map.remove("android");
        Set<String> got = paths(tool().scan(e2.ctx()));
        is("DCIM/Camera never a remnant, even without the package android", got.contains("sd/DCIM/Camera") || got.contains("sd/DCIM"), false);
    }

    // ---------------------------------------------------------------------------------------------------------- delete

    static void deleting() throws Exception {
        // 1. everything
        Env e = new Env();
        Sdm.Result r = tool().scan(e.ctx());
        long bytes = r.bytes();
        Sdm.DeleteReport rep = tool().delete(r, new Sdm.Selection(), e.ctx());
        eq("deleted", rep.deleted.size(), 7);
        eq("none failed", rep.failed.size(), 0);
        eq("bytes of the report = bytes of the scan", rep.bytes(), bytes);
        for (String p : defaultCorpses()) is("gone " + p, exists(p), false);
        for (String p : new String[] { "sd/DCIM/Camera/a.jpg", "sd/KeepApp/k.txt", "sd/Android/data/com.keep.app/files/k", "sd/Android/data/.com.keep.app/h", "sd/Unknown/u.txt",
                "sd/Android/media/com.keep.app/m", "sd/Blocked/x.txt", "sd/Notes/n.txt", "sd/Documents/DeadNotes/d.txt", "sd/yahoo", "sd/Android/data/com.uninst.keepdata/u" }) {
            is("untouched " + p, exists(p), true);
        }
        eq("result is empty after deleting everything", r.groupCount(), 0);
        eq("result bytes drop to zero", r.bytes(), 0L);
        eq("summary after the delete", r.summary().getString("primary"), "0 app remnants discovered");
        eq("report: first path is a remnant", rep.deleted.get(0).group.equals(rep.deleted.get(0).path), true);
        eq("report: label is the name", rep.deleted.get(0).label, rep.deleted.get(0).path.substring(rep.deleted.get(0).path.lastIndexOf('/') + 1));
        buildTree();

        // 2. a dropped group and a dropped content path
        Env e2 = new Env();
        Sdm.Result r2 = tool().scan(e2.ctx());
        Sdm.Selection sel = new Sdm.Selection();
        sel.dropGroups.add(sd + "/RegApp");
        sel.dropItems.add(sd + "/DeadApp/a.txt");
        Sdm.DeleteReport rep2 = tool().delete(r2, sel, e2.ctx());
        is("a dropped group stays", exists("sd/RegApp/r.txt"), true);
        is("a dropped content path stays", exists("sd/DeadApp/a.txt"), true);
        is("the rest of that remnant went", exists("sd/DeadApp/cache"), false);
        is("the remnant folder itself stays", exists("sd/DeadApp"), true);
        is("other remnants went", exists("sd/yahoo/atom") || exists("sd/Android/data/com.dead.app"), false);
        eq("deleted: 5 remnants + the content root of DeadApp", rep2.deleted.size(), 6);
        is("the content root is reported with the bytes below it", bytesOf(rep2, sd + "/DeadApp/cache") >= 20, true);
        eq("result: RegApp and DeadApp are left", paths(r2), set("sd/RegApp", "sd/DeadApp"));
        JSONArray left = r2.items(sd + "/DeadApp", 0, 100);
        eq("DeadApp has only the dropped file left in the result", left.length(), 1);
        eq("…the dropped one", left.getJSONObject(0).getString("path"), sd + "/DeadApp/a.txt");
        is("bytes of the remnant dropped", r2.bytes() > 0, true);
        // delete the rest (a second run with nothing dropped removes DeadApp and RegApp as wholes)
        Sdm.DeleteReport rep3 = tool().delete(r2, new Sdm.Selection(), e2.ctx());
        eq("second run deletes both", rep3.deleted.size(), 2);
        eq("result empty", r2.groupCount(), 0);
        buildTree();

        // 3. a dropped directory protects its subtree and the ancestors of a dropped path stay
        write("sd/DeadApp/deep/x/y/z.txt", 7);
        write("sd/DeadApp/deep/x/w.txt", 8);
        write("sd/DeadApp/deep/v.txt", 9);
        Env e3 = new Env();
        e3.fs = new TestFs();
        Sdm.Result r3 = tool().scan(e3.ctx());
        Sdm.Selection sel3 = new Sdm.Selection();
        sel3.dropItems.add(sd + "/DeadApp/deep/x/y");
        Sdm.Selection only = sel3;
        for (String s : new String[] { "sd/RegApp", "sd/yahoo/atom", "sd/.tmpabcdef12", "sd/Android/data/com.dead.app", "sd/Android/data/_com.hidden.dead", "sd/Android/media/com.dead.app" }) only.dropGroups.add(root + "/" + s);
        Sdm.DeleteReport rep4 = tool().delete(r3, only, e3.ctx());
        is("a dropped folder keeps its files", exists("sd/DeadApp/deep/x/y/z.txt"), true);
        is("…and its ancestors", exists("sd/DeadApp/deep/x"), true);
        is("a sibling file of an ancestor goes", exists("sd/DeadApp/deep/x/w.txt"), false);
        is("a file higher up goes", exists("sd/DeadApp/deep/v.txt"), false);
        is("other content goes", exists("sd/DeadApp/a.txt") || exists("sd/DeadApp/cache"), false);
        List<String> roots = new ArrayList<String>();
        for (Sdm.Deleted d : rep4.deleted) roots.add(d.path.substring(root.getPath().length() + 1));
        eq("only distinct roots are reported", new HashSet<String>(roots), set("sd/DeadApp/a.txt", "sd/DeadApp/cache", "sd/DeadApp/deep/x/w.txt", "sd/DeadApp/deep/v.txt"));
        List<String> called = new ArrayList<String>();
        for (List<String> c : ((TestFs) e3.fs).deleteCalls) called.addAll(c);
        eq("deleteAll got the roots only", new HashSet<String>(called).size(), called.size());
        is("no path below another one was passed", !called.contains(sd + "/DeadApp/cache/b.txt"), true);
        new SdmFsJava().delete(sd + "/DeadApp/deep");
        buildTree();

        // 4. looked at again: gone, changed, reinstalled, excluded after the scan
        Env e4 = new Env();
        Sdm.Result r4 = tool().scan(e4.ctx());
        new SdmFsJava().delete(sd + "/RegApp");                                  // gone by itself
        e4.pkgs.add("com.dead.app", 10500, false);                               // the app came back
        e4.ex.paths.add(sd + "/yahoo/atom");                                     // excluded after the scan
        Sdm.DeleteReport rep5 = tool().delete(r4, new Sdm.Selection(), e4.ctx());
        is("a path that is gone counts as deleted", containsPath(rep5.deleted, sd + "/RegApp"), true);
        is("a remnant whose app is installed again is left alone", exists("sd/DeadApp/a.txt") && exists("sd/Android/data/com.dead.app/files/big.bin") && exists("sd/Android/media/com.dead.app/m"), true);
        is("…and reported as failed", rep5.failed.contains(sd + "/DeadApp") && rep5.failed.contains(data + "/com.dead.app"), true);
        is("a path excluded after the scan is left alone", exists("sd/yahoo/atom/z.bin") && rep5.failed.contains(sd + "/yahoo/atom"), true);
        is("the others went", exists("sd/.tmpabcdef12") || exists("sd/Android/data/_com.hidden.dead"), false);
        is("a note says why", rep5.notes.toString().contains("its app is installed again") && rep5.notes.toString().contains("it is excluded"), true);
        is("the result still lists what was left", paths(r4).contains("sd/DeadApp") && paths(r4).contains("sd/yahoo/atom"), true);
        is("…and not what went", paths(r4).contains("sd/RegApp") || paths(r4).contains("sd/.tmpabcdef12"), false);
        buildTree();

        // 5. a changed type is not an error
        Env e5 = new Env();
        Sdm.Result r5 = tool().scan(e5.ctx());
        new SdmFsJava().delete(sd + "/RegApp");
        write("sd/RegApp", 99);                                                  // a file now, a folder at the scan
        Sdm.DeleteReport rep6 = tool().delete(r5, new Sdm.Selection(), e5.ctx());
        is("the changed path is deleted all the same", exists("sd/RegApp"), false);
        is("…and reported", containsPath(rep6.deleted, sd + "/RegApp"), true);
        buildTree();

        // 6. safety: a result that points at an area root or outside is refused
        Env e6 = new Env();
        Sdm.Entry lookup = new SdmFsJava().stat(data);
        SdmCorpseFinder.Corpse bad1 = new SdmCorpseFinder.Corpse(data, SdmCorpseFinder.F_PUBLICDATA, Sdm.Area.PUBLIC_DATA, 0, new ArrayList<SdmCorpseFinder.Owner>(), false, lookup, new ArrayList<Sdm.Entry>());
        Sdm.Entry l2 = new SdmFsJava().stat(root.getPath());
        SdmCorpseFinder.Corpse bad2 = new SdmCorpseFinder.Corpse(root.getPath(), SdmCorpseFinder.F_SDCARD, Sdm.Area.SDCARD, 0, new ArrayList<SdmCorpseFinder.Owner>(), false, l2, new ArrayList<Sdm.Entry>());
        Sdm.Entry l3 = new SdmFsJava().stat(sd + "/Unknown");
        SdmCorpseFinder.Corpse bad3 = new SdmCorpseFinder.Corpse(sd + "/Unknown/../KeepApp", SdmCorpseFinder.F_SDCARD, Sdm.Area.SDCARD, 0, new ArrayList<SdmCorpseFinder.Owner>(), false, l3, new ArrayList<Sdm.Entry>());
        SdmCorpseFinder.Corpse bad4 = new SdmCorpseFinder.Corpse(sd, SdmCorpseFinder.F_SDCARD, Sdm.Area.SDCARD, 0, new ArrayList<SdmCorpseFinder.Owner>(), false, new SdmFsJava().stat(sd), new ArrayList<Sdm.Entry>());
        int refused = 0, wrongly = 0;
        for (SdmCorpseFinder.Corpse bad : Arrays.asList(bad1, bad2, bad3, bad4)) {
            Sdm.DeleteReport rep7 = tool().delete(new SdmCorpseFinder.CorpseResult(Arrays.asList(bad), new ArrayList<String>()), new Sdm.Selection(), e6.ctx());
            refused += rep7.failed.size();
            wrongly += rep7.deleted.size();
        }
        eq("unsafe targets are all refused", wrongly, 0);
        eq("…and listed as failed", refused, 4);
        is("the area root is still there", exists("sd/Android/data/com.keep.app/files/k") && exists("sd/KeepApp/k.txt") && exists("sd/DCIM"), true);

        // 7. cancel: a cancelled delete stops, what went stays gone
        Env e7 = new Env();
        Sdm.Result r7 = tool().scan(e7.ctx());
        final boolean[] stop = { true };
        Sdm.Ctx c7 = new Sdm.Ctx(e7.fs, e7.areas, e7.pkgs, e7.ex, e7.shell, e7.settings, e7.rec, new Sdm.Cancel() { @Override public boolean cancelled() { return stop[0]; } }, 1700000000L);
        Sdm.DeleteReport rep8 = tool().delete(r7, new Sdm.Selection(), c7);
        eq("a delete cancelled before the start does nothing", rep8.deleted.size() + rep8.failed.size(), 0);
        eq("…and the result is unchanged", r7.groupCount(), 7);
        for (String p : defaultCorpses()) is("still there " + p, exists(p), true);
        buildTree();
    }

    static boolean containsPath(List<Sdm.Deleted> l, String path) { for (Sdm.Deleted d : l) if (d.path.equals(path)) return true; return false; }

    static long bytesOf(Sdm.DeleteReport rep, String path) {
        for (Sdm.Deleted d : rep.deleted) if (d.path.equals(path)) return d.bytes;
        return -1;
    }

    // ---------------------------------------------------------------------------------------------------------- result API for the engine

    static void resultApi() throws Exception {
        Env e = new Env();
        SdmCorpseFinder.CorpseResult r = (SdmCorpseFinder.CorpseResult) tool().scan(e.ctx());
        SdmCorpseFinder.CorpseResult snap = r.snapshot();
        eq("dropPaths removes the excluded remnant", r.dropPaths(Arrays.asList(sd + "/DeadApp")), 1);
        eq("…and one that holds an excluded path (nested rule)", r.dropPaths(Arrays.asList(sd + "/yahoo/atom/z.bin")), 1);
        eq("…and one below an excluded folder", r.dropPaths(Arrays.asList(data)), 2);
        eq("dropPackages removes the remnants of a package", r.dropPackages(Arrays.asList("com.dead.app")), 1);
        eq("what is left", r.groupCount(), 2);
        eq("the snapshot is untouched", snap.groupCount(), 7);
        r.restore(snap);
        eq("restore brings the remnants back", r.groupCount(), 7);
        eq("dropPaths of an unrelated path", r.dropPaths(Arrays.asList(sd + "/Nope")), 0);
        eq("the tool", r.tool(), Sdm.Tool.CORPSEFINDER);
        eq("tool()", new SdmCorpseFinder().tool(), Sdm.Tool.CORPSEFINDER);
    }
}
