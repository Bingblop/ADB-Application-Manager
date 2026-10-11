package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-tool-appcleaner: core/AppCleaner.kt, AppJunk.kt, AppCleanerSettings.kt, InaccessibleDeleter.kt, scanner/AppScanner.kt, scanner/PostProcessorModule.kt,
 * scanner/InaccessibleCacheProvider.kt, forensics/filter/*.kt, forensics/BaseExpendablesFilter.kt; app-common-data: clutter/manual/*, forensics/csi/pub|priv).
 * Changed for this port: plain Java against the {@link Sdm} interfaces (no coroutines, no Hilt, no android.*); the 21 filters are one class with a switch and a
 * shared per-file candidate; the first enabled filter in settings-screen order wins (upstream's order is an undefined Dagger set); a package list, running apps,
 * StorageStats and the area roots come from the {@link Sdm.Ctx}; the clutter markers are read from assets/sdm/db_clutter_markers.json by a private loader (only the
 * manual JSON markers: the dynamic marker modules of upstream are not ported); the owner attribution is the part of CSI that AppCleaner needs (installed
 * package, hidden-folder spellings, markers); multi-user is not ported; the delete re-verifies every root with stat and never deletes what the user deselected
 * inside a selected folder; the result of a delete is reported as one {@link Sdm.Deleted} per removed match (so the engine counts the way the scan counted)
 * and as the exact result lines in {@link Sdm.DeleteReport#notes} ("primary: ...", "secondary: ...").
 *
 * <p>Scan (spec 3.2): settings -> package set (system apps, running apps, package exclusions, this app) -> search map (private data listing, shortcut service
 * bitmaps, top-level folders of Android/data, Android/media and the sdcard matched by name or by the marker database, nested marker folders, dynamic owners) ->
 * one walk per search path with every enabled filter per entry -> inaccessible caches (StorageStats, not with root) -> post processing (aliases, exclusions,
 * minimum size) -> sorted by size.
 *
 * <p>Delete (spec 3.6): the files of the selected apps through {@link Sdm.Fs}; then the inaccessible caches: the global {@code pm trim-caches 128G} when the
 * whole tool is cleaned with a shell that is not root, the re-queried size, an optional force-stop, {@link Sdm.Automation} for what is left, and the freed
 * space measured through StorageStats.
 *
 * <p>Notes written to the report (see {@link Sdm.DeleteReport#notes}): "primary: &lt;line&gt;", "secondary: &lt;line&gt;", "count: n", "bytes: n",
 * "stopped: SCREEN_UNAVAILABLE | ERROR | AUTOMATION_NO_CONSENT", "skipped: n" (caches that still need the service), "error: &lt;text&gt;", "cancelled".
 */
public final class SdmAppCleaner implements Sdm.ToolImpl {

    // ---------------------------------------------------------------------------------------------------------- knobs the tests turn down

    static volatile long trimWaitMs = 3000;            // wait after pm trim-caches before measuring
    static volatile long trimPollTimeoutMs = 10000;    // per non-system app: poll StorageStats until the size changed
    static volatile long recheckTimeoutMs = 10000;     // system apps after the trim
    static volatile long pollIntervalMs = 500;
    static volatile long settleTimeoutMs = 4000;       // measuring what the automation freed
    static volatile String ownPkg = "com.bloatware.bingblop";

    public SdmAppCleaner() {}

    @Override public Sdm.Tool tool() { return Sdm.Tool.APPCLEANER; }

    // ---------------------------------------------------------------------------------------------------------- the 21 filters (spec 3.3), in settings-screen order

    static final int F_DCPUB = 0, F_DCPRIV = 1, F_HIDDEN = 2, F_THUMBS = 3, F_CODE = 4, F_ADS = 5, F_BUG = 6, F_ANALYTICS = 7, F_GAME = 8, F_OFFLINE = 9,
            F_TRASH = 10, F_WEBVIEW = 11, F_SHORTCUT = 12, F_WA_BACKUP = 13, F_WA_RECV = 14, F_WA_SENT = 15, F_TELEGRAM = 16, F_THREEMA = 17, F_WECHAT = 18,
            F_VIBER = 19, F_QQ = 20, FILTER_COUNT = 21;

    static final String[] IDS = { "defaultcachespublic", "defaultcachesprivate", "hiddencaches", "thumbnails", "codecache", "advertisement", "bugreporting", "analytics",
            "gamefiles", "offlinecache", "recyclebins", "webview", "shortcutservice", "whatsapp.backups", "whatsapp.received", "whatsapp.sent", "telegram", "threema",
            "wechat", "viber", "mobileqq" };
    static final String[] KEYS = new String[FILTER_COUNT];
    static final boolean[] DEFAULT_ON = { true, true, true, true, true, true, false, true, false, false, false, true, false, false, false, false, false, false, false, false, false };
    static final String[] LABELS = { "Public default caches", "Private default caches", "Hidden caches", "Media thumbnails", "Code cache", "Advertisement data", "Bug reporting",
            "Analytics", "Game files", "Offline cache", "Recycle bins", "Webview files", "App shortcut icons", "WhatsApp backups", "WhatsApp received files",
            "WhatsApp sent files", "Telegram media files", "Threema", "WeChat", "Viber", "QQ chat" };
    static final String[] SUMMARIES = {
            "Default public app caches. Accessible under Android/data on older Android versions. Deleted when tapping \"Clear cache\" in the system settings.",
            "Default private app caches. Deleted when tapping \"Clear cache\" in the system settings.",
            "General purpose caches stored outside of the system's reach.",
            "Image, video and audio preview thumbnail pictures.",
            "JVM code cache. Blocks of bytecode compiled into native code.",
            "Files related to advertisements, e.g. cached media.",
            "Bug reporting related data, e.g. error logs or crash dumps.",
            "Analytics related data, e.g. cached stats for what you tap in an app.",
            "Game related resources that the game would reload on next launch if necessary.",
            "Files stored for use when offline, e.g. map tiles.",
            "Files that have been deleted but put into a special folder to allow to restore them.",
            "Files cached by Webview instances used within apps.",
            "Cached icons for app shortcuts and share menus.",
            "Delete extra copies of WhatsApp backups and only keep the latest.",
            "Files you received from people via WhatsApp.",
            "Files sent from WhatsApp to others.",
            "Files received or send through Telegram.",
            "Files received through Threema.",
            "Sent or received files in conversations and WeChat moments.",
            "Cached media files that are downloaded again when viewed in chat.",
            "Sent or received files in QQ chat conversations." };
    static {
        for (int i = 0; i < FILTER_COUNT; i++) KEYS[i] = "filter." + IDS[i] + ".enabled";
    }

