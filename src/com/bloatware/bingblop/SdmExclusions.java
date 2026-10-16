package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The exclusion manager (spec section 6): app, path and segment exclusions with tool tags, the three stock default exclusions (removable, with
 * "Restore default exclusions"), a JSON file, import and export in the upstream container format, and the lookups the tools use while they walk
 * ({@link Sdm.Exclusions}).
 *
 * <ul>
 * <li>Pkg: matches the package. Path: matches the path and everything below it. Segment: matches when the path's segments contain the given
 *     segments (optionally partial, optionally ignoring case), e.g. "DCIM/Camera" excludes "/sdcard/DCIM/Camera/photo.png".</li>
 * <li>Tags: {@code general} (all tools) or any of {@code systemcleaner, appcleaner, corpsefinder, deduplicator}; a set that holds general, holds all four
 *     tools or is empty collapses to {general}; saving an exclusion whose id exists merges the tags. A user exclusion with the id of a default shadows it.</li>
 * <li>The nested rule ({@link #excludeNested}): drop every path an exclusion matches, and every path that is a strict ancestor of a dropped one (a parent
 *     folder must not be deleted when it holds an excluded child). The port also treats a path that is a strict ancestor of an excluded path whose own
 *     path is not in the list as a conflict (excluding more is safer).</li>
 * <li>The index of a tool is a hash lookup (the path and each of its parent prefixes), not a scan, so thousands of exclusions stay cheap.</li>
 * </ul>
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * ({@code app-common-exclusion}, {@code SegmentsExtensions.kt}). Changes: no SAF paths, no Squeezer/Swiper tags (dropped on import), a plain JSON file
 * in the data folder instead of the DataStore, and the extra ancestor rule above. The stored and exported JSON is the upstream shape
 * ({@code {"pkgId":{"name":..},"tags":[..]}}, {@code {"path":{"file":..},"tags":[..]}}, {@code {"segments":[..],"allowPartial":..,"ignoreCase":..,"tags":[..]}},
 * container {@code {"exclusionRaw":"<json text>","version":1}}). Pure Java.
 */
public final class SdmExclusions implements Sdm.Exclusions {
    public static final String PKG = "pkg", PATH = "path", SEGMENT = "segment";
    public static final String GENERAL = "general";
    private static final String[] TOOL_TAGS = { "systemcleaner", "appcleaner", "corpsefinder", "deduplicator" };
    private static final Pattern PKG_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*");

    /** One exclusion. Immutable. */
    public static final class Exclusion {
        public final String kind;                 // PKG, PATH or SEGMENT
        public final String pkg;                  // PKG
        public final String path;                 // PATH (absolute, no trailing slash)
        public final String[] segments;           // SEGMENT
        public final boolean allowPartial, ignoreCase;
        public final Set<String> tags;            // lower case, normalized
        public final boolean isDefault;
        public final String reason;               // defaults only: why it exists

        Exclusion(String kind, String pkg, String path, String[] segments, boolean allowPartial, boolean ignoreCase, Set<String> tags, boolean isDefault, String reason) {
            this.kind = kind; this.pkg = pkg; this.path = path; this.segments = segments; this.allowPartial = allowPartial; this.ignoreCase = ignoreCase;
            this.tags = Collections.unmodifiableSet(tags); this.isDefault = isDefault; this.reason = reason == null ? "" : reason;
        }

        public static Exclusion pkg(String pkg, Collection<String> tags) { return new Exclusion(PKG, pkg, null, null, false, false, normalize(tags), false, ""); }
        public static Exclusion path(String path, Collection<String> tags) { return new Exclusion(PATH, null, cleanPath(path), null, false, false, normalize(tags), false, ""); }
        public static Exclusion segment(String[] segments, boolean allowPartial, boolean ignoreCase, Collection<String> tags) {
            return new Exclusion(SEGMENT, null, null, segments.clone(), allowPartial, ignoreCase, normalize(tags), false, "");
        }

        /** The id upstream uses: kind class name plus the target; the tags do not take part. */
        public String id() {
            if (PKG.equals(kind)) return "PkgExclusion-" + pkg;
            if (PATH.equals(kind)) return "PathExclusion-" + path;
            return "SegmentExclusion-" + join(segments);
        }

        public String label() {
            return PKG.equals(kind) ? pkg : PATH.equals(kind) ? path : join(segments);
        }

        public boolean hasTag(Sdm.Tool t) { return tags.contains(GENERAL) || tags.contains(t.id); }

        Exclusion withTags(Set<String> t) { return new Exclusion(kind, pkg, path, segments, allowPartial, ignoreCase, t, isDefault, reason); }

        Exclusion asDefault(String why) { return new Exclusion(kind, pkg, path, segments, allowPartial, ignoreCase, new LinkedHashSet<String>(tags), true, why); }
    }

    /** What a {@link #create} did, so {@link #revert} can put it back (the previous state of every id it touched, null = did not exist). */
    public static final class Created {
        public final List<String> ids = new ArrayList<String>();
        final Map<String, Exclusion> previous = new LinkedHashMap<String, Exclusion>();
    }

    private static String join(String[] s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length; i++) { if (i > 0) sb.append('/'); sb.append(s[i]); }
        return sb.toString();
    }

    /**
     * The path as the scans name it: absolute, "//" and "/./" collapsed, ".." taken back lexically (a walk never follows a link, so this is the name a scan
     * reports), no trailing slash. A path typed by hand in the exclusion editor ("/storage/emulated/0//Download/keep/") would otherwise protect nothing.
     */
    static String cleanPath(String p) {
        if (p == null) throw new IllegalArgumentException("A path is needed");
        if (p.isEmpty() || p.charAt(0) != '/') throw new IllegalArgumentException("The path must be absolute: " + p);
        for (int i = 0; i < p.length(); i++) { char c = p.charAt(i); if (c == 0 || c == '\n' || c == '\r') throw new IllegalArgumentException("The path holds a control character"); }
        List<String> segs = new ArrayList<String>();
        for (String x : p.split("/")) {
            if (x.isEmpty() || x.equals(".")) continue;
            if (x.equals("..")) {
                if (segs.isEmpty()) throw new IllegalArgumentException("The path goes above /: " + p);
                segs.remove(segs.size() - 1);
                continue;
            }
            segs.add(x);
        }
        if (segs.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String x : segs) sb.append('/').append(x);
        return sb.toString();
    }

    /**
     * The other names of shared storage, mapped to the one the scans report: /sdcard, /mnt/sdcard and /storage/self/primary are links to
     * /storage/emulated/&lt;user&gt;, and /data/media/N is what /storage/emulated/N is backed by (and what a scan as root reaches). Any other path is returned as it is.
     */
    static String resolveAlias(String path, int user) {
        for (String l : new String[] { "/sdcard", "/mnt/sdcard", "/storage/self/primary" }) {
            if (path.equals(l) || path.startsWith(l + "/")) return "/storage/emulated/" + user + path.substring(l.length());
        }
        if (path.startsWith("/data/media/")) {
            int end = path.indexOf('/', 12);
            String n = end < 0 ? path.substring(12) : path.substring(12, end);
            boolean digits = !n.isEmpty() && n.length() <= 6;
            for (int i = 0; i < n.length() && digits; i++) digits = n.charAt(i) >= '0' && n.charAt(i) <= '9';
            if (digits) return "/storage/emulated/" + n + (end < 0 ? "" : path.substring(end));
        }
        return path;
    }

    /**
     * What a path exclusion and a path are compared by: the alias resolved and, below /storage (shared storage and SD cards: FUSE / sdcardfs, vfat, exfat,
     * where "Download" and "download" are the same folder), in lower case. Folding both sides can only make an exclusion hold more, never less.
     */
    static String matchKey(String path, int user) {
        String p = resolveAlias(path, user);
        return p.startsWith("/storage/") ? p.toLowerCase(Locale.ROOT) : p;
    }

    /** general / empty / all four tools collapse to {general}; unknown tags are an error. */
    static Set<String> normalize(Collection<String> tags) {
        Set<String> out = new LinkedHashSet<String>();
        if (tags != null) {
            for (String t : tags) {
                String x = t == null ? "" : t.toLowerCase(Locale.ROOT);
                if (x.equals(GENERAL)) { out.clear(); out.add(GENERAL); return out; }
                boolean ok = false;
                for (String k : TOOL_TAGS) if (k.equals(x)) ok = true;
                if (!ok) throw new IllegalArgumentException("unknown tag: " + t);
                out.add(x);
            }
        }
        if (out.isEmpty() || out.size() == TOOL_TAGS.length) { out.clear(); out.add(GENERAL); }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------- the stock defaults

    /** The stock WebView provider packages (Android System WebView and its channels, the AOSP one). AppCleaner never force-stops or drives them, with or without the default exclusions. */
    static final String[] WEBVIEW_PROVIDERS = { "com.google.android.webview", "com.google.android.webview.beta", "com.google.android.webview.dev", "com.google.android.webview.canary", "com.android.webview" };

    private static final List<Exclusion> DEFAULTS = new ArrayList<Exclusion>();
    static {
        DEFAULTS.add(Exclusion.pkg("com.starfinanz.mobile.android.pushtan", Arrays.asList("appcleaner")).asDefault("https://github.com/d4rken-org/sdmaid-se/issues/618"));
        DEFAULTS.add(Exclusion.pkg("de.zollsoft.impfapp", Arrays.asList("appcleaner")).asDefault("https://github.com/d4rken-org/sdmaid-se/issues/618"));
        DEFAULTS.add(Exclusion.path("/data/rootfs", Arrays.asList("systemcleaner")).asDefault("https://github.com/d4rken-org/sdmaid-se/issues/1331"));
        // this app and the WebView its screen runs on: clearing or force-stopping them would end the cleaning that is running
        DEFAULTS.add(Exclusion.pkg("com.bloatware.bingblop", Arrays.asList("appcleaner")).asDefault("Clearing it ends the cleaning: this app's own screen is what shows it."));
        for (String wv : WEBVIEW_PROVIDERS)
            DEFAULTS.add(Exclusion.pkg(wv, Arrays.asList("appcleaner")).asDefault("Clearing it ends the cleaning: the app's own screen runs on it."));
    }

    // ---------------------------------------------------------------------------------------------------------- state

    private final File file;
    private final LinkedHashMap<String, Exclusion> user = new LinkedHashMap<String, Exclusion>();
    private final LinkedHashSet<String> removedDefaults = new LinkedHashSet<String>();
    private volatile Map<Sdm.Tool, Index> indexes = new EnumMap<Sdm.Tool, Index>(Sdm.Tool.class);

    private final int androidUser;          // the Android user whose shared storage the scans read (Sdm.Areas.storageUserId)

    /** @param dir the data folder ({@code exclusions.json} lives there); null keeps everything in memory only */
    public SdmExclusions(File dir) {
        this(dir, 0);
    }

    /** @param user the Android user whose shared storage the scans read: /sdcard is that user's /storage/emulated/&lt;user&gt; */
    public SdmExclusions(File dir, int user) {
        this.file = dir == null ? null : new File(dir, "exclusions.json");
        this.androidUser = Math.max(0, user);
        load();
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try {
            JSONObject o = new JSONObject(SdmSettings.read(file));
            JSONArray u = o.optJSONArray("user");
            if (u != null) {
                for (int i = 0; i < u.length(); i++) {
                    try {
                        Exclusion e = fromUpstream(u.getJSONObject(i));
                        if (e != null) user.put(e.id(), e);
                    } catch (JSONException bad) {
                        // one damaged entry does not cost the others
                    } catch (IllegalArgumentException bad) {
                        // same
                    }
                }
            }
            JSONArray r = o.optJSONArray("removedDefaults");
            if (r != null) for (int i = 0; i < r.length(); i++) removedDefaults.add(r.optString(i));
        } catch (IOException e) {
            // unreadable: nothing stored
        } catch (JSONException e) {
            // damaged: nothing stored
        }
    }

    private void save() {
        indexes = new EnumMap<Sdm.Tool, Index>(Sdm.Tool.class);
        if (file == null) return;
        try {
            JSONObject o = new JSONObject();
            JSONArray u = new JSONArray();
            for (Exclusion e : user.values()) u.put(toUpstream(e));
            o.put("version", 1);
            o.put("user", u);
            o.put("removedDefaults", new JSONArray(removedDefaults));
            SdmSettings.write(file, o.toString(1));
        } catch (IOException e) {
            // stays in memory for this run
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- model

    /** Everything that is in effect: the user's exclusions, then the defaults that were not removed and are not shadowed. */
    public synchronized List<Exclusion> list() {
        List<Exclusion> out = new ArrayList<Exclusion>(user.values());
        for (Exclusion d : DEFAULTS) if (!removedDefaults.contains(d.id()) && !user.containsKey(d.id())) out.add(d);
        return out;
    }

    public synchronized Exclusion get(String id) {
        Exclusion e = user.get(id);
        if (e != null) return e;
        for (Exclusion d : DEFAULTS) if (d.id().equals(id) && !removedDefaults.contains(id)) return d;
        return null;
    }

    public synchronized int defaultsRemoved() {
        int n = 0;
        for (Exclusion d : DEFAULTS) if (removedDefaults.contains(d.id())) n++;
        return n;
    }

    /** Adds an exclusion, or merges the tags into the user exclusion that has the same id. Returns the id. */
    public synchronized String save(Exclusion e) {
        String id = e.id();
        Exclusion old = user.get(id);
        if (old != null) {
            Set<String> merged = new LinkedHashSet<String>(old.tags);
            merged.addAll(e.tags);
            user.put(id, e.withTags(normalize(merged)));
        } else {
            user.put(id, e);
        }
        save();
        return id;
    }

    /** Creates the tool-tagged exclusions for these paths and packages (the "Exclude" action of a result list). */
    public synchronized Created create(Sdm.Tool tool, Collection<String> paths, Collection<String> pkgs) {
        Created c = new Created();
        List<String> tag = Collections.singletonList(tool.id);
        List<Exclusion> todo = new ArrayList<Exclusion>();
        if (paths != null) for (String p : paths) todo.add(Exclusion.path(p, tag));
        if (pkgs != null) {
            for (String p : pkgs) {
                if (p == null || !PKG_NAME.matcher(p).matches()) throw new IllegalArgumentException("not a package name: " + p);
                if (tool == Sdm.Tool.DEDUPLICATOR) throw new IllegalArgumentException("The Deduplicator has no app exclusions");
                todo.add(Exclusion.pkg(p, tag));
            }
        }
        for (Exclusion e : todo) {
            String id = e.id();
            if (!c.previous.containsKey(id)) c.previous.put(id, user.get(id));
            Exclusion old = user.get(id);
            if (old != null) {
                Set<String> merged = new LinkedHashSet<String>(old.tags);
                merged.addAll(e.tags);
                user.put(id, e.withTags(normalize(merged)));
            } else {
                user.put(id, e);
            }
            if (!c.ids.contains(id)) c.ids.add(id);
        }
        save();
        return c;
    }

    /** Takes back what {@link #create} did: new exclusions go, merged ones get their old tags again. */
    public synchronized void revert(Created c) {
        for (Map.Entry<String, Exclusion> en : c.previous.entrySet()) {
            if (en.getValue() == null) user.remove(en.getKey()); else user.put(en.getKey(), en.getValue());
        }
        save();
    }

    /** Removes exclusions by id; a default is only marked as removed (and comes back with {@link #restoreDefaults}). */
    public synchronized void remove(Collection<String> ids) {
        for (String id : ids) {
            if (user.remove(id) != null) continue;
            for (Exclusion d : DEFAULTS) if (d.id().equals(id)) removedDefaults.add(id);
        }
        save();
    }

    public synchronized void restoreDefaults() {
        removedDefaults.clear();
        save();
    }

    // ---------------------------------------------------------------------------------------------------------- lookups

    private static final class Index {
        final int user;
        final Set<String> exact = new HashSet<String>();         // PathExclusion paths of the tool, as match keys (matchKey)
        final Set<String> ancestors = new HashSet<String>();     // every strict ancestor of those keys
        final List<Exclusion> segments = new ArrayList<Exclusion>();
        final Set<String> pkgs = new HashSet<String>();
        final List<String> paths = new ArrayList<String>();     // the paths with their alias resolved (for pruning a walk)
        Index(int user) { this.user = user; }
    }

    private Index index(Sdm.Tool t) {
        Index ix = indexes.get(t);
        if (ix != null) return ix;
        synchronized (this) {
            ix = indexes.get(t);
            if (ix != null) return ix;
            ix = new Index(androidUser);
            for (Exclusion e : list()) {
                if (!e.hasTag(t)) continue;
                if (PKG.equals(e.kind)) {
                    if (t != Sdm.Tool.DEDUPLICATOR) ix.pkgs.add(e.pkg);
                } else if (PATH.equals(e.kind)) {
                    String key = matchKey(e.path, androidUser);
                    ix.exact.add(key);
                    String resolved = resolveAlias(e.path, androidUser);
                    if (!ix.paths.contains(resolved)) ix.paths.add(resolved);
                    forEachAncestor(key, ix.ancestors);
                } else {
                    ix.segments.add(e);
                }
            }
            Map<Sdm.Tool, Index> copy = new EnumMap<Sdm.Tool, Index>(Sdm.Tool.class);
            copy.putAll(indexes);
            copy.put(t, ix);
            indexes = copy;
            return ix;
        }
    }

    private static void forEachAncestor(String path, Set<String> into) {
        for (int i = path.length() - 1; i > 0; i--) if (path.charAt(i) == '/') into.add(path.substring(0, i));
        if (path.length() > 1) into.add("/");
    }

    private static boolean matches(Index ix, String path) {
        if (!ix.exact.isEmpty()) {
            String key = matchKey(path, ix.user);
            if (ix.exact.contains(key)) return true;
            for (int i = key.length() - 1; i > 0; i--) if (key.charAt(i) == '/' && ix.exact.contains(key.substring(0, i))) return true;
            if (key.length() > 1 && ix.exact.contains("/")) return true;
        }
        if (!ix.segments.isEmpty()) {
            String[] seg = Sdm.segments(path);
            for (Exclusion e : ix.segments) if (containsSegments(seg, e.segments, e.ignoreCase, e.allowPartial)) return true;
        }
        return false;
    }

    @Override
    public boolean excludesPath(Sdm.Tool t, String path) {
        return path != null && matches(index(t), path);
    }

    @Override
    public boolean excludesPackage(Sdm.Tool t, String pkg) {
        return pkg != null && index(t).pkgs.contains(pkg);
    }

    /** The absolute paths of the path exclusions of a tool (to prune a walk); segment exclusions are not in it. */
    @Override
    public List<String> paths(Sdm.Tool t) {
        return new ArrayList<String>(index(t).paths);
    }

    /** True when deleting this path would break an exclusion: it is excluded itself, or an excluded path lies below it. */
    public boolean blocksDelete(Sdm.Tool t, String path) {
        Index ix = index(t);
        return matches(ix, path) || ix.ancestors.contains(matchKey(path, ix.user));
    }

    /** The nested rule: the paths without the excluded ones and without the strict ancestors of excluded ones (order kept). */
    public List<String> excludeNested(Sdm.Tool t, Collection<String> paths) {
        Index ix = index(t);
        List<String> survivors = new ArrayList<String>();
        Set<String> ancestorOfExcluded = new HashSet<String>(ix.ancestors);
        for (String p : paths) {
            if (matches(ix, p)) forEachAncestor(matchKey(p, ix.user), ancestorOfExcluded);
            else survivors.add(p);
        }
        List<String> out = new ArrayList<String>();
        for (String p : survivors) if (!ancestorOfExcluded.contains(matchKey(p, ix.user))) out.add(p);
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------- segments (SegmentsExtensions.kt)

    /** {@code Segments.containsSegments}: the other segments appear as a contiguous run, or (allowPartial) as plain text in the joined path. */
    public static boolean containsSegments(String[] target, String[] other, boolean ignoreCase, boolean allowPartial) {
        if (target == null || other == null) return false;
        if (target.length < other.length) return false;
        if (allowPartial) {
            String a = join(target), b = join(other);
            if (ignoreCase) { a = a.toLowerCase(Locale.ROOT); b = b.toLowerCase(Locale.ROOT); }
            return a.contains(b);
        }
        if (other.length == 0) return true;
        for (int i = 0; i + other.length <= target.length; i++) {
            boolean all = true;
            for (int j = 0; j < other.length && all; j++) {
                all = ignoreCase ? target[i + j].toLowerCase(Locale.ROOT).equals(other[j].toLowerCase(Locale.ROOT)) : target[i + j].equals(other[j]);
            }
            if (all) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------- JSON: upstream shape

    private static JSONArray upstreamTags(Set<String> tags) {
        JSONArray a = new JSONArray();
        for (String t : tags) a.put(t.toUpperCase(Locale.ROOT));
        return a;
    }

    static JSONObject toUpstream(Exclusion e) {
        try {
            JSONObject o = new JSONObject();
            if (PKG.equals(e.kind)) o.put("pkgId", new JSONObject().put("name", e.pkg));
            else if (PATH.equals(e.kind)) o.put("path", new JSONObject().put("file", e.path));
            else {
                o.put("segments", new JSONArray(Arrays.asList(e.segments)));
                o.put("allowPartial", e.allowPartial);
                o.put("ignoreCase", e.ignoreCase);
            }
            o.put("tags", upstreamTags(e.tags));
            return o;
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** One upstream exclusion; null when it only has tags this port does not know (Squeezer, Swiper) or is of an unsupported kind (SAF path). */
    static Exclusion fromUpstream(JSONObject o) throws JSONException {
        JSONArray t = o.optJSONArray("tags");
        List<String> tags = new ArrayList<String>();
        boolean dropped = false;
        if (t != null) {
            for (int i = 0; i < t.length(); i++) {
                String x = t.optString(i).toLowerCase(Locale.ROOT);
                if (x.equals("squeezer") || x.equals("swiper")) dropped = true; else tags.add(x);
            }
        }
        if (dropped && tags.isEmpty()) return null;
        if (o.has("pkgId")) {
            Object p = o.get("pkgId");
            String name = p instanceof JSONObject ? ((JSONObject) p).getString("name") : String.valueOf(p);
            if (!PKG_NAME.matcher(name).matches()) throw new IllegalArgumentException("not a package name: " + name);
            return Exclusion.pkg(name, tags);
        }
        if (o.has("path")) {
            Object p = o.get("path");
            String path = null;
            if (p instanceof JSONObject) {
                JSONObject po = (JSONObject) p;
                if (po.has("file")) path = po.getString("file"); else if (po.has("path")) path = po.getString("path");
            } else if (p instanceof String) {
                path = (String) p;
            }
            if (path == null) return null;                    // a SAF path: not supported
            return Exclusion.path(path, tags);
        }
        if (o.has("segments")) {
            JSONArray s = o.getJSONArray("segments");
            String[] seg = new String[s.length()];
            for (int i = 0; i < seg.length; i++) seg[i] = s.getString(i);
            if (seg.length == 0) throw new IllegalArgumentException("no segments");
            return Exclusion.segment(seg, o.optBoolean("allowPartial", false), o.optBoolean("ignoreCase", true), tags);
        }
        throw new IllegalArgumentException("Unknown exclusion type");
    }

    /** The container SD Maid exports: {@code {"exclusionRaw":"[...]","version":1}} with the user's exclusions. */
    public synchronized String export() {
        try {
            JSONArray a = new JSONArray();
            for (Exclusion e : user.values()) a.put(toUpstream(e));
            return new JSONObject().put("exclusionRaw", a.toString()).put("version", 1).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized int exportCount() {
        return user.size();
    }

    /**
     * Imports SD Maid 2 exports ({@code exclusionRaw}) and SD Maid 1 files ({@code exclusions} with {@code contains_string}, version 6 or newer: a package
     * name becomes an app exclusion, anything else a partial, case-insensitive segment exclusion; regex entries are skipped). Returns how many were added
     * or merged. Throws IllegalArgumentException on anything else.
     */
    public synchronized int importJson(String json) {
        if (json == null || json.trim().isEmpty()) throw new IllegalArgumentException("Exclusion data was empty");
        List<Exclusion> in = new ArrayList<Exclusion>();
        try {
            JSONObject c = new JSONObject(json);
            if (c.has("exclusionRaw")) {
                if (c.optInt("version", 1) != 1) throw new IllegalArgumentException("Unsupported version: " + c.optInt("version"));
                JSONArray a = new JSONArray(c.getString("exclusionRaw"));
                for (int i = 0; i < a.length(); i++) {
                    Exclusion e = fromUpstream(a.getJSONObject(i));
                    if (e != null) in.add(e);
                }
            } else if (c.has("exclusions")) {
                if (c.optInt("version", 0) < 6) throw new IllegalArgumentException("SD Maid 1 exclusions older than version 6 are not supported");
                JSONArray a = c.getJSONArray("exclusions");
                for (int i = 0; i < a.length(); i++) {
                    JSONObject h = a.getJSONObject(i);
                    if (!"SIMPLE_CONTAINS".equals(h.optString("type"))) continue;
                    String contains = h.optString("contains_string", "");
                    if (contains.isEmpty()) continue;
                    List<String> tags = new ArrayList<String>();
                    JSONArray t = h.optJSONArray("tags");
                    if (t != null) {
                        for (int k = 0; k < t.length(); k++) {
                            String x = t.optString(k);
                            if (x.equals("CORPSEFINDER")) tags.add("corpsefinder");
                            else if (x.equals("SYSTEMCLEANER")) tags.add("systemcleaner");
                            else if (x.equals("APPCLEANER")) tags.add("appcleaner");
                            else if (x.equals("DUPLICATES")) tags.add("deduplicator");
                            else tags.add(GENERAL);
                        }
                    }
                    if (contains.matches("[A-Za-z0-9.]+") && !contains.startsWith(".") && !contains.endsWith(".") && !contains.contains("..") && PKG_NAME.matcher(contains).matches()) in.add(Exclusion.pkg(contains, tags));
                    else in.add(Exclusion.segment(contains.split("/", -1), true, true, tags));
                }
            } else {
                throw new IllegalArgumentException("Invalid exclusion data");
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid exclusion data", e);
        }
        int n = 0;
        for (Exclusion e : in) {
            String id = e.id();
            Exclusion old = user.get(id);
            if (old != null) {
                Set<String> merged = new LinkedHashSet<String>(old.tags);
                merged.addAll(e.tags);
                user.put(id, e.withTags(normalize(merged)));
            } else {
                user.put(id, e);
            }
            n++;
        }
        save();
        return n;
    }

    // ---------------------------------------------------------------------------------------------------------- JSON: for the page

    /** {id, kind, pkg?, path?, segments?, allowPartial?, ignoreCase?, tags, label, isDefault, reason?} with lower case tags. */
    public static JSONObject toPage(Exclusion e, String label) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", e.id());
            o.put("kind", e.kind);
            if (PKG.equals(e.kind)) o.put("pkg", e.pkg);
            else if (PATH.equals(e.kind)) o.put("path", e.path);
            else { o.put("segments", new JSONArray(Arrays.asList(e.segments))); o.put("allowPartial", e.allowPartial); o.put("ignoreCase", e.ignoreCase); }
            o.put("tags", new JSONArray(e.tags));
            o.put("label", label == null || label.isEmpty() ? e.label() : label);
            o.put("isDefault", e.isDefault);
            if (e.isDefault) o.put("reason", e.reason);
            return o;
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Reads what the exclusion editor sends: {kind, pkg | path | segments ("a/b" or an array), allowPartial, ignoreCase, tags}. */
    public static Exclusion fromPage(JSONObject o) {
        String kind = o.optString("kind", "");
        JSONArray t = o.optJSONArray("tags");
        List<String> tags = new ArrayList<String>();
        if (t != null) for (int i = 0; i < t.length(); i++) tags.add(t.optString(i));
        if (PKG.equals(kind)) {
            String p = o.optString("pkg", "");
            if (!PKG_NAME.matcher(p).matches()) throw new IllegalArgumentException("not a package name: " + p);
            return Exclusion.pkg(p, tags);
        }
        if (PATH.equals(kind)) return Exclusion.path(o.optString("path", ""), tags);
        if (SEGMENT.equals(kind)) {
            Object s = o.opt("segments");
            String[] seg;
            if (s instanceof JSONArray) {
                JSONArray a = (JSONArray) s;
                seg = new String[a.length()];
                for (int i = 0; i < seg.length; i++) seg[i] = a.optString(i);
            } else if (s instanceof String) {
                seg = ((String) s).split("/", -1);
            } else {
                throw new IllegalArgumentException("segments are needed");
            }
            boolean any = false;
            for (String x : seg) if (!x.isEmpty()) any = true;
            if (!any) throw new IllegalArgumentException("segments are needed");
            return Exclusion.segment(seg, o.optBoolean("allowPartial", false), o.optBoolean("ignoreCase", true), tags);
        }
        throw new IllegalArgumentException("unknown kind: " + kind);
    }
}
