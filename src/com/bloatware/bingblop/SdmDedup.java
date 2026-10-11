package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-tool-deduplicator: core/Deduplicator.kt, Duplicate.kt, DeduplicatorSettings.kt, scanner/{DuplicatesScanner,CommonFilesCheck}.kt,
 * scanner/checksum/{ChecksumSleuth,ChecksumDuplicate}.kt, arbiter/{DuplicatesArbiter,ArbiterCriterium}.kt and arbiter/checks/*, deleter/DuplicatesDeleter.kt).
 * Changed for this port: plain Java on {@link Sdm.Fs} (no coroutines, no android.*); the content checksum is the only detection method (the perceptual hash and
 * the media fingerprint sleuths are not ported, every cluster row still carries its {@code method}); before the SHA-256 of a file its first 64 KiB are compared,
 * which rules out most files of the same size without reading them whole (not upstream); the MIME type of a file comes from its extension (a table of the
 * types upstream calls common); the media-provider criterion of the arbiter cannot be asked without android.* and keeps the order unchanged.
 * Quirk fixes: an unreadable file leaves only itself out of its size bucket (upstream drops the whole bucket); before a delete every file is looked at again,
 * and a file that changed since the scan (size or date) is left alone, as is a whole set that has no intact copy left to keep; a path can only be a candidate
 * once, so two walks that meet cannot make a file its own duplicate.
 *
 * What it does: walks public storage (or the chosen search locations), keeps the files of at least the minimum size (and of a common type), groups them by size,
 * reads the first 64 KiB of the files of every size that occurs twice or more, hashes (SHA-256) those whose start is equal, and every hash that occurs twice or more
 * is a set of exact copies ("cluster"). The arbiter orders the copies of a set by the deletion strategy (spec 5.2); the first is the one that is kept.
 * Result groups are the clusters (id = the hash), items are their files (the keeper has {@code keep: true}).
 *
 * Delete selection (the page sends {@code options.deleteAll}): a set in dropGroups is left alone; a file in dropItems is never deleted; everything else of a set goes
 * EXCEPT that at least one copy must stay: when none of the set's files is protected by dropItems, the arbiter's keeper stays. Only when {@code options.deleteAll}
 * is true AND the setting "protection.deleteall.allowed" is on may a set lose every copy (then only dropItems protect).
 */
public final class SdmDedup implements Sdm.ToolImpl {

    // ---------------------------------------------------------------------------------------------------------- settings (spec 5.4)

    public static final String K_PATHS = "scan.location.paths", K_ARBITER = "arbiter.config", K_DELETEALL = "protection.deleteall.allowed",
            K_MINSIZE = "skip.minsize.bytes", K_UNCOMMON = "skip.files.uncommon", K_CHECKSUM = "sleuth.checksum.enabled";
    public static final long DEFAULT_MIN_SIZE = 512 * 1024L;
    public static final int PREFIX_BYTES = 64 * 1024;
    public static final String METHOD_CHECKSUM = "Content checksum";
    public static final String KEEP_ONE_REQUIRED = "At least one file per duplicate set must remain. Enable \"Make 'Delete all' possible\" in settings to override.";

    @Override public Sdm.Tool tool() { return Sdm.Tool.DEDUPLICATOR; }

    // ---------------------------------------------------------------------------------------------------------- texts (spec 7.3, 9.4)

    public static String foundLine(int n) { return n + (n == 1 ? " duplicate set found" : " duplicate sets found"); }
    public static String deletedLine(int n) { return n + (n == 1 ? " duplicate deleted" : " duplicates deleted"); }
    public static String freedLine(long bytes) { return "Freed " + SdmCorpseFinder.fmtSize(bytes) + " space."; }

    /** "X is occupied by duplicates" (one) / "X are occupied by duplicates": the plural follows the rounded number in the formatted size, as Android's plural rule does. */
    public static String occupiedLine(long bytes) {
        String f = SdmCorpseFinder.fmtSize(bytes);
        int sp = f.indexOf(' ');
        long q;
        try { q = Math.round(Double.parseDouble(f.substring(0, sp))); } catch (NumberFormatException e) { q = bytes; }
        return f + (q == 1 ? " is" : " are") + " occupied by duplicates";
    }

    // ---------------------------------------------------------------------------------------------------------- the arbiter (spec 5.2)

