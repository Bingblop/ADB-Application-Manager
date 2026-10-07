package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-common-data: common/clutter/ClutterRepo.kt, Marker.kt, manual/{JsonMarkerGroup,ManualMarker,ManualMarkerSource}.kt, dynamic/{NestedPackageMatcher,
 * NestedPackageV2Matcher}.kt and dynamic/modules/*; the database assets/sdm/db_clutter_markers.json is the upstream file, unchanged).
 * Changed for this port: plain Java (no coroutines, no Hilt, no android.*); the database text comes from the same reader as the AppCleaner databases
 * ({@link SdmAppSieve#readAsset}, installed once by the host with {@link SdmAppSieve#setAssets}; the tests read the repository's assets folder);
 * the "regexPkgs" of a group are resolved against the installed apps again for EVERY {@link #index} (upstream builds its map once per process and so never
 * sees an app installed or removed later); a group without any package or without a marker is skipped instead of failing the whole database.
 *
 * What a marker is: "the folder (or file) X in area Y belongs to package P". CorpseFinder asks which packages claim a path (an owner is a package + the
 * flags keeper / common / custodian) and decides from that whether the path is a remnant. Format of the JSON (a top-level array of groups):
 * <pre>{ "pkgs":["a.b"], "regexPkgs":["^a\\.b\\..+$"], "mrks":[ {"loc":"SDCARD", "path":"a/b", "contains":"x", "regex":"^...$", "flags":["keeper"]} ] }</pre>
 * {@link Marker#match}: the area is equal, the pfp (path below the area root) is not empty; a marker with a path and no regex matches when the pfp EQUALS the
 * path (segment by segment, ignoring case in the public areas); a marker with a regex matches when (path, if set, is a partial prefix of the pfp) and (contains,
 * if set, is a substring of the joined pfp) and the regex matches the WHOLE joined pfp. The dynamic markers (code in upstream, not JSON) map
 * "&lt;base&gt;/&lt;com.some.pkg&gt;" to that package.
 */
public final class SdmMarkers {

    public static final String ASSET_PATH = "sdm/db_clutter_markers.json";
    public static final String KEEPER = "keeper", COMMON = "common", CUSTODIAN = "custodian";

    private static final Object LOCK = new Object();
    private static SdmMarkers shared;

    private final List<Group> groups;
    private final int markerCount;

    private SdmMarkers(List<Group> groups, int markerCount) { this.groups = groups; this.markerCount = markerCount; }

    // ---------------------------------------------------------------------------------------------------------- loading

    /** The database of the app, read once (through {@link SdmAppSieve#readAsset}); throws when it cannot be read. */
    public static SdmMarkers shared() throws IOException {
        synchronized (LOCK) {
            if (shared == null) {
                try {
                    shared = parse(SdmAppSieve.readAsset(ASSET_PATH));
                } catch (JSONException e) {
                    throw new IOException("Bad marker database " + ASSET_PATH + ": " + e.getMessage());
                }
            }
            return shared;
        }
    }

    /** Forgets the shared database (tests, or after the host installed another assets reader). */
    public static void reset() { synchronized (LOCK) { shared = null; } }

