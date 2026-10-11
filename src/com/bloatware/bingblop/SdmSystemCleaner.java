package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-tool-systemcleaner: core/SystemCleaner.kt, SystemCrawler.kt, FilterContent.kt, SystemCleanerSettings.kt, filter/stock/*.kt, strings.xml).
 *
 * SystemCleaner: "Superfluous data that doesn't belong to any specific app." 22 stock filters (spec 2.2, in settings-screen order) run over the data areas in
 * ONE walk per area with every enabled filter asked per entry; the first filter that matches wins. A scan result is a list of filters (groups) with the paths they
 * matched (items); a deletion removes the distinct roots of the selected paths and then reconciles the result.
 *
 * Changed for this port:
 *  - the areas come from {@link Sdm.Areas} (areas with via "" are skipped), reading goes through {@link Sdm.Fs}; no android.* and no coroutines;
 *  - the filters whose upstream order was an unordered Dagger set run in settings order (spec 8.8 item 2); TempFilesFilter's path exclusions
 *    "backup/pending" and "cache/recovery" (which can never match absolute segments upstream) are applied to the path below the area root as Contain (8.8 item 1);
 *  - a directory is not descended when it is the root of another data area (what upstream's "peek-content-area" check does) and the three Android/* folders
 *    never belong to SDCARD; excluded directories are pruned while walking, and a result path that is an ancestor of an excluded path is dropped (the "nested rule");
 *  - EmptyDirectoryFilter checks a candidate's emptiness after the walk, from what the walk saw plus one listing of the candidates (an excluded child blocks);
 *  - SuperfluousApksFilter reads the manifest of the archive itself (a small binary-XML reader) instead of PackageManager.getPackageArchiveInfo, and the
 *    installed version code is taken from Sdm.Pkg.versionCode when that field exists (version name equality otherwise);
 *  - the deletion re-verifies every root right before deleting (gone = deleted; a type change, a no longer empty folder, a path that is not strictly inside an
 *    area, a root that holds a deselected or excluded item are skipped, never deleted) and removes nested matches of every group with the root;
 *  - custom filters (spec 2.5) are not ported.
 */
public final class SdmSystemCleaner implements Sdm.ToolImpl {

    public SdmSystemCleaner() {}

    @Override public Sdm.Tool tool() { return Sdm.Tool.SYSTEMCLEANER; }

    // ---------------------------------------------------------------------------------------------------------- the 22 filters (labels: spec 9.1)

    private static final class Meta {
        final String id, label, summary;
        final boolean on, root;
        Meta(String id, String label, String summary, boolean on, boolean root) { this.id = id; this.label = label; this.summary = summary; this.on = on; this.root = root; }
        String key() { return "filter." + id + ".enabled"; }
    }

    /** Settings-screen order = the order the filters are asked in. */
    private static final Meta[] METAS = {
        new Meta("logfiles", "System log files", "System and system service log files (e.g. *.log) in various locations.", true, false),
        new Meta("advertisements", "Ad files", "Files that have been created for or by advertisements.", true, false),
        new Meta("emptydirectories", "Empty folders", "Empty folders from all over the device. Nested folders may require multiple passes.", true, false),
        new Meta("superfluosapks", "Superfluous APKs", "Setup files that are no longer needed based on installed app versions.", false, false),
        new Meta("trashed", "Trashed files", "Files in recycle bins from various apps and locations that are marked for deletion but not yet permanently removed.", false, false),
        new Meta("screenshots", "Screenshots", "Screenshots older than %s from multiple apps and locations.", false, false),
        new Meta("lostdir", "LOST.DIR folders", "Temporary files that are created when transferring data between Android devices.", true, false),
        new Meta("linuxfiles", "Linux files", "Files and folders that are created when storage has been connected to a linux computer.", true, false),
        new Meta("macfiles", "Mac files", "Files and folders that are created when browsing storage with Apple's finder app.", true, false),
        new Meta("windowsfiles", "Windows files", "Data that the Windows file explorer can create on connected storage.", true, false),
        new Meta("tempfiles", "Temporary system files", "System related temporary files, usually short lived caches for active operations.", true, false),
        new Meta("thumbnails", "Thumbnail images", "Cached previews for images and videos.", false, false),
        new Meta("analytics", "Analytics files", "Data related to analytics and bug tracking.", true, false),
        new Meta("anr", "ANR errors", "Traces from 'application not responding' events.", true, true),
        new Meta("localtmp", "Local temporary files", "System related temporary files (e.g. from Android Studio).", false, true),
        new Meta("downloadcache", "Download cache", "System cache folder used for various things (e.g. OTA updates).", true, true),
        new Meta("datalogger", "System log files", "System service specific log files.", true, true),
        new Meta("logdropbox", "Log drop box", "An area for the system to drop log files into.", true, true),
        new Meta("recenttasks", "Recent tasks", "Data related to the tasks history in the system's task switcher.", false, true),
        new Meta("tombstones", "System crash residue", "If native processes crash (e.g. games), then files are created here.", false, true),
        new Meta("usagestats", "Usage stats", "Files related to application usage stats.", false, true),
        new Meta("packagecache", "Installer cache", "Cached data related to installed apps that is used by the system's package management.", false, true),
    };

    public static final String KEY_SUPERFLUOUS_SAME = "filter.superfluosapks.includesameversion";
    public static final String KEY_SCREENSHOTS_AGE = "filter.screenshots.age";          // milliseconds (upstream: a Duration, 0 h .. 90 days, default 14 days)
    public static final long SCREENSHOTS_AGE_DEFAULT_MS = 14L * 24 * 3600 * 1000;
    public static final long SCREENSHOTS_AGE_MAX_MS = 90L * 24 * 3600 * 1000;

    private static Meta meta(String id) {
        for (Meta m : METAS) if (m.id.equals(id)) return m;
        throw new IllegalArgumentException("unknown filter: " + id);
    }

    /** The ids (= group ids) of the 22 filters in settings order. */
    public static String[] filterIds() {
        String[] a = new String[METAS.length];
        for (int i = 0; i < a.length; i++) a[i] = METAS[i].id;
        return a;
    }
    public static String filterLabel(String id) { return meta(id).label; }
    /** The description; for "screenshots" it still holds the "%s" for the age. */
    public static String filterSummary(String id) { return meta(id).summary; }
    public static String filterKey(String id) { return meta(id).key(); }
    public static boolean filterDefault(String id) { return meta(id).on; }
    public static boolean filterNeedsRoot(String id) { return meta(id).root; }

    // ---------------------------------------------------------------------------------------------------------- strings (spec 7.3, 7.7, 9.1)

    public static final String P_SEARCHING = "Searching", P_GENERATING = "Generating search paths", P_LOADING = "Loading";

    /** "%d filter match(es)" */
    public static String foundText(int n) { return n == 1 ? "1 filter match" : n + " filter matches"; }
    /** "%d match(es) deleted" */
    public static String deletedText(int n) { return n == 1 ? "1 match deleted" : n + " matches deleted"; }
    public static String canBeFreedText(long bytes) { return fmtSize(bytes) + " can be freed"; }
    public static String freedText(long bytes) { return "Freed " + fmtSize(bytes) + " space."; }