    /** One criterium of the deletion strategy: type (DUPLICATE_TYPE, PREFERRED_PATH, MEDIA_PROVIDER, LOCATION, NESTING, MODIFIED, SIZE), its mode, or the preferred paths. */
    public static final class Criterium {
        public final String type, mode;
        public final List<String> paths;
        Criterium(String type, String mode, List<String> paths) { this.type = type; this.mode = mode; this.paths = paths; }
    }

    public static List<Criterium> defaultCriteria() {
        List<Criterium> l = new ArrayList<Criterium>();
        l.add(new Criterium("DUPLICATE_TYPE", "PREFER_CHECKSUM", Collections.<String>emptyList()));
        l.add(new Criterium("PREFERRED_PATH", "", Collections.<String>emptyList()));
        l.add(new Criterium("MEDIA_PROVIDER", "PREFER_INDEXED", Collections.<String>emptyList()));
        l.add(new Criterium("LOCATION", "PREFER_PRIMARY", Collections.<String>emptyList()));
        l.add(new Criterium("NESTING", "PREFER_SHALLOW", Collections.<String>emptyList()));
        l.add(new Criterium("MODIFIED", "PREFER_OLDER", Collections.<String>emptyList()));
        l.add(new Criterium("SIZE", "PREFER_LARGER", Collections.<String>emptyList()));
        return l;
    }