    public static SdmMarkers parse(String json) throws JSONException {
        JSONArray arr = new JSONArray(json);
        List<Group> out = new ArrayList<Group>();
        int markers = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.getJSONObject(i);
            List<String> pkgs = strings(g.optJSONArray("pkgs")), regexPkgs = strings(g.optJSONArray("regexPkgs"));
            JSONArray mrks = g.optJSONArray("mrks");
            if ((pkgs.isEmpty() && regexPkgs.isEmpty()) || mrks == null || mrks.length() == 0) continue;
            Group grp = new Group(pkgs, regexPkgs);
            for (int k = 0; k < mrks.length(); k++) {
                JSONObject m = mrks.getJSONObject(k);
                Sdm.Area area;
                try { area = Sdm.Area.valueOf(m.optString("loc", "")); } catch (IllegalArgumentException e) { continue; }
                String path = m.has("path") && !m.isNull("path") ? m.getString("path") : null;
                String contains = m.has("contains") && !m.isNull("contains") ? m.getString("contains") : null;
                String regex = m.has("regex") && !m.isNull("regex") ? m.getString("regex") : null;
                if (path == null && contains == null && regex == null) continue;
                Set<String> flags = new LinkedHashSet<String>();
                JSONArray fl = m.optJSONArray("flags");
                if (fl != null) for (int f = 0; f < fl.length(); f++) flags.add(fl.getString(f));
                grp.raw.add(new Raw(area, path, contains, regex, flags));
                markers++;
            }
            if (!grp.raw.isEmpty()) out.add(grp);
        }
        return new SdmMarkers(Collections.unmodifiableList(out), markers);
    }

    private static List<String> strings(JSONArray a) {
        List<String> l = new ArrayList<String>();
        if (a != null) for (int i = 0; i < a.length(); i++) { String s = a.optString(i, null); if (s != null) l.add(s); }
        return l;
    }

    public int groupCount() { return groups.size(); }
    public int markerCount() { return markerCount; }

    // ---------------------------------------------------------------------------------------------------------- model

    private static final class Group {
        final List<String> pkgs, regexPkgs;
        final List<Raw> raw = new ArrayList<Raw>();
        Group(List<String> pkgs, List<String> regexPkgs) { this.pkgs = pkgs; this.regexPkgs = regexPkgs; }
    }

    private static final class Raw {
        final Sdm.Area area; final String path, contains, regex; final Set<String> flags;
        Raw(Sdm.Area area, String path, String contains, String regex, Set<String> flags) { this.area = area; this.path = path; this.contains = contains; this.regex = regex; this.flags = flags; }
    }

    /** Which packages (and with which flags) a marker assigns a path to. */
    public static final class Match {
        public final Set<String> pkgs;
        public final Set<String> flags;
        Match(Set<String> pkgs, Set<String> flags) { this.pkgs = pkgs; this.flags = flags; }
        public boolean has(String flag) { return flags.contains(flag); }
        @Override public String toString() { return "Match(pkgs=" + pkgs + ", flags=" + flags + ")"; }
    }

    /** One marker: "path P in area A belongs to packages". */
    public abstract static class Marker {
        public final Sdm.Area area;
        /** The marker's path segments ("a/b" -> [a, b]); empty for a regex marker without a path. */
        public final String[] segments;
        /** No regex: the marker names exactly one path, so it can be looked up in reverse (CorpseFinder SdcardCorpseFilter). */
        public final boolean direct;
        Marker(Sdm.Area area, String[] segments, boolean direct) { this.area = area; this.segments = segments; this.direct = direct; }
        /** pfp = the path below the area root; null when this marker does not claim it. */
        public abstract Match match(Sdm.Area otherArea, String[] pfp);
        public Set<String> pkgs() { return Collections.emptySet(); }
    }

    private static final class Manual extends Marker {
        final Set<String> pkgs, flags;
        final String[] path;
        final String contains;
        final Pattern pattern;
        final boolean ci;
        Manual(Raw r, Set<String> pkgs) {
            super(r.area, r.path == null ? new String[0] : SdmSieve.toSegs(r.path), r.regex == null);
            this.pkgs = pkgs; this.flags = r.flags; this.path = r.path == null ? null : SdmSieve.toSegs(r.path); this.contains = r.contains;
            this.ci = r.area.caseInsensitive;
            this.pattern = r.regex == null ? null : Pattern.compile(r.regex, ci ? Pattern.CASE_INSENSITIVE : 0);
        }
        @Override public Set<String> pkgs() { return pkgs; }
        @Override public Match match(Sdm.Area otherArea, String[] o) {
            if (area != otherArea || o == null || o.length == 0 || o[0].isEmpty()) return null;
            boolean ok;
            if (path != null && pattern == null) {
                ok = SdmSieve.sameSegs(o, path, ci);
            } else if (pattern != null) {
                if (path != null && !SdmSieve.startsWithPartial(o, path, ci)) ok = false;
                else {
                    String joined = SdmSieve.join(o);
                    if (contains != null && !(ci ? SdmSieve.lower(joined).contains(SdmSieve.lower(contains)) : joined.contains(contains))) ok = false;
                    else ok = pattern.matcher(joined).matches();
                }
            } else ok = false;
            return ok ? new Match(pkgs, flags) : null;
        }
        @Override public String toString() { return "Marker(" + area + ", " + (path == null ? "" : SdmSieve.join(path)) + (pattern == null ? "" : ", regex=" + pattern.pattern()) + ", " + flags + ", " + pkgs + ")"; }
    }

    /** NestedPackageMatcher: base/&lt;pkg&gt; for any child name that holds a dot and is not in badMatches. */
    private static final class Nested extends Marker {
        final String[] base; final Set<String> bad; final boolean ci;
        Nested(Sdm.Area area, String[] base, String... bad) {
            super(area, base, false);
            this.base = base; this.bad = new HashSet<String>(java.util.Arrays.asList(bad)); this.ci = area.caseInsensitive;
        }
        @Override public Match match(Sdm.Area otherArea, String[] o) {
            if (area != otherArea || o == null || o.length != base.length + 1) return null;
            for (int i = 0; i < base.length; i++) if (!(ci ? o[i].equalsIgnoreCase(base[i]) : o[i].equals(base[i]))) return null;
            String last = o[o.length - 1];
            if (bad.contains(last) || !last.contains(".")) return null;
            return new Match(Collections.singleton(last), Collections.<String>emptySet());
        }
        /** getMarkerForPkg: the one path this package would have below the base. */
        Marker forPkg(String pkg) {
            String[] segs = new String[base.length + 1];
            System.arraycopy(base, 0, segs, 0, base.length);
            segs[base.length] = pkg;
            return new Fixed(area, segs, Collections.singleton(pkg), Collections.<String>emptySet());
        }
    }

    /** NestedPackageV2Matcher with the PackagePathConverter (tencent/msflogs/a/b/c -> package a.b.c). */
    private static final class NestedV2 extends Marker {
        static final Pattern GOOD = Pattern.compile("^(?>tencent/msflogs/((?:\\w+/){2}\\w+))$", Pattern.CASE_INSENSITIVE);
        final boolean ci;
        NestedV2() { super(Sdm.Area.SDCARD, new String[] { "tencent", "msflogs" }, false); this.ci = true; }
        @Override public Match match(Sdm.Area otherArea, String[] o) {
            if (area != otherArea || !SdmSieve.isAncestorSegs(segments, o, ci)) return null;
            java.util.regex.Matcher m = GOOD.matcher(SdmSieve.join(o));
            if (!m.matches()) return null;
            return new Match(Collections.singleton(m.group(1).replace('/', '.')), Collections.<String>emptySet());
        }
        Marker forPkg(String pkg) {
            String[] parts = pkg.split("\\.");
            String[] segs = new String[2 + parts.length];
            segs[0] = "tencent"; segs[1] = "msflogs";
            System.arraycopy(parts, 0, segs, 2, parts.length);
            return new Fixed(area, segs, Collections.singleton(pkg), Collections.<String>emptySet());
        }
    }

    /** A package marker of a dynamic matcher: one fixed path. */
    private static final class Fixed extends Marker {
        final Set<String> pkgs, flags; final boolean ci;
        Fixed(Sdm.Area area, String[] segs, Set<String> pkgs, Set<String> flags) { super(area, segs, true); this.pkgs = pkgs; this.flags = flags; this.ci = area.caseInsensitive; }
        @Override public Set<String> pkgs() { return pkgs; }
        @Override public Match match(Sdm.Area otherArea, String[] o) { return area == otherArea && SdmSieve.sameSegs(o, segments, ci) ? new Match(pkgs, flags) : null; }
    }

    /** The dynamic markers of upstream (modules/*), all in the SDCARD area. */
    private static List<Marker> dynamicMarkers() {
        List<Marker> l = new ArrayList<Marker>();
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "bmwgroup" }, ".nomedia"));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { ".EveryplayCache" }, ".nomedia", "images", "videos"));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { ".backups" }, ".nomedia"));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "IQQI" }, ".nomedia"));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "data", "data" }));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "data", "user", "0" }));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "Android", ".Trash" }, ".nomedia"));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "tencent", "wns", "EncryptLogs" }, ".nomedia"));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "tencent", "tbs", "backup" }, ".nomedia"));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { ".UTSystemConfig" }));
        l.add(new Nested(Sdm.Area.SDCARD, new String[] { "VideoCache" }));
        l.add(new NestedV2());
        return l;
    }

    // ---------------------------------------------------------------------------------------------------------- the index of one scan

    /**
     * The database for one scan: the "regexPkgs" resolved against the installed apps, the direct markers in a hash by (lower case) path, the regex and dynamic
     * markers in lists. Cheap to build (milliseconds), so a scan builds its own.
     */
    public Index index(Collection<String> installedPkgs) {
        return new Index(installedPkgs);
    }

    public final class Index {
        private final Map<Sdm.Area, Map<String, List<Manual>>> direct = new HashMap<Sdm.Area, Map<String, List<Manual>>>();
        private final Map<Sdm.Area, List<Marker>> other = new HashMap<Sdm.Area, List<Marker>>();
        private final Map<Sdm.Area, List<Marker>> all = new HashMap<Sdm.Area, List<Marker>>();
        private final List<Marker> dynamic = dynamicMarkers();
        private Map<String, List<Marker>> byPkg;

        Index(Collection<String> installed) {
            for (Group g : groups) {
                Set<String> raw = new LinkedHashSet<String>(g.pkgs);
                if (!g.regexPkgs.isEmpty()) {
                    for (String rx : g.regexPkgs) {
                        Pattern p = Pattern.compile(rx);
                        for (String app : installed) if (p.matcher(app).matches()) raw.add(app);
                    }
                    // no installed app matches: the regexes themselves stand in for the packages, so the path still has an owner that is not installed
                    if (raw.isEmpty()) raw.addAll(g.regexPkgs);
                }
                Set<String> pkgs = Collections.unmodifiableSet(raw);
                for (Raw r : g.raw) {
                    Manual m = new Manual(r, pkgs);
                    add(all, r.area, m);
                    if (m.path != null && m.pattern == null && !hasEmpty(m.path)) {
                        Map<String, List<Manual>> map = direct.get(r.area);
                        if (map == null) { map = new HashMap<String, List<Manual>>(); direct.put(r.area, map); }
                        String key = key(r.area, m.path);
                        List<Manual> l = map.get(key);
                        if (l == null) { l = new ArrayList<Manual>(2); map.put(key, l); }
                        l.add(m);
                    } else if (m.pattern != null) {
                        add(other, r.area, m);
                    }
                }
            }
            for (Marker d : dynamic) { add(other, d.area, d); add(all, d.area, d); }
        }

        private void add(Map<Sdm.Area, List<Marker>> map, Sdm.Area a, Marker m) {
            List<Marker> l = map.get(a);
            if (l == null) { l = new ArrayList<Marker>(); map.put(a, l); }
            l.add(m);
        }

        private boolean hasEmpty(String[] segs) { for (String s : segs) if (s.isEmpty()) return true; return false; }

        private String key(Sdm.Area a, String[] segs) {
            String j = SdmSieve.join(segs);
            return a.caseInsensitive ? SdmSieve.lower(j) : j;
        }

        /** All markers of an area (manual ones in file order, then the dynamic ones): ClutterRepo.getMarkerForLocation. */
        public List<Marker> forLocation(Sdm.Area a) {
            List<Marker> l = all.get(a);
            return l == null ? Collections.<Marker>emptyList() : Collections.unmodifiableList(l);
        }

        /** Every marker that claims the path (pfp = the segments below the area root): ClutterRepo.match. */
        public List<Match> match(Sdm.Area a, String[] pfp) {
            List<Match> out = new ArrayList<Match>();
            if (pfp == null || pfp.length == 0 || pfp[0].isEmpty()) return out;
            Map<String, List<Manual>> map = direct.get(a);
            if (map != null) {
                List<Manual> l = map.get(key(a, pfp));
                if (l != null) for (Manual m : l) { Match x = m.match(a, pfp); if (x != null) out.add(x); }
            }
            List<Marker> rest = other.get(a);
            if (rest != null) for (Marker m : rest) { Match x = m.match(a, pfp); if (x != null) out.add(x); }
            return out;
        }

        /** The markers that name this package (ClutterRepo.getMarkerForPkg): its direct markers and the dynamic base/package paths. */
        public List<Marker> forPackage(String pkg) {
            synchronized (this) {
                if (byPkg == null) {
                    byPkg = new HashMap<String, List<Marker>>();
                    for (List<Marker> l : all.values()) for (Marker m : l) {
                        if (m instanceof Manual) for (String p : m.pkgs()) {
                            List<Marker> x = byPkg.get(p);
                            if (x == null) { x = new ArrayList<Marker>(); byPkg.put(p, x); }
                            x.add(m);
                        }
                    }
                }
            }
            List<Marker> out = new ArrayList<Marker>();
            List<Marker> l = byPkg.get(pkg);
            if (l != null) out.addAll(l);
            for (Marker d : dynamic) {
                if (d instanceof Nested) out.add(((Nested) d).forPkg(pkg));
                else if (d instanceof NestedV2) out.add(((NestedV2) d).forPkg(pkg));
            }
            return out;
        }
    }

    /** Lower case with the Kotlin rule (Locale.ROOT); exposed for the tools that key their own maps the same way. */
    public static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
