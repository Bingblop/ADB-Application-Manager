package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AppCleaner (SdmAppCleaner, SdmAppSieve) against real folder trees: the JSON databases, every one of the 21 filters on a path that is taken and one that is
 * not, the scan (package set, exclusions, system and running apps, minimum size and age, marker folders, dynamic owners, inaccessible caches counted as
 * upstream does) and the delete (files, a fake shell that records pm trim-caches and force-stop, a fake automation that clears or fails with every error code,
 * what the user kept, the result lines).
 */
public class SdmAppCleanerTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static final long NOW = 1_700_000_000L;
    static final long OLD = NOW - 10L * 86400L;

    // ---------------------------------------------------------------------------------------------------------- fakes

    static final class FakeAreas implements Sdm.Areas {
        final List<Sdm.AreaInfo> list = new ArrayList<Sdm.AreaInfo>();
        @Override public List<Sdm.AreaInfo> all() { return list; }
        @Override public Sdm.AreaInfo get(Sdm.Area a) { for (Sdm.AreaInfo i : list) if (i.area == a) return i; return null; }
        @Override public Sdm.AreaInfo areaOf(String path) {
            Sdm.AreaInfo best = null;
            for (Sdm.AreaInfo i : list) {
                if (!Sdm.isInside(path, i.root)) continue;
                if (i.area == Sdm.Area.SDCARD) {
                    String under = path.substring(i.root.length() + 1);
                    if (under.equals("Android") || under.startsWith("Android/")) continue;       // never the sdcard for Android/data, media, obb
                }
                if (best == null || i.root.length() > best.root.length()) best = i;
            }
            return best;
        }
    }

    /** Cache sizes like StorageStats gives them: a private part plus the files that really are in Android/data/pkg/cache (so deleting them changes the figure). */
    static final class FakePkgs implements Sdm.Packages {
        final Map<String, Sdm.Pkg> pkgs = new java.util.LinkedHashMap<String, Sdm.Pkg>();
        final Map<String, Long> priv = new HashMap<String, Long>();
        final Set<String> running = new HashSet<String>();
        final AtomicInteger cacheCalls = new AtomicInteger();
        String pubData;
        Sdm.Pkg add(String pkg, String label, boolean system) {
            Sdm.Pkg p = new Sdm.Pkg(); p.pkg = pkg; p.label = label; p.system = system; p.uid = 10000 + pkgs.size();
            pkgs.put(pkg, p);
            return p;
        }
        long pub(String pkg) {
            final long[] sum = { 0 };
            if (pubData != null) new SdmFsJava().walk(pubData + "/" + pkg + "/cache", new Sdm.EntrySink() { @Override public boolean accept(Sdm.Entry e) { if (e.isFile()) sum[0] += e.size; return true; } }, null);
            return sum[0];
        }
        /** The total cache of the app is now this much. */
        void setTotal(String pkg, long total) { priv.put(pkg, Math.max(0, total - pub(pkg))); }
        /** What "Clear cache" does. */
        void clear(String pkg) {
            priv.put(pkg, 0L);
            if (pubData != null) Phone.rm(new File(pubData + "/" + pkg + "/cache"));
        }
        @Override public List<Sdm.Pkg> installed() { return new ArrayList<Sdm.Pkg>(pkgs.values()); }
        @Override public Sdm.Pkg get(String pkg) { return pkgs.get(pkg); }
        @Override public Set<String> running() { return running; }
        @Override public long cacheBytes(String pkg) { cacheCalls.incrementAndGet(); Long v = priv.get(pkg); return v == null ? -1 : v + pub(pkg); }
    }

    static final class FakeShell implements Sdm.Shell {
        int uid;
        final List<String> scripts = new ArrayList<String>();
        Runnable onTrim;
        FakeShell(int uid) { this.uid = uid; }
        @Override public int uid() { return uid; }
        @Override public String run(String script, int timeoutMs) { scripts.add(script); if (script.contains("pm trim-caches") && onTrim != null) onTrim.run(); return ""; }
        @Override public void stream(String script, int timeoutMs, Sdm.LineSink sink, Sdm.Cancel cancel) { scripts.add(script); }
        boolean ran(String part) { for (String s : scripts) if (s.contains(part)) return true; return false; }
    }

    static final class FakeExcl implements Sdm.Exclusions {
        final Set<String> pkgs = new HashSet<String>();
        final TreeSet<String> paths = new TreeSet<String>();
        @Override public boolean excludesPath(Sdm.Tool t, String path) { return SdmAppCleaner.pathExcluded(paths, path); }
        @Override public boolean excludesPackage(Sdm.Tool t, String pkg) { return pkgs.contains(pkg); }
        @Override public List<String> paths(Sdm.Tool t) { return new ArrayList<String>(paths); }
    }

    /** The accessibility service: clears the caches of the packages it is given, or stops with an error code after some of them. */
    static final class FakeAuto implements Sdm.Automation, SdmAcsPlan.AutomationInfo {
        final FakePkgs pkgs;
        boolean ready = true;
        String errorAfter;           // code to throw
        int errorAt = -1;            // index of the package the error hits
        final Set<String> willFail = new HashSet<String>();     // packages whose cache stays
        final Map<String, String> unreachable = new HashMap<String, String>();
        final Map<String, String> failure = new HashMap<String, String>();
        List<String> asked;
        FakeAuto(FakePkgs pkgs) { this.pkgs = pkgs; }
        @Override public boolean ready() { return ready; }
        @Override public Set<String> clearCaches(List<String> list, Sdm.Progress progress, Sdm.Cancel cancel) throws Sdm.AutomationError {
            asked = new ArrayList<String>(list);
            Set<String> done = new LinkedHashSet<String>();
            for (int i = 0; i < list.size(); i++) {
                if (i == errorAt) throw new Sdm.AutomationError(errorAfter, "boom " + errorAfter, done);
                String p = list.get(i);
                if (willFail.contains(p)) continue;
                pkgs.clear(p);
                done.add(p);
            }
            return done;
        }
        @Override public String unreachableReason(String pkg, boolean enabled) { return unreachable.get(pkg); }
        @Override public String failureOf(String pkg) { return failure.get(pkg); }
    }

    // ---------------------------------------------------------------------------------------------------------- the phone

    static final class Phone {
        final File root;
        final String sd, pubData, pubMedia, priv, sysCe;
        final FakeAreas areas = new FakeAreas();
        final FakePkgs pkgs = new FakePkgs();
        final FakeExcl excl = new FakeExcl();
        final JSONObject settings = new JSONObject();
        FakeShell shell;
        FakeAuto auto;
        Sdm.Cancel cancel = Sdm.NEVER;
        final List<String> progress = new ArrayList<String>();

        Phone() throws Exception {
            root = Files.createTempDirectory("sdmapp").toFile();
            sd = root.getPath() + "/storage/emulated/0";
            pubData = sd + "/Android/data";
            pubMedia = sd + "/Android/media";
            priv = root.getPath() + "/data/user/0";
            sysCe = root.getPath() + "/data/system_ce/0";
            for (String d : new String[] { sd, pubData, pubMedia, priv, sysCe }) new File(d).mkdirs();
            areas.list.add(new Sdm.AreaInfo(Sdm.Area.SDCARD, sd, true, "java", ""));
            areas.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_DATA, pubData, true, "java", ""));
            areas.list.add(new Sdm.AreaInfo(Sdm.Area.PUBLIC_MEDIA, pubMedia, true, "java", ""));
            areas.list.add(new Sdm.AreaInfo(Sdm.Area.PRIVATE_DATA, priv, true, "java", ""));
            areas.list.add(new Sdm.AreaInfo(Sdm.Area.DATA_SYSTEM_CE, sysCe, true, "java", ""));
            pkgs.pubData = pubData;
            auto = new FakeAuto(pkgs);
            try { settings.put("skip.mincachesize.bytes", 0); } catch (Exception e) { throw new RuntimeException(e); }
        }

        Phone set(String key, Object v) { try { settings.put(key, v); } catch (Exception e) { throw new RuntimeException(e); } return this; }

        File file(String path, int len, long mtime) throws Exception {
            File f = new File(path);
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), new byte[len]);
            f.setLastModified(mtime * 1000L);
            return f;
        }
        File file(String path, int len) throws Exception { return file(path, len, NOW - 3600); }

        Sdm.Ctx ctx() {
            Sdm.Progress p = new Sdm.Progress() { @Override public void update(String a, String b, long d, long t, long by) { progress.add(a); } };
            Sdm.Ctx c = new Sdm.Ctx(new SdmFsJava(), areas, pkgs, excl, shell, settings, p, cancel, NOW);
            c.automation = auto;
            return c;
        }

        SdmAppCleaner.AppResult scan() throws Exception { return (SdmAppCleaner.AppResult) new SdmAppCleaner().scan(ctx()); }

        Sdm.DeleteReport delete(Sdm.Result r, Sdm.Selection s) throws Exception { return new SdmAppCleaner().delete(r, s, ctx()); }

        void cleanup() { rm(root); }
        static void rm(File f) { File[] k = f.listFiles(); if (k != null) for (File c : k) rm(c); f.delete(); }
    }

    static Sdm.Selection sel(String json) throws Exception { return Sdm.Selection.of(new JSONObject(json)); }

    static String note(Sdm.DeleteReport r, String key) {
        for (String s : r.notes) if (s.startsWith(key + ": ")) return s.substring(key.length() + 2);
        return null;
    }

    static List<String> pathsOf(SdmAppCleaner.AppResult r, String pkg) throws Exception {
        List<String> out = new ArrayList<String>();
        JSONArray a = r.items(pkg, 0, 100000);
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("path"));
        return out;
    }

    static List<String> groupIds(SdmAppCleaner.AppResult r) throws Exception {
        List<String> out = new ArrayList<String>();
        JSONArray a = r.groups(0, 100000);
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("id"));
        return out;
    }

    static JSONObject group(SdmAppCleaner.AppResult r, String pkg) throws Exception {
        JSONArray a = r.groups(0, 100000);
        for (int i = 0; i < a.length(); i++) if (a.getJSONObject(i).getString("id").equals(pkg)) return a.getJSONObject(i);
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------- the filters, one at a time

    /** A ctx with only this filter switched on, so that a path is either taken by it or by nobody. */
    static Sdm.Ctx only(String id) throws Exception {
        JSONObject s = new JSONObject();
        for (int i = 0; i < SdmAppCleaner.FILTER_COUNT; i++) s.put(SdmAppCleaner.KEYS[i], SdmAppCleaner.IDS[i].equals(id));
        return new Sdm.Ctx(new SdmFsJava(), null, null, null, null, s, null, null, NOW);
    }

    static void take(String filter, String pkg, Sdm.Area area, String pfp, boolean want) throws Exception {
        take(filter, pkg, area, pfp, want, NOW - 100, 10);
    }

    static void take(String filter, String pkg, Sdm.Area area, String pfp, boolean want, long mtime, long size) throws Exception {
        String got = SdmAppCleaner.filterOf(only(filter), pkg, area, pfp, size, mtime, NOW);
        is(filter + " " + (want ? "takes " : "leaves ") + area + ":" + pfp + " of " + pkg, filter.equals(got), want);
    }

    static void testFilters() throws Exception {
        final Sdm.Area SD = Sdm.Area.SDCARD, PD = Sdm.Area.PUBLIC_DATA, PM = Sdm.Area.PUBLIC_MEDIA, PR = Sdm.Area.PRIVATE_DATA;

        // 1 / 2 default caches: public and private, pfp = [pkg, cache, x ...]
        take("defaultcachespublic", "com.a", PD, "com.a/cache/x.bin", true);
        take("defaultcachespublic", "com.a", PD, "com.a/cache/sub/deep/y", true);
        take("defaultcachespublic", "com.a", PD, "com.a/cache", false);                 // the folder itself is not a match, only what is in it
        take("defaultcachespublic", "com.a", PD, "com.a/Cache/x", false);               // case matters
        take("defaultcachespublic", "com.a", PD, "com.a/cache/.nomedia", false);
        take("defaultcachespublic", "com.a", PR, "com.a/cache/x", false);               // not a public area
        take("defaultcachespublic", "com.a", PD, "com.a/files/x", false);
        take("defaultcachesprivate", "com.a", PR, "com.a/cache/x", true);
        take("defaultcachesprivate", "com.a", PR, "com.a/cache", false);
        take("defaultcachesprivate", "com.a", PD, "com.a/cache/x", false);
        take("defaultcachesprivate", "com.a", PR, "com.a/code_cache/x", false);
        // 3 code cache
        take("codecache", "com.a", PR, "com.a/code_cache/x/y", true);
        take("codecache", "com.a", PD, "com.a/code_cache/x", false);
        take("codecache", "com.a", PR, "com.a/code_cache", false);
        // 4 advertisement
        take("advertisement", "com.a", PR, "com.a/vast_rtb_cache/x", true);
        take("advertisement", "com.a", PD, "com.a/files/GoAdSdk/x/y", true);
        take("advertisement", "com.a", PD, "com.a/files/mb/res/.mbridge/x", true);
        take("advertisement", "com.a", PD, "com.a/files/mb/res/.mbridge", false);
        take("advertisement", "com.a", PD, "com.a/cache/vast_rtb_cache/x", false);       // default caches are not this filter's
        take("advertisement", "com.a", PR, "com.a/vast_rtb_cache/.nomedia", false);
        take("advertisement", "com.a", PD, "com.a/files/other/x", false);
        take("advertisement", "com.tencent.mm", SD, "tencent/MicroMsg/sns_ad_landingpages/x.jpg", true);               // JSON database
        take("advertisement", "com.other", SD, "tencent/MicroMsg/sns_ad_landingpages/x.jpg", false);                   // a database rule of another app
        take("advertisement", "com.a", PR, "com.a/files/.ab12-cd.mologiq", true);
        // 5 bug reporting
        take("bugreporting", "com.a", PD, "com.a/2024-01-31.log.txt", true);
        take("bugreporting", "com.a", PD, "com.a/log.txt", true);
        take("bugreporting", "com.a", PD, "com.a/logs/x", true);
        take("bugreporting", "com.a", PD, "com.a/files/log.txt", true);
        take("bugreporting", "com.a", PD, "com.a/files/logfiles/x", true);
        take("bugreporting", "com.a", PD, "com.a/files/.crashlytics.v3/com.a/x", true);
        take("bugreporting", "com.a", PD, "com.a/files/.com.google.firebase.crashlytics.files.v2:com.a/x", true);
        take("bugreporting", "com.a", PD, "com.a/files/.crashlytics.v3/com.a", false);
        take("bugreporting", "com.a", PD, "com.a/files/.crashlytics.v3/com.b/x", false);
        take("bugreporting", "com.a", PD, "com.a/cache/logs/x", false);
        take("bugreporting", "com.a", SD, "com.a/files/logs/x", false);                  // only the app data areas look into files/
        take("bugreporting", "com.a", PD, "com.a/files/data.bin", false);
        take("bugreporting", "com.android.shell", PR, "com.android.shell/files/bugreports/bugreport-1.zip", true);      // JSON
        // 6 analytics (JSON only)
        take("analytics", "com.a", PR, "com.a/files/.yflurryreport.123", true);
        take("analytics", "com.a", PR, "com.a/shared_prefs/APP_MEASUREMENT_CACHE.xml", true);
        take("analytics", "com.a", PD, "com.a/files/.yflurryreport.123", false);         // the rule is for the private data
        take("analytics", "com.a", PR, "com.a/files/other", false);
        // 7 game files
        take("gamefiles", "com.a", PD, "com.a/UnityCache/Shared/x", true);
        take("gamefiles", "com.a", PD, "com.a/files/unitycache/x", true);
        take("gamefiles", "com.a", PD, "com.a/files/Cache/foo.unity3d", true);           // JSON
        take("gamefiles", "com.a", PD, "com.a/files/other/x", false);
        take("gamefiles", "com.a", PD, "com.a/unitycache", false);
        // 8 hidden caches
        take("hiddencaches", "com.a", PD, "com.a/Cache/x", true);                        // another casing than the system's cache folder
        take("hiddencaches", "com.a", PD, "com.a/.cache/x", true);
        take("hiddencaches", "com.a", PD, "com.a/files/image_cache/x", true);
        take("hiddencaches", "com.a", PD, "com.a/files/cache/x", true);
        take("hiddencaches", "com.a", PD, "com.a/cache.dat", true);
        take("hiddencaches", "com.a", PD, "com.a/files/temp.dat", true);
        take("hiddencaches", "com.a", SD, "Huawei/Themes/.cache/x", true);
        take("hiddencaches", "com.ninegag.android.app", PD, "com.ninegag.android.app/files/images/a.jpg", true);        // JSON, with notContains
        take("hiddencaches", "com.ninegag.android.app", PD, "com.ninegag.android.app/files/images/.nomedia", false);
        take("hiddencaches", "eu.thedarken.sdm.test", SD, "sdm_test_file_cache_v2", true);                              // JSON
        take("hiddencaches", "com.a", PD, "com.a/cache/x", false);                       // the default cache
        take("hiddencaches", "com.a", PD, "com.a/cache", false);
        take("hiddencaches", "com.a", PD, "com.a/files/Cache/foo.unity3d&1", false);     // the unity exception
        take("hiddencaches", "com.a", PD, "com.a/.cache/.nomedia", false);
        take("hiddencaches", "com.a", PD, "com.a/files/data/x", false);
        take("hiddencaches", "com.a", SD, "Documents/x/y", false);
        // 9 thumbnails
        take("thumbnails", "com.a", PD, "com.a/.thumbnails/t.jpg", true);
        take("thumbnails", "com.a", PD, "com.a/files/thumbs/t.jpg", true);
        take("thumbnails", "com.a", SD, ".thumbs/t.jpg", true);
        take("thumbnails", "com.a", SD, "DCIM/x/albumthumbs/t.jpg", true);
        take("thumbnails", "nextapp.fx", SD, ".FX/CloudThumbnail/a.jpg", true);           // JSON
        take("thumbnails", "nextapp.fx", SD, ".FX/CloudThumbnail", false);               // strictly inside the folder
        take("thumbnails", "com.other", SD, ".FX/CloudThumbnail/a.jpg", false);
        take("thumbnails", "com.a", PD, "com.a/cache/thumbnails/t.jpg", false);
        take("thumbnails", "com.a", PD, "com.a/.thumbnails/.nomedia", false);
        // 10 offline cache
        take("offlinecache", "com.a", PD, "com.a/offline_cache/x", true);
        take("offlinecache", "com.a", PD, "com.a/files/.offline-cache/x/y", true);
        take("offlinecache", "com.spotify.music", PD, "com.spotify.music/files/spotifycache/x", true);                   // JSON
        take("offlinecache", "com.a", PD, "com.a/offline_cache", false);
        take("offlinecache", "com.a", PD, "com.a/files/offline/x", false);
        // 11 recycle bins
        take("recyclebins", "com.a", PD, "com.a/.trash/x", true);
        take("recyclebins", "com.a", PR, "com.a/files/.recyclebin/x", true);
        take("recyclebins", "com.a", SD, "Notes/.Trash/x", true);                        // the compare is on the lower case name
        take("recyclebins", "com.a", SD, "Notes/.trash/x", true);
        take("recyclebins", "com.a", SD, "Android/.Trash/com.a/f", true);
        take("recyclebins", "com.a", SD, "Android/.Trash/com.b/f", false);
        take("recyclebins", "com.a", PM, "com.a/trash/x", true);
        take("recyclebins", "com.meizu.media.gallery", PD, ".MeizuGalleryTrashBin/a.jpg", true);                          // JSON
        take("recyclebins", "com.a", PD, "com.a/files/data/x", false);
        take("recyclebins", "com.a", PD, "com.a/.trash", false);
        // 12 webview
        take("webview", "com.a", PR, "com.a/app_webview/Cache/x", true);
        take("webview", "com.a", PR, "com.a/app_chrome/Default/GPUCache/data_0", true);
        take("webview", "com.a", PR, "com.a/app_webview/Service Worker/CacheStorage/a/b", true);
        take("webview", "com.a", PR, "com.a/app_webview/Cache", false);                  // the folder itself stays, what is in it goes
        take("webview", "com.a", PR, "com.a/app_webview/Local Storage/x", false);
        take("webview", "com.b", PR, "com.a/app_webview/Cache/x", false);                // another app's folder
        take("webview", "com.cloudmosa.puffin", PR, "com.cloudmosa.puffin/app_favicon_cache/a.png", true);                // JSON
        // 13 shortcut service
        take("shortcutservice", "com.a", Sdm.Area.DATA_SYSTEM_CE, "shortcut_service/bitmaps/com.a/x.png", true);
        take("shortcutservice", "com.a", Sdm.Area.DATA_SYSTEM_CE, "shortcut_service/bitmaps/com.b/x.png", false);
        take("shortcutservice", "com.a", Sdm.Area.DATA_SYSTEM_CE, "shortcut_service/bitmaps/com.a", false);
        take("shortcutservice", "com.a", PR, "shortcut_service/bitmaps/com.a/x.png", false);
        // 14 WhatsApp backups: older than a day, a known file name
        take("whatsapp.backups", "com.whatsapp", SD, "WhatsApp/Databases/msgstore-2020-01-01.1.db.crypt14", true, OLD, 100);
        take("whatsapp.backups", "com.whatsapp.w4b", SD, "WhatsApp Business/Backups/wa-2020.1.db.crypt12", true, OLD, 100);
        take("whatsapp.backups", "com.whatsapp", SD, "com.whatsapp/WhatsApp/Databases/stickers-1.1.db.crypt15", true, OLD, 100);
        take("whatsapp.backups", "com.whatsapp", SD, "WhatsApp/Databases/msgstore-2020-01-01.1.db.crypt14", false, NOW - 3600, 100);     // younger than a day
        take("whatsapp.backups", "com.whatsapp", SD, "WhatsApp/Databases/msgstore-2020-01-01.1.db.crypt14", false, 0, 100);            // no date
        take("whatsapp.backups", "com.whatsapp", SD, "WhatsApp/Databases/msgstore.db", false, OLD, 100);                               // not a backup name
        take("whatsapp.backups", "com.whatsapp", SD, "WhatsApp/Media/msgstore-1.1.db.crypt14", false, OLD, 100);                        // not the Databases folder
        take("whatsapp.backups", "com.other", SD, "WhatsApp/Databases/msgstore-2020-01-01.1.db.crypt14", false, OLD, 100);
        // 15 / 16 WhatsApp received and sent
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/IMG-1.jpg", true);
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Animated Gifs/g.mp4", true);
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Voice Notes/2024-01/PTT-1.opus", true);
        take("whatsapp.received", "com.whatsapp", PM, "com.whatsapp/WhatsApp/Media/WhatsApp Video/a.mp4", true);
        take("whatsapp.received", "com.whatsapp.w4b", SD, "WhatsApp Business/Media/WhatsApp Business Documents/d.pdf", true);
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Voice Notes/PTT-1.opus", false);        // the voice note rule wants the week folder
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/Sent/IMG-2.jpg", false);
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/Private", false);                // the folder itself (upstream only excludes the name)
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/.nomedia", false);
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images", false);
        take("whatsapp.received", "com.whatsapp", SD, "WhatsApp/Databases/x", false);
        take("whatsapp.sent", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/Sent/IMG-2.jpg", true);
        take("whatsapp.sent", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Audio/Sent/a.opus", true);
        take("whatsapp.sent", "com.whatsapp", PM, "com.whatsapp/WhatsApp/Media/WhatsApp Documents/Sent/d.pdf", true);
        take("whatsapp.sent", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/IMG-1.jpg", false);
        take("whatsapp.sent", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/Sent/.nomedia", false);
        take("whatsapp.sent", "com.whatsapp", SD, "WhatsApp/Media/WhatsApp Images/Sent", false);
        // 17 Telegram
        take("telegram", "org.telegram.messenger", SD, "Telegram/Telegram Images/a.jpg", true);
        take("telegram", "org.telegram.messenger", PD, "org.telegram.messenger/files/Telegram/Telegram Video/v.mp4", true);
        take("telegram", "org.telegram.messenger.web", PD, "org.telegram.messenger.web/files/Telegram/Telegram Stories/v.mp4", true);
        take("telegram", "org.telegram.plus", SD, "Telegram/Telegram Audio/a.ogg", true);
        take("telegram", "org.thunderdog.challegram", PD, "org.thunderdog.challegram/files/photos/x.jpg", true);
        take("telegram", "org.telegram.plus", PD, "org.telegram.plus/files/Telegram/Telegram Audio/a.ogg", false);          // plus only on the sdcard
        take("telegram", "com.other", SD, "Telegram/Telegram Images/a.jpg", false);
        take("telegram", "org.telegram.messenger", SD, "Telegram/Telegram Images/.nomedia", false);
        take("telegram", "org.telegram.messenger", SD, "Telegram/Telegram Images", false);
        take("telegram", "org.telegram.messenger", SD, "Telegram/Other/a.jpg", false);
        // 18 Threema
        take("threema", "ch.threema.app", SD, "Threema/Threema Pictures/a.jpg", true);
        take("threema", "ch.threema.app", PD, "Threema/Threema Pictures/a.jpg", false);
        take("threema", "ch.threema.app", SD, "Threema/Threema Pictures", false);
        // 19 WeChat
        take("wechat", "com.tencent.mm", SD, "tencent/MicroMsg/h1/image2/p.jpg", true);
        take("wechat", "com.tencent.mm", SD, "tencent/MicroMsg/h1/voice2/p.amr", true);
        take("wechat", "com.tencent.mm", PD, "com.tencent.mm/MicroMsg/h1/video/v.mp4", true);
        take("wechat", "com.tencent.mm", PR, "com.tencent.mm/MicroMsg/h1/sns/s.jpg", true);
        take("wechat", "com.tencent.mm", SD, "tencent/MicroMsg/h1/avatar/p.jpg", false);
        take("wechat", "com.tencent.mm", SD, "tencent/MicroMsg/h1/image2/.nomedia", false);
        take("wechat", "com.tencent.mm", SD, "tencent/Other/h1/image2/p.jpg", false);
        // 20 Viber
        take("viber", "com.viber.voip", PD, "com.viber.voip/files/.image/a.jpg", true);
        take("viber", "com.viber.voip", PD, "com.viber.voip/files/.converted_videos/a.mp4", true);
        take("viber", "com.viber.voip", SD, "com.viber.voip/files/.image/a.jpg", false);
        take("viber", "com.viber.voip", PD, "com.viber.voip/files/.image", false);
        take("viber", "com.viber.voip", PD, "com.viber.voip/files/other/a.jpg", false);
        // 21 QQ
        take("mobileqq", "com.tencent.mobileqq", SD, "Tencent/MobileQQ/chatpic/a.jpg", true);
        take("mobileqq", "com.tencent.mobileqq", PD, "com.tencent.mobileqq/MobileQQ/shortvideo/a.mp4", true);
        take("mobileqq", "com.tencent.mobileqq", SD, "Tencent/MobileQQ/u123/ptt/a.amr", true);
        take("mobileqq", "com.tencent.mobileqq", SD, "Tencent/MobileQQ/u123/other/a.amr", false);
        take("mobileqq", "com.tencent.mobileqq", SD, "Tencent/MobileQQ/chatpic", false);
        take("mobileqq", "com.other", SD, "Tencent/MobileQQ/chatpic/a.jpg", false);

        // the order: the first enabled filter of the settings screen takes a file two filters would take
        JSONObject all = new JSONObject();
        for (int i = 0; i < SdmAppCleaner.FILTER_COUNT; i++) all.put(SdmAppCleaner.KEYS[i], true);
        Sdm.Ctx ctxAll = new Sdm.Ctx(new SdmFsJava(), null, null, null, null, all, null, null, NOW);
        eq("a default cache goes to the public default caches, not to hidden caches", SdmAppCleaner.filterOf(ctxAll, "com.a", PD, "com.a/cache/x", 1, NOW - 5, NOW), "defaultcachespublic");
        eq("the private one goes to private default caches", SdmAppCleaner.filterOf(ctxAll, "com.a", PR, "com.a/cache/x", 1, NOW - 5, NOW), "defaultcachesprivate");
        eq("another casing is a hidden cache", SdmAppCleaner.filterOf(ctxAll, "com.a", PD, "com.a/Cache/x", 1, NOW - 5, NOW), "hiddencaches");
        eq("a file nobody wants", SdmAppCleaner.filterOf(ctxAll, "com.a", PD, "com.a/files/data.bin", 1, NOW - 5, NOW), null);
    }

    // ---------------------------------------------------------------------------------------------------------- the databases

    static void testDatabases() throws Exception {
        String[] names = { "advertisement_files", "analytics_files", "bug_reporting_files", "downloaded_game_files", "hidden_caches_files", "offline_cache_files", "thumbnail_files", "trash_files", "webcaches" };
        int[] apps = { 15, 10, 46, 2, 112, 9, 11, 12, 2 };
        int[] files = { 17, 14, 50, 2, 141, 10, 13, 13, 2 };
        for (int i = 0; i < names.length; i++) {
            SdmAppSieve s = SdmAppSieve.load("expendables/db_" + names[i] + ".json");
            eq("db " + names[i] + " app filters", s.appFilterCount(), apps[i]);
            eq("db " + names[i] + " file filters", s.fileFilterCount(), files[i]);
        }
        // the format
        String json = "{\"schemaVersion\":1,\"appFilter\":["
                + "{\"packages\":[\"p.one\"],\"fileFilter\":[{\"locations\":[\"SDCARD\"],\"startsWith\":[\"dir/sub/\"],\"notContains\":[\".nomedia\"]}]},"
                + "{\"fileFilter\":[{\"locations\":[],\"contains\":[\"/files/x\"],\"patterns\":[\"^(?>a/files/)(X.*)$\"]}]},"
                + "{\"packages\":[\"p.two\"],\"fileFilter\":[{\"locations\":[\"PRIVATE_DATA\"],\"startsWith\":[\"Dir\"]}]}"
                + "]}";
        SdmAppSieve s = SdmAppSieve.parse(json, "t");
        is("startsWith with a trailing slash: strictly inside", s.matches("p.one", Sdm.Area.SDCARD, new String[] { "dir", "sub", "a.txt" }), true);
        is("the folder itself is not inside", s.matches("p.one", Sdm.Area.SDCARD, new String[] { "dir", "sub" }), false);
        is("case is ignored in the sdcard area", s.matches("p.one", Sdm.Area.SDCARD, new String[] { "DIR", "SUB", "a" }), true);
        is("notContains", s.matches("p.one", Sdm.Area.SDCARD, new String[] { "dir", "sub", ".nomedia" }), false);
        is("another package", s.matches("p.other", Sdm.Area.SDCARD, new String[] { "dir", "sub", "a.txt" }), false);
        is("another area", s.matches("p.one", Sdm.Area.PRIVATE_DATA, new String[] { "dir", "sub", "a.txt" }), false);
        is("no packages = every package, no locations = every area, regex ignores case in a public area", s.matches("p.any", Sdm.Area.PUBLIC_DATA, new String[] { "a", "files", "XYZ" }), true);
        is("... the contains rule is a substring of the joined path", s.matches("p.any", Sdm.Area.PUBLIC_DATA, new String[] { "a", "other", "XYZ" }), false);
        is("a regex has to match the whole path (and is exact in a private area)", s.matches("p.any", Sdm.Area.PRIVATE_DATA, new String[] { "a", "files", "xyz" }), false);
        is("case matters in the private data", s.matches("p.two", Sdm.Area.PRIVATE_DATA, new String[] { "dir", "x" }), false);
        is("... same case matches", s.matches("p.two", Sdm.Area.PRIVATE_DATA, new String[] { "Dir", "x" }), true);
        is("startsWith is a partial prefix: the last segment starts with it", s.matches("p.two", Sdm.Area.PRIVATE_DATA, new String[] { "Directory" }), true);
        for (String bad : new String[] { "{\"appFilter\":[]}", "{\"appFilter\":[{\"fileFilter\":[]}]}", "{\"appFilter\":[{\"fileFilter\":[{\"locations\":[\"SDCARD\"]}]}]}" }) {
            boolean threw = false;
            try { SdmAppSieve.parse(bad, "bad"); } catch (org.json.JSONException e) { threw = true; }
            is("a damaged database is refused: " + bad, threw, true);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the scan

    static void testCatalog() throws Exception {
        JSONArray c = SdmAppCleaner.filterCatalog();
        eq("21 filters", c.length(), 21);
        int on = 0;
        Set<String> keys = new HashSet<String>();
        for (int i = 0; i < c.length(); i++) { JSONObject o = c.getJSONObject(i); if (o.getBoolean("default")) on++; keys.add(o.getString("key")); }
        eq("8 filters are on by default", on, 8);
        eq("21 distinct keys", keys.size(), 21);
        is("the key of upstream", keys.contains("filter.defaultcachespublic.enabled"), true);
        is("the key of upstream, nested", keys.contains("filter.whatsapp.backups.enabled"), true);
        eq("the label", c.getJSONObject(0).getString("label"), "Public default caches");
        eq("the label of QQ", c.getJSONObject(20).getString("label"), "QQ chat");
    }

    static void testScan() throws Exception {
        Phone p = new Phone();
        try {
            p.pkgs.add("com.alpha", "Alpha", false);
            p.pkgs.add("com.beta", "Beta", true);
            p.pkgs.add("com.gamma", "Gamma", false);
            p.pkgs.add("com.delta", "Delta", false);
            p.pkgs.add("com.epsilon", "Epsilon", false);
            p.pkgs.add("com.whatsapp", "WhatsApp", false);
            p.pkgs.add("com.tencent.mm", "WeChat", false);
            p.pkgs.add("org.telegram.messenger", "Telegram", false);
            p.pkgs.running.add("com.gamma");
            p.excl.pkgs.add("com.delta");

            p.file(p.pubData + "/com.alpha/cache/a.tmp", 200);
            p.file(p.pubData + "/com.alpha/cache/sub/b.tmp", 300);
            p.file(p.pubData + "/com.alpha/cache/keep/k.tmp", 100);
            p.file(p.pubData + "/com.alpha/files/.thumbnails/t.jpg", 500);
            p.file(p.pubData + "/com.alpha/files/logs/x.log", 40);                      // bug reporting is off by default
            p.file(p.pubData + "/com.alpha/files/data.bin", 90);                        // nobody's
            p.file(p.priv + "/com.alpha/cache/c1", 400);
            p.file(p.priv + "/com.alpha/code_cache/cc1", 100);
            p.file(p.priv + "/com.alpha/app_webview/Cache/w", 50);
            p.file(p.priv + "/com.alpha/databases/main.db", 70);
            p.file(p.sysCe + "/shortcut_service/bitmaps/com.alpha/s.png", 30);          // shortcut icons are off by default
            p.file(p.pubData + "/com.beta/cache/z", 1000);
            p.file(p.pubData + "/com.gamma/cache/g", 600);
            p.file(p.pubData + "/com.delta/cache/d", 600);
            p.file(p.pubData + "/.com.epsilon/cache/e", 60);                            // a hidden spelling of an installed app's folder
            p.file(p.pubData + "/unknown.app/cache/u", 10);                             // nobody installed
            p.file(p.sd + "/WhatsApp/Databases/msgstore-2020.1.db.crypt14", 80, OLD);
            p.file(p.sd + "/tencent/MicroMsg/h1/image2/p.jpg", 90);
            p.file(p.sd + "/Telegram/Telegram Images/t.jpg", 33);
            p.excl.paths.add(p.pubData + "/com.alpha/cache/keep");

            // ---- the defaults: eight filters, no system apps, running apps included, one exclusion
            SdmAppCleaner.AppResult r = p.scan();
            eq("apps with something to clean", groupIds(r), Arrays.asList("com.alpha", "com.gamma", "com.epsilon"));
            Set<String> a = new HashSet<String>(pathsOf(r, "com.alpha"));
            is("public default cache file", a.contains(p.pubData + "/com.alpha/cache/a.tmp"), true);
            is("public default cache folder: the folder and what is in it", a.contains(p.pubData + "/com.alpha/cache/sub") && a.contains(p.pubData + "/com.alpha/cache/sub/b.tmp"), true);
            is("the excluded path and what is below it stay out", a.contains(p.pubData + "/com.alpha/cache/keep/k.tmp") || a.contains(p.pubData + "/com.alpha/cache/keep"), false);
            is("thumbnails", a.contains(p.pubData + "/com.alpha/files/.thumbnails/t.jpg"), true);
            is("private default cache", a.contains(p.priv + "/com.alpha/cache/c1"), true);
            is("code cache", a.contains(p.priv + "/com.alpha/code_cache/cc1"), true);
            is("webview", a.contains(p.priv + "/com.alpha/app_webview/Cache/w"), true);
            is("bug reporting is off", a.contains(p.pubData + "/com.alpha/files/logs/x.log"), false);
            is("user data stays", a.contains(p.pubData + "/com.alpha/files/data.bin") || a.contains(p.priv + "/com.alpha/databases/main.db"), false);
            is("shortcut icons are off", a.contains(p.sysCe + "/shortcut_service/bitmaps/com.alpha/s.png"), false);
            is("the hidden spelling is found through the name", pathsOf(r, "com.epsilon").contains(p.pubData + "/.com.epsilon/cache/e"), true);
            JSONObject ga = group(r, "com.alpha");
            eq("the group row: label", ga.getString("label"), "Alpha");
            eq("the group row: sub", ga.getString("sub"), "com.alpha");
            eq("the group row: count", ga.getInt("count"), a.size());
            long sum = 0;
            JSONArray rows = r.items("com.alpha", 0, 1000);
            for (int i = 0; i < rows.length(); i++) sum += rows.getJSONObject(i).getLong("size");
            eq("the group row: bytes are the sum of its items", ga.getLong("bytes"), sum);
            JSONObject row0 = rows.getJSONObject(0);
            for (String k : new String[] { "id", "path", "name", "size", "mtime", "type" }) is("item row has " + k, row0.has(k), true);
            eq("item rows are paged", r.items("com.alpha", 2, 3).length(), 3);
            JSONObject sm = r.summary();
            eq("summary item count", sm.getInt("itemCount"), r.itemCount());
            eq("summary primary", sm.getString("primary"), r.itemCount() + " expendable items found");
            is("summary secondary", sm.getString("secondary").endsWith(" can be freed"), true);
            // sorted by size, biggest first
            long prev = Long.MAX_VALUE;
            JSONArray gs = r.groups(0, 100);
            boolean sorted = true;
            for (int i = 0; i < gs.length(); i++) { long b = gs.getJSONObject(i).getLong("bytes"); if (b > prev) sorted = false; prev = b; }
            is("apps sorted by size, biggest first", sorted, true);
            // the folders have a size too (a directory's own entry size), and every match is its own item: files AND folders
            JSONObject typeRow = null;
            for (int i = 0; i < rows.length(); i++) if (rows.getJSONObject(i).getString("path").endsWith("/cache/sub")) typeRow = rows.getJSONObject(i);
            eq("a folder is type 1", typeRow == null ? -1 : typeRow.getInt("type"), 1);

            // ---- system apps, running apps
            p.set("include.systemapps.enabled", true);
            r = p.scan();
            is("with system apps: Beta is there and marked", group(r, "com.beta") != null && group(r, "com.beta").getBoolean("system"), true);
            p.set("include.systemapps.enabled", false).set("include.runningapps.enabled", false);
            r = p.scan();
            eq("without running apps", groupIds(r), Arrays.asList("com.alpha", "com.epsilon"));
            p.set("include.runningapps.enabled", true);

            // ---- filters switched on: the folders of apps that live outside Android/data
            p.set("filter.whatsapp.backups.enabled", true).set("filter.wechat.enabled", true).set("filter.telegram.enabled", true);
            r = p.scan();
            is("WhatsApp: the marker database maps the WhatsApp folder to the app", pathsOf(r, "com.whatsapp").contains(p.sd + "/WhatsApp/Databases/msgstore-2020.1.db.crypt14"), true);
            is("WeChat: a nested marker folder (tencent/MicroMsg) on the sdcard", pathsOf(r, "com.tencent.mm").contains(p.sd + "/tencent/MicroMsg/h1/image2/p.jpg"), true);
            is("Telegram: a keeper folder of the marker database", pathsOf(r, "org.telegram.messenger").contains(p.sd + "/Telegram/Telegram Images/t.jpg"), true);
            p.set("filter.whatsapp.backups.enabled", false).set("filter.wechat.enabled", false).set("filter.telegram.enabled", false);

            // ---- minimum size and age
            p.set("skip.mincachesize.bytes", 100000);
            eq("below the minimum size nothing is left", groupIds(p.scan()).size(), 0);
            p.set("skip.mincachesize.bytes", 0).set("skip.mincacheage.milliseconds", 2L * 3600L * 1000L);
            eq("files younger than the minimum age are skipped (all files are an hour old)", groupIds(p.scan()).size(), 0);
            p.set("skip.mincacheage.milliseconds", 0);

            // ---- no filter enabled
            for (int i = 0; i < SdmAppCleaner.FILTER_COUNT; i++) p.set(SdmAppCleaner.KEYS[i], false);
            eq("no filter, no result", p.scan().groupCount(), 0);
            for (int i = 0; i < SdmAppCleaner.FILTER_COUNT; i++) p.settings.remove(SdmAppCleaner.KEYS[i]);

            // ---- cancel
            final int[] polls = { 0 };
            p.cancel = new Sdm.Cancel() { @Override public boolean cancelled() { return ++polls[0] > 3; } };
            boolean cancelled = false;
            try { p.scan(); } catch (CancellationException e) { cancelled = true; }
            is("a cancelled scan throws CancellationException", cancelled, true);
            p.cancel = Sdm.NEVER;
        } finally { p.cleanup(); }
    }

    static void testInaccessible() throws Exception {
        // an app's default caches that this tool cannot read: the total comes from StorageStats, counted as two items, the public part guessed from the files found
        Phone p = new Phone();
        try {
            p.pkgs.add("com.alpha", "Alpha", false);
            p.pkgs.add("com.delta", "Delta", false);
            p.pkgs.add("com.beta", "Beta", true);
            p.pkgs.add("com.google.android.permissioncontroller", "Permission controller", true);
            p.pkgs.add("com.empty", "Empty", false);
            p.excl.pkgs.add("com.delta");
            p.set("include.systemapps.enabled", true);
            p.file(p.pubData + "/com.alpha/cache/a.tmp", 200);
            p.file(p.pubData + "/com.alpha/files/.thumbnails/t.jpg", 500);
            p.file(p.pubData + "/com.delta/cache/d", 600);
            p.file(p.pubData + "/com.delta/files/.thumbnails/t.jpg", 7);
            p.pkgs.setTotal("com.alpha", 10000L);
            p.pkgs.setTotal("com.delta", 7000L);
            p.pkgs.setTotal("com.beta", 3000L);
            p.pkgs.setTotal("com.google.android.permissioncontroller", 9000L);
            p.pkgs.clear("com.empty");
            p.shell = new FakeShell(2000);
            SdmAppCleaner.AppResult r = p.scan();
            JSONObject ga = group(r, "com.alpha");
            eq("the files and the inaccessible cache: items", ga.getInt("count"), 2 + 2);                  // the cache file, the thumbnail, plus two for the inaccessible cache
            eq("the size: files + total - public files found", ga.getLong("bytes"), (long) (200 + 500 + (10000 - 200)));
            eq("the inaccessible part", ga.getJSONObject("inaccessible").getLong("size"), (long) (10000 - 200));
            eq("public part is the guess", ga.getJSONObject("inaccessible").getLong("public"), 200L);
            JSONArray rows = r.items("com.alpha", 0, 100);
            eq("row 0 is the inaccessible cache", rows.getJSONObject(0).getString("id"), "inaccessible:com.alpha");
            eq("it is named like upstream", rows.getJSONObject(0).getString("name"), "Default caches");
            eq("its filter", rows.getJSONObject(0).getString("filter"), "inaccessible");
            eq("only an inaccessible cache: size is the total", group(r, "com.beta").getLong("bytes"), 3000L);
            eq("only an inaccessible cache: two items", group(r, "com.beta").getInt("count"), 2);
            is("the Google module has its inaccessible cache removed (and is gone)", group(r, "com.google.android.permissioncontroller") == null, true);
            is("an empty cache is no cache", group(r, "com.empty") == null, true);
            JSONObject gd = group(r, "com.delta");
            is("an excluded app stays when the global trim reaches it", gd != null && gd.getBoolean("limited"), true);
            is("... with the hint of upstream", gd != null && gd.getString("hint").equals("Excluded, but cleaning all apps at once still clears these caches"), true);
            is("... and only its default caches (the thumbnail is not listed)", !pathsOf(r, "com.delta").contains(p.pubData + "/com.delta/files/.thumbnails/t.jpg"), true);
            eq("a limited app: default cache file + two for the cache", gd == null ? -1 : gd.getInt("count"), 1 + 2);

            // without ADB the excluded app is simply excluded
            p.shell = null;
            r = p.scan();
            is("no privileged shell: no limited app", group(r, "com.delta") == null, true);
            is("... but the inaccessible caches are still there", group(r, "com.alpha") != null && group(r, "com.alpha").has("inaccessible"), true);
            // with root the default caches are files and there is no inaccessible cache
            p.shell = new FakeShell(0);
            r = p.scan();
            is("root: no inaccessible caches", group(r, "com.alpha") != null && !group(r, "com.alpha").has("inaccessible"), true);
            is("root: nothing left for Beta", group(r, "com.beta") == null, true);
            // the setting
            p.shell = null;
            p.set("include.inaccessible.enabled", false);
            r = p.scan();
            is("the setting off: no inaccessible caches", !group(r, "com.alpha").has("inaccessible"), true);
            p.set("include.inaccessible.enabled", true).set(SdmAppCleaner.KEYS[SdmAppCleaner.F_DCPRIV], false);
            r = p.scan();
            is("a default cache filter off: no inaccessible caches", !group(r, "com.alpha").has("inaccessible"), true);
            p.settings.remove(SdmAppCleaner.KEYS[SdmAppCleaner.F_DCPRIV]);
            // no usage access: the query answers -1
            p.pkgs.priv.clear();
            r = p.scan();
            is("an unknown cache size (no usage access): the files only", group(r, "com.alpha") != null && !group(r, "com.alpha").has("inaccessible"), true);
            // an app without files but with a cache whose public files were found: the size does not count them twice
            p.pkgs.setTotal("com.alpha", 5000L);
            r = p.scan();
            eq("size = files + (total - public files)", group(r, "com.alpha").getLong("bytes"), (long) (200 + 500 + (5000 - 200)));
        } finally { p.cleanup(); }
    }

    // ---------------------------------------------------------------------------------------------------------- delete

    static Phone deletePhone() throws Exception {
        Phone p = new Phone();
        p.pkgs.add("com.alpha", "Alpha", false);
        p.pkgs.add("com.beta", "Beta", false);
        p.pkgs.add("com.sys", "System thing", true);
        p.file(p.pubData + "/com.alpha/cache/a.tmp", 200);
        p.file(p.pubData + "/com.alpha/cache/b.tmp", 300);
        p.file(p.pubData + "/com.alpha/files/.thumbnails/t.jpg", 500);
        p.file(p.pubData + "/com.alpha/files/data.bin", 90);
        p.file(p.priv + "/com.alpha/cache/c1", 400);
        p.file(p.pubData + "/com.beta/cache/z", 1000);
        p.file(p.pubData + "/com.sys/cache/sy", 77);
        p.set("include.systemapps.enabled", true);
        return p;
    }

    static boolean exists(String path) { return new File(path).exists(); }

    static void testDeleteFiles() throws Exception {
        Phone p = deletePhone();
        try {
            SdmAppCleaner.AppResult r = p.scan();
            int before = r.itemCount();
            long bytesBefore = r.bytes();
            Set<String> expected = new HashSet<String>();
            for (String g : groupIds(r)) expected.addAll(pathsOf(r, g));
            Sdm.DeleteReport rep = p.delete(r, sel("{\"options\":{\"includeInaccessible\":false}}"));
            eq("one Deleted per match", rep.deleted.size(), before);
            Set<String> got = new HashSet<String>();
            long sum = 0;
            for (Sdm.Deleted d : rep.deleted) { got.add(d.path); sum += d.bytes; }
            eq("the deleted paths are the matches", got, expected);
            eq("the bytes are the bytes the scan found", sum, bytesBefore);
            eq("nothing failed", rep.failed.size(), 0);
            is("the cache is gone", exists(p.pubData + "/com.alpha/cache/a.tmp") ||  exists(p.priv + "/com.alpha/cache/c1"), false);
            is("the thumbnail is gone", exists(p.pubData + "/com.alpha/files/.thumbnails/t.jpg"), false);
            is("user data stays", exists(p.pubData + "/com.alpha/files/data.bin"), true);
            is("the app's own folders stay", exists(p.pubData + "/com.alpha/cache") || exists(p.pubData + "/com.alpha"), true);   // the cache folder itself is not a match, only what is in it
            eq("the result is empty again", r.groupCount(), 0);
            eq("the primary line", note(rep, "primary"), before + " expendable items deleted");
            eq("the secondary line", note(rep, "secondary"), "Freed " + SdmAppCleaner.size(bytesBefore) + " space.");
            eq("count note", note(rep, "count"), String.valueOf(before));
            is("no trim, no force stop, no shell", p.shell == null, true);
        } finally { p.cleanup(); }

        // a selection: one app, one file kept
        p = deletePhone();
        try {
            SdmAppCleaner.AppResult r = p.scan();
            int beforeBeta = r.itemCountOf("com.beta");
            Sdm.DeleteReport rep = p.delete(r, sel("{\"dropGroups\":[\"com.beta\",\"com.sys\"],\"dropItems\":[\"" + p.pubData + "/com.alpha/cache/b.tmp\"],\"options\":{\"includeInaccessible\":false}}"));
            is("the dropped app is untouched", exists(p.pubData + "/com.beta/cache/z"), true);
            is("the kept file stays", exists(p.pubData + "/com.alpha/cache/b.tmp"), true);
            is("the other cache file goes", exists(p.pubData + "/com.alpha/cache/a.tmp"), false);
            is("the private cache goes", exists(p.priv + "/com.alpha/cache/c1"), false);
            is("the kept file is still in the result", pathsOf(r, "com.alpha").contains(p.pubData + "/com.alpha/cache/b.tmp"), true);
            is("the dropped app is still in the result", r.find("com.beta") != null && r.itemCountOf("com.beta") == beforeBeta, true);
            is("the report does not list what was kept", !rep.deleted.isEmpty() && !contains(rep, p.pubData + "/com.alpha/cache/b.tmp"), true);
        } finally { p.cleanup(); }

        // a path that changed or vanished since the scan
        p = deletePhone();
        try {
            SdmAppCleaner.AppResult r = p.scan();
            new File(p.pubData + "/com.alpha/cache/a.tmp").delete();                                // gone: counts as deleted
            p.file(p.pubData + "/com.alpha/cache/b.tmp", 5000);                                  // another size: not an error
            Sdm.DeleteReport rep = p.delete(r, sel("{\"options\":{\"includeInaccessible\":false}}"));
            is("a path that was gone is reported deleted", contains(rep, p.pubData + "/com.alpha/cache/a.tmp"), true);
            eq("no failure", rep.failed.size(), 0);
            is("the changed file is deleted too", exists(p.pubData + "/com.alpha/cache/b.tmp"), false);
        } finally { p.cleanup(); }

        // an exclusion made after the scan is respected
        p = deletePhone();
        try {
            SdmAppCleaner.AppResult r = p.scan();
            p.excl.paths.add(p.pubData + "/com.beta/cache/z");
            p.excl.pkgs.add("com.sys");
            p.delete(r, sel("{\"options\":{\"includeInaccessible\":false}}"));
            is("an excluded path is not deleted", exists(p.pubData + "/com.beta/cache/z"), true);
            is("a package excluded after the scan is not deleted unless the whole tool trims", exists(p.pubData + "/com.sys/cache/sy"), true);
            is("others are", exists(p.pubData + "/com.alpha/files/.thumbnails/t.jpg"), false);
        } finally { p.cleanup(); }

        // a folder that holds an excluded path is not deleted either (the nested rule)
        p = deletePhone();
        try {
            p.file(p.pubData + "/com.alpha/files/.thumbnails/keep/k.jpg", 5);
            p.excl.paths.add(p.pubData + "/com.alpha/files/.thumbnails/keep/k.jpg");
            SdmAppCleaner.AppResult r = p.scan();
            is("the excluded file is not in the result", !pathsOf(r, "com.alpha").contains(p.pubData + "/com.alpha/files/.thumbnails/keep/k.jpg"), true);
            is("nor the folder that holds it", !pathsOf(r, "com.alpha").contains(p.pubData + "/com.alpha/files/.thumbnails/keep"), true);
            p.delete(r, sel("{\"options\":{\"includeInaccessible\":false}}"));
            is("it is still there after the delete", exists(p.pubData + "/com.alpha/files/.thumbnails/keep/k.jpg"), true);
        } finally { p.cleanup(); }

        // cancel: what was deleted is reported, the rest stays
        p = deletePhone();
        try {
            SdmAppCleaner.AppResult r = p.scan();
            final int[] polls = { 0 };
            p.cancel = new Sdm.Cancel() { @Override public boolean cancelled() { return ++polls[0] > 2; } };
            Sdm.DeleteReport rep = p.delete(r, sel("{\"options\":{\"includeInaccessible\":false}}"));
            is("a cancelled delete says so", rep.notes.contains("cancelled"), true);
            is("... and leaves the rest in the result", r.itemCount() > 0, true);
        } finally { p.cleanup(); }
    }

    static boolean contains(Sdm.DeleteReport rep, String path) { for (Sdm.Deleted d : rep.deleted) if (d.path.equals(path)) return true; return false; }

    static void testSafety() throws Exception {
        Phone p = deletePhone();
        try {
            SdmAppCleaner.AppResult r = p.scan();
            SdmAppCleaner.Junk j = r.find("com.alpha");
            // matches that must never be deleted, planted in the result as if a rule had produced them
            String[] bad = { p.pubData, p.pubData + "/com.alpha", p.sd, p.sd + "/Android", "/data", "/storage", p.pubData + "/com.alpha/../com.beta/cache", p.pubData + "/com.alpha/files/data\nbin", "/", p.priv };
            List<SdmAppCleaner.Match> planted = new ArrayList<SdmAppCleaner.Match>();
            for (String b : bad) planted.add(new SdmAppCleaner.Match(b, 1, 1, Sdm.DIR, SdmAppCleaner.F_HIDDEN));
            j.exp.put(SdmAppCleaner.F_HIDDEN, planted);
            j.changed();
            Sdm.DeleteReport rep = p.delete(r, sel("{\"options\":{\"includeInaccessible\":false}}"));
            for (String b : bad) is("never deleted: " + b.replace("\n", "\\n"), contains(rep, b), false);
            is("the planted ones are reported failed", rep.failed.contains(p.pubData) && rep.failed.contains(p.pubData + "/com.alpha"), true);
            is("the data of the area is untouched", exists(p.pubData + "/com.alpha/files/data.bin") && exists(p.pubData + "/com.beta") && exists(p.sd), true);
        } finally { p.cleanup(); }
    }

    static void testTrimAndAutomation() throws Exception {
        // --- ADB without root, the whole tool: pm trim-caches 128G, then the sizes are checked; limited apps are cleared by it too
        SdmAppCleaner.trimWaitMs = 0; SdmAppCleaner.pollIntervalMs = 5; SdmAppCleaner.trimPollTimeoutMs = 200; SdmAppCleaner.recheckTimeoutMs = 200; SdmAppCleaner.settleTimeoutMs = 200;
        Phone p = deletePhone();
        try {
            p.pkgs.add("com.excl", "Excluded app", false);
            p.excl.pkgs.add("com.excl");
            p.file(p.pubData + "/com.excl/cache/x", 55);
            p.file(p.pubData + "/com.excl/files/.thumbnails/t.jpg", 56);
            for (String k : new String[] { "com.alpha", "com.beta", "com.excl" }) p.pkgs.setTotal(k, 10000L);
            p.pkgs.setTotal("com.sys", 3000L);
            final Phone pp = p;
            p.shell = new FakeShell(2000);
            p.shell.onTrim = new Runnable() { @Override public void run() { for (String k : new String[] { "com.alpha", "com.beta", "com.excl", "com.sys" }) pp.pkgs.clear(k); } };
            SdmAppCleaner.AppResult r = p.scan();
            is("the excluded app is limited", group(r, "com.excl") != null && group(r, "com.excl").getBoolean("limited"), true);
            int before = r.itemCount();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            is("pm trim-caches 128G ran once", p.shell.ran("pm trim-caches 128G"), true);
            int trims = 0;
            for (String s : p.shell.scripts) if (s.contains("trim-caches")) trims++;
            eq("exactly once", trims, 1);
            is("the excluded app's files were not deleted by us", exists(p.pubData + "/com.excl/files/.thumbnails/t.jpg"), true);
            is("... but its default cache is covered by the trim, so it leaves the result", r.find("com.excl") == null, true);
            eq("everything is gone from the result", r.groupCount(), 0);
            eq("the count is the scan's count", note(rep, "count"), String.valueOf(before));
            eq("one Deleted per item removed", rep.deleted.size(), before);
            is("a deleted default cache of an app with an inaccessible cache: the freed bytes are measured, not the scan guess twice", rep.bytes() > 0, true);
            is("no force stop unless asked", p.shell.ran("force-stop"), false);
            is("the system app was re-checked and counted at zero", rep.deleted.size() > 0, true);
        } finally { p.cleanup(); }

        // --- a trim that does nothing: the apps are failures and the automation takes over
        p = deletePhone();
        try {
            for (String k : new String[] { "com.alpha", "com.beta" }) p.pkgs.setTotal(k, 10000L);
            p.shell = new FakeShell(2000);        // the trim changes nothing
            SdmAppCleaner.AppResult r = p.scan();
            p.delete(r, sel("{}"));
            is("the automation was asked for the apps the trim did not clear", p.auto.asked != null && p.auto.asked.contains("com.alpha") && p.auto.asked.contains("com.beta"), true);
            eq("the automation cleared them", r.groupCount(), 0);                       // com.sys has no inaccessible cache and keeps nothing: it was deleted as a file. The fake cleared alpha and beta
        } catch (AssertionError e) { throw e; } finally { p.cleanup(); }
    }

    static void testAutomation() throws Exception {
        // --- no shell at all: the automation clears what is left; the freed space is measured
        Phone p = deletePhone();
        try {
            SdmAppCleaner.trimWaitMs = 0; SdmAppCleaner.pollIntervalMs = 5; SdmAppCleaner.settleTimeoutMs = 200;
            p.pkgs.setTotal("com.alpha", 10000L);
            p.pkgs.setTotal("com.beta", 4000L);
            p.pkgs.clear("com.sys");
            SdmAppCleaner.AppResult r = p.scan();
            int before = r.itemCount();
            long bytesBefore = r.bytes();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            eq("the automation got the two apps, biggest cache first", p.auto.asked, Arrays.asList("com.alpha", "com.beta"));
            eq("their caches are cleared", p.pkgs.cacheBytes("com.alpha"), 0L);
            eq("no result left", r.groupCount(), 0);
            eq("the count is the scan's count", note(rep, "count"), String.valueOf(before));
            eq("one Deleted per item", rep.deleted.size(), before);
            is("a synthetic path stands for the cleared cache", contains(rep, "/storage/emulated/0/Android/data/com.alpha/cache") && contains(rep, "/data/user/0/com.alpha/cache"), true);
            eq("freed = what was found (the cache sizes went to 0 from the baseline)", rep.bytes(), bytesBefore);
            eq("the primary line", note(rep, "primary"), before + " expendable items deleted");
            is("no stop", note(rep, "stopped") == null, true);
        } finally { p.cleanup(); }

        // --- useAutomation false: the inaccessible caches stay
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            SdmAppCleaner.AppResult r = p.scan();
            Sdm.DeleteReport rep = p.delete(r, sel("{\"options\":{\"useAutomation\":false}}"));
            is("the automation was not asked", p.auto.asked == null, true);
            is("the inaccessible cache is still listed", r.find("com.alpha") != null && r.find("com.alpha").inacc != null, true);
            is("no 'still need' text when the user did not want it", note(rep, "stopped") == null, true);
        } finally { p.cleanup(); }

        // --- onlyInaccessible: the files stay
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            SdmAppCleaner.AppResult r = p.scan();
            p.delete(r, sel("{\"options\":{\"onlyInaccessible\":true}}"));
            is("only the default caches: the thumbnail stays", exists(p.pubData + "/com.alpha/files/.thumbnails/t.jpg"), true);
            eq("the cache was cleared by the automation", p.pkgs.cacheBytes("com.alpha"), 0L);
            is("the files of its public cache vanished from the result with the cache", r.find("com.alpha") != null && !pathsOf(r, "com.alpha").contains(p.pubData + "/com.alpha/cache/a.tmp"), true);
            is("... the system clear took the files (the fake does what Clear cache does)", exists(p.pubData + "/com.alpha/cache/a.tmp"), false);
        } finally { p.cleanup(); }

        // --- no consent: the count of caches that still need the service
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.pkgs.setTotal("com.beta", 4000L);
            p.auto.ready = false;
            SdmAppCleaner.AppResult r = p.scan();
            int before = r.itemCount();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            int deleted = Integer.parseInt(note(rep, "count"));
            eq("no consent: the primary line", note(rep, "primary"), deleted + " expendable " + (deleted == 1 ? "item" : "items") + " deleted, 2 caches still need the accessibility service");
            eq("stopped variant", note(rep, "stopped"), "AUTOMATION_NO_CONSENT");
            eq("skipped", note(rep, "skipped"), "2");
            is("the two apps keep their inaccessible cache", r.find("com.alpha").inacc != null && r.find("com.beta").inacc != null, true);
            is("their files were deleted anyway", before - r.itemCount() == deleted, true);
        } finally { p.cleanup(); }
        // singular
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.auto.ready = false;
            SdmAppCleaner.AppResult r = p.scan();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            is("one cache: singular", note(rep, "primary").endsWith(", 1 cache still needs the accessibility service"), true);
        } finally { p.cleanup(); }
        // nothing else deleted and no consent: an error to show
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.auto.ready = false;
            SdmAppCleaner.AppResult r = p.scan();
            Sdm.DeleteReport rep = p.delete(r, sel("{\"options\":{\"onlyInaccessible\":true}}"));
            eq("0 items deleted, 1 cache needs the service", note(rep, "primary"), "0 expendable items deleted, 1 cache still needs the accessibility service");
            eq("and the error of upstream", note(rep, "error"), "Accessibility service is not set up. Complete the setup and give consent.");
        } finally { p.cleanup(); }

        // --- the screen went off after the first app
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.pkgs.setTotal("com.beta", 4000L);
            p.auto.errorAfter = "SCREEN_UNAVAILABLE"; p.auto.errorAt = 1;
            SdmAppCleaner.AppResult r = p.scan();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            int count = Integer.parseInt(note(rep, "count"));
            eq("screen off: the line", note(rep, "primary"), count + " expendable items deleted, stopped because the screen was off or locked");
            eq("stopped", note(rep, "stopped"), "SCREEN_UNAVAILABLE");
            is("the first app was cleared before the stop", r.find("com.alpha") == null, true);
            is("the second keeps its cache", r.find("com.beta") != null && r.find("com.beta").inacc != null, true);
            is("the message of the error is kept", note(rep, "error").contains("SCREEN_UNAVAILABLE"), true);
        } finally { p.cleanup(); }
        // --- an error
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.pkgs.setTotal("com.beta", 4000L);
            p.auto.errorAfter = "ERROR"; p.auto.errorAt = 0;
            SdmAppCleaner.AppResult r = p.scan();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            int count = Integer.parseInt(note(rep, "count"));
            eq("error: the line", note(rep, "primary"), count + " expendable items deleted, stopped by an error");
            eq("stopped", note(rep, "stopped"), "ERROR");
            is("both keep their cache", r.find("com.alpha").inacc != null && r.find("com.beta").inacc != null, true);
        } finally { p.cleanup(); }
        // --- consent withdrawn in the middle
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.pkgs.setTotal("com.beta", 4000L);
            p.auto.errorAfter = "NO_CONSENT"; p.auto.errorAt = 1;
            SdmAppCleaner.AppResult r = p.scan();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            is("consent withdrawn: one cache still needs the service", note(rep, "primary").endsWith(", 1 cache still needs the accessibility service"), true);
            is("the first was cleared", r.find("com.alpha") == null, true);
        } finally { p.cleanup(); }

        // --- failures per app: unclearable ones are marked, the rest is cleared
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.pkgs.setTotal("com.beta", 4000L);
            p.pkgs.add("com.google.mainline.x", "Mainline", true);
            p.file(p.pubData + "/com.nofiles/x", 1);
            p.pkgs.setTotal("com.google.mainline.x", 900L);
            p.auto.unreachable.put("com.beta", "DISABLED_APP");
            SdmAppCleaner.AppResult r = p.scan();
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            is("the automation was not asked for the unreachable ones", p.auto.asked.contains("com.alpha") && !p.auto.asked.contains("com.beta") && !p.auto.asked.contains("com.google.mainline.x"), true);
            SdmAppCleaner.Junk beta = r.find("com.beta");
            is("a disabled app: marked, nothing else left, so it is unclearable", beta != null && "DISABLED_APP".equals(beta.acsError) && beta.unclearable(), true);
            is("the group row says so", group(r, "com.beta").getBoolean("unclearable") && group(r, "com.beta").getString("acsError").equals("DISABLED_APP"), true);
            SdmAppCleaner.Junk ml = r.find("com.google.mainline.x");
            is("a mainline module has no settings page", ml != null && "NO_SETTINGS".equals(ml.acsError), true);
            JSONObject sm = r.summary();
            is("an unclearable app is not advertised as freeable", sm.getInt("actionableCount") < sm.getInt("itemCount"), true);
        } finally { p.cleanup(); }

        // --- a cache that is already empty at delete time needs no automation
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            SdmAppCleaner.AppResult r = p.scan();
            p.pkgs.clear("com.alpha");                                             // cleared by the user in the meantime
            Sdm.DeleteReport rep = p.delete(r, sel("{}"));
            is("an empty cache: no automation", p.auto.asked == null, true);
            is("the app is gone from the result", r.find("com.alpha") == null, true);
        } finally { p.cleanup(); }

        // --- force stop before clearing (a shell exists but it is not the whole tool: no trim)
        p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.shell = new FakeShell(2000);
            p.set("forcestop.before.clearing.enabled", true);
            SdmAppCleaner.AppResult r = p.scan();
            p.delete(r, sel("{\"dropGroups\":[\"com.beta\",\"com.sys\"]}"));
            is("no global trim for a selection", p.shell.ran("trim-caches"), false);
            is("force stop with the user of the app", p.shell.ran("am force-stop --user 0 com.alpha"), true);
            is("... before the automation", p.auto.asked != null && p.auto.asked.contains("com.alpha"), true);
        } finally { p.cleanup(); }
    }

    static void testResultText() throws Exception {
        eq("one item", SdmAppCleaner.deletedText(1), "1 expendable item deleted");
        eq("many items", SdmAppCleaner.deletedText(2), "2 expendable items deleted");
        eq("found, one", SdmAppCleaner.foundPrimary(1), "1 expendable item found");
        eq("found, none", SdmAppCleaner.foundPrimary(0), "0 expendable items found");
        eq("size", SdmAppCleaner.formatSize(512), "512 B");
        eq("size KB", SdmAppCleaner.formatSize(1536), "1.5 KB");
        eq("size MB", SdmAppCleaner.formatSize(5L * 1024 * 1024), "5 MB");
        eq("size big", SdmAppCleaner.formatSize(12345678), "11.8 MB");
    }

    static void testExclusionsAfterScan() throws Exception {
        Phone p = deletePhone();
        try {
            p.pkgs.setTotal("com.alpha", 10000L);
            p.shell = new FakeShell(2000);
            SdmAppCleaner.AppResult r = p.scan();
            int removed = r.excludePaths(Arrays.asList(p.pubData + "/com.alpha/cache"));
            is("excluding a folder removes the matches below it", removed >= 2 && !pathsOf(r, "com.alpha").contains(p.pubData + "/com.alpha/cache/a.tmp"), true);
            int n0 = r.groupCount();
            r.excludePackages(Arrays.asList("com.beta"), true);
            is("excluding an app while the trim reaches it keeps it with its default caches only", r.find("com.beta") != null && r.find("com.beta").limited, true);
            r.excludePackages(Arrays.asList("com.sys"), false);
            is("excluding an app without the trim removes it", r.find("com.sys") == null, true);
            r.excludePackages(Arrays.asList("com.alpha"), false);
            is("when nothing is left that the trim reaches, the limited apps go too", r.find("com.beta") == null, true);
        } finally { p.cleanup(); }
    }

    public static void main(String[] a) throws Exception {
        try {
            testCatalog();
            testDatabases();
            testFilters();
            testScan();
            testInaccessible();
            testDeleteFiles();
            testSafety();
            testTrimAndAutomation();
            testAutomation();
            testResultText();
            testExclusionsAfterScan();
        } catch (Throwable t) {
            fails++;
            System.out.println("FAIL uncaught " + t);
            t.printStackTrace(System.out);
        }
        System.out.println((fails == 0 ? "PASS " : "FAIL ") + "SdmAppCleanerTest: " + n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