    /** The criteria from the setting value (a JSON object or its text, {"criteria":[{"criteriumType":..., "mode":...}]}); the default when it is missing or broken. */
    public static List<Criterium> parseCriteria(Object raw) {
        try {
            JSONObject o = null;
            if (raw instanceof JSONObject) o = (JSONObject) raw;
            else if (raw instanceof String && !((String) raw).trim().isEmpty()) o = new JSONObject((String) raw);
            if (o == null) return defaultCriteria();
            JSONArray arr = o.optJSONArray("criteria");
            if (arr == null) return defaultCriteria();
            List<Criterium> l = new ArrayList<Criterium>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.getJSONObject(i);
                String type = c.optString("criteriumType", "");
                List<String> paths = new ArrayList<String>();
                JSONArray p = c.optJSONArray("keepPreferPaths");
                if (p != null) for (int k = 0; k < p.length(); k++) {
                    Object x = p.opt(k);
                    if (x instanceof JSONObject) { String s = ((JSONObject) x).optString("path", ""); if (!s.isEmpty()) paths.add(s); }
                    else if (x instanceof String && !((String) x).isEmpty()) paths.add((String) x);
                }
                if (!Arrays.asList("DUPLICATE_TYPE", "PREFERRED_PATH", "MEDIA_PROVIDER", "LOCATION", "NESTING", "MODIFIED", "SIZE").contains(type)) continue;
                l.add(new Criterium(type, c.optString("mode", ""), paths));
            }
            return l.isEmpty() ? defaultCriteria() : l;
        } catch (JSONException e) {
            return defaultCriteria();
        }
    }

    /**
     * Orders the copies so that the one to keep is first: from the LAST criterium to the first, a stable sort moves the preferred files to the front, so the top
     * criterium has the final say and the ones below break its ties. The incoming order is by path, so the result does not depend on how the files were found.
     */
    static List<Dup> decide(Collection<Dup> litigants, List<Criterium> crits) {
        List<Dup> work = new ArrayList<Dup>(litigants);
        Collections.sort(work, new Comparator<Dup>() { @Override public int compare(Dup a, Dup b) { return a.path.compareTo(b.path); } });
        for (int i = crits.size() - 1; i >= 0; i--) {
            final Criterium c = crits.get(i);
            Comparator<Dup> cmp = null;
            if (c.type.equals("PREFERRED_PATH")) {
                if (c.paths.isEmpty()) continue;
                final List<String> keep = c.paths;
                cmp = new Comparator<Dup>() {
                    @Override public int compare(Dup a, Dup b) { return rank(a) - rank(b); }
                    int rank(Dup d) { for (String p : keep) if (d.path.equals(p) || SdmSieve.isAncestorOf(p, d.path)) return 0; return 1; }
                };
            } else if (c.type.equals("LOCATION")) {
                final boolean primary = !c.mode.equals("PREFER_SECONDARY");
                cmp = new Comparator<Dup>() {
                    @Override public int compare(Dup a, Dup b) { return flag(b) - flag(a); }
                    int flag(Dup d) { return d.primary == null ? 0 : (d.primary.booleanValue() == primary ? 1 : 0); }
                };
            } else if (c.type.equals("NESTING")) {
                final boolean shallow = !c.mode.equals("PREFER_DEEPER");
                cmp = new Comparator<Dup>() {
                    @Override public int compare(Dup a, Dup b) { return shallow ? a.depth - b.depth : b.depth - a.depth; }
                };
            } else if (c.type.equals("MODIFIED")) {
                final boolean older = !c.mode.equals("PREFER_NEWER");
                cmp = new Comparator<Dup>() {
                    @Override public int compare(Dup a, Dup b) { int r = a.mtime < b.mtime ? -1 : a.mtime > b.mtime ? 1 : 0; return older ? r : -r; }
                };
            } else if (c.type.equals("SIZE")) {
                final boolean larger = !c.mode.equals("PREFER_SMALLER");
                cmp = new Comparator<Dup>() {
                    @Override public int compare(Dup a, Dup b) { int r = a.size < b.size ? -1 : a.size > b.size ? 1 : 0; return larger ? -r : r; }
                };
            }
            // DUPLICATE_TYPE: every copy is a checksum duplicate, nothing to prefer. MEDIA_PROVIDER: not known here, the order stays.
            if (cmp != null) Collections.sort(work, cmp);     // Collections.sort is stable
        }
        return work;
    }

    // ---------------------------------------------------------------------------------------------------------- common file types (CommonFilesCheck)

    private static final Set<String> COMMON_EXT = new HashSet<String>(Arrays.asList(
            // images
            "bmp", "jpg", "jpeg", "jpe", "png", "gif", "webp", "heic", "heif", "svg",
            // videos
            "mp4", "m4v", "webm", "ogv", "avi", "mpeg", "mpg", "mpe", "3gp", "3gpp", "mov", "qt",
            // audio
            "mp3", "mpga", "m4a", "ogg", "oga", "opus", "wav", "weba", "aac", "aif", "aiff", "aifc", "flac",
            // archives
            "zip", "rar", "tar", "gz", "7z",
            // documents
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "text", "html", "htm", "csv", "rtf",
            // packages (application/octet-stream with these suffixes)
            "apk", "apks"));

    static boolean isCommon(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return false;
        return COMMON_EXT.contains(name.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------------------------------------------------- model

    /** One file of a set. */
    public static final class Dup {
        public final String path, name;
        public final long size, mtime;
        final int depth;
        final Boolean primary;
        Dup(Sdm.Entry e, Boolean primary) {
            this.path = e.path; this.name = e.name; this.size = e.size; this.mtime = e.mtime; this.depth = e.path.split("/", -1).length; this.primary = primary;
        }
    }

    /** A set of exact copies. {@code files} is ordered by the strategy: files.get(0) is the one kept. */
    public static final class Cluster {
        public final String id, hash, method;
        public final List<Dup> files;
        Cluster(String hash, List<Dup> files) { this.id = hash; this.hash = hash; this.method = METHOD_CHECKSUM; this.files = files; }
        public Dup keeper() { return files.get(0); }
        public long totalSize() { long s = 0; for (Dup d : files) s += d.size; return s; }
        /** What a keep-one delete frees: everything but the keeper's size. */
        public long redundantSize() { return files.size() < 2 ? 0 : totalSize() - keeper().size; }
        public int redundantCount() { return Math.max(0, files.size() - 1); }

        JSONObject row() throws JSONException {
            Dup k = keeper();
            int i = k.path.lastIndexOf('/');
            return new JSONObject().put("id", id).put("label", k.name).put("sub", i <= 0 ? "/" : k.path.substring(0, i)).put("count", files.size()).put("bytes", redundantSize())
                    .put("freeable", redundantSize()).put("total", totalSize()).put("avgSize", files.isEmpty() ? 0 : totalSize() / files.size())
                    .put("method", method).put("methods", new JSONArray().put(method)).put("hash", hash).put("preview", k.path).put("keeper", k.path).put("mtime", k.mtime);
        }
    }

    /** What a scan found; a delete and the engine's exclusions change it in place. */
    public static final class DedupResult implements Sdm.Result {
        private List<Cluster> clusters;
        private final List<Criterium> criteria;

        DedupResult(List<Cluster> clusters, List<Criterium> criteria) { this.clusters = new ArrayList<Cluster>(clusters); this.criteria = criteria; }

        @Override public Sdm.Tool tool() { return Sdm.Tool.DEDUPLICATOR; }
        @Override public synchronized int groupCount() { return clusters.size(); }
        /** The dashboard counts sets (upstream: itemCount = clusters.size). */
        @Override public synchronized int itemCount() { return clusters.size(); }
        /** The space a keep-one delete frees. */
        @Override public synchronized long bytes() { long b = 0; for (Cluster c : clusters) b += c.redundantSize(); return b; }
        /** The files a keep-one delete removes (what the dashboard's main action counts). */
        public synchronized int redundantCount() { int n = 0; for (Cluster c : clusters) n += c.redundantCount(); return n; }
        public synchronized List<Cluster> clusters() { return new ArrayList<Cluster>(clusters); }
        public List<Criterium> criteria() { return criteria; }
        public synchronized DedupResult snapshot() { return new DedupResult(clusters, criteria); }
        public synchronized void restore(DedupResult other) { this.clusters = new ArrayList<Cluster>(other.clusters()); }

        synchronized Cluster find(String id) { for (Cluster c : clusters) if (c.id.equals(id)) return c; return null; }

        @Override public synchronized JSONArray groups(int offset, int limit) throws JSONException {
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, offset); i < clusters.size() && out.length() < limit; i++) out.put(clusters.get(i).row());
            return out;
        }

        @Override public synchronized JSONArray items(String groupId, int offset, int limit) throws JSONException {
            JSONArray out = new JSONArray();
            Cluster c = find(groupId);
            if (c == null) return out;
            for (int i = Math.max(0, offset); i < c.files.size() && out.length() < limit; i++) {
                Dup d = c.files.get(i);
                out.put(new JSONObject().put("id", d.path).put("path", d.path).put("name", d.name).put("size", d.size).put("mtime", d.mtime).put("type", Sdm.FILE)
                        .put("keep", i == 0).put("marked", i != 0).put("method", c.method).put("hash", c.hash));
            }
            return out;
        }

        @Override public synchronized JSONObject summary() throws JSONException {
            int n = clusters.size();
            long b = bytes();
            return new JSONObject().put("itemCount", n).put("bytes", b).put("groupCount", n).put("redundantCount", redundantCount())
                    .put("primary", foundLine(n)).put("secondary", occupiedLine(b));
        }

        /** Files that were deleted: out of their sets; a set that is left with fewer than two files vanishes; a deleted keeper is decided again. */
        synchronized void removeFiles(Set<String> paths) {
            List<Cluster> keep = new ArrayList<Cluster>();
            for (Cluster c : clusters) {
                List<Dup> rest = new ArrayList<Dup>();
                boolean changed = false;
                for (Dup d : c.files) { if (paths.contains(d.path)) changed = true; else rest.add(d); }
                if (!changed) { keep.add(c); continue; }
                if (rest.size() < 2) continue;
                boolean keeperStays = rest.get(0) == c.files.get(0);
                keep.add(new Cluster(c.hash, keeperStays ? rest : decide(rest, criteria)));
            }
            clusters = keep;
        }

        /** Exclusions made after the scan: the files leave their sets. */
        public synchronized int dropPaths(Collection<String> paths) {
            int before = 0, after = 0;
            for (Cluster c : clusters) before += c.files.size();
            Set<String> set = new HashSet<String>();
            for (Dup d : allFiles()) for (String p : paths) if (d.path.equals(p) || SdmSieve.isAncestorOf(p, d.path)) { set.add(d.path); break; }
            removeFiles(set);
            for (Cluster c : clusters) after += c.files.size();
            return before - after;
        }

        private List<Dup> allFiles() { List<Dup> l = new ArrayList<Dup>(); for (Cluster c : clusters) l.addAll(c.files); return l; }
    }

    // ---------------------------------------------------------------------------------------------------------- scan (spec 5.1)

    @Override
    public Sdm.Result scan(Sdm.Ctx ctx) throws Exception {
        long minSize = Math.max(0, ctx.num(K_MINSIZE, DEFAULT_MIN_SIZE));
        boolean skipUncommon = ctx.bool(K_UNCOMMON, true);
        boolean checksum = ctx.bool(K_CHECKSUM, true);
        List<Criterium> criteria = parseCriteria(ctx.settings.opt(K_ARBITER));
        List<String> custom = parsePaths(ctx.settings.opt(K_PATHS));

        Run run = new Run(ctx);
        Map<String, Sdm.Entry> files = run.search(custom, minSize, skipUncommon);
        run.check();
        if (!checksum) return new DedupResult(new ArrayList<Cluster>(), criteria);

        List<List<Group>> perBucket = run.checksums(files);
        List<Group> groups = new ArrayList<Group>();
        for (List<Group> l : perBucket) groups.addAll(l);

        run.progress("Filtering", "", 0, -1);
        run.check();
        run.progress("Preparing", "", 0, groups.size());
        List<Cluster> clusters = new ArrayList<Cluster>();
        for (int i = 0; i < groups.size(); i++) {
            run.check();
            Group g = groups.get(i);
            List<Dup> dups = new ArrayList<Dup>();
            for (Sdm.Entry e : g.files) dups.add(new Dup(e, run.primaryOf(e.path)));
            List<Dup> ordered = decide(dups, criteria);
            clusters.add(new Cluster(g.hash, ordered));
            run.progress("Preparing", ordered.get(0).path, i + 1, groups.size());
        }
        Collections.sort(clusters, new Comparator<Cluster>() {
            @Override public int compare(Cluster a, Cluster b) {
                long x = a.redundantSize(), y = b.redundantSize();
                if (x != y) return x > y ? -1 : 1;
                return a.hash.compareTo(b.hash);
            }
        });
        return new DedupResult(clusters, criteria);
    }

    /** The search locations from the setting: a JSON array of paths, {"paths": [...]}, or lines of text. */
    static List<String> parsePaths(Object raw) {
        List<String> out = new ArrayList<String>();
        try {
            if (raw instanceof String) {
                String s = ((String) raw).trim();
                if (s.startsWith("[") || s.startsWith("{")) raw = s.startsWith("[") ? new JSONArray(s) : new JSONObject(s);
                else { for (String line : s.split("\n")) if (!line.trim().isEmpty()) out.add(line.trim()); return out; }
            }
            if (raw instanceof JSONObject) raw = ((JSONObject) raw).opt("paths");
            if (raw instanceof JSONArray) {
                JSONArray a = (JSONArray) raw;
                for (int i = 0; i < a.length(); i++) {
                    Object x = a.opt(i);
                    if (x instanceof JSONObject) x = ((JSONObject) x).opt("path");
                    if (x instanceof String && !((String) x).isEmpty()) out.add((String) x);
                }
            }
        } catch (JSONException e) {
            return new ArrayList<String>();
        }
        return out;
    }

    /** The files of one hash. */
    private static final class Group {
        final String hash;
        final List<Sdm.Entry> files;
        Group(String hash, List<Sdm.Entry> files) { this.hash = hash; this.files = files; }
    }

    /** One scan: the walk, the checksum pipeline and the progress. */
    private static final class Run {
        final Sdm.Ctx ctx;
        final List<Sdm.AreaInfo> areas = new ArrayList<Sdm.AreaInfo>();
        final Object progressLock = new Object();
        long candidateBytes;

        Run(Sdm.Ctx ctx) {
            this.ctx = ctx;
            List<Sdm.AreaInfo> all = ctx.areas == null ? Collections.<Sdm.AreaInfo>emptyList() : ctx.areas.all();
            for (Sdm.AreaInfo a : all) if (a.available()) areas.add(a);
        }

        void check() { if (ctx.cancelled()) throw new CancellationException("cancelled"); }

        void progress(String primary, String secondary, long done, long total) {
            synchronized (progressLock) { ctx.progress.update(primary, secondary, done, total, candidateBytes); }
        }

        /** The data area a path is in (deepest root that is an ancestor; never SDCARD below Android/data|media|obb), or null. */
        Sdm.AreaInfo identify(String path) {
            Sdm.AreaInfo best = null;
            for (Sdm.AreaInfo a : areas) {
                if (!SdmSieve.isAncestorOf(a.root, path)) continue;
                if (a.area == Sdm.Area.SDCARD) {
                    if (SdmSieve.isAncestorOf(SdmCorpseFinder.join(a.root, "Android/obb"), path) || SdmSieve.isAncestorOf(SdmCorpseFinder.join(a.root, "Android/data"), path)
                            || SdmSieve.isAncestorOf(SdmCorpseFinder.join(a.root, "Android/media"), path)) continue;
                }
                if (best == null || a.root.length() > best.root.length()) best = a;
            }
            return best;
        }

        Boolean primaryOf(String path) {
            Sdm.AreaInfo a = identify(path);
            return a == null ? null : Boolean.valueOf(a.primary);
        }

        /** The candidates: files of at least minSize (and of a common type), found below the search locations, by path. */
        Map<String, Sdm.Entry> search(List<String> custom, final long minSize, final boolean skipUncommon) throws IOException {
            final Map<String, Sdm.Entry> found = new LinkedHashMap<String, Sdm.Entry>();
            final String[] where = { "" };
            final long[] seen = { 0 };
            progress("Searching", "", 0, -1);

            List<String[]> walks = new ArrayList<String[]>();            // {root, kind}: kind = area type name, or "custom"
            if (custom.isEmpty()) {
                for (Sdm.AreaInfo a : areas) {
                    if (a.area == Sdm.Area.SDCARD || a.area == Sdm.Area.PUBLIC_DATA || a.area == Sdm.Area.PUBLIC_MEDIA || a.area == Sdm.Area.PORTABLE) walks.add(new String[] { a.root, a.area.name() });
                }
            } else {
                for (String p : custom) {
                    Sdm.AreaInfo a = identify(SdmCorpseFinder.join(p, "sdm-testfile-" + System.nanoTime()));
                    if (a == null || !(a.area == Sdm.Area.SDCARD || a.area == Sdm.Area.PUBLIC_DATA || a.area == Sdm.Area.PUBLIC_MEDIA || a.area == Sdm.Area.PORTABLE)) {
                        throw new IllegalArgumentException("Unsupported area for " + p);
                    }
                    walks.add(new String[] { p, "custom" });
                }
            }

            boolean dataDataIsRoot = false;
            for (String[] w : walks) if (w[0].equals("/data/data")) dataDataIsRoot = true;
            final List<String[]> globalSkips = new ArrayList<String[]>();
            if (!dataDataIsRoot) { globalSkips.add(Sdm.segments("/data/data")); globalSkips.add(Sdm.segments("/data/user/0")); }
            globalSkips.add(Sdm.segments("/data/media/0"));
            final String[][] sdcardSkips = { { "Android", "data" }, { "Android", "media" }, { "Android", "obb" } };

            for (final String[] w : walks) {
                check();
                final boolean sdcard = w[1].equals("SDCARD");
                final boolean custom1 = w[1].equals("custom");
                ctx.fs.walk(w[0], new Sdm.EntrySink() {
                    @Override public boolean accept(Sdm.Entry e) {
                        if (ctx.cancelled()) return false;
                        String[] segs = null;
                        if (sdcard) {
                            segs = Sdm.segments(e.path);
                            for (String[] s : sdcardSkips) if (SdmSieve.endsWith(segs, s, true)) return false;
                        } else if (!custom1) {
                            segs = Sdm.segments(e.path);
                            for (String[] s : globalSkips) if (SdmSieve.startsWith(segs, s, false)) return false;
                        }
                        if (ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.DEDUPLICATOR, e.path)) return false;
                        if (e.type == Sdm.DIR) {
                            where[0] = e.path;
                            if ((++seen[0] & 63) == 0) progress("Searching", e.path, seen[0], -1);
                            return true;
                        }
                        if (e.type == Sdm.FILE && e.size >= minSize && (!skipUncommon || isCommon(e.name)) && !found.containsKey(e.path)) {
                            found.put(e.path, e);
                            candidateBytes += e.size;
                        }
                        return true;
                    }
                }, ctx.cancel);
            }
            progress("Searching", where[0], seen[0], -1);
            return found;
        }

        /** The ChecksumSleuth: size buckets of two or more, a 64 KiB prefix check, SHA-256 of what is left, hashes seen twice or more. */
        List<List<Group>> checksums(Map<String, Sdm.Entry> candidates) throws Exception {
            final String label = METHOD_CHECKSUM;
            progress(label, "Searching", 0, -1);
            Map<Long, List<Sdm.Entry>> bySize = new TreeMap<Long, List<Sdm.Entry>>();
            for (Sdm.Entry e : candidates.values()) {
                List<Sdm.Entry> l = bySize.get(e.size);
                if (l == null) { l = new ArrayList<Sdm.Entry>(); bySize.put(e.size, l); }
                l.add(e);
            }
            final List<List<Sdm.Entry>> buckets = new ArrayList<List<Sdm.Entry>>();
            long total = 0;
            for (List<Sdm.Entry> l : bySize.values()) if (l.size() >= 2) { buckets.add(l); total += l.size(); }
            final long all = total;
            final AtomicLong done = new AtomicLong();
            progress(label, "Comparing files", 0, all);

            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<List<Group>>> futures = new ArrayList<Future<List<Group>>>();
            try {
                for (final List<Sdm.Entry> bucket : buckets) {
                    futures.add(pool.submit(new Callable<List<Group>>() {
                        @Override public List<Group> call() throws Exception {
                            return resolveBucket(bucket, label, done, all);
                        }
                    }));
                }
                List<List<Group>> out = new ArrayList<List<Group>>();
                for (Future<List<Group>> f : futures) {
                    try {
                        out.add(f.get());
                    } catch (ExecutionException e) {
                        Throwable c = e.getCause();
                        if (c instanceof CancellationException) throw (CancellationException) c;
                        if (c instanceof Exception) throw (Exception) c;
                        throw e;
                    }
                    check();
                }
                return out;
            } finally {
                pool.shutdownNow();
            }
        }

        /** The files of one size: those whose first 64 KiB are equal are hashed, the hashes seen twice or more are returned. Unreadable files are left out. */
        List<Group> resolveBucket(List<Sdm.Entry> bucket, String label, AtomicLong done, long all) {
            Map<String, List<Sdm.Entry>> byHead = new LinkedHashMap<String, List<Sdm.Entry>>();
            for (Sdm.Entry e : bucket) {
                check();
                String head = null;
                try {
                    byte[] b = ctx.fs.head(e.path, PREFIX_BYTES);
                    if (b != null) head = hex(MessageDigest.getInstance("SHA-256").digest(b));
                } catch (Exception x) {
                    head = null;
                }
                if (head == null) { progress(label, "Comparing files", done.incrementAndGet(), all); continue; }       // unreadable: out
                List<Sdm.Entry> l = byHead.get(head);
                if (l == null) { l = new ArrayList<Sdm.Entry>(); byHead.put(head, l); }
                l.add(e);
            }
            Map<String, List<Sdm.Entry>> byHash = new LinkedHashMap<String, List<Sdm.Entry>>();
            for (List<Sdm.Entry> same : byHead.values()) {
                if (same.size() < 2) { progress(label, "Comparing files", done.addAndGet(same.size()), all); continue; }   // nothing else starts like it
                for (Sdm.Entry e : same) {
                    check();
                    String h = null;
                    try { h = ctx.fs.sha256(e.path, ctx.cancel); } catch (IOException x) { h = null; }
                    check();
                    progress(label, "Comparing files", done.incrementAndGet(), all);
                    if (h == null) continue;
                    List<Sdm.Entry> l = byHash.get(h);
                    if (l == null) { l = new ArrayList<Sdm.Entry>(); byHash.put(h, l); }
                    l.add(e);
                }
            }
            List<Group> out = new ArrayList<Group>();
            for (Map.Entry<String, List<Sdm.Entry>> e : byHash.entrySet()) if (e.getValue().size() >= 2) out.add(new Group(e.getKey(), e.getValue()));
            return out;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    // ---------------------------------------------------------------------------------------------------------- delete (spec 5.3)

    private static final int BATCH = 25;

    @Override
    public Sdm.DeleteReport delete(Sdm.Result result, Sdm.Selection selection, Sdm.Ctx ctx) throws Exception {
        DedupResult r = (DedupResult) result;
        Sdm.DeleteReport report = new Sdm.DeleteReport();
        boolean requested = selection.options != null && selection.options.optBoolean("deleteAll", false);
        boolean allowed = ctx.bool(K_DELETEALL, false);
        boolean deleteAll = requested && allowed;
        if (requested && !allowed) report.notes.add(KEEP_ONE_REQUIRED);

        // plan: which files of which set go
        List<Planned> plan = new ArrayList<Planned>();
        for (Cluster c : r.clusters()) {
            if (selection.dropGroups.contains(c.id)) continue;
            Set<String> prot = new HashSet<String>();
            for (Dup d : c.files) if (selection.dropItems.contains(d.path)) prot.add(d.path);
            if (!deleteAll && prot.isEmpty()) prot.add(c.keeper().path);       // at least one copy stays
            List<Dup> go = new ArrayList<Dup>(), stay = new ArrayList<Dup>();
            for (Dup d : c.files) (prot.contains(d.path) ? stay : go).add(d);
            if (go.isEmpty()) continue;

            // look at the files again: the scan may be hours old
            if (!deleteAll) {
                boolean intact = false;
                for (Dup s : stay) if (unchanged(ctx, s)) { intact = true; break; }
                if (!intact) {
                    // the copy meant to stay is gone or changed: the first other copy that is still intact stays instead
                    for (int k = 0; k < go.size() && !intact; k++) {
                        if (unchanged(ctx, go.get(k))) { stay.add(go.remove(k)); intact = true; }
                    }
                    if (intact && go.isEmpty()) continue;
                }
                if (!intact) {
                    report.notes.add("Skipped a set of " + c.keeper().name + ": no intact copy is left to keep.");
                    for (Dup d : go) report.failed.add(d.path);
                    continue;
                }
            }
            for (Dup d : go) {
                Sdm.Entry now = null;
                try { now = ctx.fs.stat(d.path); } catch (IOException e) { now = null; }
                if (now == null) { plan.add(new Planned(d, c, true)); continue; }               // already gone: counts as deleted
                if (now.type != Sdm.FILE || now.size != d.size || now.mtime != d.mtime) {
                    report.failed.add(d.path);
                    report.notes.add("Skipped " + d.path + ": it changed since the scan.");
                    continue;
                }
                String why = SdmSafety.check(d.path, ctx.areas);
                if (why != null) { report.failed.add(d.path); report.notes.add("Skipped " + d.path + ": " + why); continue; }
                if (ctx.exclusions != null && ctx.exclusions.excludesPath(Sdm.Tool.DEDUPLICATOR, d.path)) { report.failed.add(d.path); report.notes.add("Skipped " + d.path + ": it is excluded."); continue; }
                plan.add(new Planned(d, c, false));
            }
        }

        Set<String> removed = new HashSet<String>();
        int total = plan.size(), done = 0;
        for (int i = 0; i < plan.size(); i += BATCH) {
            if (ctx.cancelled()) break;
            List<Planned> batch = plan.subList(i, Math.min(plan.size(), i + BATCH));
            ctx.progress.update("Deleting", batch.get(0).dup.path, done, total, report.bytes());
            List<String> todo = new ArrayList<String>();
            for (Planned p : batch) if (!p.alreadyGone) todo.add(p.dup.path);
            Set<String> gone = todo.isEmpty() ? new HashSet<String>() : ctx.fs.deleteAll(todo, ctx.cancel);
            for (Planned p : batch) {
                boolean isGone = p.alreadyGone || (gone != null && gone.contains(p.dup.path));
                if (!isGone && !ctx.cancelled()) { try { isGone = ctx.fs.stat(p.dup.path) == null; } catch (IOException e) { isGone = false; } }
                if (isGone) {
                    report.deleted.add(new Sdm.Deleted(p.dup.path, p.dup.size, p.cluster.id, p.dup.name));
                    removed.add(p.dup.path);
                } else if (!ctx.cancelled()) {
                    report.failed.add(p.dup.path);
                }
            }
            done += batch.size();
        }
        ctx.progress.update("Deleting", "", total, total, report.bytes());
        r.removeFiles(removed);
        return report;
    }

    private static final class Planned {
        final Dup dup; final Cluster cluster; final boolean alreadyGone;
        Planned(Dup dup, Cluster cluster, boolean alreadyGone) { this.dup = dup; this.cluster = cluster; this.alreadyGone = alreadyGone; }
    }

    private static boolean unchanged(Sdm.Ctx ctx, Dup d) {
        try {
            Sdm.Entry e = ctx.fs.stat(d.path);
            return e != null && e.type == Sdm.FILE && e.size == d.size && e.mtime == d.mtime;
        } catch (IOException x) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- helpers for the engine

    /** The scan line of a result (spec 7.3): {primary, secondary}. */
    public static JSONObject scanLines(int clusters, long redundantBytes) {
        try { return new JSONObject().put("primary", foundLine(clusters)).put("secondary", occupiedLine(redundantBytes)); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    /** The receipt of a delete (spec 7.3): count = deleted files. */
    public static JSONObject deleteLines(int files, long bytes) {
        try { return new JSONObject().put("primary", deletedLine(files)).put("secondary", freedLine(bytes)); } catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public static JSONObject deleteLines(Sdm.DeleteReport report) { return deleteLines(report.deleted.size(), report.bytes()); }
}