    /** Binary units, one decimal: "512 B", "1.5 KB", "12.3 MB". (SdmEngine.formatSize is the same format.) */
    public static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        String[] u = { "KB", "MB", "GB", "TB", "PB" };
        double v = b / 1024.0;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", v, u[i]);
    }

    /** formatAge of upstream: "N days" when it is a day or more, else "N hours" (whole units, rounded down). */
    public static String ageText(long ms) {
        long days = ms / (24L * 3600 * 1000);
        if (days > 0) return days + (days == 1 ? " day" : " days");
        long hours = ms / (3600L * 1000);
        return hours + (hours == 1 ? " hour" : " hours");
    }

    // ---------------------------------------------------------------------------------------------------------- result model

    private static final class Item {
        final String path, name;
        final long size, mtime;
        final int type;
        final Sdm.AreaInfo ai;
        Item(Sdm.Entry e, Sdm.AreaInfo ai) { this.path = e.path; this.name = e.name; this.size = e.size; this.mtime = e.mtime; this.type = e.type; this.ai = ai; }
    }

    private static final class Grp {
        final Meta meta;
        final String sub;
        final ArrayList<Item> items;
        long bytes;
        Grp(Meta meta, String sub, ArrayList<Item> items) {
            this.meta = meta; this.sub = sub; this.items = items;
            for (Item i : items) bytes += i.size;
        }
        void recount() { bytes = 0; for (Item i : items) bytes += i.size; }
    }

    /** What a scan found: the filters that matched something, biggest first, each with its matched paths. */
    public static final class SysResult implements Sdm.Result {
        private final ArrayList<Grp> groups;

        private SysResult(ArrayList<Grp> groups) { this.groups = groups; }

        @Override public Sdm.Tool tool() { return Sdm.Tool.SYSTEMCLEANER; }
        @Override public synchronized int groupCount() { return groups.size(); }
        @Override public synchronized int itemCount() { int n = 0; for (Grp g : groups) n += g.items.size(); return n; }
        @Override public synchronized long bytes() { long b = 0; for (Grp g : groups) b += g.bytes; return b; }

        @Override public synchronized JSONArray groups(int offset, int limit) throws Exception {
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, offset); i < groups.size() && (limit <= 0 || out.length() < limit); i++) {
                Grp g = groups.get(i);
                JSONObject o = new JSONObject();
                o.put("id", g.meta.id);
                o.put("label", g.meta.label);
                o.put("sub", g.sub);
                o.put("count", g.items.size());
                o.put("bytes", g.bytes);
                o.put("root", g.meta.root);
                out.put(o);
            }
            return out;
        }

        @Override public synchronized JSONArray items(String groupId, int offset, int limit) throws Exception {
            JSONArray out = new JSONArray();
            Grp g = find(groupId);
            if (g == null) return out;
            for (int i = Math.max(0, offset); i < g.items.size() && (limit <= 0 || out.length() < limit); i++) {
                Item it = g.items.get(i);
                JSONObject o = new JSONObject();
                o.put("id", it.path);
                o.put("path", it.path);
                o.put("name", it.name);
                o.put("size", it.size);
                o.put("mtime", it.mtime);
                o.put("type", it.type == Sdm.DIR ? 1 : it.type == Sdm.LINK ? 2 : 0);
                out.put(o);
            }
            return out;
        }

        /** {itemCount, bytes, groupCount, primary: "N filter match(es)", secondary: "X can be freed"}, from the live data. */
        @Override public synchronized JSONObject summary() throws Exception {
            int n = itemCount();
            long b = bytes();
            JSONObject o = new JSONObject();
            o.put("itemCount", n);
            o.put("bytes", b);
            o.put("groupCount", groups.size());
            o.put("primary", foundText(n));
            o.put("secondary", canBeFreedText(b));
            return o;
        }

        private Grp find(String id) { for (Grp g : groups) if (g.meta.id.equals(id)) return g; return null; }

        /** Every matched path of a group, in list order (for tests and the page's "select all"). */
        public synchronized List<String> paths(String groupId) {
            List<String> out = new ArrayList<String>();
            Grp g = find(groupId);
            if (g != null) for (Item i : g.items) out.add(i.path);
            return out;
        }

        public synchronized List<String> groupIds() {
            List<String> out = new ArrayList<String>();
            for (Grp g : groups) out.add(g.meta.id);
            return out;
        }

        public synchronized long groupBytes(String groupId) { Grp g = find(groupId); return g == null ? 0 : g.bytes; }

        /**
         * After the user excluded paths (spec 6.1, "nested rule"): drops every item that is an excluded path or below one, and every item that is a strict
         * ancestor of an excluded path; empty groups vanish. Returns how many items went.
         */
        public synchronized int removeExcluded(Collection<String> excluded) {
            Set<String> ex = new HashSet<String>(excluded);
            Set<String> anc = new HashSet<String>();
            for (String e : ex) addAncestors(anc, e);
            int removed = 0;
            for (java.util.Iterator<Grp> gi = groups.iterator(); gi.hasNext(); ) {
                Grp g = gi.next();
                for (java.util.Iterator<Item> ii = g.items.iterator(); ii.hasNext(); ) {
                    Item it = ii.next();
                    if (anc.contains(it.path) || ex.contains(it.path) || hasAncestorIn(ex, it.path)) { ii.remove(); removed++; }
                }
                if (g.items.isEmpty()) gi.remove(); else g.recount();
            }
            return removed;
        }

        @Override public synchronized String toString() { return "SysResult(" + groups.size() + " filters, " + itemCount() + " items, " + bytes() + " bytes)"; }
    }

    private static SysResult build(Map<String, ArrayList<Item>> found, Map<String, String> subs) {
        ArrayList<Grp> gs = new ArrayList<Grp>();
        for (Meta m : METAS) {
            ArrayList<Item> items = found.get(m.id);
            if (items == null || items.isEmpty()) continue;
            sortItems(m.id, items);
            gs.add(new Grp(m, subs.get(m.id), items));
        }
        Collections.sort(gs, new Comparator<Grp>() {   // stable: equal sizes keep the settings order
            @Override public int compare(Grp a, Grp b) { return a.bytes == b.bytes ? 0 : a.bytes > b.bytes ? -1 : 1; }
        });
        return new SysResult(gs);
    }

    /** As the details page of upstream: empty folders by path, trashed files newest first, screenshots oldest first, everything else biggest first. */
    private static void sortItems(String id, ArrayList<Item> items) {
        Comparator<Item> c;
        if (id.equals("emptydirectories")) c = new Comparator<Item>() { @Override public int compare(Item a, Item b) { return a.path.compareTo(b.path); } };
        else if (id.equals("trashed")) c = new Comparator<Item>() { @Override public int compare(Item a, Item b) { return a.mtime == b.mtime ? a.path.compareTo(b.path) : a.mtime > b.mtime ? -1 : 1; } };
        else if (id.equals("screenshots")) c = new Comparator<Item>() { @Override public int compare(Item a, Item b) { return a.mtime == b.mtime ? a.path.compareTo(b.path) : a.mtime < b.mtime ? -1 : 1; } };
        else c = new Comparator<Item>() { @Override public int compare(Item a, Item b) { return a.size == b.size ? a.path.compareTo(b.path) : a.size > b.size ? -1 : 1; } };
        Collections.sort(items, c);
    }

    // ---------------------------------------------------------------------------------------------------------- path helpers

    /** Adds every strict ancestor of path ("/a/b/c" -> "/a/b", "/a") to set; stops at the first one that is already there. */
    private static void addAncestors(Set<String> set, String path) {
        for (int i = path.lastIndexOf('/'); i > 0; i = path.lastIndexOf('/', i - 1)) {
            if (!set.add(path.substring(0, i))) break;
        }
    }

    /** True when path or one of its ancestors is in set. */
    private static boolean hasAncestorIn(Set<String> set, String path) {
        if (set.isEmpty()) return false;
        if (set.contains(path)) return true;
        for (int i = path.lastIndexOf('/'); i > 0; i = path.lastIndexOf('/', i - 1)) if (set.contains(path.substring(0, i))) return true;
        return set.contains("/") && path.length() > 1;
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    private static String normRoot(String root) {
        return root.length() > 1 && root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }

    private static final Set<String> NEVER_DELETE = new HashSet<String>(Arrays.asList(
        "/", "/data", "/storage", "/storage/emulated", "/sdcard", "/system", "/vendor", "/cache", "/mnt", "/proc", "/dev", "/sys", "/etc"));

    /**
     * Why path must never be deleted by this tool, or null when it may be: not absolute, NUL / line break, a "." or ".." segment, an empty segment, one of the
     * system folders, an area root or &lt;sdcard&gt;/Android, or not strictly inside one of the available area roots.
     */
    static String unsafeReason(String path, List<Sdm.AreaInfo> areas) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') return "not an absolute path";
        if (path.indexOf('\0') >= 0 || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) return "name contains a NUL or a line break";
        if (path.length() > 1 && path.endsWith("/")) return "not a normalised path";
        String[] segs = Sdm.segments(path);
        for (int i = 1; i < segs.length; i++) {
            String s = segs[i];
            if (s.isEmpty()) return "not a normalised path";
            if (s.equals(".") || s.equals("..")) return "relative path segment";
        }
        if (NEVER_DELETE.contains(path)) return "protected folder";
        boolean inside = false;
        for (Sdm.AreaInfo a : areas) {
            if (a.root == null) continue;
            String r = normRoot(a.root);
            if (path.equals(r)) return "data area root";
            if (a.area == Sdm.Area.SDCARD && path.equals(Sdm.join(r, "Android"))) return "the Android folder";
            if (a.available() && SdmSieve.isAncestorOf(r, path)) inside = true;
        }
        return inside ? null : "not inside a data area";
    }

    // ---------------------------------------------------------------------------------------------------------- filters

    private static final int NO = 0, YES = 1, DEFER = 2;

    /** One entry as the filters see it. */
    private static final class Ev {
        final Sdm.Entry e;
        final String[] segs, pfp;
        final Sdm.AreaInfo ai;
        Ev(Sdm.Entry e, String[] segs, String[] pfp, Sdm.AreaInfo ai) { this.e = e; this.segs = segs; this.pfp = pfp; this.ai = ai; }
    }

    private abstract static class F {
        final Meta meta;
        final EnumSet<Sdm.Area> areas;
        F(Meta meta, Sdm.Area... a) { this.meta = meta; this.areas = EnumSet.noneOf(Sdm.Area.class); this.areas.addAll(Arrays.asList(a)); }
        abstract int match(Ev v);
    }

    /** A filter made of one or more crawler sieves; any of them matching is a match. */
    private static final class SieveF extends F {
        final SdmSieve.Config[] sieves;
        final long now;
        SieveF(Meta meta, long now, EnumSet<Sdm.Area> areas, SdmSieve.Config... sieves) {
            super(meta);
            this.areas.addAll(areas);
            this.sieves = sieves;
            this.now = now;
        }
        @Override int match(Ev v) {
            for (SdmSieve.Config c : sieves) if (c.matches(v.e, v.segs, v.ai.area, v.pfp, now)) return YES;
            return NO;
        }
    }

    private static EnumSet<Sdm.Area> set(Sdm.Area... a) { return EnumSet.copyOf(Arrays.asList(a)); }

    private static SdmSieve.Seg anc(String s) { return SdmSieve.Seg.anc(s); }
    private static SdmSieve.Seg eqs(String s) { return SdmSieve.Seg.eq(s); }
    private static SdmSieve.Seg contain(String s) { return SdmSieve.Seg.contain(s); }
    private static SdmSieve.Seg startsP(String s) { return SdmSieve.Seg.startPartial(s); }
    private static SdmSieve.Name nEnd(String s) { return SdmSieve.Name.end(s); }
    private static SdmSieve.Name nStart(String s) { return SdmSieve.Name.start(s); }
    private static SdmSieve.Name nEq(String s) { return SdmSieve.Name.eq(s); }

    // ---- AdvertisementFilter: top-level ad SDK folders of the SDCARD roots (pfp criterion AND case-sensitive full-path regex of upstream)

    private static final class AdF extends F {
        private static final class Rule {
            final String name; final boolean self, partial;
            Rule(String name, boolean self, boolean partial) { this.name = name; this.self = self; this.partial = partial; }
            boolean pfpMatches(String[] pfp) {
                if (pfp.length == 0) return false;
                if (self) return pfp.length == 1 && pfp[0].equalsIgnoreCase(name);
                return partial ? pfp[0].regionMatches(true, 0, name, 0, name.length()) : pfp[0].equalsIgnoreCase(name);
            }
        }
        static final Rule[] RULES = {
            new Rule("ppy_cross", true, false), new Rule(".mologiq", false, true), new Rule(".Adcenix", false, false), new Rule("ApplifierVideoCache", false, false),
            new Rule("burstlyVideoCache", false, false), new Rule("UnityAdsVideoCache", false, false), new Rule("ApplifierImageCache", false, false),
            new Rule("burstlyImageCache", false, false), new Rule("UnityAdsImageCache", false, false), new Rule("__chartboost", false, false),
            new Rule(".chartboost", false, false), new Rule("adhub", false, false), new Rule(".mobvista", false, true), new Rule(".goadsdk", false, false),
            new Rule(".goproduct", false, false) };
        private final Map<String, Pattern[]> regexes = new HashMap<String, Pattern[]>();

        AdF(Meta meta, List<Sdm.AreaInfo> sdcards) {
            super(meta, Sdm.Area.SDCARD);
            for (Sdm.AreaInfo a : sdcards) {
                String r = Pattern.quote(normRoot(a.root));
                Pattern[] ps = new Pattern[RULES.length];
                for (int i = 0; i < RULES.length; i++) {
                    Rule rule = RULES[i];
                    String n = Pattern.quote(rule.name);
                    if (rule.self) ps[i] = Pattern.compile("^(?:" + r + "/" + n + ")$");
                    else if (rule.name.equals(".mobvista")) ps[i] = Pattern.compile("^(?:" + r + "/)(?:\\.mobvista\\d+|\\.mobvista\\d+/.+)$");
                    else ps[i] = Pattern.compile("^(?:" + r + "/)(?:" + n + "|" + n + "/.+)$");
                }
                regexes.put(normRoot(a.root), ps);
            }
        }

        @Override int match(Ev v) {
            Pattern[] ps = regexes.get(normRoot(v.ai.root));
            if (ps == null) return NO;
            for (int i = 0; i < RULES.length; i++) {
                if (RULES[i].pfpMatches(v.pfp) && ps[i].matcher(v.e.path).matches()) {
                    // a file called "...chartboost" is not an ad folder, only the directory is
                    if (v.e.name.endsWith("chartboost") && v.e.type != Sdm.DIR) return NO;
                    return YES;
                }
            }
            return NO;
        }
    }

    // ---- EmptyDirectoryFilter

    private static final String[][] PROTECTED_BASE_DIRS = {
        { "Camera" }, { "Photos" }, { "Music" }, { "DCIM" }, { "Pictures" }, { "Movies" }, { "Recordings" }, { "Video" }, { "Download" }, { "Audiobooks" },
        { "Documents" }, { "Alarms" }, { "Ringtones" }, { "Notifications" }, { "Podcasts" }, { "Android", "data" }, { "Android", "media" }, { "Android", "obb" } };
    private static final SdmSieve.Seg[] EMPTY_PATH_EXCLUSIONS = {
        SdmSieve.Seg.contain("mnt/asec"), SdmSieve.Seg.contain("mnt/obb"), SdmSieve.Seg.contain("mnt/secure"), SdmSieve.Seg.contain("mnt/shell"),
        SdmSieve.Seg.contain("Android/obb"), SdmSieve.Seg.contain(".stfolder") };
    private static final int EMPTY_CACHE_MAX = 100000;

    /** The rules every folder must satisfy to be an empty-folder match, apart from being empty. */
    static boolean emptyDirCandidate(String[] segs, String[] pfp, Sdm.Area area) {
        for (SdmSieve.Seg s : EMPTY_PATH_EXCLUSIONS) if (s.match(segs)) return false;
        if (pfp.length == 0) return false;
        for (String[] p : PROTECTED_BASE_DIRS) if (SdmSieve.sameSegs(p, pfp, true)) return false;
        boolean pkgArea = area == Sdm.Area.PUBLIC_DATA || area == Sdm.Area.PUBLIC_MEDIA || area == Sdm.Area.PUBLIC_OBB;
        if (pkgArea && pfp.length == 1) return false;                                               // the top-level package folders
        if (pkgArea && pfp.length == 2 && (pfp[1].equals("files") || pfp[1].equals("cache"))) return false;
        return true;
    }

    private static String[] pfpOf(String[] segs, Sdm.AreaInfo ai) {
        int depth = Sdm.segments(normRoot(ai.root)).length;
        return depth >= segs.length ? new String[0] : Arrays.copyOfRange(segs, depth, segs.length);
    }

    /** isEmptyTree of upstream with a cache: no file (or link or anything that is no folder) anywhere below, every sub folder a match itself. */
    private static final class EmptyDirs {
        final Sdm.Ctx ctx;
        final HashMap<String, Boolean> cache = new HashMap<String, Boolean>();
        EmptyDirs(Sdm.Ctx ctx) { this.ctx = ctx; }

        boolean emptyTree(String dir, Sdm.AreaInfo ai) {
            if (ctx.cancelled()) throw new CancellationException("cancelled");
            Boolean c = cache.get(dir);
            if (c != null) return c;
            if (cache.size() >= EMPTY_CACHE_MAX) cache.clear();
            boolean result = compute(dir, ai);
            cache.put(dir, result);
            return result;
        }

        private boolean compute(String dir, Sdm.AreaInfo ai) {
            String[] names;
            try { names = ctx.fs.list(dir); } catch (IOException e) { return false; }
            if (names == null) return false;                                                         // unreadable: never "empty"
            List<String> subs = new ArrayList<String>();
            for (String n : names) {
                String child = Sdm.join(dir, n);
                Sdm.Entry ce;
                try { ce = ctx.fs.stat(child); } catch (IOException e) { return false; }
                if (ce == null) continue;                                                            // gone meanwhile
                if (ce.type != Sdm.DIR) return false;
                if (ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.SYSTEMCLEANER, child)) return false;   // an excluded folder must survive
                subs.add(child);
            }
            for (String sub : subs) {
                String[] segs = Sdm.segments(sub);
                if (!emptyDirCandidate(segs, pfpOf(segs, ai), ai.area)) return false;
                if (!emptyTree(sub, ai)) return false;
            }
            return true;
        }
    }

    private static final class EmptyF extends F {
        EmptyF(Meta meta) { super(meta, Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA, Sdm.Area.PUBLIC_MEDIA, Sdm.Area.PORTABLE); }
        @Override int match(Ev v) {
            if (v.e.type != Sdm.DIR) return NO;
            return emptyDirCandidate(v.segs, v.pfp, v.ai.area) ? DEFER : NO;
        }
    }

    // ---- SuperfluousApksFilter

    private static final class ApkInfo {
        String pkg = "", versionName = "";
        long versionCode = -1;
    }

    private static final int MAX_MANIFEST = 8 * 1024 * 1024;

    /** package / versionCode / versionName of an .apk, or of the base.apk inside an .apks; null when it cannot be read. */
    static ApkInfo readApk(String path, boolean apks) {
        ZipFile zf = null;
        try {
            zf = new ZipFile(path);
            if (!apks) {
                ZipEntry en = zf.getEntry("AndroidManifest.xml");
                if (en == null) return null;
                InputStream in = zf.getInputStream(en);
                try { return readManifest(readAll(in, MAX_MANIFEST)); } finally { in.close(); }
            }
            ZipEntry base = zf.getEntry("base.apk");
            if (base == null) return null;
            ZipInputStream zis = new ZipInputStream(zf.getInputStream(base));
            try {
                ZipEntry en;
                while ((en = zis.getNextEntry()) != null) {
                    if ("AndroidManifest.xml".equals(en.getName())) return readManifest(readAll(zis, MAX_MANIFEST));
                }
            } finally { zis.close(); }
            return null;
        } catch (IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        } finally {
            if (zf != null) try { zf.close(); } catch (IOException ignored) {}
        }
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > max) return null;
        }
        return out.toByteArray();
    }

    /** Reads the attributes of the root &lt;manifest&gt; element out of a compiled (binary) AndroidManifest.xml. */
    static ApkInfo readManifest(byte[] d) {
        if (d == null || d.length < 16) return null;
        try {
            ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            if ((b.getShort(0) & 0xffff) != 0x0003) return null;
            int pos = b.getShort(2) & 0xffff;
            int total = Math.min(d.length, b.getInt(4));
            String[] strings = null;
            while (pos >= 0 && pos + 8 <= total) {
                int type = b.getShort(pos) & 0xffff, hs = b.getShort(pos + 2) & 0xffff, size = b.getInt(pos + 4);
                if (size < 8 || pos + size > total) return null;
                if (type == 0x0001) {
                    strings = readPool(b, pos, size);
                    if (strings == null) return null;
                } else if (type == 0x0102) {
                    if (strings == null) return null;
                    int ext = pos + hs;
                    if (!"manifest".equals(str(strings, b.getInt(ext + 4)))) return null;
                    int attrStart = b.getShort(ext + 8) & 0xffff, attrSize = b.getShort(ext + 10) & 0xffff, attrCount = b.getShort(ext + 12) & 0xffff;
                    if (attrSize < 20) return null;
                    ApkInfo info = new ApkInfo();
                    long code = -1, major = 0;
                    for (int i = 0; i < attrCount; i++) {
                        int a = ext + attrStart + i * attrSize;
                        String name = str(strings, b.getInt(a + 4));
                        int raw = b.getInt(a + 8), dataType = b.get(a + 15) & 0xff, data = b.getInt(a + 16);
                        if ("package".equals(name)) info.pkg = raw != -1 ? str(strings, raw) : dataType == 0x03 ? str(strings, data) : "";
                        else if ("versionName".equals(name)) info.versionName = raw != -1 ? str(strings, raw) : dataType == 0x03 ? str(strings, data) : "";
                        else if ("versionCode".equals(name)) code = data & 0xFFFFFFFFL;
                        else if ("versionCodeMajor".equals(name)) major = data & 0xFFFFFFFFL;
                    }
                    if (info.pkg.isEmpty() || code < 0) return null;
                    info.versionCode = (major << 32) | code;
                    return info;
                }
                pos += size;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String str(String[] pool, int i) { return i >= 0 && i < pool.length && pool[i] != null ? pool[i] : ""; }

    private static String[] readPool(ByteBuffer b, int pos, int size) {
        int hs = b.getShort(pos + 2) & 0xffff;
        int count = b.getInt(pos + 8), flags = b.getInt(pos + 16), stringsStart = b.getInt(pos + 20);
        if (count < 0 || count > size / 4 || stringsStart < 0) return null;
        boolean utf8 = (flags & 0x100) != 0;
        String[] out = new String[count];
        int end = pos + size;
        for (int i = 0; i < count; i++) {
            int off = b.getInt(pos + hs + i * 4);
            int p = pos + stringsStart + off;
            if (off < 0 || p >= end) { out[i] = ""; continue; }
            try {
                if (utf8) {
                    int l1 = b.get(p) & 0xff; p++;
                    if ((l1 & 0x80) != 0) { l1 = ((l1 & 0x7f) << 8) | (b.get(p) & 0xff); p++; }
                    int l2 = b.get(p) & 0xff; p++;
                    if ((l2 & 0x80) != 0) { l2 = ((l2 & 0x7f) << 8) | (b.get(p) & 0xff); p++; }
                    if (p + l2 > end) { out[i] = ""; continue; }
                    byte[] raw = new byte[l2];
                    for (int k = 0; k < l2; k++) raw[k] = b.get(p + k);
                    out[i] = new String(raw, "UTF-8");
                } else {
                    int l = b.getShort(p) & 0xffff; p += 2;
                    if ((l & 0x8000) != 0) { l = ((l & 0x7fff) << 16) | (b.getShort(p) & 0xffff); p += 2; }
                    if (l < 0 || p + l * 2 > end) { out[i] = ""; continue; }
                    byte[] raw = new byte[l * 2];
                    for (int k = 0; k < raw.length; k++) raw[k] = b.get(p + k);
                    out[i] = new String(raw, "UTF-16LE");
                }
            } catch (java.io.UnsupportedEncodingException e) {
                out[i] = "";
            }
        }
        return out;
    }

    /** installed >= archive (include same version) or installed > archive. */
    static boolean superfluous(long installedCode, long apkCode, boolean includeSame) {
        return includeSame ? installedCode >= apkCode : installedCode > apkCode;
    }

    private static final java.lang.reflect.Field PKG_VERSION_CODE = versionCodeField();

    private static java.lang.reflect.Field versionCodeField() {
        try { return Sdm.Pkg.class.getField("versionCode"); } catch (NoSuchFieldException e) { return null; } catch (RuntimeException e) { return null; }
    }

    /** Sdm.Pkg.versionCode when that field exists, else -1. */
    private static long installedCode(Sdm.Pkg p) {
        if (PKG_VERSION_CODE == null) return -1;
        try { return ((Number) PKG_VERSION_CODE.get(p)).longValue(); } catch (Exception e) { return -1; }
    }

    private static final class ApkF extends F {
        final SdmSieve.Config sieve;
        final Sdm.Ctx ctx;
        final boolean includeSame;
        ApkF(Meta meta, Sdm.Ctx ctx, boolean includeSame) {
            super(meta, Sdm.Area.SDCARD, Sdm.Area.PORTABLE);
            this.ctx = ctx;
            this.includeSame = includeSame;
            this.sieve = new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PORTABLE).types(SdmSieve.T_FILE).names(nEnd(".apk"), nEnd(".apks"))
                .notPath(contain("Backup"), contain("Backups"), contain("Recover"), contain("Recovery"), contain("TWRP"));
        }
        @Override int match(Ev v) {
            if (!sieve.matches(v.e, v.segs, v.ai.area, v.pfp, ctx.nowSec)) return NO;
            boolean apk = v.e.name.endsWith(".apk"), apks = v.e.name.endsWith(".apks");    // upstream checks the case here: "A.APK" is never read
            if (!apk && !apks) return NO;
            if (!"java".equals(v.ai.via)) return NO;                                        // only readable where this app reads the files itself
            ApkInfo info = readApk(v.e.path, apks);
            if (info == null) return NO;                                                    // unreadable archives are not matched
            Sdm.Pkg p = ctx.pkgs == null ? null : ctx.pkgs.get(info.pkg);
            if (p == null || p.uninstalled) return NO;                                      // not installed: not superfluous
            long inst = installedCode(p);
            if (inst >= 0) return superfluous(inst, info.versionCode, includeSame) ? YES : NO;
            // no version code in the package model: only "same version" can be told, by name
            if (!p.versionName.isEmpty() && p.versionName.equals(info.versionName)) return includeSame ? YES : NO;
            return NO;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the scan

    @Override
    public Sdm.Result scan(Sdm.Ctx ctx) throws Exception {
        return new Run(ctx).crawl();
    }

    private static final class Pending {
        final Sdm.Entry e;
        final Sdm.AreaInfo ai;
        final List<F> fs;
        final int next;
        Pending(Sdm.Entry e, Sdm.AreaInfo ai, List<F> fs, int next) { this.e = e; this.ai = ai; this.fs = fs; this.next = next; }
    }

    /** SystemCrawler's global skips: "/data/media/0" always, "/data/data" and "/data/user/0" unless a target area is /data/data itself (skipDataData). */
    static boolean isGlobalSkip(String path, boolean skipDataData) {
        if (path.equals("/data/media/0") || path.startsWith("/data/media/0/")) return true;
        if (!skipDataData) return false;
        return path.equals("/data/data") || path.startsWith("/data/data/") || path.equals("/data/user/0") || path.startsWith("/data/user/0/");
    }

    private static final class Run {
        final Sdm.Ctx ctx;
        final long now;
        final List<Sdm.AreaInfo> all = new ArrayList<Sdm.AreaInfo>();
        final List<Sdm.AreaInfo> available = new ArrayList<Sdm.AreaInfo>();
        final List<F> filters = new ArrayList<F>();
        final Map<String, String> subs = new HashMap<String, String>();
        final Map<String, ArrayList<Item>> found = new HashMap<String, ArrayList<Item>>();
        final List<String> excludedSeen = new ArrayList<String>();
        long seen, bytes;
        boolean skipDataData;

        Run(Sdm.Ctx ctx) {
            this.ctx = ctx;
            this.now = ctx.nowSec;
        }

        void prog(String primary, String secondary) { ctx.progress.update(primary, secondary, seen, -1, bytes); }

        Sdm.Result crawl() throws Exception {
            prog(P_SEARCHING, P_GENERATING);
            if (ctx.areas != null) {
                for (Sdm.AreaInfo a : ctx.areas.all()) {
                    if (a == null || a.root == null || a.root.isEmpty()) continue;
                    all.add(a);
                    if (a.available()) available.add(a);
                }
            }
            buildFilters();
            if (ctx.cancelled()) throw new CancellationException("cancelled");

            EnumSet<Sdm.Area> wanted = EnumSet.noneOf(Sdm.Area.class);
            for (F f : filters) wanted.addAll(f.areas);
            List<Sdm.AreaInfo> targets = new ArrayList<Sdm.AreaInfo>();
            for (Sdm.AreaInfo a : available) if (wanted.contains(a.area)) targets.add(a);

            // "/data/data" and "/data/user/0" are skipped unless a target area is /data/data itself; "/data/media/0" always
            skipDataData = true;
            for (Sdm.AreaInfo a : targets) if (normRoot(a.root).equals("/data/data")) skipDataData = false;

            for (Sdm.AreaInfo a : targets) walkArea(a);

            return finish();
        }

        void buildFilters() {
            boolean root = ctx.shell != null && ctx.shell.uid() == 0;
            for (Meta m : METAS) {
                if (!ctx.bool(m.key(), m.on)) continue;
                if (m.root && !root) continue;                                                       // "specific" filters need root, ADB / Shizuku do not count
                F f = create(m);
                filters.add(f);
                String sub = m.summary;
                if (m.id.equals("screenshots")) sub = String.format(java.util.Locale.ROOT, m.summary, ageText(screenshotMs()));
                subs.put(m.id, sub);
            }
        }

        long screenshotMs() {
            return Math.max(0, Math.min(ctx.num(KEY_SCREENSHOTS_AGE, SCREENSHOTS_AGE_DEFAULT_MS), SCREENSHOTS_AGE_MAX_MS));
        }

        F create(Meta m) {
            String id = m.id;
            if (id.equals("logfiles")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.DOWNLOAD_CACHE, Sdm.Area.DATA_VENDOR, Sdm.Area.DATA_SYSTEM, Sdm.Area.DATA_MISC, Sdm.Area.DATA),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD).types(SdmSieve.T_FILE).names(nEnd(".log"))
                        .notPath(SdmSieve.Seg.containPartial(".indexeddb.leveldb"), contain("t/Paths"), contain("app_chrome"), contain("app_webview"), contain("leveldb"), contain("shared_proto_db")),
                    new SdmSieve.Config().areas(Sdm.Area.DOWNLOAD_CACHE).types(SdmSieve.T_FILE).names(nEnd(".log")),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_VENDOR).types(SdmSieve.T_FILE).names(nEnd(".txt.old")).pfp(anc("radio/extended_logs")),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_VENDOR).types(SdmSieve.T_FILE).names(nEq("bt_activity_pkt.txt.last")).pfp(anc("bluetooth")),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_SYSTEM).types(SdmSieve.T_FILE).names(nStart("checkpoints-")).pfp(anc("shutdown-checkpoints")),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_MISC).types(SdmSieve.T_FILE).names(nStart("update_engine.")).pfp(anc("update_engine_log")).minAgeSec(2L * 24 * 3600),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_MISC).types(SdmSieve.T_FILE).names(nStart("last_kmsg.")).pfp(anc("recovery")),
                    new SdmSieve.Config().areas(Sdm.Area.DATA).types(SdmSieve.T_FILE | SdmSieve.T_DIR).pfp(anc("miuilog/stability/scout/app")));
            }
            if (id.equals("advertisements")) {
                List<Sdm.AreaInfo> sd = new ArrayList<Sdm.AreaInfo>();
                for (Sdm.AreaInfo a : available) if (a.area == Sdm.Area.SDCARD) sd.add(a);
                return new AdF(m, sd);
            }
            if (id.equals("emptydirectories")) return new EmptyF(m);
            if (id.equals("superfluosapks")) return new ApkF(m, ctx, ctx.bool(KEY_SUPERFLUOUS_SAME, true));
            if (id.equals("trashed")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PORTABLE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PORTABLE).types(SdmSieve.T_FILE).names(nStart(".trashed-")));
            }
            if (id.equals("screenshots")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PORTABLE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PORTABLE).types(SdmSieve.T_FILE).pfp(anc("Pictures/Screenshots")).minAgeSec(screenshotMs() / 1000L));
            }
            if (id.equals("lostdir")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PORTABLE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PORTABLE).types(SdmSieve.T_FILE).regex("^(?:[\\W\\w]+/LOST\\.DIR/[\\W\\w]+)$"));
            }
            if (id.equals("linuxfiles")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PORTABLE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PORTABLE).types(SdmSieve.T_DIR).pfp(anc(".Trash"), startsP(".Trash-")).notPfp(anc("Android")));
            }
            if (id.equals("macfiles")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_MEDIA, Sdm.Area.PORTABLE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_MEDIA, Sdm.Area.PORTABLE).types(SdmSieve.T_FILE).names(nStart("._"), nEq(".DS_Store")),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PORTABLE).types(SdmSieve.T_DIR)
                        .pfp(eqs(".Trashes"), eqs(".spotlight"), eqs(".Spotlight-V100"), eqs(".fseventsd"), eqs(".TemporaryItems")));
            }
            if (id.equals("windowsfiles")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA, Sdm.Area.PUBLIC_MEDIA, Sdm.Area.PUBLIC_OBB, Sdm.Area.PORTABLE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA, Sdm.Area.PUBLIC_MEDIA, Sdm.Area.PUBLIC_OBB, Sdm.Area.PORTABLE).types(SdmSieve.T_FILE)
                        .names(nEq("desktop.ini"), nEq("thumbs.db")));
            }
            if (id.equals("tempfiles")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.DATA, Sdm.Area.DATA_SYSTEM, Sdm.Area.DATA_SYSTEM_DE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.DATA, Sdm.Area.DATA_SYSTEM, Sdm.Area.DATA_SYSTEM_DE).types(SdmSieve.T_FILE)
                        .names(nEnd(".tmp"), nEnd(".temp"), nEq(".mmsyscache"), nStart("sdm_write_test-"), nStart("eu.darken.sdmse-test-sd"), nStart("eu.darken.sdmse-test-usb"))
                        .notPfp(contain("backup/pending"), contain("cache/recovery")));
            }
            if (id.equals("thumbnails")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_MEDIA, Sdm.Area.PORTABLE),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_MEDIA, Sdm.Area.PORTABLE).pfp(contain(".thumbnails")));
            }
            if (id.equals("analytics")) {
                return new SieveF(m, now, set(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA, Sdm.Area.PRIVATE_DATA),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA).pfp(
                        eqs(".tlocalcookieid"), eqs(".INSTALLATION"), eqs(".wps_preloaded_2.txt"), eqs(".UTSystemConfig/Global/Alvin2.xml"), eqs(".DataStorage/ContextData.xml"),
                        eqs("com.snssdk.api.embed/cache/clientudid.dat"), eqs("Tencent/ams/cache/meta.dat"), eqs("com.tencent.ams/cache/meta.dat"),
                        eqs("backups/.SystemConfig/.cuid"), eqs("backups/.SystemConfig/.cuid2"), eqs("backups/.adiu"), eqs("Mob/comm/dbs/.duid"), eqs(".mn_1006862472"),
                        eqs(".imei.txt"), eqs(".DC4278477faeb9.txt"), eqs("Android/obj/.um/sysid.dat"), eqs(".um/sysid.dat"), anc(".pns/.uniqueId"), eqs(".oukdtft"),
                        eqs("libs/com.igexin.sdk.deviceId.db"), eqs("data/.push_deviceid"), eqs("msc/.2F6E2C5B63F0F83B"), eqs(".lm_device/.lm_device_id"), eqs("LMDevice/lm_device_id")),
                    new SdmSieve.Config().areas(Sdm.Area.SDCARD, Sdm.Area.PUBLIC_DATA, Sdm.Area.PRIVATE_DATA).types(SdmSieve.T_FILE).path(contain(".bugsense"))
                        .regex("^(?:[\\W\\w]+/\\.(?:bugsense))$"));
            }
            if (id.equals("anr")) {
                List<Pattern> rx = new ArrayList<Pattern>();
                for (Sdm.AreaInfo a : available) {
                    if (!a.primary || (a.area != Sdm.Area.DATA && a.area != Sdm.Area.DOWNLOAD_CACHE)) continue;
                    rx.add(Pattern.compile("^(?:" + Pattern.quote(normRoot(a.root)) + "/anr/[\\W\\w]+)$"));
                }
                if (rx.isEmpty()) return new SieveF(m, now, EnumSet.noneOf(Sdm.Area.class));        // upstream: "sieve is underdefined", never matches
                return new SieveF(m, now, set(Sdm.Area.DATA, Sdm.Area.DOWNLOAD_CACHE),
                    new SdmSieve.Config().areas(Sdm.Area.DATA, Sdm.Area.DOWNLOAD_CACHE).types(SdmSieve.T_FILE).pfp(anc("anr")).regex(rx.toArray(new Pattern[0])));
            }
            if (id.equals("localtmp")) {
                return new SieveF(m, now, set(Sdm.Area.DATA), new SdmSieve.Config().areas(Sdm.Area.DATA).pfp(anc("local/tmp")));
            }
            if (id.equals("downloadcache")) {
                return new SieveF(m, now, set(Sdm.Area.DOWNLOAD_CACHE),
                    new SdmSieve.Config().areas(Sdm.Area.DOWNLOAD_CACHE).types(SdmSieve.T_FILE).notPfp(anc("dalvik-cache"), anc("lost+found"), SdmSieve.Seg.start("recovery/last_log"),
                        contain("last_postrecovery"), contain("last_data_partition_info"), contain("last_dataresizing"), anc("magisk")));
            }
            if (id.equals("datalogger")) {
                return new SieveF(m, now, set(Sdm.Area.DATA),
                    new SdmSieve.Config().areas(Sdm.Area.DATA).types(SdmSieve.T_FILE).pfp(anc("logger"), anc("log"), anc("log_other_mode")));
            }
            if (id.equals("logdropbox")) {
                return new SieveF(m, now, set(Sdm.Area.DATA_SYSTEM), new SdmSieve.Config().areas(Sdm.Area.DATA_SYSTEM).types(SdmSieve.T_FILE).pfp(anc("dropbox")));
            }
            if (id.equals("recenttasks")) {
                return new SieveF(m, now, set(Sdm.Area.DATA_SYSTEM_CE),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_SYSTEM_CE).types(SdmSieve.T_FILE).pfp(anc("recent_images"), anc("recent_tasks")));
            }
            if (id.equals("tombstones")) {
                return new SieveF(m, now, set(Sdm.Area.DATA, Sdm.Area.DATA_VENDOR),
                    new SdmSieve.Config().areas(Sdm.Area.DATA).types(SdmSieve.T_FILE).pfp(anc("tombstones")),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_VENDOR).types(SdmSieve.T_FILE).pfp(anc("tombstones")));
            }
            if (id.equals("usagestats")) {
                return new SieveF(m, now, set(Sdm.Area.DATA_SYSTEM),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_SYSTEM).types(SdmSieve.T_FILE).pfp(anc("usagestats")).regex(".+/usagestats/[0-9]+/.+"));
            }
            if (id.equals("packagecache")) {
                return new SieveF(m, now, set(Sdm.Area.DATA_SYSTEM),
                    new SdmSieve.Config().areas(Sdm.Area.DATA_SYSTEM).types(SdmSieve.T_FILE | SdmSieve.T_DIR).pfp(anc("package_cache")));
            }
            throw new IllegalStateException("no filter for " + id);
        }

        // ---- the walk of one area

        void walkArea(final Sdm.AreaInfo ai) throws Exception {
            final String root = normRoot(ai.root);
            // a folder that is the root of another data area is that area's business (upstream: its "peek-content-area" would be identified as another area)
            final Set<String> foreign = new HashSet<String>();
            for (Sdm.AreaInfo b : all) {
                if (b == ai || b.area == ai.area && normRoot(b.root).equals(root)) continue;
                if (SdmSieve.isAncestorOf(root, normRoot(b.root))) foreign.add(normRoot(b.root));
            }
            if (ai.area == Sdm.Area.SDCARD) {
                foreign.add(Sdm.join(root, "Android/data"));
                foreign.add(Sdm.join(root, "Android/media"));
                foreign.add(Sdm.join(root, "Android/obb"));
            }
            final List<F> fs = new ArrayList<F>();
            F empty = null;
            for (F f : filters) if (f.areas.contains(ai.area)) { fs.add(f); if (f instanceof EmptyF) empty = f; }
            if (fs.isEmpty()) return;
            final boolean emptyOn = empty != null;
            final LinkedHashMap<String, Pending> pending = new LinkedHashMap<String, Pending>();
            final int rootDepth = Sdm.segments(root).length;
            final Sdm.Exclusions ex = ctx.exclusions;
            final Sdm.Cancel cancel = ctx.cancel;

            Sdm.EntrySink sink = new Sdm.EntrySink() {
                @Override public boolean accept(Sdm.Entry e) {
                    if (cancel.cancelled()) return false;
                    String path = e.path;
                    boolean dir = e.type == Sdm.DIR;
                    if (path.startsWith("/data/") && isGlobalSkip(path, skipDataData)) return false;
                    if (dir && foreign.contains(path)) return false;
                    if (ex != null && ex.excludesPath(Sdm.Tool.SYSTEMCLEANER, path)) { excludedSeen.add(path); return false; }
                    seen++;
                    prog(P_SEARCHING, path);
                    if (emptyOn && !dir && !pending.isEmpty()) {
                        Pending p = pending.remove(parentOf(path));                                   // a file below: not empty, the filters after "empty folders" still want it
                        if (p != null) continueFrom(p);
                    }
                    String[] segs = Sdm.segments(path);
                    String[] pfp = rootDepth >= segs.length ? new String[0] : Arrays.copyOfRange(segs, rootDepth, segs.length);
                    Ev v = new Ev(e, segs, pfp, ai);
                    for (int i = 0; i < fs.size(); i++) {
                        int r = fs.get(i).match(v);
                        if (r == YES) { add(fs.get(i), e, ai); break; }
                        if (r == DEFER) { pending.put(path, new Pending(e, ai, fs, i + 1)); break; }
                    }
                    return true;
                }
            };
            ctx.fs.walk(root, sink, cancel);
            if (ctx.cancelled()) throw new CancellationException("cancelled");

            if (!pending.isEmpty()) {
                EmptyDirs dirs = new EmptyDirs(ctx);
                for (Pending p : new ArrayList<Pending>(pending.values())) {
                    if (ctx.cancelled()) throw new CancellationException("cancelled");
                    seen++;
                    prog(P_SEARCHING, p.e.path);
                    boolean isEmpty;
                    try { isEmpty = dirs.emptyTree(p.e.path, p.ai); } catch (CancellationException c) { throw c; }
                    if (isEmpty) add(p.fs.get(p.next - 1), p.e, p.ai); else continueFrom(p);
                }
            }
        }

        /** The filters after the one that deferred (empty folders) get their turn for a folder that turned out not to be empty. */
        void continueFrom(Pending p) {
            String[] segs = Sdm.segments(p.e.path);
            Ev v = new Ev(p.e, segs, pfpOf(segs, p.ai), p.ai);
            for (int i = p.next; i < p.fs.size(); i++) {
                if (p.fs.get(i).match(v) == YES) { add(p.fs.get(i), p.e, p.ai); return; }
            }
        }

        void add(F f, Sdm.Entry e, Sdm.AreaInfo ai) {
            if (unsafeReason(e.path, available) != null) return;                                     // never list what must not be deleted (e.g. an empty <sdcard>/Android)
            ArrayList<Item> l = found.get(f.meta.id);
            if (l == null) { l = new ArrayList<Item>(); found.put(f.meta.id, l); }
            l.add(new Item(e, ai));
            bytes += e.size;
        }

        /** The nested rule of the exclusions on the result, then the groups. */
        Sdm.Result finish() {
            Set<String> anc = new HashSet<String>();
            for (String p : excludedSeen) addAncestors(anc, p);
            if (ctx.exclusions != null) {
                List<String> ps = ctx.exclusions.paths(Sdm.Tool.SYSTEMCLEANER);
                if (ps != null) for (String p : ps) addAncestors(anc, p);
            }
            for (java.util.Iterator<Map.Entry<String, ArrayList<Item>>> gi = found.entrySet().iterator(); gi.hasNext(); ) {
                ArrayList<Item> items = gi.next().getValue();
                for (java.util.Iterator<Item> ii = items.iterator(); ii.hasNext(); ) {
                    Item it = ii.next();
                    if (anc.contains(it.path) || (ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.SYSTEMCLEANER, it.path))) ii.remove();
                }
                if (items.isEmpty()) gi.remove();
            }
            return build(found, subs);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the deletion

    private static final int BATCH = 20;

    @Override
    public Sdm.DeleteReport delete(Sdm.Result result, Sdm.Selection selection, Sdm.Ctx ctx) throws Exception {
        if (!(result instanceof SysResult)) throw new IllegalArgumentException("not a SystemCleaner result: " + result);
        SysResult r = (SysResult) result;
        Sdm.DeleteReport rep = new Sdm.DeleteReport();
        List<Sdm.AreaInfo> areas = new ArrayList<Sdm.AreaInfo>();
        if (ctx.areas != null) for (Sdm.AreaInfo a : ctx.areas.all()) if (a != null && a.root != null && !a.root.isEmpty()) areas.add(a);

        // 1. what the selection resolves to: everything except dropped groups and dropped items
        List<Grp> gs;
        final LinkedHashMap<String, Item> selected = new LinkedHashMap<String, Item>();
        final HashMap<String, Grp> groupOf = new HashMap<String, Grp>();
        Set<String> dropped = new HashSet<String>();
        synchronized (r) {
            gs = new ArrayList<Grp>(r.groups);
            for (Grp g : gs) {
                boolean gdrop = selection.dropGroups.contains(g.meta.id);
                for (Item it : g.items) {
                    groupOf.put(it.path, g);
                    if (gdrop || selection.dropItems.contains(it.path)) dropped.add(it.path); else selected.put(it.path, it);
                }
            }
        }
        if (selected.isEmpty()) return rep;

        // 2. distinct roots; a root that would take a deselected item with it is not deleted, its selected children are (one level down, repeatedly)
        Set<String> droppedAnc = new HashSet<String>();
        for (String d : dropped) addAncestors(droppedAnc, d);
        Set<String> cand = new LinkedHashSet<String>(selected.keySet());
        List<String> roots;
        int keptForDropped = 0;
        while (true) {
            roots = SdmSieve.distinctRoots(cand);
            List<String> bad = new ArrayList<String>();
            for (String p : roots) if (droppedAnc.contains(p)) bad.add(p);
            if (bad.isEmpty()) break;
            cand.removeAll(bad);
            keptForDropped += bad.size();
        }
        if (keptForDropped > 0) rep.notes.add(keptForDropped + " folder(s) were kept because they contain items that are not selected");

        // 3. re-verify every root right before deleting
        Set<String> exAnc = new HashSet<String>();
        if (ctx.exclusions != null) {
            List<String> ps = ctx.exclusions.paths(Sdm.Tool.SYSTEMCLEANER);
            if (ps != null) for (String p : ps) addAncestors(exAnc, p);
        }
        EmptyDirs dirs = new EmptyDirs(ctx);
        final Set<String> goneRoots = new HashSet<String>();
        LinkedHashMap<Grp, List<String>> todo = new LinkedHashMap<Grp, List<String>>();
        for (Grp g : gs) todo.put(g, new ArrayList<String>());
        int skipped = 0;
        for (String root : roots) {
            if (ctx.cancelled()) break;
            Item it = selected.get(root);
            Grp g = groupOf.get(root);
            String why = unsafeReason(root, areas);
            if (why != null) { rep.failed.add(root); rep.notes.add("Not deleted (" + why + "): " + root); continue; }
            if (exAnc.contains(root) || (ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.SYSTEMCLEANER, root))) { skipped++; continue; }
            Sdm.Entry cur;
            try { cur = ctx.fs.stat(root); } catch (IOException e) { rep.failed.add(root); continue; }
            if (cur == null) { goneRoots.add(root); continue; }                                      // gone already counts as deleted
            if ((cur.type == Sdm.DIR) != (it.type == Sdm.DIR)) { rep.failed.add(root); rep.notes.add("Not deleted (changed since the scan): " + root); continue; }
            if (g.meta.id.equals("emptydirectories") && !dirs.emptyTree(root, it.ai)) { rep.failed.add(root); rep.notes.add("Not deleted (not empty any more): " + root); continue; }
            todo.get(g).add(root);
        }
        if (skipped > 0) rep.notes.add(skipped + " item(s) were kept because of exclusions");

        // 4. delete, group by group, in batches
        int total = 0;
        for (List<String> l : todo.values()) total += l.size();
        int done = 0;
        long bytesDone = 0;
        boolean cancelled = ctx.cancelled();
        for (Map.Entry<Grp, List<String>> en : todo.entrySet()) {
            if (cancelled) break;
            Grp g = en.getKey();
            List<String> list = en.getValue();
            for (int i = 0; i < list.size(); i += BATCH) {
                if (ctx.cancelled()) { cancelled = true; break; }
                List<String> batch = new ArrayList<String>(list.subList(i, Math.min(i + BATCH, list.size())));
                ctx.progress.update(g.meta.label, batch.get(0), done, total, bytesDone);
                Set<String> ok = ctx.fs.deleteAll(batch, ctx.cancel);
                for (String p : batch) {
                    if ((ok != null && ok.contains(p)) || !ctx.fs.exists(p)) { goneRoots.add(p); bytesDone += selected.get(p).size; }
                    else if (!ctx.cancelled()) rep.failed.add(p);
                }
                done += batch.size();
            }
        }
        if (cancelled || ctx.cancelled()) rep.notes.add("cancelled");
        ctx.progress.update(P_LOADING, "", done, total, bytesDone);

        // 5. reconcile the result: the roots that are gone and everything below them, in every group
        if (!goneRoots.isEmpty()) {
            synchronized (r) {
                for (java.util.Iterator<Grp> gi = r.groups.iterator(); gi.hasNext(); ) {
                    Grp g = gi.next();
                    for (java.util.Iterator<Item> ii = g.items.iterator(); ii.hasNext(); ) {
                        Item it = ii.next();
                        if (hasAncestorIn(goneRoots, it.path)) {
                            rep.deleted.add(new Sdm.Deleted(it.path, it.size, g.meta.id, g.meta.label));
                            ii.remove();
                        }
                    }
                    if (g.items.isEmpty()) gi.remove(); else g.recount();
                }
            }
        }
        return rep;
    }

    @Override public String toString() { return "SdmSystemCleaner"; }
}
