package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se
 * (app-tool-appcleaner: forensics/sieves/AppSieveJsonDb.kt, forensics/sieves/JsonAppSieve.kt; the databases assets/sdm/expendables/db_*.json are the upstream files, unchanged).
 * Changed for this port: plain Java on org.json, the rules are indexed once at load (by package name) instead of a scan over every app filter per file, regexes are
 * compiled once per case mode, the path segments come as String[] and the matching uses the shared helpers of {@link SdmSieve}; the databases are read through an
 * {@link Assets} hook because a tool has no android.* (the host installs one that reads the app's assets, the tests read the files of the repository).
 *
 * The JSON sieve engine of spec 3.4. File format:
 * <pre>{ "schemaVersion":1, "appFilter":[ { "packages":["a.b"], "fileFilter":[ { "locations":["SDCARD"], "startsWith":["dir/sub/"], "contains":["x/y"],
 *                                                                                 "notContains":[".nomedia"], "patterns":["^(?>...)$"] } ] } ] }</pre>
 * {@link #matches}: the file filters of every app filter whose "packages" is empty or has the package, kept when "locations" is empty or has the area; the
 * comparison is case-insensitive in the public areas. A file filter matches when no "notContains" is found (substring of the joined path, "allowPartial"), AND
 * "startsWith" is empty or one entry is a (partial) prefix of the path, AND "contains" is empty or one entry is a substring of the joined path, AND "patterns" is
 * empty or one regex matches the WHOLE joined path. A trailing "/" in an entry makes its last segment empty, so the rule means "strictly inside that folder".
 */
public final class SdmAppSieve {

    /** Where the databases come from. path is relative to the assets folder, e.g. "sdm/expendables/db_trash_files.json". */
    public interface Assets { String read(String path) throws IOException; }

    private static volatile Assets assets;
    private static final Map<String, SdmAppSieve> CACHE = new HashMap<String, SdmAppSieve>();

    /** The host calls this once at start (MainActivity: getAssets().open(path)); tests call it with a reader of the repository's assets folder. */
    public static void setAssets(Assets a) { synchronized (CACHE) { assets = a; CACHE.clear(); } }

    public static Assets assets() { return assets; }

    /** A reader for an "assets" folder on disk (the tests, and a tool run on a computer). */
    public static Assets folder(final java.io.File dir) {
        return new Assets() {
            @Override public String read(String path) throws IOException {
                java.io.File f = new java.io.File(dir, path);
                InputStream in = new java.io.FileInputStream(f);
                try { return readAll(in); } finally { in.close(); }
            }
        };
    }

    /** The assets folder of the repository, found from the working directory upwards (the tests run a few folders below the repository); null when there is none. */
    public static java.io.File findRepoAssets() {
        String prop = System.getProperty("sdm.assets");
        if (prop != null && new java.io.File(prop, "sdm").isDirectory()) return new java.io.File(prop);
        java.io.File d = new java.io.File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        for (int i = 0; i < 10 && d != null; i++, d = d.getParentFile()) {
            java.io.File a = new java.io.File(d, "assets");
            if (new java.io.File(a, "sdm/expendables").isDirectory()) return a;
        }
        return null;
    }

    public static String readAsset(String path) throws IOException {
        Assets a = assets;
        if (a == null) {
            java.io.File repo = findRepoAssets();
            if (repo != null) a = folder(repo);
        }
        if (a == null) throw new IOException("No assets reader installed (SdmAppSieve.setAssets) for " + path);
        return a.read(path);
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), "UTF-8");
    }

    /** The sieve of one database, loaded once ("expendables/db_trash_files.json" is read from "sdm/expendables/db_trash_files.json"). */
    public static SdmAppSieve load(String name) throws IOException {
        synchronized (CACHE) {
            SdmAppSieve s = CACHE.get(name);
            if (s != null) return s;
            try {
                s = parse(readAsset("sdm/" + name), name);
            } catch (JSONException e) {
                throw new IOException("Bad database " + name + ": " + e.getMessage());
            }
            CACHE.put(name, s);
            return s;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the model

    /** One "fileFilter" object with everything precomputed. */
    static final class FileFilter {
        final Set<Sdm.Area> areas;            // empty: every area
        final String[][] startsWith, contains, notContains;
        final String[] patterns;
        private Pattern[] plain, ignoreCase;

        FileFilter(Set<Sdm.Area> areas, String[][] startsWith, String[][] contains, String[][] notContains, String[] patterns) {
            this.areas = areas; this.startsWith = startsWith; this.contains = contains; this.notContains = notContains; this.patterns = patterns;
        }

        private synchronized Pattern[] regexes(boolean ci) {
            if (patterns.length == 0) return null;
            Pattern[] cached = ci ? ignoreCase : plain;
            if (cached == null) {
                cached = new Pattern[patterns.length];
                for (int i = 0; i < patterns.length; i++) cached[i] = ci ? Pattern.compile(patterns[i], Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE) : Pattern.compile(patterns[i]);
                if (ci) ignoreCase = cached; else plain = cached;
            }
            return cached;
        }

        boolean matches(Sdm.Area area, String[] target) {
            if (!areas.isEmpty() && !areas.contains(area)) return false;
            boolean ci = area.caseInsensitive;
            for (String[] x : notContains) if (SdmSieve.containsSegments(target, x, true, ci)) return false;
            if (startsWith.length > 0) {
                boolean any = false;
                for (String[] x : startsWith) if (SdmSieve.startsWithPartial(target, x, ci)) { any = true; break; }
                if (!any) return false;
            }
            if (contains.length > 0) {
                boolean any = false;
                for (String[] x : contains) if (SdmSieve.containsSegments(target, x, true, ci)) { any = true; break; }
                if (!any) return false;
            }
            Pattern[] re = regexes(ci);
            if (re != null) {
                String joined = SdmSieve.join(target);
                boolean any = false;
                for (Pattern p : re) if (p.matcher(joined).matches()) { any = true; break; }
                if (!any) return false;
            }
            return true;
        }
    }

    private final String name;
    private final List<FileFilter> general = new ArrayList<FileFilter>();               // appFilter without packages
    private final Map<String, List<FileFilter>> byPkg = new HashMap<String, List<FileFilter>>();
    private int appFilters, fileFilters;

    private SdmAppSieve(String name) { this.name = name; }

    public String name() { return name; }
    public int appFilterCount() { return appFilters; }
    public int fileFilterCount() { return fileFilters; }

    // ---------------------------------------------------------------------------------------------------------- parsing

    /** Parses a database; throws like upstream's init blocks do: no app filters, no file filters, a file filter with neither startsWith nor contains. */
    public static SdmAppSieve parse(String json, String name) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray apps = root.getJSONArray("appFilter");
        if (apps.length() == 0) throw new JSONException("App filters are empty");
        SdmAppSieve s = new SdmAppSieve(name);
        for (int i = 0; i < apps.length(); i++) {
            JSONObject app = apps.getJSONObject(i);
            JSONArray files = app.getJSONArray("fileFilter");
            if (files.length() == 0) throw new JSONException("File filters are empty");
            List<FileFilter> list = new ArrayList<FileFilter>();
            for (int k = 0; k < files.length(); k++) list.add(parseFileFilter(files.getJSONObject(k)));
            s.appFilters++;
            s.fileFilters += list.size();
            JSONArray pk = app.optJSONArray("packages");
            if (pk == null || pk.length() == 0) {
                s.general.addAll(list);
            } else {
                for (int p = 0; p < pk.length(); p++) {
                    String pkg = pk.getString(p);
                    List<FileFilter> l = s.byPkg.get(pkg);
                    if (l == null) { l = new ArrayList<FileFilter>(); s.byPkg.put(pkg, l); }
                    l.addAll(list);
                }
            }
        }
        return s;
    }

    private static FileFilter parseFileFilter(JSONObject o) throws JSONException {
        if (!o.has("startsWith") && !o.has("contains")) throw new JSONException("Underdefined filter");
        JSONArray loc = o.getJSONArray("locations");
        Set<Sdm.Area> areas = EnumSet.noneOf(Sdm.Area.class);
        for (int i = 0; i < loc.length(); i++) areas.add(Sdm.Area.valueOf(loc.getString(i)));
        return new FileFilter(areas, segs(o.optJSONArray("startsWith")), segs(o.optJSONArray("contains")), segs(o.optJSONArray("notContains")), strings(o.optJSONArray("patterns")));
    }

    private static String[][] segs(JSONArray a) throws JSONException {
        if (a == null) return new String[0][];
        String[][] out = new String[a.length()][];
        for (int i = 0; i < a.length(); i++) out[i] = SdmSieve.toSegs(a.getString(i));
        return out;
    }

    private static String[] strings(JSONArray a) throws JSONException {
        if (a == null) return new String[0];
        String[] out = new String[a.length()];
        for (int i = 0; i < a.length(); i++) out[i] = a.getString(i);
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------- matching

    /** True when the file (its path below the area root as segments) of this package in this area is covered by the database. */
    public boolean matches(String pkg, Sdm.Area area, String[] pfp) {
        for (FileFilter f : general) if (f.matches(area, pfp)) return true;
        List<FileFilter> own = byPkg.get(pkg);
        if (own != null) for (FileFilter f : own) if (f.matches(area, pfp)) return true;
        return false;
    }
}