    /** The filters as the settings screen and the page see them: [{id, key, label, summary, default}]. */
    public static JSONArray filterCatalog() {
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < FILTER_COUNT; i++) {
                a.put(new JSONObject().put("id", IDS[i]).put("key", KEYS[i]).put("label", LABELS[i]).put("summary", SUMMARIES[i]).put("default", DEFAULT_ON[i]));
            }
        } catch (JSONException e) { throw new IllegalStateException(e); }
        return a;
    }

    private static Set<String> set(String... a) { return new HashSet<String>(Arrays.asList(a)); }
    private static Set<String> lowerSet(String... a) { Set<String> s = new HashSet<String>(); for (String x : a) s.add(x.toLowerCase(Locale.ROOT)); return s; }

    private static final Set<String> HIDDEN_FOLDERS = lowerSet("tmp", ".tmp", "tmpdata", "tmp-data", "tmp_data", ".tmpdata", ".tmp-data", ".tmp_data", ".temp", "temp",
            "tempdata", "temp-data", "temp_data", ".tempdata", ".temp-data", ".temp_data", ".cache", "cache", "_cache", "-cache", ".caches", "caches", "_caches", "-caches",
            "imagecache", "image-cache", "image_cache", ".imagecache", ".image-cache", ".image_cache", "imagecaches", "image-caches", "image_caches", ".imagecaches",
            ".image-caches", ".image_caches", "videocache", "video-cache", "video_cache", ".videocache", ".video-cache", ".video_cache", "videocaches", "video-caches",
            "video_caches", ".videocaches", ".video-caches", ".video_caches", "mediacache", "media-cache", "media_cache", ".mediacache", ".media-cache", ".media_cache",
            "mediacaches", "media-caches", "media_caches", ".mediacaches", ".media-caches", ".media_caches", "diskcache", "disk-cache", "disk_cache", ".diskcache",
            ".disk-cache", ".disk_cache", "diskcaches", "disk-caches", "disk_caches", ".diskcaches", ".disk-caches", ".disk_caches", "filescache", "AVFSCache");
    private static final Set<String> HIDDEN_FILES = lowerSet("cache.dat", "tmp.dat", "temp.dat", ".temp.jpg");
    private static final Set<String> AD_FOLDERS = lowerSet("vast_rtb_cache", "GoAdSdk", "IFlyAdImgCache");
    private static final String[] AD_SEGS = { "files", "mb", "res", ".mbridge" };
    private static final Set<String> BUG_FOLDERS = lowerSet("logs", ".logs", "logfiles", ".logfiles", "log", ".log", "logtmp", ".logtmp", "gslb_sdk_log", "klog", "mipushlog", "xlog", "tlog_v9");
    private static final Set<String> BUG_FILES = lowerSet("log.txt", "usage_logs_v2.txt", "gslb_sdk_log", "update_component_log", "update_component_plugin_log", "gslb_log.txt",
            "usage_logs_v2.txt", "app_upgrade_log");
    private static final Pattern BUG_LOGFILE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.log\\.txt");
    private static final Set<String> GAME_FOLDERS = set("unitycache");
    private static final Set<String> OFFLINE_FOLDERS = set("offlinecache", "offline-cache", "offline_cache", ".offlinecache", ".offline-cache", ".offline_cache");
    private static final Set<String> TRASH_FOLDERS = set(".trash", "trash", ".trashfiles", "trashfiles", ".trashbin", "trashbin", ".recycle", "recycle", ".recyclebin", "recyclebin", ".garbage");
    private static final Set<String> THUMB_FOLDERS = set(".thumbs", "thumbs", ".thumbnails", "thumbnails", "albumthumbs");
    private static final String[][] WEBVIEW_CACHES = {
            SdmSieve.toSegs("app_webview/Cache"), SdmSieve.toSegs("app_webview/Application Cache"), SdmSieve.toSegs("app_webview/Service Worker/CacheStorage"),
            SdmSieve.toSegs("app_webview/Service Worker/ScriptCache"), SdmSieve.toSegs("app_webview/GPUCache"), SdmSieve.toSegs("app_chrome/ShaderCache"),
            SdmSieve.toSegs("app_chrome/GrShaderCache"), SdmSieve.toSegs("app_chrome/Default/Application Cache"), SdmSieve.toSegs("app_chrome/Default/Service Worker/CacheStorage"),
            SdmSieve.toSegs("app_chrome/Default/Service Worker/ScriptCache"), SdmSieve.toSegs("app_chrome/Default/GPUCache") };

    private static final Set<String> WA_PKGS = set("com.whatsapp", "com.whatsapp.w4b");
    private static final String[][] WA_BACKUP_PREFIXES;
    private static final Pattern[] WA_BACKUP_FILES = {
            Pattern.compile("msgstore-.+?\\.1\\.db\\.crypt\\d+"), Pattern.compile("backup_settings-.+?\\.1\\.json\\.crypt\\d+"), Pattern.compile("chatsettingsbackup-.+?\\.1\\.db\\.crypt\\d+"),
            Pattern.compile("commerce_backup-.+?\\.1\\.db\\.crypt\\d+"), Pattern.compile("stickers-.+?\\.1\\.db\\.crypt\\d+"), Pattern.compile("wa-.+?\\.1\\.db\\.crypt\\d+"),
            Pattern.compile("wallpapers-.+?\\.1\\.backup\\.crypt\\d+") };
    static {
        String[] pre1 = { "WhatsApp", "com.whatsapp", "com.whatsapp/WhatsApp", "WhatsApp Business", "com.whatsapp.w4b", "com.whatsapp.w4b/WhatsApp Business" };
        String[] pre2 = { "Databases", "Backups" };
        List<String[]> l = new ArrayList<String[]>();
        for (String a : pre1) for (String b : pre2) l.add(SdmSieve.toSegs(a + "/" + b));
        WA_BACKUP_PREFIXES = l.toArray(new String[0][]);
    }

    /** One rule set of the filters that are built from criteria (upstream DynamicAppSieve2.MatchConfig). */
    static final class Dyn {
        final Set<String> pkgs;
        final Set<Sdm.Area> areas;
        final SdmSieve.Crit[] criteria, exclusions;
        Dyn(Set<String> pkgs, EnumSet<Sdm.Area> areas, SdmSieve.Crit[] criteria, SdmSieve.Crit[] exclusions) {
            this.pkgs = pkgs; this.areas = areas; this.criteria = criteria; this.exclusions = exclusions;
        }
        boolean matches(String pkg, Sdm.Area area, String[] pfp) {
            if (!areas.isEmpty() && !areas.contains(area)) return false;
            if (!pkgs.isEmpty() && !pkgs.contains(pkg)) return false;
            if (exclusions.length > 0 && SdmSieve.matchAny(exclusions, pfp)) return false;
            return SdmSieve.matchAny(criteria, pfp);
        }
    }

    private static final SdmSieve.Crit[] NOMEDIA = { SdmSieve.Name.eq(".nomedia") };

    private static boolean dyn(List<Dyn> cfg, String pkg, Sdm.Area area, String[] pfp) {
        for (Dyn d : cfg) if (d.matches(pkg, area, pfp)) return true;
        return false;
    }

    private static SdmSieve.Crit[] anc(String... raw) {
        SdmSieve.Crit[] c = new SdmSieve.Crit[raw.length];
        for (int i = 0; i < raw.length; i++) c[i] = SdmSieve.Seg.anc(raw[i]);
        return c;
    }

    private static final List<Dyn> TELEGRAM = new ArrayList<Dyn>(), THREEMA = new ArrayList<Dyn>(), WECHAT = new ArrayList<Dyn>(), VIBER = new ArrayList<Dyn>(),
            QQ = new ArrayList<Dyn>(), WA_RECV = new ArrayList<Dyn>(), WA_SENT = new ArrayList<Dyn>();
    static {
        String[] tgKinds = { "Audio", "Documents", "Images", "Video", "Stories" };
        for (String pkg : new String[] { "org.telegram.messenger", "ir.ilmili.telegraph", "org.telegram.messenger.web" }) {
            List<SdmSieve.Crit> c = new ArrayList<SdmSieve.Crit>();
            for (String k : tgKinds) c.add(SdmSieve.Seg.anc("Telegram/Telegram " + k));
            for (String k : tgKinds) c.add(SdmSieve.Seg.anc(pkg + "/files/Telegram/Telegram " + k));
            TELEGRAM.add(new Dyn(set(pkg), EnumSet.of(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA), c.toArray(new SdmSieve.Crit[0]), NOMEDIA));
        }
        List<SdmSieve.Crit> plus = new ArrayList<SdmSieve.Crit>();
        for (String k : tgKinds) plus.add(SdmSieve.Seg.anc("Telegram/Telegram " + k));
        TELEGRAM.add(new Dyn(set("org.telegram.plus"), EnumSet.of(Sdm.Area.SDCARD), plus.toArray(new SdmSieve.Crit[0]), NOMEDIA));
        List<SdmSieve.Crit> chal = new ArrayList<SdmSieve.Crit>();
        for (String k : tgKinds) chal.add(SdmSieve.Seg.anc("Telegram/Telegram " + k));
        for (String k : new String[] { "documents", "music", "videos", "video_notes", "animations", "voice", "photos", "stories" }) chal.add(SdmSieve.Seg.anc("org.thunderdog.challegram/files/" + k));
        TELEGRAM.add(new Dyn(set("org.thunderdog.challegram"), EnumSet.of(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA), chal.toArray(new SdmSieve.Crit[0]), NOMEDIA));

        THREEMA.add(new Dyn(set("ch.threema.app"), EnumSet.of(Sdm.Area.SDCARD), anc("Threema/Threema Audio", "Threema/Threema Pictures", "Threema/Threema Videos"), NOMEDIA));

        SdmSieve.Crit wechatCrit = SdmSieve.or(SdmSieve.Seg.specific("sns", 1, true, true, false), SdmSieve.Seg.specific("video", 1, true, true, false),
                SdmSieve.Seg.specific("image2", 1, true, true, false), SdmSieve.Seg.specific("voice2", 1, true, true, false));
        WECHAT.add(new Dyn(set("com.tencent.mm"), EnumSet.of(Sdm.Area.SDCARD), new SdmSieve.Crit[] { SdmSieve.and(SdmSieve.Seg.anc("tencent/MicroMsg"), wechatCrit) }, NOMEDIA));
        WECHAT.add(new Dyn(set("com.tencent.mm"), EnumSet.of(Sdm.Area.PUBLIC_DATA), new SdmSieve.Crit[] { SdmSieve.and(SdmSieve.Seg.anc("com.tencent.mm/MicroMsg"), wechatCrit) }, NOMEDIA));
        WECHAT.add(new Dyn(set("com.tencent.mm"), EnumSet.of(Sdm.Area.PRIVATE_DATA), new SdmSieve.Crit[] { SdmSieve.and(SdmSieve.Seg.anc("com.tencent.mm/MicroMsg"), wechatCrit) }, NOMEDIA));

        List<SdmSieve.Crit> vb = new ArrayList<SdmSieve.Crit>();
        for (String k : new String[] { ".converted_gifs", ".converted_videos", ".import", ".image", ".video", ".gif", ".ptt", ".vptt" }) vb.add(SdmSieve.Seg.anc("com.viber.voip/files/" + k));
        VIBER.add(new Dyn(set("com.viber.voip"), EnumSet.of(Sdm.Area.PUBLIC_DATA), vb.toArray(new SdmSieve.Crit[0]), NOMEDIA));

        QQ.add(new Dyn(set("com.tencent.mobileqq"), EnumSet.of(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA),
                anc("Tencent/MobileQQ/chatpic", "Tencent/MobileQQ/shortvideo", "com.tencent.mobileqq/MobileQQ/chatpic", "com.tencent.mobileqq/MobileQQ/shortvideo"), NOMEDIA));
        SdmSieve.Crit ptt = SdmSieve.Seg.specific("ptt", 1, true, true, false);
        QQ.add(new Dyn(set("com.tencent.mobileqq"), EnumSet.of(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA),
                new SdmSieve.Crit[] { SdmSieve.and(SdmSieve.Seg.anc("com.tencent.mobileqq/MobileQQ"), ptt), SdmSieve.and(SdmSieve.Seg.anc("Tencent/MobileQQ"), ptt) }, NOMEDIA));

        String[][] wa = { { "com.whatsapp", "WhatsApp" }, { "com.whatsapp.w4b", "WhatsApp Business" } };
        for (String[] p : wa) {
            String pkg = p[0], name = p[1];
            String[][] maps = { { "SDCARD", name, name }, { "PUBLIC_MEDIA", pkg + "/" + name, name } };
            for (String[] m : maps) {
                String folder1 = m[1], folder2 = m[2];
                EnumSet<Sdm.Area> loc = EnumSet.of(Sdm.Area.valueOf(m[0]));
                WA_RECV.add(new Dyn(set(pkg), loc, new SdmSieve.Crit[] {
                        SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Video"), SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Images"),
                        SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Animated Gifs"), SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Audio"),
                        SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Documents"),
                        SdmSieve.and(SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Voice Notes"), SdmSieve.Seg.specific(folder2 + " Voice Notes", 2, true, true, false)) },
                        new SdmSieve.Crit[] { SdmSieve.Name.eq(".nomedia"), SdmSieve.Name.eq("Sent"), SdmSieve.Name.eq("Private"), SdmSieve.Seg.specific("Sent", 1, true, true, false) }));
                WA_SENT.add(new Dyn(set(pkg), loc, new SdmSieve.Crit[] {
                        SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Video/Sent"), SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Animated Gifs/Sent"),
                        SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Images/Sent"), SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Audio/Sent"),
                        SdmSieve.Seg.anc(folder1 + "/Media/" + folder2 + " Documents/Sent") }, NOMEDIA));
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------- one file as the filters see it

    /** A file or folder found in a search path: the package it is attributed to, the entry, the area and its path below the area root. */
    static final class Cand {
        final String pkg;
        final Sdm.Entry e;
        final Sdm.Area area;
        final String[] pfp;
        final long nowSec;
        private String[] lc;
        Cand(String pkg, Sdm.Entry e, Sdm.Area area, String[] pfp, long nowSec) { this.pkg = pkg; this.e = e; this.area = area; this.pfp = pfp; this.nowSec = nowSec; }
        String[] lc() {
            if (lc == null) { lc = new String[pfp.length]; for (int i = 0; i < pfp.length; i++) lc[i] = pfp[i].toLowerCase(Locale.ROOT); }
            return lc;
        }
    }

    private static boolean isDescendant(String[] segs, String[] ancestor) {
        return segs.length > ancestor.length && SdmSieve.startsWith(segs, ancestor, false);
    }

    /** What the filters need besides the file: the settings are read once, the JSON databases are loaded once. */
    static final class Filters {
        final boolean[] enabled = new boolean[FILTER_COUNT];
        final SdmAppSieve[] sieves = new SdmAppSieve[FILTER_COUNT];
        int enabledCount;
        int[] order;

        Filters(Sdm.Ctx ctx) throws IOException {
            List<Integer> on = new ArrayList<Integer>();
            for (int i = 0; i < FILTER_COUNT; i++) {
                enabled[i] = ctx.bool(KEYS[i], DEFAULT_ON[i]);
                if (enabled[i]) on.add(i);
            }
            enabledCount = on.size();
            order = new int[enabledCount];
            for (int i = 0; i < enabledCount; i++) order[i] = on.get(i);
            String[] db = new String[FILTER_COUNT];
            db[F_ADS] = "expendables/db_advertisement_files.json"; db[F_ANALYTICS] = "expendables/db_analytics_files.json";
            db[F_BUG] = "expendables/db_bug_reporting_files.json"; db[F_GAME] = "expendables/db_downloaded_game_files.json";
            db[F_HIDDEN] = "expendables/db_hidden_caches_files.json"; db[F_OFFLINE] = "expendables/db_offline_cache_files.json";
            db[F_THUMBS] = "expendables/db_thumbnail_files.json"; db[F_TRASH] = "expendables/db_trash_files.json"; db[F_WEBVIEW] = "expendables/db_webcaches.json";
            for (int i = 0; i < FILTER_COUNT; i++) if (enabled[i] && db[i] != null) sieves[i] = SdmAppSieve.load(db[i]);
        }

        /** The first enabled filter (settings order) that takes the file, or -1. */
        int match(Cand c) {
            for (int k = 0; k < order.length; k++) if (matchOne(order[k], c)) return order[k];
            return -1;
        }

        boolean sieve(int f, Cand c) { return c.pfp.length > 0 && sieves[f].matches(c.pkg, c.area, c.pfp); }

        boolean matchOne(int f, Cand c) {
            final String[] pfp = c.pfp;
            final int n = pfp.length;
            switch (f) {
                case F_DCPUB: {
                    if (n > 0 && ".nomedia".equals(pfp[n - 1])) return false;
                    if (!c.area.caseInsensitive) return false;                      // not a public area
                    return n >= 3 && "cache".equals(pfp[1]);
                }
                case F_DCPRIV: {
                    if (c.area.caseInsensitive) return false;
                    return n >= 3 && "cache".equals(pfp[1]);
                }
                case F_CODE:
                    return n >= 3 && c.area == Sdm.Area.PRIVATE_DATA && "code_cache".equals(pfp[1]);
                case F_HIDDEN: {
                    if (n >= 2 && c.pkg.equals(pfp[0]) && "cache".equals(pfp[1])) return false;          // case matters: other spellings are hidden caches
                    String[] lc = c.lc();
                    if (n > 0 && ".nomedia".equals(lc[n - 1])) return false;
                    if (n == 2 && HIDDEN_FILES.contains(lc[1])) return true;
                    if (n == 3 && HIDDEN_FILES.contains(lc[2])) return true;
                    if (n >= 3 && HIDDEN_FOLDERS.contains(lc[1])) return true;
                    if (n >= 4 && pfp[2].equals("Cache") && pfp[3].contains(".unity3d&")) return false;
                    if (n >= 4 && "files".equals(lc[1]) && (HIDDEN_FOLDERS.contains(lc[2]) || "cache".equals(lc[2]))) return true;
                    if (n >= 4 && c.area == Sdm.Area.SDCARD && HIDDEN_FOLDERS.contains(lc[2])) return true;
                    return sieve(f, c);
                }
                case F_THUMBS: {
                    String[] lc = c.lc();
                    if (n > 0 && ".nomedia".equals(lc[n - 1])) return false;
                    if (n >= 2 && THUMB_FOLDERS.contains(lc[0])) return true;
                    if (n >= 2 && c.pkg.equals(lc[0]) && "cache".equals(lc[1])) return false;
                    if (n >= 3 && THUMB_FOLDERS.contains(lc[1])) return true;
                    if (n >= 4 && "files".equals(lc[1]) && THUMB_FOLDERS.contains(lc[2])) return true;
                    if (n >= 4 && c.area == Sdm.Area.SDCARD && THUMB_FOLDERS.contains(lc[2])) return true;
                    return sieve(f, c);
                }
                case F_ADS: {
                    String[] lc = c.lc();
                    if (n >= 2 && (c.area == Sdm.Area.PRIVATE_DATA || c.area == Sdm.Area.PUBLIC_DATA) && c.pkg.equals(lc[0]) && "cache".equals(lc[1])) return false;
                    if (n > 0 && ".nomedia".equals(lc[n - 1])) return false;
                    if (n >= 3 && AD_FOLDERS.contains(lc[1])) return true;
                    if (n >= 4 && "files".equals(lc[1]) && AD_FOLDERS.contains(lc[2])) return true;
                    if (n >= 1 && SdmSieve.isAncestorSegs(AD_SEGS, Arrays.copyOfRange(lc, 1, n), false)) return true;
                    return sieve(f, c);
                }
                case F_BUG: {
                    String[] lc = c.lc();
                    if (n >= 2 && (c.area == Sdm.Area.PRIVATE_DATA || c.area == Sdm.Area.PUBLIC_DATA) && c.pkg.equals(lc[0]) && "cache".equals(lc[1])) return false;
                    if (n > 0 && ".nomedia".equals(lc[n - 1])) return false;
                    if (n >= 2 && BUG_LOGFILE.matcher(lc[1]).matches()) return true;
                    if (n >= 2 && BUG_FILES.contains(lc[1])) return true;
                    if (n >= 3 && BUG_FOLDERS.contains(lc[1])) return true;
                    if (n >= 3 && BUG_FILES.contains(lc[2])) return true;
                    if (n >= 4 && (c.area == Sdm.Area.PUBLIC_DATA || c.area == Sdm.Area.PRIVATE_DATA) && "files".equals(lc[1]) && BUG_FOLDERS.contains(lc[2])) return true;
                    if (sieve(f, c)) return true;
                    if (isDescendant(pfp, SdmSieve.toSegs(c.pkg + "/files/.com.google.firebase.crashlytics.files.v2:" + c.pkg))) return true;
                    return isDescendant(pfp, SdmSieve.toSegs(c.pkg + "/files/.crashlytics.v3/" + c.pkg));
                }
                case F_ANALYTICS: {
                    if (n > 0 && ".nomedia".equals(pfp[n - 1])) return false;
                    return sieve(f, c);
                }
                case F_GAME: {
                    String[] lc = c.lc();
                    if (n > 0 && ".nomedia".equals(lc[n - 1])) return false;
                    if (n >= 3 && GAME_FOLDERS.contains(lc[1])) return true;
                    if (n >= 4 && "files".equals(lc[1]) && GAME_FOLDERS.contains(lc[2])) return true;
                    return sieve(f, c);
                }
                case F_OFFLINE: {
                    if (n > 0 && ".nomedia".equals(pfp[n - 1])) return false;
                    String[] lc = c.lc();
                    if (n >= 3 && OFFLINE_FOLDERS.contains(lc[1])) return true;
                    if (n >= 4 && "files".equals(lc[1]) && OFFLINE_FOLDERS.contains(lc[2])) return true;
                    return sieve(f, c);
                }
                case F_TRASH: {
                    String[] lc = c.lc();
                    if (n > 0 && ".nomedia".equals(lc[n - 1])) return false;
                    boolean areaOk = c.area == Sdm.Area.SDCARD || c.area == Sdm.Area.PRIVATE_DATA || c.area == Sdm.Area.PUBLIC_DATA || c.area == Sdm.Area.PUBLIC_MEDIA;
                    boolean notUnderAndroid = c.area != Sdm.Area.SDCARD || !SdmSieve.isAncestorSegs(new String[] { "Android" }, lc, true);
                    if (n >= 3 && areaOk && notUnderAndroid && TRASH_FOLDERS.contains(lc[1])) return true;
                    if (n >= 4 && areaOk && notUnderAndroid && "files".equals(lc[1]) && TRASH_FOLDERS.contains(lc[2])) return true;
                    if (n >= 4 && "android".equals(lc[0]) && ".trash".equals(lc[1]) && lc[2].equals(c.pkg)) return true;
                    return sieve(f, c);
                }
                case F_WEBVIEW: {
                    if (n > 0 && ".nomedia".equals(pfp[n - 1])) return false;
                    if (n >= 2 && c.pkg.equals(pfp[0])) {
                        String[] rest = Arrays.copyOfRange(pfp, 1, n);
                        for (String[] w : WEBVIEW_CACHES) if (SdmSieve.isAncestorSegs(w, rest, false)) return true;
                    }
                    return sieve(f, c);
                }
                case F_SHORTCUT: {
                    if (c.area != Sdm.Area.DATA_SYSTEM_CE) return false;
                    return n >= 4 && "shortcut_service".equals(pfp[0]) && "bitmaps".equals(pfp[1]) && c.pkg.equals(pfp[2]);
                }
                case F_WA_BACKUP: {
                    if (n > 0 && ".nomedia".equals(pfp[n - 1])) return false;
                    if (!WA_PKGS.contains(c.pkg)) return false;
                    boolean pre = false;
                    for (String[] p : WA_BACKUP_PREFIXES) if (SdmSieve.startsWith(pfp, p, false)) { pre = true; break; }
                    if (!pre) return false;
                    boolean file = false;
                    for (Pattern p : WA_BACKUP_FILES) if (p.matcher(pfp[n - 1]).matches()) { file = true; break; }
                    if (!file) return false;
                    if (c.e.mtime == 0) return false;
                    return (c.nowSec - c.e.mtime) > 24L * 3600L;
                }
                case F_WA_RECV: return n > 0 && dyn(WA_RECV, c.pkg, c.area, pfp);
                case F_WA_SENT: return n > 0 && dyn(WA_SENT, c.pkg, c.area, pfp);
                case F_TELEGRAM: return n > 0 && dyn(TELEGRAM, c.pkg, c.area, pfp);
                case F_THREEMA: return n > 0 && dyn(THREEMA, c.pkg, c.area, pfp);
                case F_WECHAT: return n > 0 && dyn(WECHAT, c.pkg, c.area, pfp);
                case F_VIBER: return n > 0 && dyn(VIBER, c.pkg, c.area, pfp);
                case F_QQ: return n > 0 && dyn(QQ, c.pkg, c.area, pfp);
                default: return false;
            }
        }
    }

    /** Test hook: the filter that takes a file, by id ("hiddencaches"), or null. The package is the owner, pfp the path below the area root. */
    static String filterOf(Sdm.Ctx ctx, String pkg, Sdm.Area area, String pfpPath, long size, long mtime, long nowSec) throws IOException {
        Filters f = new Filters(ctx);
        String path = "/" + pfpPath;
        int fi = f.match(new Cand(pkg, new Sdm.Entry(path, size, mtime, Sdm.FILE, -1), area, pfpPath.split("/"), nowSec));
        return fi < 0 ? null : IDS[fi];
    }

    // ---------------------------------------------------------------------------------------------------------- clutter markers (assets/sdm/db_clutter_markers.json)

    /** One marker of the database: where an outlier folder of an app lives (upstream ManualMarker). */
    static final class Marker {
        final Set<String> pkgs;
        final Sdm.Area area;
        final String[] path;          // null when the marker has only a regex
        final String contains;
        final Pattern regex;
        final boolean custodian, keeper, common;
        Marker(Set<String> pkgs, Sdm.Area area, String[] path, String contains, String regex, boolean keeper, boolean common, boolean custodian) {
            this.pkgs = pkgs; this.area = area; this.path = path; this.contains = contains; this.keeper = keeper; this.common = common; this.custodian = custodian;
            this.regex = regex == null ? null : area.caseInsensitive ? Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : Pattern.compile(regex);
        }
        boolean direct() { return regex == null; }
        boolean matches(Sdm.Area a, String[] segs) {
            if (a != area || segs.length == 0) return false;
            boolean ci = area.caseInsensitive;
            if (path != null && regex == null) return SdmSieve.sameSegs(segs, path, ci);
            if (regex != null) {
                if (path != null && !SdmSieve.startsWithPartial(segs, path, ci)) return false;
                String joined = SdmSieve.join(segs);
                if (contains != null && !(ci ? joined.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT)) : joined.contains(contains))) return false;
                return regex.matcher(joined).matches();
            }
            return false;
        }
    }

    /** The markers of every package, indexed by package and by area / first folder name. */
    static final class Markers {
        final Map<String, List<Marker>> byPkg = new HashMap<String, List<Marker>>();
        final Map<Sdm.Area, Map<String, List<Marker>>> oneSegment = new HashMap<Sdm.Area, Map<String, List<Marker>>>();
        final Map<Sdm.Area, List<Marker>> regexMarkers = new HashMap<Sdm.Area, List<Marker>>();
        int count;

        static String key(Sdm.Area a, String name) { return a.caseInsensitive ? name.toLowerCase(Locale.ROOT) : name; }

        /** Reads the database; a missing or damaged file gives an empty set of markers (the scan then only finds what a name or a rule shows). */
        static Markers load(Collection<String> installedPkgs) {
            Markers m = new Markers();
            JSONArray groups;
            try {
                groups = new JSONArray(SdmAppSieve.readAsset("sdm/db_clutter_markers.json"));
            } catch (Exception e) {
                return m;
            }
            for (int g = 0; g < groups.length(); g++) {
                JSONObject grp = groups.optJSONObject(g);
                if (grp == null) continue;
                JSONArray mrks = grp.optJSONArray("mrks");
                if (mrks == null || mrks.length() == 0) continue;
                Set<String> pkgs = new LinkedHashSet<String>();
                JSONArray pk = grp.optJSONArray("pkgs");
                if (pk != null) for (int i = 0; i < pk.length(); i++) pkgs.add(pk.optString(i));
                JSONArray rp = grp.optJSONArray("regexPkgs");
                if (rp != null) {
                    for (int i = 0; i < rp.length(); i++) {
                        Pattern p;
                        try { p = Pattern.compile(rp.optString(i)); } catch (RuntimeException e) { continue; }
                        for (String ip : installedPkgs) if (p.matcher(ip).matches()) pkgs.add(ip);
                    }
                    if (pkgs.isEmpty()) for (int i = 0; i < rp.length(); i++) pkgs.add(rp.optString(i));
                }
                if (pkgs.isEmpty()) continue;
                for (int i = 0; i < mrks.length(); i++) {
                    JSONObject o = mrks.optJSONObject(i);
                    if (o == null) continue;
                    Sdm.Area area;
                    try { area = Sdm.Area.valueOf(o.optString("loc")); } catch (RuntimeException e) { continue; }
                    String path = o.optString("path", null), contains = o.optString("contains", null), regex = o.optString("regex", null);
                    if (path == null && contains == null && regex == null) continue;
                    boolean keeper = false, common = false, custodian = false;
                    JSONArray fl = o.optJSONArray("flags");
                    if (fl != null) for (int k = 0; k < fl.length(); k++) {
                        String f = fl.optString(k);
                        if ("keeper".equals(f)) keeper = true; else if ("common".equals(f)) common = true; else if ("custodian".equals(f)) custodian = true;
                    }
                    try {
                        m.add(new Marker(pkgs, area, path == null ? null : SdmSieve.toSegs(path), contains, regex, keeper, common, custodian));
                    } catch (RuntimeException e) {
                        // an unusable regex is skipped like the rest of a damaged entry
                    }
                }
            }
            return m;
        }

        void add(Marker mk) {
            count++;
            for (String p : mk.pkgs) {
                List<Marker> l = byPkg.get(p);
                if (l == null) { l = new ArrayList<Marker>(); byPkg.put(p, l); }
                l.add(mk);
            }
            if (mk.direct() && mk.path != null && mk.path.length == 1) {
                Map<String, List<Marker>> idx = oneSegment.get(mk.area);
                if (idx == null) { idx = new HashMap<String, List<Marker>>(); oneSegment.put(mk.area, idx); }
                String k = key(mk.area, mk.path[0]);
                List<Marker> l = idx.get(k);
                if (l == null) { l = new ArrayList<Marker>(); idx.put(k, l); }
                l.add(mk);
            } else if (mk.regex != null) {
                List<Marker> l = regexMarkers.get(mk.area);
                if (l == null) { l = new ArrayList<Marker>(); regexMarkers.put(mk.area, l); }
                l.add(mk);
            }
        }

        /** The markers that name this top-level folder of the area. */
        List<Marker> matchTop(Sdm.Area area, String name) {
            List<Marker> out = new ArrayList<Marker>();
            Map<String, List<Marker>> idx = oneSegment.get(area);
            if (idx != null) { List<Marker> l = idx.get(key(area, name)); if (l != null) out.addAll(l); }
            List<Marker> rx = regexMarkers.get(area);
            if (rx != null) { String[] segs = { name }; for (Marker r : rx) if (r.matches(area, segs)) out.add(r); }
            return out;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the model of a scan

    static final class Match {
        final String path;
        final long size, mtime;
        final int type, filter;
        Match(String path, long size, long mtime, int type, int filter) { this.path = path; this.size = size; this.mtime = mtime; this.type = type; this.filter = filter; }
        String name() { int i = path.lastIndexOf('/'); return i < 0 ? path : path.substring(i + 1); }
    }

    /** What the system keeps for an app and this tool cannot reach: its default caches (upstream InaccessibleCache). */
    static final class Inacc {
        final long total;
        Long publicSize;
        final String[] theoreticalPaths;
        Inacc(long total, Long publicSize, String[] theoreticalPaths) { this.total = total; this.publicSize = publicSize; this.theoreticalPaths = theoreticalPaths; }
        static final int ITEM_COUNT = 2;
        long privateSize() { return total - (publicSize == null ? 0L : publicSize); }
        Inacc with(long newTotal, Long newPublic) { return new Inacc(newTotal, newPublic, theoreticalPaths); }
    }

    /** One app with what can be cleaned for it (upstream AppJunk). */
    static final class Junk {
        final Sdm.Pkg pkg;
        final boolean limited;                                         // excluded from the scan, but the global trim reaches it anyway
        final Map<Integer, List<Match>> exp = new LinkedHashMap<Integer, List<Match>>();
        Inacc inacc;
        String acsError;                                               // NO_SETTINGS | DISABLED_APP | LOCKED | OTHER of the last attempt
        private long sizeCache = -1;
        private List<Match> sorted;

        Junk(Sdm.Pkg pkg, boolean limited) { this.pkg = pkg; this.limited = limited; }

        void changed() { sizeCache = -1; sorted = null; }

        int matchCount() { int n = 0; for (List<Match> l : exp.values()) n += l.size(); return n; }
        int itemCount() { return matchCount() + (inacc != null ? Inacc.ITEM_COUNT : 0); }

        long knownFiles() { long b = 0; for (List<Match> l : exp.values()) for (Match m : l) b += m.size; return b; }

        long publicCacheMatches() { long b = 0; List<Match> l = exp.get(F_DCPUB); if (l != null) for (Match m : l) b += m.size; return b; }

        /** The part of the size that comes from the inaccessible cache (spec 3.1). */
        long inaccessiblePart() {
            if (inacc == null) return 0;
            List<Match> pub = exp.get(F_DCPUB);
            if (pub == null) return inacc.total;                                        // no extra info about public caches
            if (inacc.publicSize != null) return inacc.privateSize();                    // the system has separate info for public / private
            return inacc.total - publicCacheMatches();                                   // assume the system figure includes the public caches
        }

        long size() { if (sizeCache < 0) sizeCache = knownFiles() + inaccessiblePart(); return sizeCache; }

        boolean noFiles() { for (List<Match> l : exp.values()) if (!l.isEmpty()) return false; return true; }
        boolean isEmpty() { return noFiles() && inacc == null; }
        boolean unclearable() { return noFiles() && inacc != null && ("NO_SETTINGS".equals(acsError) || "DISABLED_APP".equals(acsError) || "LOCKED".equals(acsError)); }

        List<Match> sortedMatches() {
            if (sorted == null) {
                List<Match> all = new ArrayList<Match>();
                for (List<Match> l : exp.values()) all.addAll(l);
                Collections.sort(all, new Comparator<Match>() {
                    @Override public int compare(Match a, Match b) { return a.filter != b.filter ? (a.filter < b.filter ? -1 : 1) : a.path.compareTo(b.path); }
                });
                sorted = all;
            }
            return sorted;
        }

        /** The limit of upstream's limitToTrimBlastRadius: only the default caches and the inaccessible cache stay. Returns false when nothing is left. */
        boolean limitToTrimBlast() {
            Iterator<Map.Entry<Integer, List<Match>>> it = exp.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, List<Match>> en = it.next();
                if ((en.getKey() != F_DCPUB && en.getKey() != F_DCPRIV) || en.getValue().isEmpty()) it.remove();
            }
            changed();
            return !exp.isEmpty() || inacc != null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- result lines (spec 7.3)

    static String plural(long n, String one, String other) { return n == 1 ? one : other; }

    static String foundPrimary(int n) { return n + " expendable " + (n == 1 ? "item" : "items") + " found"; }

    static String deletedText(int n) { return n + " expendable " + (n == 1 ? "item" : "items") + " deleted"; }

    static String size(long bytes) {
        try {
            Class<?> c = Class.forName("com.bloatware.bingblop.SdmEngine");
            Object o = c.getMethod("formatSize", long.class).invoke(null, bytes);
            if (o instanceof String) return (String) o;
        } catch (Throwable ignored) {
            // the engine is not on this class path (a unit test): the same rule below
        }
        return formatSize(bytes);
    }

    /** Binary units with one decimal: "512 B", "12.3 MB". */
    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = { "KB", "MB", "GB", "TB", "PB" };
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        String s = String.format(Locale.US, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s + " " + u[i];
    }

    // ---------------------------------------------------------------------------------------------------------- the result

    /** The scan result: the apps sorted by size, held in memory until it is deleted or discarded. */
    public static final class AppResult implements Sdm.Result {
        final List<Junk> junks;
        AppResult(List<Junk> junks) { this.junks = junks; sort(); }

        void sort() {
            Collections.sort(junks, new Comparator<Junk>() {
                @Override public int compare(Junk a, Junk b) { long x = a.size(), y = b.size(); return x == y ? a.pkg.pkg.compareTo(b.pkg.pkg) : (x > y ? -1 : 1); }
            });
        }

        @Override public Sdm.Tool tool() { return Sdm.Tool.APPCLEANER; }
        @Override public synchronized int groupCount() { return junks.size(); }
        @Override public synchronized int itemCount() { int n = 0; for (Junk j : junks) n += j.itemCount(); return n; }
        @Override public synchronized long bytes() { long b = 0; for (Junk j : junks) b += j.size(); return b; }

        synchronized Junk find(String pkg) { for (Junk j : junks) if (j.pkg.pkg.equals(pkg)) return j; return null; }

        static String labelOf(Sdm.Pkg p) { return p.label == null || p.label.isEmpty() ? p.pkg : p.label; }

        @Override public synchronized JSONArray groups(int offset, int limit) throws Exception {
            JSONArray a = new JSONArray();
            for (int i = Math.max(0, offset); i < junks.size() && a.length() < limit; i++) a.put(groupRow(junks.get(i)));
            return a;
        }

        static JSONObject groupRow(Junk j) throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", j.pkg.pkg).put("label", labelOf(j.pkg)).put("sub", j.pkg.pkg).put("count", j.itemCount()).put("bytes", j.size());
            o.put("pkg", j.pkg.pkg).put("system", j.pkg.system).put("limited", j.limited).put("unclearable", j.unclearable());
            if (j.limited) o.put("hint", "Excluded, but cleaning all apps at once still clears these caches");
            if (j.acsError != null) o.put("acsError", j.acsError);
            if (j.inacc != null) {
                JSONObject i = new JSONObject().put("total", j.inacc.total).put("size", j.inaccessiblePart()).put("private", j.inacc.privateSize());
                if (j.inacc.publicSize != null) i.put("public", (long) j.inacc.publicSize);
                o.put("inaccessible", i);
            }
            JSONArray fl = new JSONArray();
            for (Map.Entry<Integer, List<Match>> en : j.exp.entrySet()) {
                if (en.getValue().isEmpty()) continue;
                long b = 0;
                for (Match m : en.getValue()) b += m.size;
                fl.put(new JSONObject().put("id", IDS[en.getKey()]).put("label", LABELS[en.getKey()]).put("count", en.getValue().size()).put("bytes", b));
            }
            o.put("filters", fl);
            return o;
        }

        @Override public synchronized JSONArray items(String groupId, int offset, int limit) throws Exception {
            Junk j = find(groupId);
            JSONArray a = new JSONArray();
            if (j == null) return a;
            int idx = 0;                                                  // row 0 is the inaccessible cache when there is one
            int skip = Math.max(0, offset);
            if (j.inacc != null) {
                if (skip == 0 && a.length() < limit) {
                    JSONObject o = new JSONObject();
                    o.put("id", "inaccessible:" + j.pkg.pkg).put("path", j.inacc.theoreticalPaths[0]).put("name", "Default caches").put("size", j.inaccessiblePart())
                            .put("mtime", 0).put("type", Sdm.DIR).put("filter", "inaccessible").put("filterLabel", "Default caches").put("inaccessible", true)
                            .put("note", "Public and private default caches. Not directly accessible, but can be deleted indirectly (e.g. via accessibility service).");
                    a.put(o);
                }
                idx = 1;
            }
            List<Match> all = j.sortedMatches();
            for (int i = Math.max(0, skip - idx); i < all.size() && a.length() < limit; i++) {
                Match m = all.get(i);
                a.put(new JSONObject().put("id", m.path).put("path", m.path).put("name", m.name()).put("size", m.size).put("mtime", m.mtime).put("type", m.type)
                        .put("filter", IDS[m.filter]).put("filterLabel", LABELS[m.filter]));
            }
            return a;
        }

        /** Rows of one app: the number of items (matches plus the inaccessible cache). */
        public synchronized int itemCountOf(String pkg) { Junk j = find(pkg); return j == null ? 0 : j.itemCount(); }

        @Override public synchronized JSONObject summary() throws Exception {
            int n = itemCount();
            long b = bytes();
            int ac = 0; long ab = 0;
            for (Junk j : junks) if (!j.unclearable()) { ac += j.itemCount(); ab += j.size(); }
            return new JSONObject().put("itemCount", n).put("bytes", b).put("groupCount", junks.size()).put("actionableCount", ac).put("actionableBytes", ab)
                    .put("primary", foundPrimary(n)).put("secondary", size(b) + " can be freed");
        }

        /** Exclusion of whole apps made after the scan (spec 3.6 step 4): the apps leave the result, or stay with the default caches only when the trim would reach them. */
        public synchronized int excludePackages(Collection<String> pkgs, boolean trimReachesThem) {
            int removed = 0;
            List<Junk> next = new ArrayList<Junk>();
            for (Junk j : junks) {
                if (!pkgs.contains(j.pkg.pkg)) { next.add(j); continue; }
                removed++;
                if (trimReachesThem) {
                    Junk lim = new Junk(j.pkg, true);
                    lim.exp.putAll(j.exp);
                    lim.inacc = j.inacc;
                    lim.acsError = j.acsError;
                    if (lim.limitToTrimBlast()) next.add(lim);
                }
            }
            junks.clear();
            junks.addAll(next);
            pruneOrphanedLimited();
            sort();
            return removed;
        }

        /** Exclusion of single paths made after the scan: the matches at and below them leave the result. */
        public synchronized int excludePaths(Collection<String> paths) {
            TreeSet<String> ex = new TreeSet<String>(paths);
            int removed = 0;
            for (Iterator<Junk> it = junks.iterator(); it.hasNext(); ) {
                Junk j = it.next();
                for (Iterator<Map.Entry<Integer, List<Match>>> ei = j.exp.entrySet().iterator(); ei.hasNext(); ) {
                    List<Match> l = ei.next().getValue();
                    for (Iterator<Match> mi = l.iterator(); mi.hasNext(); ) {
                        Match m = mi.next();
                        if (pathExcluded(ex, m.path)) { mi.remove(); removed++; }
                    }
                    if (l.isEmpty()) ei.remove();
                }
                j.changed();
                if (j.isEmpty()) it.remove();
            }
            pruneOrphanedLimited();
            sort();
            return removed;
        }

        void pruneOrphanedLimited() {
            boolean trimEligible = false;
            for (Junk j : junks) if (!j.limited && j.inacc != null) { trimEligible = true; break; }
            if (!trimEligible) for (Iterator<Junk> it = junks.iterator(); it.hasNext(); ) if (it.next().limited) it.remove();
        }
    }

    /** A path that is one of the paths or below one (PathExclusion.match). */
    static boolean pathExcluded(TreeSet<String> ex, String path) {
        if (ex.contains(path)) return true;
        for (int i = path.lastIndexOf('/'); i > 0; i = path.lastIndexOf('/', i - 1)) if (ex.contains(path.substring(0, i))) return true;
        return false;
    }

    /** True when something of the set lies strictly below the path (the nested rule: a folder that holds an excluded or kept path must not be deleted). */
    static boolean hasPathBelow(TreeSet<String> set, String path) {
        if (set.isEmpty()) return false;
        String lo = path.endsWith("/") ? path : path + "/";
        String c = set.ceiling(lo);
        return c != null && c.startsWith(lo) && c.length() > lo.length();
    }

    // ---------------------------------------------------------------------------------------------------------- scan

    /** Where a walk starts: a folder of an area and the apps that may own what is in it. */
    private static final class SearchPath {
        final String path;
        final Sdm.AreaInfo area;
        final LinkedHashSet<String> owners = new LinkedHashSet<String>();
        SearchPath(String path, Sdm.AreaInfo area) { this.path = path; this.area = area; }
    }

    private static final Set<String> HIDDEN_Q_PKGS = set("com.google.android.networkstack.permissionconfig", "com.google.android.ext.services", "com.google.android.angle",
            "com.google.android.documentsui", "com.google.android.modulemetadata", "com.google.android.networkstack", "com.google.android.permissioncontroller",
            "com.google.android.captiveportallogin");
    private static final Set<String> SHIZUKU_PKGS = set("moe.shizuku.privileged.api", "af.shizuku.plus.api", "rikka.shizuku.manager");

    private static boolean isRoot(Sdm.Ctx ctx) { return ctx.shell != null && ctx.shell.uid() == 0; }
    private static boolean isAdbNoRoot(Sdm.Ctx ctx) { return ctx.shell != null && ctx.shell.uid() > 0; }

    private static boolean excludesPkg(Sdm.Ctx ctx, String pkg) { return ctx.exclusions != null && ctx.exclusions.excludesPackage(Sdm.Tool.APPCLEANER, pkg); }
    private static boolean excludesPath(Sdm.Ctx ctx, String path) { return ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.APPCLEANER, path); }

    private static void checkCancel(Sdm.Ctx ctx) { if (ctx.cancelled()) throw new CancellationException("cancelled"); }

    @Override
    public Sdm.Result scan(final Sdm.Ctx ctx) throws Exception {
        ctx.progress.update("Preparing", "", 0, -1, 0);
        final Filters filters = new Filters(ctx);
        if (filters.enabledCount == 0) return new AppResult(new ArrayList<Junk>());

        final boolean includeSystem = ctx.bool("include.systemapps.enabled", false);
        final boolean includeRunning = ctx.bool("include.runningapps.enabled", true);
        final boolean includeInaccessible = ctx.bool("include.inaccessible.enabled", true);
        final long minSize = ctx.num("skip.mincachesize.bytes", 48 * 1024L);
        final long minAgeMs = ctx.num("skip.mincacheage.milliseconds", 0L);
        final boolean root = isRoot(ctx), adbNoRoot = isAdbNoRoot(ctx);
        final boolean trimBlastPossible = adbNoRoot && includeInaccessible && ctx.bool(KEYS[F_DCPUB], true) && ctx.bool(KEYS[F_DCPRIV], true);

        // ---- the package set
        ctx.progress.update("Preparing", "Loading app data", 0, -1, 0);
        List<Sdm.Pkg> installed = ctx.pkgs.installed();
        Set<String> running = includeRunning ? Collections.<String>emptySet() : ctx.pkgs.running();
        Set<String> installedNames = new HashSet<String>();
        for (Sdm.Pkg p : installed) if (!p.uninstalled) installedNames.add(p.pkg);
        final List<Sdm.Pkg> scanPkgs = new ArrayList<Sdm.Pkg>();
        final Set<String> limitedPkgs = new HashSet<String>();
        for (Sdm.Pkg p : installed) {
            if (p.uninstalled) continue;
            if (!includeSystem && p.system) continue;
            if (!includeRunning && (p.running || (running != null && running.contains(p.pkg)))) continue;
            if (ownPkg.equals(p.pkg)) continue;
            if (excludesPkg(ctx, p.pkg)) {
                if (trimBlastPossible) limitedPkgs.add(p.pkg); else continue;
            }
            scanPkgs.add(p);
        }
        final Set<String> scanSet = new HashSet<String>();
        for (Sdm.Pkg p : scanPkgs) scanSet.add(p.pkg);

        // ---- the search map
        checkCancel(ctx);
        ctx.progress.update("Preparing", "Loading data areas", 0, -1, 0);
        Markers markers = Markers.load(installedNames);
        Map<String, SearchPath> searchMap = new LinkedHashMap<String, SearchPath>();
        buildSearchMap(ctx, scanPkgs, scanSet, installedNames, markers, searchMap);

        // ---- the walks
        final Map<String, Junk> junks = new LinkedHashMap<String, Junk>();
        Map<String, Sdm.Pkg> pkgByName = new HashMap<String, Sdm.Pkg>();
        for (Sdm.Pkg p : scanPkgs) pkgByName.put(p.pkg, p);
        readAppDirs(ctx, filters, searchMap, pkgByName, limitedPkgs, minAgeMs, adbNoRoot, junks);

        // ---- inaccessible caches
        Map<String, Inacc> inaccessible = Collections.emptyMap();
        if (includeInaccessible && !root && ctx.bool(KEYS[F_DCPUB], true) && ctx.bool(KEYS[F_DCPRIV], true)) {
            inaccessible = determineInaccessibleCaches(ctx, scanPkgs);
        }

        // ---- the junk of every app
        checkCancel(ctx);
        ctx.progress.update("Filtering", "", 0, -1, 0);
        List<Junk> out = new ArrayList<Junk>();
        for (Sdm.Pkg p : scanPkgs) {
            Junk j = junks.get(p.pkg);
            Inacc in = inaccessible.get(p.pkg);
            if ((j == null || j.matchCount() == 0) && in == null) continue;
            if (j == null) j = new Junk(p, limitedPkgs.contains(p.pkg));
            if (in != null) {
                // below API 31 the public part is estimated from the files found under Android/data (upstream), and the port has no better figure on any API
                List<Match> pub = j.exp.get(F_DCPUB);
                if (pub != null) { long b = 0; for (Match m : pub) b += m.size; in.publicSize = b; }
                j.inacc = in;
            }
            j.changed();
            if (j.limited && !j.limitToTrimBlast()) continue;
            out.add(j);
        }
        AppResult res = new AppResult(out);
        res.pruneOrphanedLimited();

        // ---- post processing
        postProcess(ctx, res, minSize, adbNoRoot);
        res.sort();
        return res;
    }

    private void buildSearchMap(Sdm.Ctx ctx, List<Sdm.Pkg> scanPkgs, Set<String> scanSet, Set<String> installedNames, Markers markers, Map<String, SearchPath> map) throws IOException {
        List<Sdm.AreaInfo> areas = new ArrayList<Sdm.AreaInfo>();
        for (Sdm.AreaInfo a : ctx.areas.all()) if (a.available()) areas.add(a);

        // the top-level entries of the areas that hold other apps' folders
        List<Sdm.AreaInfo> tops = new ArrayList<Sdm.AreaInfo>();
        Map<Sdm.AreaInfo, List<String>> topNames = new HashMap<Sdm.AreaInfo, List<String>>();
        for (Sdm.AreaInfo a : areas) {
            if (a.area != Sdm.Area.PRIVATE_DATA && a.area != Sdm.Area.PUBLIC_DATA && a.area != Sdm.Area.PUBLIC_MEDIA && a.area != Sdm.Area.SDCARD) continue;
            checkCancel(ctx);
            String[] names;
            try { names = ctx.fs.list(a.root); } catch (IOException e) { names = null; }
            if (names == null) continue;
            List<String> keep = new ArrayList<String>();
            for (String nm : names) {
                if (nm == null || nm.isEmpty()) continue;
                if (excludesPath(ctx, Sdm.join(a.root, nm))) continue;
                keep.add(nm);
            }
            tops.add(a);
            topNames.put(a, keep);
        }

        ctx.progress.update("Generating search paths", "", 0, scanPkgs.size(), 0);

        // (b) shortcut service bitmaps of every system_ce area
        for (Sdm.AreaInfo a : areas) {
            if (a.area != Sdm.Area.DATA_SYSTEM_CE) continue;
            String dir = Sdm.join(Sdm.join(a.root, "shortcut_service"), "bitmaps");
            String[] names;
            try { names = ctx.fs.list(dir); } catch (IOException e) { names = null; }
            if (names == null) continue;
            for (String nm : names) if (scanSet.contains(nm)) add(map, Sdm.join(dir, nm), a, nm);
        }

        // (a) + (c) + (e): the top-level folders and who owns them
        for (Sdm.AreaInfo a : tops) {
            for (String nm : topNames.get(a)) {
                checkCancel(ctx);
                String path = Sdm.join(a.root, nm);
                if (a.area != Sdm.Area.SDCARD && scanSet.contains(nm)) add(map, path, a, nm);               // a direct match: the folder is named like the app
                for (Marker mk : markers.matchTop(a.area, nm)) {                                           // an outlier the marker database knows
                    if (mk.custodian) continue;
                    for (String p : mk.pkgs) if (scanSet.contains(p)) add(map, path, a, p);
                }
                for (String owner : dynamicOwners(a.area, nm, installedNames, markers)) if (scanSet.contains(owner)) add(map, path, a, owner);   // _pkg, .pkg, pkg:remote ...
            }
        }

        // (d) deeper marker folders on the sdcard (Tencent/MicroMsg, ...)
        for (Sdm.AreaInfo a : tops) {
            if (a.area != Sdm.Area.SDCARD) continue;
            Set<String> topLower = new HashSet<String>();
            for (String nm : topNames.get(a)) topLower.add(nm.toLowerCase(Locale.ROOT));
            for (Sdm.Pkg p : scanPkgs) {
                List<Marker> ms = markers.byPkg.get(p.pkg);
                if (ms == null) continue;
                for (Marker mk : ms) {
                    if (mk.area != Sdm.Area.SDCARD || mk.custodian || !mk.direct() || mk.path == null || mk.path.length == 0) continue;
                    if (!topLower.contains(mk.path[0].toLowerCase(Locale.ROOT))) continue;
                    String path = a.root;
                    for (String s : mk.path) path = Sdm.join(path, s);
                    if (ctx.fs.exists(path) && !excludesPath(ctx, path)) add(map, path, a, p.pkg);
                }
            }
        }
        ctx.progress.update("Generating search paths", "", scanPkgs.size(), scanPkgs.size(), 0);
    }

    private static void add(Map<String, SearchPath> map, String path, Sdm.AreaInfo area, String owner) {
        SearchPath sp = map.get(path);
        if (sp == null) { sp = new SearchPath(path, area); map.put(path, sp); }
        sp.owners.add(owner);
    }

    private static final Pattern LGE_THEME = Pattern.compile("^(com\\.lge\\.theme\\.[\\w_\\-]+)(\\.[\\w._\\-]+)$");

    /** The apps a top-level folder belongs to by its name (the part of CSI that AppCleaner uses); only installed apps are returned. */
    private static Set<String> dynamicOwners(Sdm.Area area, String name, Set<String> installed, Markers markers) {
        Set<String> owners = new LinkedHashSet<String>();
        if (area == Sdm.Area.SDCARD) {
            for (Marker m : markers.matchTop(area, name)) if (!m.custodian) for (String p : m.pkgs) if (installed.contains(p)) owners.add(p);
            return owners;
        }
        if (installed.contains(name)) {
            owners.add(name);
        } else {
            String hidden = null;
            if (name.startsWith(".external.")) hidden = name.substring(10);
            else if (name.startsWith("_") || name.startsWith(".")) hidden = name.substring(1);
            else if (area == Sdm.Area.PUBLIC_DATA && name.endsWith(":remote")) hidden = name.substring(0, name.length() - ":remote".length());
            else if (area == Sdm.Area.PRIVATE_DATA) {
                java.util.regex.Matcher lge = LGE_THEME.matcher(name);
                if (lge.matches()) hidden = lge.group(1);
            }
            if (hidden != null && installed.contains(hidden)) owners.add(hidden);
        }
        if (owners.isEmpty()) {
            for (Marker m : markers.matchTop(area, name)) if (!m.custodian) for (String p : m.pkgs) if (installed.contains(p)) owners.add(p);
        }
        return owners;
    }

    private void readAppDirs(final Sdm.Ctx ctx, final Filters filters, Map<String, SearchPath> map, final Map<String, Sdm.Pkg> pkgs, final Set<String> limited, final long minAgeMs,
                             final boolean adbNoRoot, final Map<String, Junk> junks) throws IOException {
        final long cutOffMs = ctx.nowSec * 1000L - minAgeMs;
        final long[] bytes = { 0 };
        int total = map.size(), done = 0;
        ctx.progress.update("Searching", "", 0, total, 0);
        for (final SearchPath sp : map.values()) {
            checkCancel(ctx);
            ctx.progress.update("Searching", sp.path, done++, total, bytes[0]);
            final List<String> owners = new ArrayList<String>(sp.owners);
            Collections.sort(owners, new Comparator<String>() {            // a path two apps could own goes to the one that is not exclusion-limited
                @Override public int compare(String a, String b) { boolean x = limited.contains(a), y = limited.contains(b); return x == y ? 0 : (x ? 1 : -1); }
            });
            final Sdm.AreaInfo ai = sp.area;
            final int[] counter = { 0 };
            ctx.fs.walk(sp.path, new Sdm.EntrySink() {
                @Override public boolean accept(Sdm.Entry e) {
                    if ((++counter[0] & 255) == 0) { checkCancel(ctx); ctx.progress.update("Searching", e.path, 0, -1, bytes[0]); }
                    String path = e.path;
                    boolean dir = e.type == Sdm.DIR;
                    if (dir ? (path + "/").contains("/org.winehq.wine/files/prefix/") || (path + "/").contains("/.wine/") : false) return false;
                    if (ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.APPCLEANER, path)) {
                        boolean keepForTrim = adbNoRoot && hasPkgCache(path, owners);
                        if (!keepForTrim) return false;
                    }
                    if (minAgeMs != 0 && e.mtime * 1000L >= cutOffMs) return true;
                    String[] pfp = ai.pfp(path);
                    if (pfp == null || pfp.length == 0) return true;
                    for (String owner : owners) {
                        Cand c = new Cand(owner, e, ai.area, pfp, ctx.nowSec);
                        int f = filters.match(c);
                        if (f < 0) continue;
                        Junk j = junks.get(owner);
                        if (j == null) { j = new Junk(pkgs.get(owner), limited.contains(owner)); junks.put(owner, j); }
                        List<Match> l = j.exp.get(f);
                        if (l == null) { l = new ArrayList<Match>(); j.exp.put(f, l); }
                        l.add(new Match(path, e.size, e.mtime, e.type, f));
                        bytes[0] += e.size;
                        break;
                    }
                    return true;
                }
            }, ctx.cancel);
        }
        checkCancel(ctx);
    }

    /** True when the path has the segments [owner, "cache"] in a row (the default cache an ADB trim clears whatever the exclusions say). */
    private static boolean hasPkgCache(String path, List<String> owners) {
        String[] segs = Sdm.segments(path);
        for (int i = 0; i + 1 < segs.length; i++) if ("cache".equals(segs[i + 1]) && owners.contains(segs[i])) return true;
        return false;
    }

    private Map<String, Inacc> determineInaccessibleCaches(final Sdm.Ctx ctx, List<Sdm.Pkg> pkgs) throws Exception {
        ctx.progress.update("Determining inaccessible caches", "", 0, pkgs.size(), 0);
        final Map<String, Inacc> out = new LinkedHashMap<String, Inacc>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Inacc>> futures = new ArrayList<Future<Inacc>>();
            for (final Sdm.Pkg p : pkgs) {
                futures.add(pool.submit(new Callable<Inacc>() {
                    @Override public Inacc call() {
                        if (ctx.cancelled()) return null;
                        long total = ctx.pkgs.cacheBytes(p.pkg);
                        if (total < 0 && p.cacheBytes >= 0) total = p.cacheBytes;
                        if (total <= 0) return null;                          // unknown, or empty: nothing to clear
                        int user = p.uid >= 0 ? p.uid / 100000 : 0;
                        return new Inacc(total, null, new String[] { "/storage/emulated/" + user + "/Android/data/" + p.pkg + "/cache", "/data/user/" + user + "/" + p.pkg + "/cache" });
                    }
                }));
            }
            for (int i = 0; i < pkgs.size(); i++) {
                checkCancel(ctx);
                Sdm.Pkg p = pkgs.get(i);
                ctx.progress.update("Determining inaccessible caches", AppResult.labelOf(p), i, pkgs.size(), 0);
                Inacc in = futures.get(i).get();
                if (in != null) out.put(p.pkg, in);
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** PostProcessorModule: aliases, exclusions, the ADB manager app, packages of the Google modules, the minimum size, empty apps. */
    private void postProcess(Sdm.Ctx ctx, AppResult res, long minSize, boolean adbNoRoot) {
        TreeSet<String> exclPaths = new TreeSet<String>();
        if (ctx.exclusions != null) exclPaths.addAll(ctx.exclusions.paths(Sdm.Tool.APPCLEANER));
        for (Iterator<Junk> it = res.junks.iterator(); it.hasNext(); ) {
            Junk j = it.next();
            // the same path found twice (aliased folders)
            for (Iterator<Map.Entry<Integer, List<Match>>> ei = j.exp.entrySet().iterator(); ei.hasNext(); ) {
                List<Match> l = ei.next().getValue();
                Set<String> seen = new HashSet<String>();
                for (Iterator<Match> mi = l.iterator(); mi.hasNext(); ) if (!seen.add(mi.next().path)) mi.remove();
                if (l.isEmpty()) ei.remove();
            }
            if (!j.exp.isEmpty()) {
                if (adbNoRoot && SHIZUKU_PKGS.contains(j.pkg.pkg)) { it.remove(); continue; }
                // exclusions: what is excluded or holds something excluded goes; ADB without root keeps the default caches an ADB trim clears anyway
                for (Iterator<Map.Entry<Integer, List<Match>>> ei = j.exp.entrySet().iterator(); ei.hasNext(); ) {
                    Map.Entry<Integer, List<Match>> en = ei.next();
                    boolean edge = adbNoRoot && (en.getKey() == F_DCPUB || en.getKey() == F_DCPRIV);
                    String[] pkgCache = { j.pkg.pkg, "cache" };
                    for (Iterator<Match> mi = en.getValue().iterator(); mi.hasNext(); ) {
                        Match m = mi.next();
                        boolean ex = excludesPath(ctx, m.path) || hasPathBelow(exclPaths, m.path);
                        if (ex && !(edge && SdmSieve.contains(Sdm.segments(m.path), pkgCache, false))) mi.remove();
                    }
                    if (en.getValue().isEmpty()) ei.remove();
                }
            }
            if (HIDDEN_Q_PKGS.contains(j.pkg.pkg)) j.inacc = null;
            j.changed();
            if (j.size() < minSize || j.isEmpty()) it.remove();
        }
        res.pruneOrphanedLimited();
    }

    // ---------------------------------------------------------------------------------------------------------- delete (spec 3.6)

    @Override
    public Sdm.DeleteReport delete(Sdm.Result result, Sdm.Selection sel, Sdm.Ctx ctx) throws Exception {
        if (!(result instanceof AppResult)) throw new IllegalArgumentException("not an AppCleaner result");
        return new Deleter((AppResult) result, sel, ctx).run();
    }

    /** What the inaccessible stage found out (upstream InaccDelResult). */
    private static final class Inacc2 {
        final Set<String> success = new LinkedHashSet<String>();
        final Map<String, String> failed = new LinkedHashMap<String, String>();
        final Map<String, Long> freed = new HashMap<String, Long>();
        final Set<String> skippedNoConsent = new LinkedHashSet<String>();
    }

    private static final class Deleter {
        final AppResult res;
        final Sdm.Selection sel;
        final Sdm.Ctx ctx;
        final Sdm.DeleteReport report = new Sdm.DeleteReport();
        final boolean includeInaccessible, onlyInaccessible, useAutomation, isAll;
        final TreeSet<String> exclPaths = new TreeSet<String>();
        Inacc2 inac;
        final Map<String, Long> scanTotals = new HashMap<String, Long>();           // the size of every inaccessible cache when the result was handed over
        String stopped;                      // SCREEN_UNAVAILABLE | ERROR | AUTOMATION_NO_CONSENT
        String error;
        boolean cancelled;

        Deleter(AppResult res, Sdm.Selection sel, Sdm.Ctx ctx) {
            this.res = res; this.sel = sel; this.ctx = ctx;
            JSONObject o = sel.options == null ? new JSONObject() : sel.options;
            includeInaccessible = o.optBoolean("includeInaccessible", true);
            onlyInaccessible = o.optBoolean("onlyInaccessible", false);
            useAutomation = o.optBoolean("useAutomation", true);
            isAll = sel.dropGroups.isEmpty();
            if (ctx.exclusions != null) exclPaths.addAll(ctx.exclusions.paths(Sdm.Tool.APPCLEANER));
        }

        boolean stop() { if (ctx.cancelled()) { cancelled = true; return true; } return false; }

        Sdm.DeleteReport run() {
            synchronized (res) {
                int before = res.itemCount();
                ctx.progress.update("Preparing", "", 0, -1, 0);
                for (Junk j : res.junks) if (j.inacc != null) scanTotals.put(j.pkg.pkg, j.inacc.total);

                // ---- 1. the accessible files, the biggest apps first
                List<Junk> targets = new ArrayList<Junk>();
                for (Junk j : res.junks) {
                    if (sel.dropGroups.contains(j.pkg.pkg)) continue;
                    if (isAll && j.limited) continue;                         // their files are not ours to delete when the whole tool is cleaned
                    if (!j.limited && excludesPkg(ctx, j.pkg.pkg)) continue;  // excluded since the scan
                    targets.add(j);
                }
                Collections.sort(targets, new Comparator<Junk>() { @Override public int compare(Junk a, Junk b) { long x = a.size(), y = b.size(); return x == y ? 0 : (x > y ? -1 : 1); } });
                if (!onlyInaccessible) {
                    int done = 0;
                    for (Junk j : targets) {
                        if (stop()) break;
                        ctx.progress.update(AppResult.labelOf(j.pkg), "", done++, targets.size(), report.bytes());
                        deleteFiles(j);
                    }
                }

                // ---- 2. the inaccessible caches, 3. what the trim or the automation cleared leaves the result
                if (includeInaccessible && !cancelled) inac = deleteInaccessible(targets);
                reconcileInaccessible();
                for (Iterator<Junk> it = res.junks.iterator(); it.hasNext(); ) { Junk j = it.next(); j.changed(); if (j.isEmpty()) it.remove(); }
                res.pruneOrphanedLimited();
                res.sort();

                writeNotes(Math.max(0, before - res.itemCount()));
                return report;
            }
        }

        // ---- files

        private void deleteFiles(Junk j) {
            // what the user kept inside a selected folder keeps the folder, and so does what an exclusion holds
            TreeSet<String> kept = new TreeSet<String>();
            for (List<Match> l : j.exp.values()) for (Match m : l) if (sel.dropItems.contains(m.path)) kept.add(m.path);
            List<String> selectedPaths = new ArrayList<String>();
            for (List<Match> l : j.exp.values()) for (Match m : l) {
                if (sel.dropItems.contains(m.path)) continue;
                if (excludesPath(ctx, m.path) || hasPathBelow(exclPaths, m.path) || hasPathBelow(kept, m.path)) continue;
                selectedPaths.add(m.path);
            }
            if (selectedPaths.isEmpty()) return;
            List<String> safePaths = new ArrayList<String>();
            for (String path : selectedPaths) { if (safeTarget(path)) safePaths.add(path); else report.failed.add(path); }
            List<String> roots = SdmSieve.distinctRoots(safePaths);
            Set<String> rootSet = new HashSet<String>(roots);

            List<String> toDelete = new ArrayList<String>();
            Set<String> gone = new HashSet<String>();
            for (String root : roots) {
                if (stop()) return;
                ctx.progress.update(AppResult.labelOf(j.pkg), root, 0, -1, report.bytes());
                Sdm.Entry cur;
                try { cur = ctx.fs.stat(root); } catch (IOException e) { report.failed.add(root); continue; }
                if (cur == null) gone.add(root); else toDelete.add(root);        // a path that is gone counts as deleted
            }
            if (!toDelete.isEmpty() && !stop()) gone.addAll(ctx.fs.deleteAll(toDelete, ctx.cancel));
            for (String root : toDelete) if (!gone.contains(root)) report.failed.add(root);

            // every match at or below a root that is gone is deleted; below a root that failed only what disappeared anyway
            Set<Match> removed = new HashSet<Match>();
            for (List<Match> l : j.exp.values()) for (Match m : l) {
                String root = rootOf(m.path, rootSet);
                if (root == null) continue;
                if (gone.contains(root)) { removed.add(m); continue; }
                try { if (!ctx.fs.exists(m.path)) removed.add(m); } catch (RuntimeException e) { /* still there as far as we know */ }
            }
            removeMatches(j, removed, true);
        }

        /** The distinct root that is this path or above it, or null. */
        private static String rootOf(String path, Set<String> roots) {
            for (String p = path; ; ) {
                if (roots.contains(p)) return p;
                int i = p.lastIndexOf('/');
                if (i <= 0) return null;
                p = p.substring(0, i);
            }
        }

        /**
         * The matches leave the result. A deleted one counts its size; one that only vanished with a cache clear counts nothing (its bytes are in the cache's
         * measured figure). One Deleted per match: the engine counts the way the scan counted.
         */
        private void removeMatches(Junk j, Set<Match> removed, boolean fromDisk) {
            if (removed.isEmpty()) return;
            long publicBytes = 0;
            for (Iterator<Map.Entry<Integer, List<Match>>> ei = j.exp.entrySet().iterator(); ei.hasNext(); ) {
                Map.Entry<Integer, List<Match>> en = ei.next();
                for (Iterator<Match> mi = en.getValue().iterator(); mi.hasNext(); ) {
                    Match m = mi.next();
                    if (!removed.contains(m)) continue;
                    mi.remove();
                    report.deleted.add(new Sdm.Deleted(m.path, fromDisk ? m.size : 0, j.pkg.pkg, LABELS[m.filter]));
                    if (fromDisk && m.filter == F_DCPUB) publicBytes += m.size;
                }
                if (en.getValue().isEmpty()) ei.remove();
            }
            if (publicBytes > 0 && j.inacc != null) {
                // the inaccessible cache goes down by the public caches that were deleted as files
                Long pub = j.inacc.publicSize == null ? null : Long.valueOf(Math.max(0L, j.inacc.publicSize - publicBytes));
                j.inacc = j.inacc.with(Math.max(0L, j.inacc.total - publicBytes), pub);
            }
            j.changed();
        }

        /** The rails of spec 8.6 for one root of a delete: inside a known area, never an area root, the app's own folder, / /data /storage, Android, no .. newline NUL. */
        private boolean safeTarget(String path) {
            if (path == null || path.isEmpty() || path.charAt(0) != '/' || path.equals("/")) return false;
            if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\0') >= 0) return false;
            for (String s : path.split("/")) if (s.equals("..")) return false;
            if (path.endsWith("/")) return false;
            if (path.equals("/data") || path.equals("/storage") || path.equals("/storage/emulated") || path.equals("/data/data") || path.equals("/data/user")) return false;
            Sdm.AreaInfo area = ctx.areas == null ? null : ctx.areas.areaOf(path);
            if (area == null) return false;
            String[] pfp = area.pfp(path);
            if (pfp == null || pfp.length == 0) return false;                                                       // an area root
            if (pfp.length == 1 && area.area != Sdm.Area.SDCARD && area.area != Sdm.Area.PORTABLE) return false;      // the app's own folder
            if (area.area == Sdm.Area.SDCARD && pfp[0].equalsIgnoreCase("Android")) return false;                   // Android/data, media and obb are areas of their own
            return true;
        }

        // ---- inaccessible caches

        private Inacc2 deleteInaccessible(List<Junk> selectedJunks) {
            Inacc2 r = new Inacc2();
            List<Junk> raw = new ArrayList<Junk>();
            for (Junk j : selectedJunks) {
                if (j.inacc == null) continue;
                if (sel.dropItems.contains("inaccessible:" + j.pkg.pkg)) continue;
                raw.add(j);
            }
            // the exclusion-limited ones are in the selection only for the trim, which reaches them whatever is excluded
            final boolean willTrim = isAdbNoRoot(ctx) && isAll;
            if (willTrim) {
                for (Junk j : res.junks) if (j.limited && j.inacc != null && !sel.dropGroups.contains(j.pkg.pkg) && !raw.contains(j)) raw.add(j);
            }
            Collections.sort(raw, new Comparator<Junk>() { @Override public int compare(Junk a, Junk b) { long x = a.inacc.total, y = b.inacc.total; return x == y ? 0 : (x > y ? -1 : 1); } });
            List<Junk> targets = raw;
            if (targets.isEmpty()) return r;

            Map<String, Long> baselines = new HashMap<String, Long>();
            Set<String> limitedIds = new HashSet<String>();
            for (Junk j : targets) { baselines.put(j.pkg.pkg, j.inacc.total); if (j.limited) limitedIds.add(j.pkg.pkg); }
            Set<String> zeroSkips = new HashSet<String>();

            if (willTrim && !stop()) trimCaches(targets, r, limitedIds);

            List<Junk> remaining = new ArrayList<Junk>();
            for (Junk j : targets) {
                if (r.success.contains(j.pkg.pkg)) continue;
                if (willTrim && j.limited) continue;                          // the trim covered it; driving its settings page is what the exclusion ruled out
                if (stop()) break;
                long now = ctx.pkgs.cacheBytes(j.pkg.pkg);
                if (now == 0) { r.success.add(j.pkg.pkg); zeroSkips.add(j.pkg.pkg); continue; }     // already empty: success without the automation
                if (now > 0) baselines.put(j.pkg.pkg, now);
                remaining.add(j);
            }

            boolean consent = ctx.automation != null && ctx.automation.ready();
            if (useAutomation && !remaining.isEmpty() && !cancelled && consent) {
                ctx.progress.update("Preparing automation via accessibility service", "", 0, -1, report.bytes());
                SdmAcsPlan.AutomationInfo info = ctx.automation instanceof SdmAcsPlan.AutomationInfo ? (SdmAcsPlan.AutomationInfo) ctx.automation : null;
                List<Junk> candidates = new ArrayList<Junk>();
                for (Junk j : remaining) {                                    // pre-flight: no settings page, no way to clear it this way
                    String reason = j.pkg.pkg.startsWith("com.google.mainline.") ? "NO_SETTINGS" : null;
                    if (reason == null && info != null) reason = info.unreachableReason(j.pkg.pkg, j.pkg.enabled);
                    if (reason != null) r.failed.put(j.pkg.pkg, reason); else candidates.add(j);
                }
                if (!candidates.isEmpty()) {
                    if (ctx.bool("forcestop.before.clearing.enabled", false)) forceStop(candidates);
                    if (!stop()) runAutomation(candidates, r, info);
                }
            } else if (useAutomation && !remaining.isEmpty() && !cancelled) {
                for (Junk j : remaining) r.skippedNoConsent.add(j.pkg.pkg);   // no consent (or the service is not connected): they still need it
            }

            // measure what really went away
            List<Junk> observe = new ArrayList<Junk>();
            for (Junk j : targets) if (r.success.contains(j.pkg.pkg) && !zeroSkips.contains(j.pkg.pkg)) observe.add(j);
            if (!observe.isEmpty()) {
                if (!cancelled) observeFreed(observe, baselines, r);
                else for (Junk j : observe) r.freed.put(j.pkg.pkg, baselines.get(j.pkg.pkg));
            }
            for (String s : r.success) r.failed.remove(s);                    // one that failed first and succeeded later is no failure
            for (Map.Entry<String, String> f : r.failed.entrySet()) { Junk j = res.find(f.getKey()); if (j != null) j.acsError = f.getValue(); }
            for (String s : r.success) { Junk j = res.find(s); if (j != null) j.acsError = null; }
            return r;
        }

        /** pm trim-caches (global), a wait, then every normal app's size has to change within the budget; system apps count at zero or smaller. */
        private void trimCaches(List<Junk> targets, Inacc2 r, Set<String> limitedIds) {
            ctx.progress.update("Deleting caches using ADB access", "Loading app data", 0, -1, report.bytes());
            Map<String, String> failedLocal = new LinkedHashMap<String, String>();
            List<Junk> normal = new ArrayList<Junk>(), system = new ArrayList<Junk>();
            for (Junk j : targets) if (j.pkg.system) system.add(j); else normal.add(j);
            try {
                ctx.shell.run("pm trim-caches 128G", 10 * 60 * 1000);
                sleep(trimWaitMs);
                List<Junk> pending = new ArrayList<Junk>(normal);
                long end = System.currentTimeMillis() + trimPollTimeoutMs;
                while (!pending.isEmpty() && !stop()) {
                    for (Iterator<Junk> it = pending.iterator(); it.hasNext(); ) {
                        Junk j = it.next();
                        long now = ctx.pkgs.cacheBytes(j.pkg.pkg);
                        Long atScan = scanTotals.get(j.pkg.pkg);
                        // the size changed: neither what the scan saw nor what is left after our own file deletions (or it can no longer be read: no better answer)
                        if (now < 0 || (now != j.inacc.total && (atScan == null || now != atScan))) { r.success.add(j.pkg.pkg); it.remove(); }
                    }
                    if (pending.isEmpty() || System.currentTimeMillis() >= end) break;
                    sleep(pollIntervalMs);
                }
                for (Junk j : pending) failedLocal.put(j.pkg.pkg, "OTHER");
                if (!system.isEmpty()) {
                    long end2 = System.currentTimeMillis() + recheckTimeoutMs;
                    List<Junk> pend = new ArrayList<Junk>(system);
                    while (!pend.isEmpty() && !stop()) {
                        for (Iterator<Junk> it = pend.iterator(); it.hasNext(); ) {
                            Junk j = it.next();
                            long now = ctx.pkgs.cacheBytes(j.pkg.pkg);
                            if (now == 0 || (now > 0 && now < j.inacc.total)) { r.success.add(j.pkg.pkg); it.remove(); }
                            else if (now < 0) it.remove();
                        }
                        if (pend.isEmpty() || System.currentTimeMillis() >= end2) break;
                        sleep(pollIntervalMs);
                    }
                }
            } catch (IOException e) {
                for (Junk j : normal) failedLocal.put(j.pkg.pkg, "OTHER");
            }
            for (Map.Entry<String, String> f : failedLocal.entrySet()) if (!limitedIds.contains(f.getKey())) r.failed.put(f.getKey(), f.getValue());   // a limited one never had a per-app attempt
        }

        private void forceStop(List<Junk> candidates) {
            if (ctx.shell == null || ctx.shell.uid() < 0) return;                // without a privileged shell upstream clicks "Force stop" through the service; not ported
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Junk j : candidates) {
                String p = j.pkg.pkg;
                if (p.equals("com.android.systemui") || p.equals(ownPkg)) continue;          // force-stopping them would break the automation
                if (!p.matches("[A-Za-z0-9._]+")) continue;
                int user = j.pkg.uid >= 0 ? j.pkg.uid / 100000 : 0;
                sb.append("am force-stop --user ").append(user).append(' ').append(p).append('\n');
                n++;
            }
            if (n == 0) return;
            ctx.progress.update("Force-stopping apps…", "", 0, -1, report.bytes());
            try { ctx.shell.run(sb.toString(), Math.max(15000, n * 4000)); } catch (IOException e) { /* best effort */ }
            sleep(500);
        }

        private void runAutomation(List<Junk> candidates, Inacc2 r, SdmAcsPlan.AutomationInfo info) {
            List<String> names = new ArrayList<String>();
            for (Junk j : candidates) names.add(j.pkg.pkg);
            Set<String> cleared = new LinkedHashSet<String>();
            try {
                Set<String> got = ctx.automation.clearCaches(names, ctx.progress, ctx.cancel);
                if (got != null) cleared.addAll(got);
            } catch (Sdm.AutomationError e) {
                cleared.addAll(e.done);
                if ("SCREEN_UNAVAILABLE".equals(e.code)) { stopped = "SCREEN_UNAVAILABLE"; error = e.getMessage(); }
                else if ("NO_CONSENT".equals(e.code)) { for (Junk j : candidates) if (!cleared.contains(j.pkg.pkg)) r.skippedNoConsent.add(j.pkg.pkg); }
                else { stopped = "ERROR"; error = e.getMessage(); }
            } catch (CancellationException e) {
                cancelled = true;
            } catch (RuntimeException e) {
                stopped = "ERROR";
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            r.success.addAll(cleared);
            for (Junk j : candidates) {
                if (cleared.contains(j.pkg.pkg) || r.skippedNoConsent.contains(j.pkg.pkg)) continue;
                String why = info == null ? null : info.failureOf(j.pkg.pkg);
                if (why != null) r.failed.put(j.pkg.pkg, why);
            }
        }

        /** Polls the cache sizes of the cleared apps until they stop moving: the freed space is the baseline minus the smallest value seen (spec 3.6 d). */
        private void observeFreed(List<Junk> observe, Map<String, Long> baselines, Inacc2 r) {
            ctx.progress.update("Checking freed space…", "", 0, observe.size(), report.bytes());
            Map<String, Long> min = new HashMap<String, Long>(), last = new HashMap<String, Long>();
            Set<String> failedReads = new HashSet<String>();
            Map<String, Integer> pinned = new HashMap<String, Integer>();
            List<Junk> pending = new ArrayList<Junk>();
            for (Junk j : observe) if (!sample(j, baselines, min, last, failedReads, pinned)) pending.add(j);     // every target is read once, whatever the budget
            long end = System.currentTimeMillis() + settleTimeoutMs;
            while (!pending.isEmpty() && System.currentTimeMillis() < end && !stop()) {
                sleep(pollIntervalMs);
                for (Iterator<Junk> it = pending.iterator(); it.hasNext(); ) if (sample(it.next(), baselines, min, last, failedReads, pinned)) it.remove();
            }
            for (Junk j : observe) {
                String id = j.pkg.pkg;
                long base = baselines.get(id);
                Long m = min.get(id);
                r.freed.put(id, failedReads.contains(id) || m == null ? base : Math.max(0L, base - m));
            }
        }

        /** One read; true when this target is settled (zero, or smaller than before and no longer moving, or unreadable, or stuck at its old size for 6 reads). */
        private boolean sample(Junk j, Map<String, Long> baselines, Map<String, Long> min, Map<String, Long> last, Set<String> failedReads, Map<String, Integer> pinned) {
            String id = j.pkg.pkg;
            long cur = ctx.pkgs.cacheBytes(id);
            if (cur < 0) { failedReads.add(id); return true; }
            Long prevMin = min.get(id);
            min.put(id, prevMin == null ? cur : Math.min(prevMin, cur));
            Long previous = last.put(id, cur);
            long base = baselines.get(id);
            if (cur == 0 || (previous != null && previous == cur && cur < base)) return true;
            Integer p = pinned.get(id);
            if ((p != null && p < 0) || cur != base) { pinned.put(id, -1); return false; }
            int reads = (p == null ? 0 : p) + 1;
            pinned.put(id, reads);
            return reads >= 6;
        }

        private static void sleep(long ms) {
            if (ms <= 0) return;
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // ---- reconcile

        /** The apps whose cache was cleared lose it, and the public default caches that were part of it (spec 3.6 step 3). */
        private void reconcileInaccessible() {
            if (inac == null) return;
            for (Junk j : new ArrayList<Junk>(res.junks)) {
                String id = j.pkg.pkg;
                if (!inac.success.contains(id) || j.inacc == null) continue;
                List<Match> pub = j.exp.get(F_DCPUB);
                if (pub != null) removeMatches(j, new HashSet<Match>(pub), false);
                long freed = inac.freed.containsKey(id) ? inac.freed.get(id) : j.inacc.total;
                for (int i = 0; i < j.inacc.theoreticalPaths.length; i++) report.deleted.add(new Sdm.Deleted(j.inacc.theoreticalPaths[i], i == 0 ? freed : 0, id, "Default caches"));
                j.inacc = null;
                j.changed();
            }
        }

        // ---- the lines of the result (spec 7.3)

        private void writeNotes(int affectedCount) {
            int skipped = inac == null ? 0 : inac.skippedNoConsent.size();
            String primary;
            if ("SCREEN_UNAVAILABLE".equals(stopped)) primary = deletedText(affectedCount) + ", stopped because the screen was off or locked";
            else if ("ERROR".equals(stopped)) primary = deletedText(affectedCount) + ", stopped by an error";
            else if (skipped > 0) {
                primary = deletedText(affectedCount) + ", " + skipped + " " + plural(skipped, "cache still needs the accessibility service", "caches still need the accessibility service");
                stopped = "AUTOMATION_NO_CONSENT";
            } else primary = deletedText(affectedCount);
            long bytes = report.bytes();
            report.notes.add("primary: " + primary);
            report.notes.add("secondary: Freed " + size(bytes) + " space.");
            report.notes.add("count: " + affectedCount);
            report.notes.add("bytes: " + bytes);
            if (stopped != null) report.notes.add("stopped: " + stopped);
            if (skipped > 0) report.notes.add("skipped: " + skipped);
            if (error != null) report.notes.add("error: " + error);
            else if (skipped > 0 && affectedCount == 0 && bytes == 0) report.notes.add("error: Accessibility service is not set up. Complete the setup and give consent.");
            if (cancelled) report.notes.add("cancelled");
        }
    }
}
