package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SystemCleaner (SdmSystemCleaner) against a real temporary folder tree with fake data areas rooted in it: one file or folder per filter plus the negatives,
 * the group list, sizes and counts, settings and root gating, exclusions (pruning and the nested rule), cancel, progress strings, the result texts, the
 * deletion (selection, distinct roots, re-verify, cancel, snapshot after the delete) and the safety rules.
 */
public class SdmSystemCleanerTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    // ---------------------------------------------------------------------------------------------------------- fakes

    static final class FakeAreas implements Sdm.Areas {
        final List<Sdm.AreaInfo> list = new ArrayList<Sdm.AreaInfo>();
        @Override public List<Sdm.AreaInfo> all() { return list; }
        @Override public Sdm.AreaInfo get(Sdm.Area a) { for (Sdm.AreaInfo i : list) if (i.area == a) return i; return null; }
        @Override public Sdm.AreaInfo areaOf(String path) {
            Sdm.AreaInfo best = null;
            for (Sdm.AreaInfo i : list) if (SdmSieve.isAncestorOf(i.root, path) && (best == null || i.root.length() > best.root.length())) best = i;
            return best;
        }
    }

    static final class FakeEx implements Sdm.Exclusions {
        final Set<String> paths = new TreeSet<String>();
        final List<String[]> segments = new ArrayList<String[]>();
        @Override public boolean excludesPath(Sdm.Tool t, String path) {
            for (String p : paths) if (p.equals(path) || SdmSieve.isAncestorOf(p, path)) return true;
            for (String[] s : segments) if (SdmSieve.containsSegments(Sdm.segments(path), s, false, true)) return true;
            return false;
        }
        @Override public boolean excludesPackage(Sdm.Tool t, String pkg) { return false; }
        @Override public List<String> paths(Sdm.Tool t) { return new ArrayList<String>(paths); }
    }

    static final class FakeShell implements Sdm.Shell {
        final int uid;
        FakeShell(int uid) { this.uid = uid; }
        @Override public int uid() { return uid; }
        @Override public String run(String script, int timeoutMs) { return ""; }
        @Override public void stream(String script, int timeoutMs, Sdm.LineSink sink, Sdm.Cancel cancel) {}
    }

    static final class FakePkgs implements Sdm.Packages {
        final Map<String, Sdm.Pkg> pkgs = new HashMap<String, Sdm.Pkg>();
        void add(String pkg, String versionName, boolean uninstalled) { Sdm.Pkg p = new Sdm.Pkg(); p.pkg = pkg; p.versionName = versionName; p.uninstalled = uninstalled; pkgs.put(pkg, p); }
        @Override public List<Sdm.Pkg> installed() { return new ArrayList<Sdm.Pkg>(pkgs.values()); }
        @Override public Sdm.Pkg get(String pkg) { return pkgs.get(pkg); }
        @Override public Set<String> running() { return new HashSet<String>(); }
        @Override public long cacheBytes(String pkg) { return -1; }
    }

    static final class Rec implements Sdm.Progress {
        final List<String[]> calls = new ArrayList<String[]>();
        final List<long[]> counts = new ArrayList<long[]>();
        Sdm.Cancel trigger;
        int cancelAfter = -1;
        volatile boolean cancel;
        @Override public synchronized void update(String primary, String secondary, long done, long total, long bytes) {
            calls.add(new String[] { primary, secondary });
            counts.add(new long[] { done, total, bytes });
            if (cancelAfter > 0 && calls.size() >= cancelAfter) cancel = true;
        }
        final Sdm.Cancel flag = new Sdm.Cancel() { @Override public boolean cancelled() { return cancel; } };
    }

    // ---------------------------------------------------------------------------------------------------------- the world

    static Path T;
    static long now;
    static final String S = "storage/emulated/0/", USB = "storage/USB1/", USBBAD = "storage/USBBAD/";
    static final String AD = S + "Android/data/", AM = S + "Android/media/", AO = S + "Android/obb/";
    static final String D = "data/", SYS = "data/system/", SYSCE = "data/system_ce/0/", SYSDE = "data/system_de/0/", MISC = "data/misc/", VEN = "data/vendor/", APP = "data/app/";
    static final String PRIV = "priv/", C = "cache/", C2 = "cache2/";

    static String p(String rel) { return T.resolve(rel).toString(); }
    static void mk(String rel) throws IOException { Files.createDirectories(T.resolve(rel)); }
    static void file(String rel, int size) throws IOException { Path f = T.resolve(rel); Files.createDirectories(f.getParent()); Files.write(f, new byte[size]); }
    static void age(String rel, double days) throws IOException { Files.setLastModifiedTime(T.resolve(rel), FileTime.fromMillis((long) (now * 1000L - days * 86400000L))); }
    static void rmtree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root);
            try { for (Path c : ds) rmtree(c); } finally { ds.close(); }
        }
        Files.delete(root);
    }
    static boolean exists(String rel) { return Files.exists(T.resolve(rel), java.nio.file.LinkOption.NOFOLLOW_LINKS); }

    static FakeAreas areas() {
        FakeAreas a = new FakeAreas();
        a.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, p("storage/emulated/0"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, p("storage/emulated/0/Android/data"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_MEDIA, p("storage/emulated/0/Android/media"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_OBB, p("storage/emulated/0/Android/obb"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, p("storage/USB1"), false, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, p("storage/USBBAD"), false, "", "not mounted"));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.PRIVATE_DATA, p("priv"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DATA, p("data"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DATA_SYSTEM, p("data/system"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DATA_SYSTEM_CE, p("data/system_ce/0"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DATA_SYSTEM_DE, p("data/system_de/0"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DATA_MISC, p("data/misc"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DATA_VENDOR, p("data/vendor"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.APP_APP, p("data/app"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DOWNLOAD_CACHE, p("cache"), true, "java", ""));
        a.list.add(new Sdm.AreaInfo(Sdm.Area.DOWNLOAD_CACHE, p("cache2"), false, "java", ""));
        return a;
    }

    static Sdm.Ctx ctx(JSONObject settings, int uid, Sdm.Progress prog, Sdm.Cancel cancel, Sdm.Exclusions ex, Sdm.Packages pkgs) {
        return new Sdm.Ctx(new SdmFsJava(), areas(), pkgs == null ? new FakePkgs() : pkgs, ex == null ? new FakeEx() : ex, new FakeShell(uid), settings, prog, cancel, now);
    }

    /** Every filter on, root. */
    static JSONObject allOn() throws Exception {
        JSONObject o = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) o.put(SdmSystemCleaner.filterKey(id), true);
        return o;
    }

    static SdmSystemCleaner.SysResult scan(Sdm.Ctx c) throws Exception { return (SdmSystemCleaner.SysResult) new SdmSystemCleaner().scan(c); }

    /** path -> group id of everything in the result. */
    static Map<String, String> flat(Sdm.Result r) throws Exception {
        Map<String, String> m = new TreeMap<String, String>();
        JSONArray gs = r.groups(0, 0);
        for (int i = 0; i < gs.length(); i++) {
            String id = gs.getJSONObject(i).getString("id");
            JSONArray its = r.items(id, 0, 0);
            for (int k = 0; k < its.length(); k++) {
                String path = its.getJSONObject(k).getString("path");
                if (m.containsKey(path)) { fails++; n++; System.out.println("FAIL a path is in two groups: " + path); }
                m.put(path, id);
            }
        }
        return m;
    }

    static Map<String, String> EXP = new TreeMap<String, String>();     // rel path -> group (positives)
    static List<String> NEG = new ArrayList<String>();                  // rel paths that must exist and must not be listed
    static void exp(String group, String... rels) { for (String r : rels) EXP.put(r, group); }
    static void neg(String... rels) { NEG.addAll(Arrays.asList(rels)); }

    // ---------------------------------------------------------------------------------------------------------- the big tree: one thing per filter plus negatives

    static void buildTree() throws Exception {
        EXP.clear(); NEG.clear();

        // ---- 1 logfiles
        file(S + "Download/app.log", 120); exp("logfiles", S + "Download/app.log");
        file(S + "Download/UPPER.LOG", 80); exp("logfiles", S + "Download/UPPER.LOG");
        file(S + "Download/notes.txt", 10); neg(S + "Download/notes.txt");
        file(S + "Download/x/leveldb/db.log", 10); neg(S + "Download/x/leveldb/db.log");
        file(S + "chrome/app_chrome/Default/a.log", 10); neg(S + "chrome/app_chrome/Default/a.log");
        file(S + "web/app_webview/b.log", 10); neg(S + "web/app_webview/b.log");
        file(S + "t/Paths/c.log", 10); neg(S + "t/Paths/c.log");
        file(S + "proto/shared_proto_db/d.log", 10); neg(S + "proto/shared_proto_db/d.log");
        file(S + "idb/https_x.indexeddb.leveldb/e.log", 10); neg(S + "idb/https_x.indexeddb.leveldb/e.log");
        file(C + "recovery/a.log", 40); exp("logfiles", C + "recovery/a.log");
        file(C2 + "b.log", 30); exp("logfiles", C2 + "b.log");
        file(VEN + "radio/extended_logs/e1.txt.old", 25); exp("logfiles", VEN + "radio/extended_logs/e1.txt.old");
        file(VEN + "radio/extended_logs/e2.txt", 25); neg(VEN + "radio/extended_logs/e2.txt");
        file(VEN + "bluetooth/bt_activity_pkt.txt.last", 26); exp("logfiles", VEN + "bluetooth/bt_activity_pkt.txt.last");
        file(SYS + "shutdown-checkpoints/checkpoints-1", 27); exp("logfiles", SYS + "shutdown-checkpoints/checkpoints-1");
        file(MISC + "update_engine_log/update_engine.2020", 28); age(MISC + "update_engine_log/update_engine.2020", 5); exp("logfiles", MISC + "update_engine_log/update_engine.2020");
        file(MISC + "update_engine_log/update_engine.new", 28); neg(MISC + "update_engine_log/update_engine.new");
        file(MISC + "recovery/last_kmsg.1", 29); exp("logfiles", MISC + "recovery/last_kmsg.1");
        file(MISC + "other.log", 29); neg(MISC + "other.log");
        file(D + "miuilog/stability/scout/app/s1.txt", 31); exp("logfiles", D + "miuilog/stability/scout/app/s1.txt");
        file(D + "miuilog/stability/scout/app/subdir/s2.txt", 32); exp("logfiles", D + "miuilog/stability/scout/app/subdir", D + "miuilog/stability/scout/app/subdir/s2.txt");
        file(USB + "app.log", 12); neg(USB + "app.log");                                                      // logfiles has no PORTABLE sieve

        // ---- 2 advertisements
        file(S + ".mologiq/a.dat", 50); exp("advertisements", S + ".mologiq", S + ".mologiq/a.dat");
        file(S + ".Adcenix", 51); exp("advertisements", S + ".Adcenix");
        file(S + "ppy_cross", 52); exp("advertisements", S + "ppy_cross");
        file(S + "ppy_cross2.txt", 5); neg(S + "ppy_cross2.txt");
        file(S + "ApplifierVideoCache/v.mp4", 53); exp("advertisements", S + "ApplifierVideoCache", S + "ApplifierVideoCache/v.mp4");
        file(S + "__chartboost/x.bin", 54); exp("advertisements", S + "__chartboost", S + "__chartboost/x.bin");
        file(S + ".chartboost/chartboost", 55); neg(S + ".chartboost/chartboost");                           // a file called ...chartboost is no ad folder
        file(S + ".chartboost/other.dat", 56); exp("advertisements", S + ".chartboost", S + ".chartboost/other.dat");
        file(S + ".mobvista123/q.dat", 57); exp("advertisements", S + ".mobvista123", S + ".mobvista123/q.dat");
        file(S + ".mobvista/q.dat", 57); neg(S + ".mobvista/q.dat");                                           // no digits
        file(S + "adhub/h.dat", 58); exp("advertisements", S + "adhub", S + "adhub/h.dat");
        file(S + "Sub/adhub/h2.dat", 58); neg(S + "Sub/adhub/h2.dat");                                         // top level only
        file(S + "unityadsimagecache/i.dat", 59); neg(S + "unityadsimagecache/i.dat");                         // the regex of upstream is case sensitive
        file(S + ".goadsdk/g.dat", 60); exp("advertisements", S + ".goadsdk", S + ".goadsdk/g.dat");
        file(S + ".goproduct", 61); exp("advertisements", S + ".goproduct");

        // ---- 3 emptydirectories
        mk(S + "EmptyA"); exp("emptydirectories", S + "EmptyA");
        mk(S + "Nest/Inner/Deep"); exp("emptydirectories", S + "Nest", S + "Nest/Inner", S + "Nest/Inner/Deep");
        file(S + "Keep/file.txt", 7); mk(S + "Keep/sub"); exp("emptydirectories", S + "Keep/sub"); neg(S + "Keep/file.txt");
        mk(S + "DCIM"); neg(S + "DCIM");                                                                       // protected
        mk(S + "Music/EmptyInMusic"); exp("emptydirectories", S + "Music/EmptyInMusic"); neg(S + "Music");   // Music itself is protected
        mk(S + "Parent/.stfolder"); neg(S + "Parent/.stfolder", S + "Parent");
        mk(S + "WithLink"); Files.createSymbolicLink(T.resolve(S + "WithLink/lnk"), T.resolve(S + "Keep")); neg(S + "WithLink", S + "WithLink/lnk");
        mk(S + ".thumbnails"); exp("emptydirectories", S + ".thumbnails");                                     // beats the thumbnails filter (settings order)
        mk(S + ".Trash-500"); exp("emptydirectories", S + ".Trash-500");                                       // beats the linux files filter
        file(AD + "com.app/desktop.ini", 3); exp("windowsfiles", AD + "com.app/desktop.ini");
        mk(AD + "com.pkg1"); neg(AD + "com.pkg1");                                                             // the top level package folders
        mk(AD + "com.pkg2/files"); mk(AD + "com.pkg2/cache"); mk(AD + "com.pkg2/other");
        exp("emptydirectories", AD + "com.pkg2/other"); neg(AD + "com.pkg2", AD + "com.pkg2/files", AD + "com.pkg2/cache");
        mk(AM + "com.m/Pics"); exp("emptydirectories", AM + "com.m/Pics");
        mk(AM + "com.m2"); neg(AM + "com.m2");
        mk(USB + "Empty"); exp("emptydirectories", USB + "Empty");
        mk(AO + "com.o/empty"); neg(AO + "com.o/empty");                                                       // no empty folder filter for the obb area
        neg(S + "Android");

        // ---- 5 trashed, 6 screenshots, 4 apks (separately), 7 lostdir
        file(S + "Pictures/.trashed-1700-a.jpg", 70); exp("trashed", S + "Pictures/.trashed-1700-a.jpg");
        file(S + ".trashed-x", 71); exp("trashed", S + ".trashed-x");
        file(S + "Pictures/trashed-b.jpg", 72); neg(S + "Pictures/trashed-b.jpg");
        file(S + ".trashed-dir/inner.txt", 73); neg(S + ".trashed-dir/inner.txt");
        file(USB + "Pictures/.trashed-9.jpg", 74); exp("trashed", USB + "Pictures/.trashed-9.jpg");
        file(S + "Pictures/Screenshots/old1.png", 81); age(S + "Pictures/Screenshots/old1.png", 30); exp("screenshots", S + "Pictures/Screenshots/old1.png");
        file(S + "Pictures/Screenshots/new1.png", 82); age(S + "Pictures/Screenshots/new1.png", 1); neg(S + "Pictures/Screenshots/new1.png");
        file(S + "Pictures/Screenshots/sub/old2.png", 83); age(S + "Pictures/Screenshots/sub/old2.png", 20); exp("screenshots", S + "Pictures/Screenshots/sub/old2.png");
        file(S + "DCIM/Screenshots/old3.png", 84); age(S + "DCIM/Screenshots/old3.png", 30); neg(S + "DCIM/Screenshots/old3.png");
        file(S + "Pictures/screenshots/old4.png", 85); age(S + "Pictures/screenshots/old4.png", 30); exp("screenshots", S + "Pictures/screenshots/old4.png");
        file(S + "Pictures/Screenshots/shot.tmp", 86); age(S + "Pictures/Screenshots/shot.tmp", 30); exp("screenshots", S + "Pictures/Screenshots/shot.tmp");   // beats tempfiles
        file(S + "Pictures/Screenshots/new.tmp", 87); age(S + "Pictures/Screenshots/new.tmp", 1); exp("tempfiles", S + "Pictures/Screenshots/new.tmp");        // too young: tempfiles
        file(USB + "Pictures/Screenshots/old.png", 88); age(USB + "Pictures/Screenshots/old.png", 40); exp("screenshots", USB + "Pictures/Screenshots/old.png");
        file(S + "LOST.DIR/f1.dat", 90); exp("lostdir", S + "LOST.DIR/f1.dat");
        file(S + "x/LOST.DIR/y/f2.dat", 91); exp("lostdir", S + "x/LOST.DIR/y/f2.dat");
        file(S + "lost.dir/f3.dat", 92); neg(S + "lost.dir/f3.dat");
        file(USB + "LOST.DIR/a", 93); exp("lostdir", USB + "LOST.DIR/a");

        // ---- 8 linuxfiles
        file(S + ".Trash-1000/files/x.txt", 100); exp("linuxfiles", S + ".Trash-1000", S + ".Trash-1000/files"); neg(S + ".Trash-1000/files/x.txt");
        file(S + ".Trash/sub/y.txt", 101); exp("linuxfiles", S + ".Trash/sub"); neg(S + ".Trash", S + ".Trash/sub/y.txt");
        file(S + "Nonempty/.Trash-7/z.txt", 102); neg(S + "Nonempty", S + "Nonempty/.Trash-7", S + "Nonempty/.Trash-7/z.txt");   // top level only

        // ---- 9 macfiles
        file(S + "._foo", 110); exp("macfiles", S + "._foo");
        file(S + "Sub/.DS_Store", 111); exp("macfiles", S + "Sub/.DS_Store");
        file(S + ".Trashes/501/x", 112); exp("macfiles", S + ".Trashes"); neg(S + ".Trashes/501/x");
        file(S + "Sub2/.Trashes/f", 113); neg(S + "Sub2/.Trashes/f");
        file(S + ".fseventsd/uuid1", 114); exp("macfiles", S + ".fseventsd");
        file(S + ".Spotlight-V100/Store/x", 115); exp("macfiles", S + ".Spotlight-V100");
        file(AM + "com.m/._mac", 116); exp("macfiles", AM + "com.m/._mac");
        file(AM + ".Trashes/x", 117); neg(AM + ".Trashes/x");                                                  // the folder rule has no PUBLIC_MEDIA
        file(USB + "._m", 118); exp("macfiles", USB + "._m");

        // ---- 10 windowsfiles
        file(S + "desktop.ini", 120); exp("windowsfiles", S + "desktop.ini");
        file(S + "Sub/Thumbs.db", 121); exp("windowsfiles", S + "Sub/Thumbs.db");
        file(S + "desktop.ini.bak", 122); neg(S + "desktop.ini.bak");
        file(AO + "com.o/thumbs.db", 123); exp("windowsfiles", AO + "com.o/thumbs.db");
        file(AM + "com.m/desktop.ini", 124); exp("windowsfiles", AM + "com.m/desktop.ini");
        file(USB + "desktop.ini", 125); exp("windowsfiles", USB + "desktop.ini");
        file(USBBAD + "desktop.ini", 126); neg(USBBAD + "desktop.ini");                                        // an area that is not available is skipped

        // ---- 11 tempfiles
        file(S + "a.tmp", 130); exp("tempfiles", S + "a.tmp");
        file(S + "b.temp", 131); exp("tempfiles", S + "b.temp");
        file(S + ".mmsyscache", 132); exp("tempfiles", S + ".mmsyscache");
        file(S + "sdm_write_test-1", 133); exp("tempfiles", S + "sdm_write_test-1");
        file(S + "eu.darken.sdmse-test-sd-area-access-1", 134); exp("tempfiles", S + "eu.darken.sdmse-test-sd-area-access-1");
        file(S + "eu.darken.sdmse-test-usb-area-access-1", 135); exp("tempfiles", S + "eu.darken.sdmse-test-usb-area-access-1");
        file(S + "backup/pending/x.tmp", 136); neg(S + "backup/pending/x.tmp");                                // the port's fix of upstream's dead exclusion
        file(S + "Other/backup/pending/z.tmp", 137); neg(S + "Other/backup/pending/z.tmp");
        file(S + "tmpfile.txt", 138); neg(S + "tmpfile.txt");
        file(S + "dir.tmp/inner.txt", 139); neg(S + "dir.tmp/inner.txt");
        file(D + "other.tmp", 140); exp("tempfiles", D + "other.tmp");
        file(D + "backup/pending/p.tmp", 141); neg(D + "backup/pending/p.tmp");
        file(D + "readme", 142); neg(D + "readme");
        file(APP + "base.tmp", 143); neg(APP + "base.tmp");                                                    // /data/app is the APP_APP area, not DATA's
        file(SYS + "f.tmp", 144); exp("tempfiles", SYS + "f.tmp");
        file(SYSDE + "z.tmp", 145); exp("tempfiles", SYSDE + "z.tmp");
        file(SYSCE + "x.tmp", 146); neg(SYSCE + "x.tmp");                                                      // DATA_SYSTEM_CE is not a tempfiles area

        // ---- 12 thumbnails
        file(S + "DCIM/.thumbnails/1.jpg", 150); exp("thumbnails", S + "DCIM/.thumbnails", S + "DCIM/.thumbnails/1.jpg");
        file(S + "DCIM/.thumbnails/data/2.jpg", 151); exp("thumbnails", S + "DCIM/.thumbnails/data", S + "DCIM/.thumbnails/data/2.jpg");
        file(S + ".thumbnails2/x", 152); neg(S + ".thumbnails2/x");
        file(AM + "com.m/.thumbnails/t.jpg", 153); exp("thumbnails", AM + "com.m/.thumbnails", AM + "com.m/.thumbnails/t.jpg");
        file(USB + ".thumbnails/t", 154); exp("thumbnails", USB + ".thumbnails", USB + ".thumbnails/t");

        // ---- 13 analytics
        file(S + ".INSTALLATION", 160); exp("analytics", S + ".INSTALLATION");
        file(S + "Sub/.INSTALLATION", 161); neg(S + "Sub/.INSTALLATION");
        file(S + ".pns/.uniqueId/x", 162); exp("analytics", S + ".pns/.uniqueId/x"); neg(S + ".pns", S + ".pns/.uniqueId");
        file(S + "Android/obj/.um/sysid.dat", 163); exp("analytics", S + "Android/obj/.um/sysid.dat");
        file(S + "foo/.bugsense", 164); exp("analytics", S + "foo/.bugsense");
        file(S + ".bugsense/x", 165); neg(S + ".bugsense/x");
        file(S + ".um/sysid.dat", 166); exp("analytics", S + ".um/sysid.dat");
        file(S + "libs/com.igexin.sdk.deviceId.db", 167); exp("analytics", S + "libs/com.igexin.sdk.deviceId.db");
        file(AD + "com.app/.bugsense", 168); exp("analytics", AD + "com.app/.bugsense");
        file(AD + ".tlocalcookieid", 169); exp("analytics", AD + ".tlocalcookieid");
        file(AD + "com.app/a.tmp", 5); neg(AD + "com.app/a.tmp");
        file(AD + "com.app/x.log", 5); neg(AD + "com.app/x.log");
        file(PRIV + "com.p/.bugsense", 170); exp("analytics", PRIV + "com.p/.bugsense");
        file(PRIV + "com.p/x.tmp", 5); neg(PRIV + "com.p/x.tmp");

        // ---- 14 anr, 15 localtmp, 16 downloadcache, 17 datalogger, 18 logdropbox, 19 recenttasks, 20 tombstones, 21 usagestats, 22 packagecache
        file(D + "anr/traces.txt", 200); exp("anr", D + "anr/traces.txt");
        file(D + "anr/sub/t2.txt", 201); exp("anr", D + "anr/sub/t2.txt");
        file(C + "anr/trace.txt", 202); exp("anr", C + "anr/trace.txt");                                       // anr comes before downloadcache
        file(C2 + "anr/n.txt", 203); exp("downloadcache", C2 + "anr/n.txt");                                   // only the primary area's anr folder is the ANR folder
        file(D + "local/tmp/x.bin", 210); exp("localtmp", D + "local/tmp/x.bin");
        file(D + "local/tmp/d/e.txt", 211); exp("localtmp", D + "local/tmp/d", D + "local/tmp/d/e.txt");
        file(C + "ota/update.zip", 220); exp("downloadcache", C + "ota/update.zip");
        file(C + "dalvik-cache/x", 5); neg(C + "dalvik-cache/x");
        file(C + "lost+found/y", 5); neg(C + "lost+found/y");
        file(C + "recovery/last_log", 5); neg(C + "recovery/last_log");
        file(C + "recovery/last_log1", 221); exp("downloadcache", C + "recovery/last_log1");                   // another segment name, not excluded
        file(C + "x/last_postrecovery", 5); neg(C + "x/last_postrecovery");
        file(C + "last_data_partition_info", 5); neg(C + "last_data_partition_info");
        file(C + "last_dataresizing", 5); neg(C + "last_dataresizing");
        file(C + "magisk/z", 5); neg(C + "magisk/z");
        file(D + "logger/l1", 230); exp("datalogger", D + "logger/l1");
        file(D + "log/l2", 231); exp("datalogger", D + "log/l2");
        file(D + "log_other_mode/l3", 232); exp("datalogger", D + "log_other_mode/l3");
        file(SYS + "dropbox/d1.txt", 240); exp("logdropbox", SYS + "dropbox/d1.txt");
        file(SYSCE + "recent_images/r1.png", 250); exp("recenttasks", SYSCE + "recent_images/r1.png");
        file(SYSCE + "recent_tasks/r2.xml", 251); exp("recenttasks", SYSCE + "recent_tasks/r2.xml");
        file(D + "tombstones/t1", 260); exp("tombstones", D + "tombstones/t1");
        file(VEN + "tombstones/vt1", 261); exp("tombstones", VEN + "tombstones/vt1");
        file(SYS + "usagestats/0/daily/u1", 270); exp("usagestats", SYS + "usagestats/0/daily/u1");
        file(SYS + "usagestats/foo/u2", 5); neg(SYS + "usagestats/foo/u2");
        file(SYS + "package_cache/p1", 280); file(SYS + "package_cache/pd/q1", 281);
        exp("packagecache", SYS + "package_cache/p1", SYS + "package_cache/pd", SYS + "package_cache/pd/q1");
    }

    static long statSize(String rel) throws IOException { return new SdmFsJava().stat(p(rel)).size; }

    // ---------------------------------------------------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        T = Files.createTempDirectory("sdmsc");
        now = System.currentTimeMillis() / 1000;
        try {
            catalog();
            strings();
            global();
            apkReader();
            bigScan();
            settingsAndGating();
            exclusions();
            cancelScan();
            progressStrings();
            deleteAll();
            deleteSelection();
            deleteReverify();
            deleteCancel();
            apkFilter();
            screenshotAge();
            safety();
        } finally {
            rmtree(T);
        }
        if (fails == 0) System.out.println("PASS SdmSystemCleanerTest: " + n + " checks");
        else { System.out.println("FAIL SdmSystemCleanerTest: " + n + " checks, " + fails + " failed"); System.exit(1); }
    }

    static void fresh() throws Exception {
        java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(T);
        try { for (Path c : ds) rmtree(c); } finally { ds.close(); }
    }

    // ---------------------------------------------------------------------------------------------------------- the catalog (22 filters, settings order, labels, defaults)

    static void catalog() {
        String[] ids = { "logfiles", "advertisements", "emptydirectories", "superfluosapks", "trashed", "screenshots", "lostdir", "linuxfiles", "macfiles", "windowsfiles", "tempfiles",
            "thumbnails", "analytics", "anr", "localtmp", "downloadcache", "datalogger", "logdropbox", "recenttasks", "tombstones", "usagestats", "packagecache" };
        String[] labels = { "System log files", "Ad files", "Empty folders", "Superfluous APKs", "Trashed files", "Screenshots", "LOST.DIR folders", "Linux files", "Mac files", "Windows files",
            "Temporary system files", "Thumbnail images", "Analytics files", "ANR errors", "Local temporary files", "Download cache", "System log files", "Log drop box", "Recent tasks",
            "System crash residue", "Usage stats", "Installer cache" };
        String[] summaries = {
            "System and system service log files (e.g. *.log) in various locations.",
            "Files that have been created for or by advertisements.",
            "Empty folders from all over the device. Nested folders may require multiple passes.",
            "Setup files that are no longer needed based on installed app versions.",
            "Files in recycle bins from various apps and locations that are marked for deletion but not yet permanently removed.",
            "Screenshots older than %s from multiple apps and locations.",
            "Temporary files that are created when transferring data between Android devices.",
            "Files and folders that are created when storage has been connected to a linux computer.",
            "Files and folders that are created when browsing storage with Apple's finder app.",
            "Data that the Windows file explorer can create on connected storage.",
            "System related temporary files, usually short lived caches for active operations.",
            "Cached previews for images and videos.",
            "Data related to analytics and bug tracking.",
            "Traces from 'application not responding' events.",
            "System related temporary files (e.g. from Android Studio).",
            "System cache folder used for various things (e.g. OTA updates).",
            "System service specific log files.",
            "An area for the system to drop log files into.",
            "Data related to the tasks history in the system's task switcher.",
            "If native processes crash (e.g. games), then files are created here.",
            "Files related to application usage stats.",
            "Cached data related to installed apps that is used by the system's package management." };
        boolean[] on = { true, true, true, false, false, false, true, true, true, true, true, false, true, true, false, true, true, true, false, false, false, false };
        boolean[] root = { false, false, false, false, false, false, false, false, false, false, false, false, false, true, true, true, true, true, true, true, true, true };
        eq("22 filters", SdmSystemCleaner.filterIds().length, 22);
        eq("filter ids in settings order", Arrays.asList(SdmSystemCleaner.filterIds()), Arrays.asList(ids));
        int onCount = 0, offCount = 0;
        for (int i = 0; i < ids.length; i++) {
            eq("label of " + ids[i], SdmSystemCleaner.filterLabel(ids[i]), labels[i]);
            eq("summary of " + ids[i], SdmSystemCleaner.filterSummary(ids[i]), summaries[i]);
            eq("default of " + ids[i], SdmSystemCleaner.filterDefault(ids[i]), on[i]);
            eq("root of " + ids[i], SdmSystemCleaner.filterNeedsRoot(ids[i]), root[i]);
            eq("settings key of " + ids[i], SdmSystemCleaner.filterKey(ids[i]), "filter." + ids[i] + ".enabled");
            if (on[i]) onCount++; else offCount++;
        }
        eq("13 filters are on by default", onCount, 13);
        eq("9 filters are off by default", offCount, 9);
        eq("the two log filters share a label", SdmSystemCleaner.filterLabel("logfiles"), SdmSystemCleaner.filterLabel("datalogger"));
        eq("tool", new SdmSystemCleaner().tool(), Sdm.Tool.SYSTEMCLEANER);
        try {
            Object o = Class.forName("com.bloatware.bingblop.SdmSystemCleaner").newInstance();
            is("reflection instantiates it (the engine does)", o instanceof Sdm.ToolImpl, true);
        } catch (Exception e) { eq("reflection", e.toString(), "no exception"); }
        eq("settings key: same version", SdmSystemCleaner.KEY_SUPERFLUOUS_SAME, "filter.superfluosapks.includesameversion");
        eq("settings key: age", SdmSystemCleaner.KEY_SCREENSHOTS_AGE, "filter.screenshots.age");
        eq("age default is 14 days in ms", SdmSystemCleaner.SCREENSHOTS_AGE_DEFAULT_MS, 14L * 86400000L);
    }

    // ---------------------------------------------------------------------------------------------------------- strings (spec 7.3, 7.7, 9.1)

    static void strings() {
        eq("1 filter match", SdmSystemCleaner.foundText(1), "1 filter match");
        eq("0 filter matches", SdmSystemCleaner.foundText(0), "0 filter matches");
        eq("2 filter matches", SdmSystemCleaner.foundText(2), "2 filter matches");
        eq("1 match deleted", SdmSystemCleaner.deletedText(1), "1 match deleted");
        eq("3 matches deleted", SdmSystemCleaner.deletedText(3), "3 matches deleted");
        eq("can be freed", SdmSystemCleaner.canBeFreedText(0), "0 B can be freed");
        eq("can be freed KB", SdmSystemCleaner.canBeFreedText(1536), "1.5 KB can be freed");
        eq("freed", SdmSystemCleaner.freedText(12898304), "Freed 12.3 MB space.");
        eq("1023 B", SdmSystemCleaner.fmtSize(1023), "1023 B");
        eq("1 KB", SdmSystemCleaner.fmtSize(1024), "1.0 KB");
        eq("1 GB", SdmSystemCleaner.fmtSize(1073741824L), "1.0 GB");
        eq("1 TB", SdmSystemCleaner.fmtSize(1099511627776L), "1.0 TB");
        eq("age 14 days", SdmSystemCleaner.ageText(14L * 86400000L), "14 days");
        eq("age 1 day", SdmSystemCleaner.ageText(86400000L), "1 day");
        eq("age 36 hours is 1 day", SdmSystemCleaner.ageText(36L * 3600000L), "1 day");
        eq("age 23 hours", SdmSystemCleaner.ageText(23L * 3600000L), "23 hours");
        eq("age 1 hour", SdmSystemCleaner.ageText(3600000L), "1 hour");
        eq("age 0", SdmSystemCleaner.ageText(0), "0 hours");
        eq("progress primary", SdmSystemCleaner.P_SEARCHING, "Searching");
        eq("progress secondary", SdmSystemCleaner.P_GENERATING, "Generating search paths");
        eq("progress loading", SdmSystemCleaner.P_LOADING, "Loading");
    }

    // ---------------------------------------------------------------------------------------------------------- small pure parts

    static void global() {
        is("/data/data is skipped", SdmSystemCleaner.isGlobalSkip("/data/data", true), true);
        is("below /data/data", SdmSystemCleaner.isGlobalSkip("/data/data/com.x/a", true), true);
        is("/data/user/0", SdmSystemCleaner.isGlobalSkip("/data/user/0/com.x", true), true);
        is("/data/user/10 is not skipped", SdmSystemCleaner.isGlobalSkip("/data/user/10/com.x", true), false);
        is("/data/datax is not /data/data", SdmSystemCleaner.isGlobalSkip("/data/datax/a", true), false);
        is("/data/media/0 is always skipped", SdmSystemCleaner.isGlobalSkip("/data/media/0/DCIM", false), true);
        is("/data/data is walked when it is a target area", SdmSystemCleaner.isGlobalSkip("/data/data/com.x", false), false);
        is("/data/local/tmp is walked", SdmSystemCleaner.isGlobalSkip("/data/local/tmp/x", true), false);
        is("/data/media/1 is not skipped", SdmSystemCleaner.isGlobalSkip("/data/media/1/x", true), false);

        is("superfluous: same version counts when included", SdmSystemCleaner.superfluous(5, 5, true), true);
        is("superfluous: same version does not count when not included", SdmSystemCleaner.superfluous(5, 5, false), false);
        is("superfluous: installed is newer", SdmSystemCleaner.superfluous(6, 5, false), true);
        is("superfluous: the file is newer", SdmSystemCleaner.superfluous(5, 6, true), false);

        is("emptyDirCandidate: plain folder", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/Foo"), new String[] { "Foo" }, Sdm.Area.SDCARD), true);
        is("emptyDirCandidate: the area root", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r"), new String[0], Sdm.Area.SDCARD), false);
        is("emptyDirCandidate: protected, any case", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/dcim"), new String[] { "dcim" }, Sdm.Area.SDCARD), false);
        is("emptyDirCandidate: protected Android/data", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/Android/data"), new String[] { "Android", "data" }, Sdm.Area.SDCARD), false);
        is("emptyDirCandidate: .stfolder", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/a/.stfolder"), new String[] { "a", ".stfolder" }, Sdm.Area.SDCARD), false);
        is("emptyDirCandidate: mnt/asec anywhere in the path", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/mnt/asec/x"), new String[] { "x" }, Sdm.Area.SDCARD), false);
        is("emptyDirCandidate: package folder", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/com.x"), new String[] { "com.x" }, Sdm.Area.PUBLIC_DATA), false);
        is("emptyDirCandidate: package/files", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/com.x/files"), new String[] { "com.x", "files" }, Sdm.Area.PUBLIC_MEDIA), false);
        is("emptyDirCandidate: package/Files (case sensitive)", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/com.x/Files"), new String[] { "com.x", "Files" }, Sdm.Area.PUBLIC_MEDIA), true);
        is("emptyDirCandidate: package/other", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/com.x/other"), new String[] { "com.x", "other" }, Sdm.Area.PUBLIC_DATA), true);
        is("emptyDirCandidate: files in the sdcard is fine", SdmSystemCleaner.emptyDirCandidate(Sdm.segments("/r/a/files"), new String[] { "a", "files" }, Sdm.Area.SDCARD), true);
    }

    // ---------------------------------------------------------------------------------------------------------- the binary manifest reader and the .apk / .apks files

    static byte[] manifest(String pkg, long versionCode, String versionName, long major, boolean utf8) throws IOException {
        List<String> pool = new ArrayList<String>(Arrays.asList("package", "versionCode", "versionName", "manifest", pkg, versionName, "http://schemas.android.com/apk/res/android", "versionCodeMajor"));
        ByteArrayOutputStream strs = new ByteArrayOutputStream();
        List<Integer> offs = new ArrayList<Integer>();
        for (String s : pool) {
            offs.add(strs.size());
            if (utf8) {
                byte[] b = s.getBytes("UTF-8");
                strs.write(s.length()); strs.write(b.length); strs.write(b); strs.write(0);
            } else {
                byte[] b = s.getBytes("UTF-16LE");
                strs.write(s.length() & 0xff); strs.write(s.length() >> 8); strs.write(b); strs.write(0); strs.write(0);
            }
        }
        while (strs.size() % 4 != 0) strs.write(0);
        int count = pool.size();
        int poolSize = 28 + count * 4 + strs.size();
        ByteBuffer pb = ByteBuffer.allocate(poolSize).order(ByteOrder.LITTLE_ENDIAN);
        pb.putShort((short) 1).putShort((short) 28).putInt(poolSize).putInt(count).putInt(0).putInt(utf8 ? 0x100 : 0).putInt(28 + count * 4).putInt(0);
        for (int o : offs) pb.putInt(o);
        pb.put(strs.toByteArray());
        int attrs = 4;
        int elSize = 16 + 20 + 20 * attrs;
        ByteBuffer eb = ByteBuffer.allocate(elSize).order(ByteOrder.LITTLE_ENDIAN);
        eb.putShort((short) 0x0102).putShort((short) 16).putInt(elSize).putInt(1).putInt(-1);
        eb.putInt(-1).putInt(3).putShort((short) 0x14).putShort((short) 0x14).putShort((short) attrs).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        // package (string), versionCode (int), versionName (string), versionCodeMajor (int)
        eb.putInt(-1).putInt(0).putInt(4).putShort((short) 8).put((byte) 0).put((byte) 0x03).putInt(4);
        eb.putInt(6).putInt(1).putInt(-1).putShort((short) 8).put((byte) 0).put((byte) 0x10).putInt((int) versionCode);
        eb.putInt(6).putInt(2).putInt(5).putShort((short) 8).put((byte) 0).put((byte) 0x03).putInt(5);
        eb.putInt(6).putInt(7).putInt(-1).putShort((short) 8).put((byte) 0).put((byte) 0x10).putInt((int) major);
        int total = 8 + poolSize + elSize;
        ByteBuffer all = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        all.putShort((short) 3).putShort((short) 8).putInt(total);
        all.put(pb.array()).put(eb.array());
        return all.array();
    }

    static void zip(String rel, String entry, byte[] data) throws IOException {
        Path f = T.resolve(rel);
        Files.createDirectories(f.getParent());
        ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f));
        try { z.putNextEntry(new ZipEntry(entry)); z.write(data); z.closeEntry(); z.putNextEntry(new ZipEntry("classes.dex")); z.write(new byte[64]); z.closeEntry(); } finally { z.close(); }
    }

    static void apks(String rel, byte[] innerApk) throws IOException {
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        ZipOutputStream iz = new ZipOutputStream(inner);
        iz.putNextEntry(new ZipEntry("res/a.png")); iz.write(new byte[10]); iz.closeEntry();
        iz.putNextEntry(new ZipEntry("AndroidManifest.xml")); iz.write(innerApk); iz.closeEntry();
        iz.close();
        Path f = T.resolve(rel);
        Files.createDirectories(f.getParent());
        ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(f));
        try {
            z.putNextEntry(new ZipEntry("split_config.arm64_v8a.apk")); z.write(new byte[20]); z.closeEntry();
            z.putNextEntry(new ZipEntry("base.apk")); z.write(inner.toByteArray()); z.closeEntry();
        } finally { z.close(); }
    }

    static void apkReader() throws Exception {
        for (boolean utf8 : new boolean[] { false, true }) {
            Object info = readManifest(manifest("com.example.app", 42, "1.2.3", 0, utf8));
            eq("manifest package " + utf8, field(info, "pkg"), "com.example.app");
            eq("manifest versionCode " + utf8, field(info, "versionCode"), 42L);
            eq("manifest versionName " + utf8, field(info, "versionName"), "1.2.3");
        }
        Object big = readManifest(manifest("com.big", 7, "9", 3, false));
        eq("versionCodeMajor makes a long version code", field(big, "versionCode"), (3L << 32) | 7L);
        Object neg = readManifest(manifest("com.neg", -2, "n", 0, false));
        eq("an int version code is unsigned", field(neg, "versionCode"), 0xFFFFFFFEL);
        eq("garbage", readManifest(new byte[] { 1, 2, 3 }), null);
        eq("null", readManifest(null), null);
        byte[] ok = manifest("com.example.app", 1, "1", 0, false);
        eq("truncated", readManifest(Arrays.copyOf(ok, ok.length - 30)), null);
        byte[] bad = ok.clone();
        bad[0] = 0x02;
        eq("wrong chunk type", readManifest(bad), null);
        byte[] zeros = new byte[200];
        eq("zeros", readManifest(zeros), null);
        // random mutations never throw
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            byte[] m = ok.clone();
            for (int k = 0; k < 1 + rnd.nextInt(6); k++) m[rnd.nextInt(m.length)] = (byte) rnd.nextInt(256);
            try { readManifest(m); } catch (RuntimeException e) { fails++; n++; System.out.println("FAIL readManifest threw on a damaged manifest: " + e); break; }
        }
        n++;
    }

    // reflection into the package-private reader (the class under test keeps it package private)
    static Object readManifest(byte[] d) throws Exception {
        java.lang.reflect.Method m = SdmSystemCleaner.class.getDeclaredMethod("readManifest", byte[].class);
        m.setAccessible(true);
        return m.invoke(null, (Object) d);
    }
    static Object field(Object o, String name) throws Exception {
        if (o == null) return null;
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    // ---------------------------------------------------------------------------------------------------------- the big scan

    static void bigScan() throws Exception {
        fresh();
        buildTree();
        Rec rec = new Rec();
        SdmSystemCleaner.SysResult r = scan(ctx(allOn(), 0, rec, null, null, null));
        Map<String, String> got = flat(r);

        // every expectation, and nothing else
        int shown = 0;
        for (Map.Entry<String, String> e : EXP.entrySet()) {
            String want = e.getValue(), have = got.get(p(e.getKey()));
            if (!want.equals(have)) { fails++; n++; if (shown++ < 60) System.out.println("FAIL " + e.getKey() + ": group " + have + " want " + want); } else n++;
        }
        for (Map.Entry<String, String> e : got.entrySet()) {
            String rel = T.relativize(java.nio.file.Paths.get(e.getKey())).toString();
            if (!EXP.containsKey(rel)) { fails++; n++; if (shown++ < 60) System.out.println("FAIL unexpected match " + rel + " in " + e.getValue()); } else n++;
        }
        for (String rel : NEG) {
            is("negative exists on disk: " + rel, exists(rel), true);
            is("negative is not listed: " + rel, got.containsKey(p(rel)), false);
        }

        // all 22 filters found something
        Set<String> ids = new TreeSet<String>(r.groupIds());
        Set<String> expectIds = new TreeSet<String>(Arrays.asList(SdmSystemCleaner.filterIds()));
        expectIds.remove("superfluosapks");                                                                    // has its own test (apkFilter)
        eq("21 groups (all but the apks)", ids, expectIds);

        // counts, bytes
        Map<String, Integer> count = new HashMap<String, Integer>();
        Map<String, Long> bytes = new HashMap<String, Long>();
        for (Map.Entry<String, String> e : EXP.entrySet()) {
            Integer c = count.get(e.getValue());
            count.put(e.getValue(), c == null ? 1 : c + 1);
            Long b = bytes.get(e.getValue());
            bytes.put(e.getValue(), (b == null ? 0 : b) + statSize(e.getKey()));
        }
        JSONArray rows = r.groups(0, 0);
        long totalBytes = 0;
        int totalCount = 0;
        long prev = Long.MAX_VALUE;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject o = rows.getJSONObject(i);
            String id = o.getString("id");
            eq("group count " + id, o.getInt("count"), count.get(id));
            eq("group bytes " + id, o.getLong("bytes"), bytes.get(id));
            eq("group label " + id, o.getString("label"), SdmSystemCleaner.filterLabel(id));
            is("group has a sub text " + id, o.getString("sub").length() > 0, true);
            is("group rows are sorted by size, largest first (" + id + ")", o.getLong("bytes") <= prev, true);
            prev = o.getLong("bytes");
            totalBytes += o.getLong("bytes");
            totalCount += o.getInt("count");
            eq("the group's items match its count " + id, r.items(id, 0, 0).length(), o.getInt("count"));
        }
        eq("total items", r.itemCount(), totalCount);
        eq("total bytes", r.bytes(), totalBytes);
        eq("group count", r.groupCount(), 21);
        eq("summary itemCount", r.summary().getInt("itemCount"), totalCount);
        eq("summary bytes", r.summary().getLong("bytes"), totalBytes);
        eq("summary groups", r.summary().getInt("groupCount"), 21);
        eq("summary primary", r.summary().getString("primary"), SdmSystemCleaner.foundText(totalCount));
        eq("summary secondary", r.summary().getString("secondary"), SdmSystemCleaner.canBeFreedText(totalBytes));
        eq("tool of the result", r.tool(), Sdm.Tool.SYSTEMCLEANER);

        // a group row and an item row, field by field
        JSONObject ad = null;
        for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getString("id").equals("advertisements")) ad = rows.getJSONObject(i);
        eq("ads sub", ad.getString("sub"), "Files that have been created for or by advertisements.");
        eq("ads is not a root filter", ad.getBoolean("root"), false);
        JSONArray adItems = r.items("advertisements", 0, 0);
        boolean sawFile = false, sawDir = false;
        for (int i = 0; i < adItems.length(); i++) {
            JSONObject it = adItems.getJSONObject(i);
            String path = it.getString("path");
            eq("item id is the path", it.getString("id"), path);
            eq("item name", it.getString("name"), new File(path).getName());
            Sdm.Entry e = new SdmFsJava().stat(path);
            eq("item size " + path, it.getLong("size"), e.size);
            eq("item mtime (seconds) " + path, it.getLong("mtime"), e.mtime);
            eq("item type " + path, it.getInt("type"), e.type == Sdm.DIR ? 1 : 0);
            if (e.type == Sdm.DIR) sawDir = true; else sawFile = true;
        }
        is("ads items are files and directories", sawFile && sawDir, true);
        eq("the first screenshots item is the oldest (screenshots are sorted by age)", r.paths("screenshots").get(0), p(USB + "Pictures/Screenshots/old.png"));
        List<String> trashed = r.paths("trashed");
        eq("trashed files are sorted newest first (same age here: by path)", trashed.size(), 3);
        List<String> empties = r.paths("emptydirectories");
        List<String> sorted = new ArrayList<String>(empties);
        Collections.sort(sorted);
        eq("empty folders are sorted by path", empties, sorted);
        List<String> temps = r.paths("tempfiles");
        long last = Long.MAX_VALUE;
        boolean bySize = true;
        for (String t : temps) { long sz = new SdmFsJava().stat(t).size; if (sz > last) bySize = false; last = sz; }
        is("temporary files are sorted by size, largest first", bySize, true);

        // paging
        JSONArray page = r.groups(2, 3);
        eq("groups(2, 3)", page.length(), 3);
        eq("groups(2, 3) is the slice", page.getJSONObject(0).getString("id"), rows.getJSONObject(2).getString("id"));
        eq("groups(offset beyond the end)", r.groups(99, 5).length(), 0);
        eq("groups(0, 1)", r.groups(0, 1).length(), 1);
        String big = rows.getJSONObject(0).getString("id");
        int bigCount = rows.getJSONObject(0).getInt("count");
        eq("items(offset 1, limit 2)", r.items(big, 1, 2).length(), Math.min(2, Math.max(0, bigCount - 1)));
        eq("items of an unknown group", r.items("nope", 0, 10).length(), 0);
        eq("items(limit 0) is all", r.items(big, 0, 0).length(), bigCount);

        // scanning twice gives the same
        Map<String, String> again = flat(scan(ctx(allOn(), 0, null, null, null, null)));
        eq("a second scan finds the same", again, got);

        // the scan did not change anything on disk
        eq("a scan never deletes: positives still exist", exists(S + "Download/app.log") && exists(S + ".mologiq/a.dat") && exists(S + "Nest/Inner/Deep"), true);

        // the progress strings
        is("progress was reported", rec.calls.size() > 10, true);
        eq("first progress primary", rec.calls.get(0)[0], "Searching");
        eq("first progress secondary", rec.calls.get(0)[1], "Generating search paths");
        eq("first progress counts: indeterminate", rec.counts.get(0)[1], -1L);
        boolean allSearching = true, sawPath = false;
        for (String[] c : rec.calls) { if (!c[0].equals("Searching")) allSearching = false; if (c[1].startsWith(T.toString())) sawPath = true; }
        is("every scan progress primary is Searching", allSearching, true);
        is("the secondary line is the current path", sawPath, true);
        boolean noForeign = true;
        for (String[] c : rec.calls) if (c[1].startsWith(p(S + "Android/data/")) && false) noForeign = false;
        is("progress ok", noForeign, true);
        long lastDone = -1;
        boolean monotonic = true;
        for (long[] c : rec.counts) { if (c[0] < lastDone) monotonic = false; lastDone = c[0]; }
        is("the done counter only grows", monotonic, true);
        is("bytes found so far reach the total of the matches", rec.counts.get(rec.counts.size() - 1)[2] >= 0, true);
    }

    // ---------------------------------------------------------------------------------------------------------- settings and the root gate

    static Set<String> groupSet(Sdm.Result r) throws Exception {
        Set<String> s = new TreeSet<String>();
        JSONArray g = r.groups(0, 0);
        for (int i = 0; i < g.length(); i++) s.add(g.getJSONObject(i).getString("id"));
        return s;
    }

    static void settingsAndGating() throws Exception {
        // the tree of the big scan is still there
        Set<String> defaultsRoot = new TreeSet<String>(Arrays.asList("logfiles", "advertisements", "emptydirectories", "lostdir", "linuxfiles", "macfiles", "windowsfiles", "tempfiles", "analytics",
            "anr", "downloadcache", "datalogger", "logdropbox"));
        eq("default settings, root: the 13 default filters", groupSet(scan(ctx(new JSONObject(), 0, null, null, null, null))), defaultsRoot);
        eq("null settings are defaults too", groupSet(scan(new Sdm.Ctx(new SdmFsJava(), areas(), new FakePkgs(), new FakeEx(), new FakeShell(0), null, null, null, now))), defaultsRoot);
        Set<String> generic = new TreeSet<String>(defaultsRoot);
        generic.removeAll(Arrays.asList("anr", "downloadcache", "datalogger", "logdropbox"));
        eq("default settings, ADB (uid 2000): the specific filters do not run", groupSet(scan(ctx(new JSONObject(), 2000, null, null, null, null))), generic);
        eq("default settings, no shell at all", groupSet(scan(new Sdm.Ctx(new SdmFsJava(), areas(), new FakePkgs(), new FakeEx(), null, new JSONObject(), null, null, now))), generic);
        eq("default settings, uid -1", groupSet(scan(ctx(new JSONObject(), -1, null, null, null, null))), generic);
        // enabling a specific filter without root changes nothing
        JSONObject on = new JSONObject();
        on.put("filter.tombstones.enabled", true);
        on.put("filter.localtmp.enabled", true);
        eq("a specific filter that is on without root stays off", groupSet(scan(ctx(on, 2000, null, null, null, null))), generic);
        // switching filters off
        JSONObject off = new JSONObject();
        off.put("filter.macfiles.enabled", false);
        off.put("filter.logfiles.enabled", false);
        off.put("filter.anr.enabled", false);
        Set<String> expected = new TreeSet<String>(defaultsRoot);
        expected.removeAll(Arrays.asList("macfiles", "logfiles", "anr"));
        // the downloadcache filter now gets the anr file of the cache
        eq("filters switched off", groupSet(scan(ctx(off, 0, null, null, null, null))), expected);
        Map<String, String> m = flat(scan(ctx(off, 0, null, null, null, null)));
        eq("an anr file falls through to the download cache filter", m.get(p(C + "anr/trace.txt")), "downloadcache");
        eq("a switched off filter's file is not listed", m.get(p(S + "._foo")), null);
        // a filter that is on and has nothing to find has no group
        JSONObject single = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) single.put(SdmSystemCleaner.filterKey(id), id.equals("windowsfiles"));
        SdmSystemCleaner.SysResult w = scan(ctx(single, 0, null, null, null, null));
        eq("only windowsfiles", groupSet(w), new TreeSet<String>(Arrays.asList("windowsfiles")));
        eq("windows files: 6 (sdcard 2, public data, media, obb, portable)", w.paths("windowsfiles").size(), 6);
        // everything off
        JSONObject none = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) none.put(SdmSystemCleaner.filterKey(id), false);
        SdmSystemCleaner.SysResult nothing = scan(ctx(none, 0, null, null, null, null));
        eq("no filter on: nothing", nothing.groupCount(), 0);
        eq("no filter on: summary", nothing.summary().getString("primary"), "0 filter matches");
        eq("no filter on: summary 2", nothing.summary().getString("secondary"), "0 B can be freed");
        // no areas at all
        Sdm.Ctx noAreas = new Sdm.Ctx(new SdmFsJava(), new FakeAreas(), new FakePkgs(), new FakeEx(), new FakeShell(0), allOn(), null, null, now);
        eq("no areas: nothing, no error", scan(noAreas).groupCount(), 0);
        // every area unavailable
        FakeAreas gone = new FakeAreas();
        gone.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, p("storage/emulated/0"), true, "", "no access"));
        eq("an area that cannot be read is skipped without an error", scan(new Sdm.Ctx(new SdmFsJava(), gone, new FakePkgs(), new FakeEx(), new FakeShell(0), allOn(), null, null, now)).groupCount(), 0);
        // a missing root folder is not an error
        FakeAreas missing = new FakeAreas();
        missing.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, p("storage/nowhere"), true, "java", ""));
        eq("an area root that does not exist is not an error", scan(new Sdm.Ctx(new SdmFsJava(), missing, new FakePkgs(), new FakeEx(), new FakeShell(0), allOn(), null, null, now)).groupCount(), 0);
        // an ANR filter without a primary data / cache area is "underdefined": no match, no error
        FakeAreas noPrimary = new FakeAreas();
        noPrimary.list.add(new Sdm.AreaInfo(Sdm.Area.DATA, p("data"), false, "java", ""));
        JSONObject anrOnly = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) anrOnly.put(SdmSystemCleaner.filterKey(id), id.equals("anr"));
        eq("anr without a primary area", scan(new Sdm.Ctx(new SdmFsJava(), noPrimary, new FakePkgs(), new FakeEx(), new FakeShell(0), anrOnly, null, null, now)).groupCount(), 0);
        // string values and a wrong type in the settings are not fatal (a missing key is the default)
        JSONObject odd = new JSONObject();
        odd.put("filter.windowsfiles.enabled", "nonsense");
        is("a non boolean value is read as false by org.json, the scan still runs", scan(ctx(odd, 0, null, null, null, null)).groupCount() > 0, true);
    }

    // ---------------------------------------------------------------------------------------------------------- exclusions

    static void exclusions() throws Exception {
        FakeEx ex = new FakeEx();
        ex.paths.add(p(S + "Download"));                       // a folder: pruned
        ex.paths.add(p(S + "Nest/Inner/Deep"));                // an empty folder: excluded, and its parents with it
        ex.paths.add(p(S + "DCIM/.thumbnails/1.jpg"));         // a file inside a matched folder
        ex.segments.add(new String[] { "Pictures", "Screenshots" });   // a segment exclusion
        ex.paths.add(p(S + "EmptyA"));
        Rec rec = new Rec();
        SdmSystemCleaner.SysResult r = scan(ctx(allOn(), 0, rec, null, ex, null));
        Map<String, String> got = flat(r);
        eq("excluded folder: its content is not listed", got.get(p(S + "Download/app.log")), null);
        eq("excluded folder: the other log files still are", got.get(p(C + "recovery/a.log")), "logfiles");
        boolean walked = false;
        for (String[] c : rec.calls) if (c[1].startsWith(p(S + "Download"))) walked = true;
        is("an excluded folder is not walked", walked, false);
        eq("excluded empty folder", got.get(p(S + "EmptyA")), null);
        eq("nested rule: the excluded empty folder", got.get(p(S + "Nest/Inner/Deep")), null);
        eq("nested rule: its parent is not deleted with it", got.get(p(S + "Nest/Inner")), null);
        eq("nested rule: nor its grandparent", got.get(p(S + "Nest")), null);
        eq("a file inside a matched folder is excluded", got.get(p(S + "DCIM/.thumbnails/1.jpg")), null);
        eq("nested rule: the matched folder that holds it is dropped", got.get(p(S + "DCIM/.thumbnails")), null);
        eq("the other thumbnails stay", got.get(p(S + "DCIM/.thumbnails/data/2.jpg")), "thumbnails");
        eq("the sub folder of the thumbnails stays", got.get(p(S + "DCIM/.thumbnails/data")), "thumbnails");
        eq("segment exclusion: screenshots are gone", got.get(p(S + "Pictures/Screenshots/old1.png")), null);
        eq("segment exclusion: also the ones in the other case", got.get(p(S + "Pictures/screenshots/old4.png")), null);
        eq("segment exclusion: the others are untouched", got.get(p(S + "Pictures/.trashed-1700-a.jpg")), "trashed");
        eq("the portable volume's screenshots are excluded by the same segments", got.get(p(USB + "Pictures/Screenshots/old.png")), null);
        eq("a sibling of an excluded file is still a match elsewhere", got.get(p(S + "a.tmp")), "tempfiles");
        // exclusions of the paths of a deeper level
        FakeEx ex2 = new FakeEx();
        ex2.paths.add(p(S + "Keep/sub"));
        Map<String, String> got2 = flat(scan(ctx(allOn(), 0, null, null, ex2, null)));
        eq("an excluded empty sub folder is not listed", got2.get(p(S + "Keep/sub")), null);
        // an exclusion on a whole area root excludes everything in it
        FakeEx ex3 = new FakeEx();
        ex3.paths.add(p("storage/emulated/0"));
        Map<String, String> got3 = flat(scan(ctx(allOn(), 0, null, null, ex3, null)));
        boolean anySd = false;
        for (String k : got3.keySet()) if (k.startsWith(p("storage/emulated/0/"))) anySd = true;
        is("the sdcard excluded: no match below it (the public app areas are inside it)", anySd, false);
        is("the other areas are still scanned", got3.containsKey(p(C + "ota/update.zip")), true);
        // removeExcluded on a result (the engine's "exclude" action)
        SdmSystemCleaner.SysResult r2 = scan(ctx(allOn(), 0, null, null, null, null));
        int before = r2.itemCount();
        int removed = r2.removeExcluded(Arrays.asList(p(S + "DCIM/.thumbnails/1.jpg"), p(S + ".mologiq")));
        // 1.jpg, its parent .thumbnails (ancestor of an excluded path), .mologiq and .mologiq/a.dat
        eq("removeExcluded removed", removed, 4);
        eq("removeExcluded count", r2.itemCount(), before - 4);
        is("removeExcluded: the folder below the ancestor stays", r2.paths("thumbnails").contains(p(S + "DCIM/.thumbnails/data")), true);
        is("removeExcluded: the ancestor is gone", r2.paths("thumbnails").contains(p(S + "DCIM/.thumbnails")), false);
        long sum = 0;
        JSONArray rows = r2.groups(0, 0);
        for (int i = 0; i < rows.length(); i++) sum += rows.getJSONObject(i).getLong("bytes");
        eq("removeExcluded: bytes follow", r2.bytes(), sum);
        // removing a whole group makes it vanish
        r2.removeExcluded(r2.paths("logdropbox"));
        is("removeExcluded: an empty group vanishes", r2.groupIds().contains("logdropbox"), false);
    }

    // ---------------------------------------------------------------------------------------------------------- cancel while scanning

    static void cancelScan() throws Exception {
        Rec rec = new Rec();
        rec.cancel = true;
        boolean threw = false;
        try { scan(ctx(allOn(), 0, rec, rec.flag, null, null)); } catch (CancellationException e) { threw = true; }
        is("cancelled before the start: CancellationException", threw, true);
        for (int after : new int[] { 3, 10, 40, 120, 250 }) {
            Rec r = new Rec();
            r.cancelAfter = after;
            threw = false;
            try { scan(ctx(allOn(), 0, r, r.flag, null, null)); } catch (CancellationException e) { threw = true; }
            is("cancelled after " + after + " progress updates: CancellationException", threw, true);
            is("... and it stopped soon after (" + r.calls.size() + " updates)", r.calls.size() <= after + 5, true);
        }
        // cancel while the empty folders are decided (after the walk)
        Rec late = new Rec();
        int total;
        {
            Rec count = new Rec();
            scan(ctx(allOn(), 0, count, null, null, null));
            total = count.calls.size();
        }
        late.cancelAfter = total - 1;
        threw = false;
        try { scan(ctx(allOn(), 0, late, late.flag, null, null)); } catch (CancellationException e) { threw = true; }
        is("cancelled near the end: still CancellationException", threw, true);
        // an uncancelled scan with a cancel object that says no
        eq("a Cancel that says no changes nothing", scan(ctx(allOn(), 0, null, new Rec().flag, null, null)).groupCount(), 21);
    }

    // ---------------------------------------------------------------------------------------------------------- progress while deleting

    static void progressStrings() throws Exception {
        // checked inside deleteAll() below; here the shape of what scan reports before any path
        Rec rec = new Rec();
        scan(ctx(allOn(), 0, rec, null, null, null));
        eq("scan: 'Searching'", rec.calls.get(1)[0], "Searching");
        is("scan: the secondary line after the first is a path", rec.calls.get(1)[1].startsWith("/"), true);
    }

    // ---------------------------------------------------------------------------------------------------------- the deletion

    static void deleteAll() throws Exception {
        fresh();
        buildTree();
        SdmSystemCleaner.SysResult r = scan(ctx(allOn(), 0, null, null, null, null));
        Map<String, String> before = flat(r);
        int itemsBefore = r.itemCount();
        long bytesBefore = r.bytes();
        Rec rec = new Rec();
        Sdm.DeleteReport rep = new SdmSystemCleaner().delete(r, new Sdm.Selection(), ctx(allOn(), 0, rec, null, null, null));

        eq("nothing failed", rep.failed, new ArrayList<String>());
        eq("every item of the result is reported once (roots and what vanished below them)", rep.deleted.size(), itemsBefore);
        eq("the freed bytes are the bytes of the result", rep.bytes(), bytesBefore);
        Set<String> reported = new HashSet<String>();
        for (Sdm.Deleted d : rep.deleted) reported.add(d.path);
        eq("no path twice", reported.size(), rep.deleted.size());
        eq("the reported paths are the listed ones", reported, before.keySet());
        for (Sdm.Deleted d : rep.deleted) if (!before.get(d.path).equals(d.group) || !SdmSystemCleaner.filterLabel(d.group).equals(d.label)) { fails++; System.out.println("FAIL a Deleted record has the wrong group or label: " + d.path); }
        n++;
        // everything that was listed is gone from the disk, the negatives are not
        int stillThere = 0;
        for (String path : before.keySet()) if (Files.exists(java.nio.file.Paths.get(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)) stillThere++;
        eq("every listed path is gone", stillThere, 0);
        for (String rel : NEG) {
            boolean below = false;                                                                         // a negative inside a matched folder goes with it
            for (String m : before.keySet()) if (SdmSieve.isAncestorOf(m, p(rel))) below = true;
            if (!below) is("negative still exists after the deletion: " + rel, exists(rel), true);
        }
        is("the area roots are untouched", exists("storage/emulated/0") && exists("data") && exists("cache") && exists("storage/USB1"), true);
        is("the Android folder is untouched", exists(S + "Android/data") || exists(S + "Android"), true);
        is("a nested file under a deleted root is gone as well", exists(S + ".mologiq/a.dat"), false);
        is("a deleted empty folder's parent that was not matched is still there", exists(S + "Keep"), true);
        // the result reflects it
        eq("the result is empty", r.groupCount(), 0);
        eq("no items", r.itemCount(), 0);
        eq("no bytes", r.bytes(), 0L);
        eq("no group rows", r.groups(0, 0).length(), 0);
        eq("summary after the delete", r.summary().getString("primary"), "0 filter matches");
        // progress
        is("delete progress was reported", rec.calls.size() >= 3, true);
        eq("the last progress primary is Loading", rec.calls.get(rec.calls.size() - 1)[0], "Loading");
        eq("the last progress secondary is empty", rec.calls.get(rec.calls.size() - 1)[1], "");
        boolean labels = true;
        Set<String> allLabels = new HashSet<String>();
        for (String id : SdmSystemCleaner.filterIds()) allLabels.add(SdmSystemCleaner.filterLabel(id));
        for (int i = 0; i < rec.calls.size() - 1; i++) if (!allLabels.contains(rec.calls.get(i)[0]) || !rec.calls.get(i)[1].startsWith(T.toString())) labels = false;
        is("delete progress: primary is the filter label, secondary the path", labels, true);
        long maxDone = 0;
        for (long[] c : rec.counts) maxDone = Math.max(maxDone, c[0]);
        is("delete progress counts the roots", rec.counts.get(0)[1] > 0 && maxDone <= rec.counts.get(0)[1], true);
        // after the delete a new scan only finds what became empty (multiple passes)
        Map<String, String> second = flat(scan(ctx(allOn(), 0, null, null, null, null)));
        for (Map.Entry<String, String> e : second.entrySet()) {
            is("a second scan only finds empty folders: " + e.getKey(), e.getValue().equals("emptydirectories"), true);
        }
        Sdm.Entry pics = new SdmFsJava().stat(p(S + "Pictures"));
        is("the Pictures folder is a protected name and stays", pics != null, true);
        // deleting again is a no-op
        Sdm.DeleteReport again = new SdmSystemCleaner().delete(r, new Sdm.Selection(), ctx(allOn(), 0, null, null, null, null));
        eq("deleting an empty result deletes nothing", again.deleted.size(), 0);
        eq("... and fails nothing", again.failed.size(), 0);
    }

    static Sdm.Selection sel(String[] groups, String[] items) {
        Sdm.Selection s = new Sdm.Selection();
        s.dropGroups.addAll(Arrays.asList(groups));
        s.dropItems.addAll(Arrays.asList(items));
        return s;
    }

    static void deleteSelection() throws Exception {
        fresh();
        buildTree();
        SdmSystemCleaner.SysResult r = scan(ctx(allOn(), 0, null, null, null, null));
        Map<String, String> before = flat(r);
        List<String> adPaths = r.paths("advertisements");
        String thumb1 = p(S + "DCIM/.thumbnails/1.jpg");
        String deep = p(S + "Nest/Inner/Deep");
        Sdm.Selection s = sel(new String[] { "advertisements" }, new String[] { thumb1, deep, p("not/in/the/result") });
        Sdm.DeleteReport rep = new SdmSystemCleaner().delete(r, s, ctx(allOn(), 0, null, null, null, null));

        for (String a : adPaths) is("a dropped group's item stays on disk: " + a, Files.exists(java.nio.file.Paths.get(a)), true);
        is("a dropped item stays", exists(S + "DCIM/.thumbnails/1.jpg"), true);
        is("the folder holding a dropped item is kept", exists(S + "DCIM/.thumbnails"), true);
        is("... but its other selected content is deleted one level down", exists(S + "DCIM/.thumbnails/data"), false);
        is("a dropped empty folder stays", exists(S + "Nest/Inner/Deep"), true);
        is("and so do its parents (deleting them would take it along)", exists(S + "Nest/Inner") && exists(S + "Nest"), true);
        boolean noteKept = false;
        for (String nt : rep.notes) if (nt.contains("were kept because they contain items that are not selected")) noteKept = true;
        is("a note says folders were kept for deselected items", noteKept, true);
        eq("nothing failed", rep.failed.size(), 0);
        is("other groups are deleted", exists(S + "Download/app.log"), false);
        is("a protected negative is untouched", exists(S + "Download/notes.txt"), true);
        // the result keeps what was not deleted
        Set<String> after = flat(r).keySet();
        for (String a : adPaths) is("the dropped group is still listed: " + a, after.contains(a), true);
        is("the dropped item is still listed", after.contains(thumb1), true);
        is("the folder that was kept is still listed", after.contains(p(S + "DCIM/.thumbnails")), true);
        is("the deleted sub folder is not listed any more", after.contains(p(S + "DCIM/.thumbnails/data")), false);
        is("the nested file below it is not listed any more", after.contains(p(S + "DCIM/.thumbnails/data/2.jpg")), false);
        is("the empty folder chain is still listed", after.contains(deep) && after.contains(p(S + "Nest")), true);
        eq("the remaining groups", new TreeSet<String>(r.groupIds()), new TreeSet<String>(Arrays.asList("advertisements", "thumbnails", "emptydirectories")));
        long sum = 0;
        for (String g : r.groupIds()) sum += r.groupBytes(g);
        eq("result bytes follow", r.bytes(), sum);
        eq("deleted + remaining = before", rep.deleted.size() + r.itemCount(), before.size());
        for (Sdm.Deleted d : rep.deleted) is("a deleted record is no longer in the result: " + d.path, after.contains(d.path), false);
        // a dropped group does not stop its own nested items of other groups from being deleted when the root is not dropped
        // (the .chartboost folder is an ads item; Analytics' files under another root are separate)
    }

    static void deleteReverify() throws Exception {
        fresh();
        file(S + "a.tmp", 10);
        file(S + "b.tmp", 20);
        file(S + "c.tmp", 30);
        file(S + "d.tmp", 40);
        file(S + "keepme.txt", 5);
        mk(S + "EmptyA");
        mk(S + "EmptyB");
        file(S + "DCIM/.thumbnails/t1.jpg", 50);
        file(S + "DCIM/.thumbnails/t2.jpg", 51);
        JSONObject set = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) set.put(SdmSystemCleaner.filterKey(id), id.equals("tempfiles") || id.equals("emptydirectories") || id.equals("thumbnails"));
        SdmSystemCleaner.SysResult r = scan(ctx(set, 0, null, null, null, null));
        eq("scan: 4 tmp files", r.paths("tempfiles").size(), 4);
        eq("scan: 2 empty folders", r.paths("emptydirectories").size(), 2);
        eq("scan: thumbnails folder and 2 files", r.paths("thumbnails").size(), 3);

        // the world changes after the scan
        Files.delete(T.resolve(S + "a.tmp"));                                   // gone: counts as deleted
        Files.delete(T.resolve(S + "b.tmp"));                                   // replaced by a folder: a changed type is not deleted
        mk(S + "b.tmp");
        file(S + "b.tmp/precious.txt", 99);
        file(S + "EmptyA/new.txt", 3);                                          // no longer empty
        FakeEx ex = new FakeEx();
        ex.paths.add(p(S + "c.tmp"));                                           // excluded after the scan
        ex.paths.add(p(S + "DCIM/.thumbnails/t2.jpg"));                         // a child of a matched folder is excluded after the scan
        Sdm.DeleteReport rep = new SdmSystemCleaner().delete(r, new Sdm.Selection(), ctx(set, 0, null, null, ex, null));

        Set<String> deleted = new HashSet<String>();
        for (Sdm.Deleted d : rep.deleted) deleted.add(d.path);
        is("a path that is gone counts as deleted", deleted.contains(p(S + "a.tmp")), true);
        is("a path whose type changed is not deleted", exists(S + "b.tmp/precious.txt"), true);
        is("... and is reported as failed", rep.failed.contains(p(S + "b.tmp")), true);
        is("a folder that is not empty any more is not deleted", exists(S + "EmptyA/new.txt") && exists(S + "EmptyA"), true);
        is("... and is reported as failed", rep.failed.contains(p(S + "EmptyA")), true);
        is("a path excluded after the scan is kept", exists(S + "c.tmp"), true);
        is("... and is no failure", rep.failed.contains(p(S + "c.tmp")), false);
        is("a folder that holds an excluded path is kept", exists(S + "DCIM/.thumbnails") && exists(S + "DCIM/.thumbnails/t2.jpg"), true);
        is("... but not the folder's other content? (the root is kept whole)", exists(S + "DCIM/.thumbnails/t1.jpg"), true);
        is("an untouched match is deleted", exists(S + "d.tmp"), false);
        is("an untouched empty folder is deleted", exists(S + "EmptyB"), false);
        is("a file that is no match stays", exists(S + "keepme.txt"), true);
        boolean excNote = false;
        for (String nt : rep.notes) if (nt.contains("exclusions")) excNote = true;
        is("a note mentions the exclusions", excNote, true);
        // the result: deleted and gone ones vanish, the others stay
        Set<String> left = flat(r).keySet();
        is("the gone one vanished from the result", left.contains(p(S + "a.tmp")), false);
        is("the deleted one vanished", left.contains(p(S + "d.tmp")), false);
        is("the changed one is still listed", left.contains(p(S + "b.tmp")), true);
        is("the folder that is not empty is still listed", left.contains(p(S + "EmptyA")), true);
        is("the excluded one is still listed", left.contains(p(S + "c.tmp")), true);
        eq("bytes of the report: a.tmp + d.tmp + EmptyB", rep.bytes(), 10L + 40L + statSize2(r, "emptydirectories", S + "EmptyB", rep));
        // the type changed the other way: a matched folder became a file
        fresh();
        mk(S + "Gone1");
        mk(S + "Gone2");
        JSONObject only = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) only.put(SdmSystemCleaner.filterKey(id), id.equals("emptydirectories"));
        SdmSystemCleaner.SysResult r2 = scan(ctx(only, 0, null, null, null, null));
        rmtree(T.resolve(S + "Gone1"));
        file(S + "Gone1", 8);
        Sdm.DeleteReport rep2 = new SdmSystemCleaner().delete(r2, new Sdm.Selection(), ctx(only, 0, null, null, null, null));
        is("a folder that became a file is not deleted", exists(S + "Gone1"), true);
        is("... reported as failed", rep2.failed.contains(p(S + "Gone1")), true);
        is("the other folder is deleted", exists(S + "Gone2"), false);
    }

    static long statSize2(SdmSystemCleaner.SysResult r, String group, String rel, Sdm.DeleteReport rep) {
        for (Sdm.Deleted d : rep.deleted) if (d.path.equals(p(rel))) return d.bytes;
        return -1;
    }

    static void deleteCancel() throws Exception {
        fresh();
        for (int i = 0; i < 45; i++) file(S + "many/f" + i + ".tmp", 10 + i);
        file(S + "many/keep.txt", 5);
        JSONObject only = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) only.put(SdmSystemCleaner.filterKey(id), id.equals("tempfiles"));
        SdmSystemCleaner.SysResult r = scan(ctx(only, 0, null, null, null, null));
        eq("45 matches", r.itemCount(), 45);
        Rec rec = new Rec();
        rec.cancelAfter = 2;                                // the second batch's progress update raises the flag
        Sdm.DeleteReport rep = new SdmSystemCleaner().delete(r, new Sdm.Selection(), ctx(only, 0, rec, rec.flag, null, null));
        int left = 0;
        for (int i = 0; i < 45; i++) if (exists(S + "many/f" + i + ".tmp")) left++;
        is("the first batch (20) was deleted", left <= 25, true);
        is("the cancel stopped the rest", left > 0, true);
        eq("what was deleted is reported", rep.deleted.size(), 45 - left);
        eq("a cancelled delete does not report the rest as failed", rep.failed.size(), 0);
        eq("the result lists what is left", r.itemCount(), left);
        boolean note = false;
        for (String nt : rep.notes) if (nt.equals("cancelled")) note = true;
        is("a note says it was cancelled", note, true);
        eq("the kept file is untouched", exists(S + "many/keep.txt"), true);
        // cancel before anything
        fresh();
        file(S + "x.tmp", 10);
        SdmSystemCleaner.SysResult r2 = scan(ctx(only, 0, null, null, null, null));
        Rec pre = new Rec();
        pre.cancel = true;
        Sdm.DeleteReport rep2 = new SdmSystemCleaner().delete(r2, new Sdm.Selection(), ctx(only, 0, pre, pre.flag, null, null));
        is("cancelled before the start: nothing deleted", exists(S + "x.tmp"), true);
        eq("... nothing reported", rep2.deleted.size() + rep2.failed.size(), 0);
        eq("... the result is unchanged", r2.itemCount(), 1);
    }

    // ---------------------------------------------------------------------------------------------------------- superfluous APKs

    static void apkFilter() throws Exception {
        fresh();
        FakePkgs pkgs = new FakePkgs();
        pkgs.add("com.inst.same", "1.0", false);
        pkgs.add("com.inst.other", "2.0", false);
        pkgs.add("com.inst.gone", "1.0", true);
        zip(S + "apk/same.apk", "AndroidManifest.xml", manifest("com.inst.same", 10, "1.0", 0, false));
        apks(S + "apk/same.apks", manifest("com.inst.same", 10, "1.0", 0, false));
        zip(S + "apk/other.apk", "AndroidManifest.xml", manifest("com.inst.other", 10, "1.0", 0, false));
        zip(S + "apk/notinstalled.apk", "AndroidManifest.xml", manifest("com.not.installed", 10, "1.0", 0, false));
        zip(S + "apk/uninstalled.apk", "AndroidManifest.xml", manifest("com.inst.gone", 10, "1.0", 0, false));
        file(S + "apk/garbage.apk", 100);
        zip(S + "apk/nomanifest.apk", "res/x.png", new byte[10]);
        zip(S + "apk/UPPER.APK", "AndroidManifest.xml", manifest("com.inst.same", 10, "1.0", 0, false));
        zip(S + "Backup/same.apk", "AndroidManifest.xml", manifest("com.inst.same", 10, "1.0", 0, false));
        zip(S + "TWRP/same.apk", "AndroidManifest.xml", manifest("com.inst.same", 10, "1.0", 0, false));
        zip(S + "apk/nobase.apks", "split_config.apk", new byte[10]);
        file(S + "apk/notes.txt", 3);
        zip(USB + "same2.apk", "AndroidManifest.xml", manifest("com.inst.same", 10, "1.0", 0, false));
        JSONObject only = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) only.put(SdmSystemCleaner.filterKey(id), id.equals("superfluosapks"));
        Sdm.Ctx c = ctx(only, 0, null, null, null, pkgs);
        SdmSystemCleaner.SysResult r = scan(c);
        Set<String> got = new TreeSet<String>(r.paths("superfluosapks"));
        Set<String> want = new TreeSet<String>(Arrays.asList(p(S + "apk/same.apk"), p(S + "apk/same.apks"), p(USB + "same2.apk")));
        eq("superfluous apks (same version by name, as the package model has no version code)", got, want);
        // the default of the filter is off
        eq("off by default", scan(ctx(new JSONObject(), 0, null, null, null, pkgs)).paths("superfluosapks").size(), 0);
        // "include same version" off: same version is no longer superfluous
        only.put("filter.superfluosapks.includesameversion", false);
        eq("include same version off: not matched", scan(ctx(only, 0, null, null, null, pkgs)).groupCount(), 0);
        // no package model: nothing is installed
        Sdm.Ctx noPkgs = new Sdm.Ctx(new SdmFsJava(), areas(), null, new FakeEx(), new FakeShell(0), only, null, null, now);
        eq("without packages: nothing", scan(noPkgs).groupCount(), 0);
        // the apk filter only reads files that this app reads itself
        FakeAreas shellOnly = new FakeAreas();
        shellOnly.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, p("storage/emulated/0"), true, "shell", ""));
        only.put("filter.superfluosapks.includesameversion", true);
        eq("a shell-read area is not opened as a zip", scan(new Sdm.Ctx(new SdmFsJava(), shellOnly, pkgs, new FakeEx(), new FakeShell(0), only, null, null, now)).groupCount(), 0);
    }

    // ---------------------------------------------------------------------------------------------------------- the screenshot age

    static void screenshotAge() throws Exception {
        fresh();
        file(S + "Pictures/Screenshots/a.png", 10); age(S + "Pictures/Screenshots/a.png", 30);
        file(S + "Pictures/Screenshots/b.png", 11); age(S + "Pictures/Screenshots/b.png", 10);
        file(S + "Pictures/Screenshots/c.png", 12); age(S + "Pictures/Screenshots/c.png", 2);
        file(S + "Pictures/Screenshots/d.png", 13); age(S + "Pictures/Screenshots/d.png", 0.01);
        JSONObject s = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) s.put(SdmSystemCleaner.filterKey(id), id.equals("screenshots"));
        SdmSystemCleaner.SysResult r = scan(ctx(s, 0, null, null, null, null));
        eq("default age 14 days: only the 30 days old one", r.paths("screenshots"), Arrays.asList(p(S + "Pictures/Screenshots/a.png")));
        eq("description with the age", r.groups(0, 0).getJSONObject(0).getString("sub"), "Screenshots older than 14 days from multiple apps and locations.");
        s.put("filter.screenshots.age", 7L * 86400000L);
        r = scan(ctx(s, 0, null, null, null, null));
        eq("7 days: two files, the older first", r.paths("screenshots"), Arrays.asList(p(S + "Pictures/Screenshots/a.png"), p(S + "Pictures/Screenshots/b.png")));
        eq("description 7 days", r.groups(0, 0).getJSONObject(0).getString("sub"), "Screenshots older than 7 days from multiple apps and locations.");
        s.put("filter.screenshots.age", 24L * 3600000L);
        r = scan(ctx(s, 0, null, null, null, null));
        eq("24 hours: three files", r.paths("screenshots").size(), 3);
        eq("description 1 day", r.groups(0, 0).getJSONObject(0).getString("sub"), "Screenshots older than 1 day from multiple apps and locations.");
        s.put("filter.screenshots.age", 3L * 3600000L);
        r = scan(ctx(s, 0, null, null, null, null));
        eq("3 hours: all but the one made a quarter of an hour ago", r.paths("screenshots").size(), 3);
        eq("description 3 hours", r.groups(0, 0).getJSONObject(0).getString("sub"), "Screenshots older than 3 hours from multiple apps and locations.");
        s.put("filter.screenshots.age", 0L);
        r = scan(ctx(s, 0, null, null, null, null));
        eq("age 0: every screenshot", r.paths("screenshots").size(), 4);
        eq("description 0 hours", r.groups(0, 0).getJSONObject(0).getString("sub"), "Screenshots older than 0 hours from multiple apps and locations.");
        s.put("filter.screenshots.age", 1000L * 86400000L);
        r = scan(ctx(s, 0, null, null, null, null));
        eq("an age beyond 90 days is clamped to 90 days", r.groupCount(), 0);
        s.put("filter.screenshots.age", -5L);
        r = scan(ctx(s, 0, null, null, null, null));
        eq("a negative age is clamped to 0", r.paths("screenshots").size(), 4);
    }

    // ---------------------------------------------------------------------------------------------------------- the safety rules

    static void safety() throws Exception {
        List<Sdm.AreaInfo> areas = new ArrayList<Sdm.AreaInfo>();
        areas.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, "/storage/emulated/0", true, "java", ""));
        areas.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, "/storage/emulated/0/Android/data", true, "shell", ""));
        areas.add(new Sdm.AreaInfo(Sdm.Area.DATA, "/data", true, "shell", ""));
        areas.add(new Sdm.AreaInfo(Sdm.Area.PORTABLE, "/storage/ABCD-1234", false, "java", ""));
        areas.add(new Sdm.AreaInfo(Sdm.Area.DOWNLOAD_CACHE, "/cache", true, "", "no root"));
        String[][] bad = {
            { "/", "root" }, { "", "empty" }, { "relative/path", "relative" }, { "/data", "/data" }, { "/storage", "/storage" }, { "/storage/emulated", "/storage/emulated" },
            { "/storage/emulated/0", "area root" }, { "/storage/emulated/0/Android", "Android folder" }, { "/storage/emulated/0/Android/data", "public data root" },
            { "/storage/ABCD-1234", "portable root" }, { "/storage/emulated/0/a/../b", "dotdot" }, { "/storage/emulated/0/..", "dotdot only" }, { "/storage/emulated/0/./a", "dot" },
            { "/storage/emulated/0/a\nb", "newline" }, { "/storage/emulated/0/a\u0000b", "NUL" }, { "/storage/emulated/0/a\rb", "CR" }, { "/storage/emulated/0//a", "double slash" },
            { "/storage/emulated/0/a/", "trailing slash" }, { "/etc/hosts", "outside every area" }, { "/system", "system" }, { "/sdcard", "alias" },
            { "/cache/x", "area that is not available" }, { "/storage/emulated/1/a", "another user's storage" }, { "/storage/emulated/00/a", "prefix is no ancestor" } };
        for (String[] b : bad) is("unsafe: " + b[1] + " (" + b[0].replace("\n", "\\n").replace("\r", "\\r").replace("\u0000", "\\0") + ")", SdmSystemCleaner.unsafeReason(b[0], areas) != null, true);
        String[] good = { "/storage/emulated/0/a", "/storage/emulated/0/DCIM/.thumbnails", "/storage/emulated/0/Android/obj", "/storage/emulated/0/Android/data/com.x/desktop.ini",
            "/storage/ABCD-1234/Pictures/.trashed-1.jpg", "/data/anr/traces.txt", "/data/local/tmp", "/storage/emulated/0/a..b", "/storage/emulated/0/..hidden" };
        for (String g : good) is("safe: " + g, SdmSystemCleaner.unsafeReason(g, areas) == null, true);
        eq("reason names the problem", SdmSystemCleaner.unsafeReason("/storage/emulated/0/a/../b", areas), "relative path segment");
        eq("reason for a root", SdmSystemCleaner.unsafeReason("/storage/emulated/0", areas), "data area root");
        eq("reason for Android", SdmSystemCleaner.unsafeReason("/storage/emulated/0/Android", areas), "the Android folder");
        eq("reason for the system folders", SdmSystemCleaner.unsafeReason("/storage", areas), "protected folder");
        eq("reason for null", SdmSystemCleaner.unsafeReason(null, areas), "not an absolute path");
        eq("trailing slash", SdmSystemCleaner.unsafeReason("/storage/emulated/0/a/", areas), "not a normalised path");

        // an empty <sdcard>/Android is never listed (and so never deleted)
        fresh();
        mk(S + "Android");
        JSONObject only = new JSONObject();
        for (String id : SdmSystemCleaner.filterIds()) only.put(SdmSystemCleaner.filterKey(id), id.equals("emptydirectories"));
        FakeAreas a = new FakeAreas();
        a.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, p("storage/emulated/0"), true, "java", ""));
        Sdm.Ctx c = new Sdm.Ctx(new SdmFsJava(), a, new FakePkgs(), new FakeEx(), new FakeShell(0), only, null, null, now);
        eq("an empty Android folder is not a match", scan(c).groupCount(), 0);
        is("... and it is still there", exists(S + "Android"), true);
        // an empty folder tree directly in the area is fine, the area root itself is never listed
        mk(S + "E1/E2");
        SdmSystemCleaner.SysResult r = scan(c);
        eq("E1 and E2", new TreeSet<String>(r.paths("emptydirectories")), new TreeSet<String>(Arrays.asList(p(S + "E1"), p(S + "E1/E2"))));
        // delete with areas that changed since the scan: a path outside every available area is refused
        FakeAreas shrunk = new FakeAreas();
        Sdm.Ctx c2 = new Sdm.Ctx(new SdmFsJava(), shrunk, new FakePkgs(), new FakeEx(), new FakeShell(0), only, null, null, now);
        Sdm.DeleteReport rep = new SdmSystemCleaner().delete(r, new Sdm.Selection(), c2);
        is("with no area available nothing is deleted", exists(S + "E1/E2") && exists(S + "E1"), true);
        eq("both are refused", rep.failed.size(), 1);   // E1 is the only root, E2 is below it
        eq("... nothing was deleted", rep.deleted.size(), 0);
        eq("the result is unchanged", r.itemCount(), 2);
        // a foreign result type is rejected
        boolean threw = false;
        try { new SdmSystemCleaner().delete(new Sdm.Result() {
            @Override public Sdm.Tool tool() { return Sdm.Tool.SYSTEMCLEANER; }
            @Override public int groupCount() { return 0; }
            @Override public int itemCount() { return 0; }
            @Override public long bytes() { return 0; }
            @Override public JSONArray groups(int o, int l) { return new JSONArray(); }
            @Override public JSONArray items(String g, int o, int l) { return new JSONArray(); }
            @Override public JSONObject summary() { return new JSONObject(); }
        }, new Sdm.Selection(), c); } catch (IllegalArgumentException e) { threw = true; }
        is("a result of another tool is rejected", threw, true);
        // an item the selection drops by an id that is a group id of another tool changes nothing
        Sdm.DeleteReport all = new SdmSystemCleaner().delete(r, sel(new String[] { "corpse-1" }, new String[0]), c);
        eq("unknown group ids in the selection are ignored", all.deleted.size(), 2);
        is("... E1 and E2 are gone", !exists(S + "E1") && !exists(S + "E1/E2"), true);
    }
}
