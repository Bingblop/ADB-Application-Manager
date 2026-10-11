package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings of the four tools and the tab itself, persisted as {@code settings.json} in the data folder. The keys are the upstream datastore
 * keys as written in the spec (2.4, 3.5, 4.5, 5.4 and 7.6), each with its upstream default, its type and its limits: an unknown key or a value of the
 * wrong type or outside the limits is rejected with an IllegalArgumentException and nothing is stored. {@link #tool} and {@link #values} always
 * return every key of the tool with the defaults filled in, which is what {@link Sdm.Ctx} hands to a tool (a missing key means its default).
 * Only values that differ from the default are written to disk.
 *
 * <p>The "general" group holds the tab's own settings: {@code oneTapMode}, {@code oneTapTools}, {@code heroAutoShow} (spec 7.6), {@code disabledTools}
 * (the tools the main action leaves out), {@code retention.reports} and {@code retention.paths} in days (spec 7.9), {@code dryRun} (spec 8.6),
 * {@code romType} (the accessibility plan override of spec 3.7.2).
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se. Changes: the Android DataStore is
 * a JSON file; Durations are milliseconds (the same unit as {@code skip.mincacheage.milliseconds}); keys of dropped features (uninstall watcher,
 * custom filter editor state, ui theme, tours) are not kept. Pure Java.
 */
public final class SdmSettings {
    public static final String GENERAL = "general";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long DAY_MS = 86400000L;
    private static final long TB = 1L << 40;

    private enum Type { BOOL, NUM, STR, LIST, OBJ }

    private static final class Def {
        final String key; final Type type; final Object def; final long min, max; final String[] allowed;
        Def(String key, Type type, Object def, long min, long max, String[] allowed) { this.key = key; this.type = type; this.def = def; this.min = min; this.max = max; this.allowed = allowed; }
    }

    private static final Map<String, Map<String, Def>> TABLE = new LinkedHashMap<String, Map<String, Def>>();

    private static Map<String, Def> group(String tool) {
        Map<String, Def> m = new LinkedHashMap<String, Def>();
        TABLE.put(tool, m);
        return m;
    }

    private static void bool(Map<String, Def> m, String key, boolean def) { m.put(key, new Def(key, Type.BOOL, def, 0, 0, null)); }
    private static void num(Map<String, Def> m, String key, long def, long min, long max) { m.put(key, new Def(key, Type.NUM, def, min, max, null)); }
    private static void str(Map<String, Def> m, String key, String def, String... allowed) { m.put(key, new Def(key, Type.STR, def, 0, 0, allowed.length == 0 ? null : allowed)); }
    private static void list(Map<String, Def> m, String key, Object def, String... allowedItems) { m.put(key, new Def(key, Type.LIST, def, 0, 0, allowedItems.length == 0 ? null : allowedItems)); }
    private static void obj(Map<String, Def> m, String key, Object def) { m.put(key, new Def(key, Type.OBJ, def, 0, 0, null)); }

    static {
        Map<String, Def> sc = group("systemcleaner");
        bool(sc, "filter.logfiles.enabled", true);
        bool(sc, "filter.advertisements.enabled", true);
        bool(sc, "filter.emptydirectories.enabled", true);
        bool(sc, "filter.superfluosapks.enabled", false);
        bool(sc, "filter.superfluosapks.includesameversion", true);
        bool(sc, "filter.lostdir.enabled", true);
        bool(sc, "filter.linuxfiles.enabled", true);
        bool(sc, "filter.macfiles.enabled", true);
        bool(sc, "filter.thumbnails.enabled", false);
        bool(sc, "filter.tempfiles.enabled", true);
        bool(sc, "filter.analytics.enabled", true);
        bool(sc, "filter.windowsfiles.enabled", true);
        bool(sc, "filter.anr.enabled", true);
        bool(sc, "filter.localtmp.enabled", false);
        bool(sc, "filter.downloadcache.enabled", true);
        bool(sc, "filter.datalogger.enabled", true);
        bool(sc, "filter.logdropbox.enabled", true);
        bool(sc, "filter.recenttasks.enabled", false);
        bool(sc, "filter.tombstones.enabled", false);
        bool(sc, "filter.usagestats.enabled", false);
        bool(sc, "filter.screenshots.enabled", false);
        num(sc, "filter.screenshots.age", 14 * DAY_MS, 0, 90 * DAY_MS);
        bool(sc, "filter.trashed.enabled", false);
        bool(sc, "filter.packagecache.enabled", false);

        Map<String, Def> ac = group("appcleaner");
        bool(ac, "include.systemapps.enabled", false);
        bool(ac, "include.runningapps.enabled", true);
        num(ac, "skip.mincachesize.bytes", 48 * 1024L, 0, 102400000L);
        num(ac, "skip.mincacheage.milliseconds", 0, 0, 182 * DAY_MS);
        bool(ac, "include.inaccessible.enabled", true);
        bool(ac, "forcestop.before.clearing.enabled", false);
        bool(ac, "filter.defaultcachespublic.enabled", true);
        bool(ac, "filter.defaultcachesprivate.enabled", true);
        bool(ac, "filter.codecache.enabled", true);
        bool(ac, "filter.advertisement.enabled", true);
        bool(ac, "filter.bugreporting.enabled", false);
        bool(ac, "filter.analytics.enabled", true);
        bool(ac, "filter.gamefiles.enabled", false);
        bool(ac, "filter.hiddencaches.enabled", true);
        bool(ac, "filter.thumbnails.enabled", true);
        bool(ac, "filter.offlinecache.enabled", false);
        bool(ac, "filter.recyclebins.enabled", false);
        bool(ac, "filter.shortcutservice.enabled", false);
        bool(ac, "filter.webview.enabled", true);
        bool(ac, "filter.threema.enabled", false);
        bool(ac, "filter.telegram.enabled", false);
        bool(ac, "filter.whatsapp.backups.enabled", false);
        bool(ac, "filter.whatsapp.received.enabled", false);
        bool(ac, "filter.whatsapp.sent.enabled", false);
        bool(ac, "filter.wechat.enabled", false);
        bool(ac, "filter.mobileqq.enabled", false);
        bool(ac, "filter.viber.enabled", false);
        str(ac, "automation.romtype", "AUTO", "AUTO", "ALCATEL", "ANDROID_TV", "AOSP", "COLOROS", "FLYME", "HUAWEI", "LGE", "LINEAGE", "MIUI", "HYPEROS", "NUBIA", "ONEPLUS", "REALME", "SAMSUNG", "VIVO", "ORIGINOS", "HONOR", "DOOGEE", "OUKITEL");

        Map<String, Def> cf = group("corpsefinder");
        bool(cf, "filter.sdcard.enabled", true);
        bool(cf, "filter.publicmedia.enabled", true);
        bool(cf, "filter.publicdata.enabled", true);
        bool(cf, "filter.privatedata.enabled", true);
        bool(cf, "filter.publicobb.enabled", false);
        bool(cf, "filter.dalvikcache.enabled", false);
        bool(cf, "filter.artprofiles.enabled", false);
        bool(cf, "filter.applib.enabled", false);
        bool(cf, "filter.appsource.enabled", false);
        bool(cf, "filter.appsourceprivate.enabled", false);
        bool(cf, "filter.appasec.enabled", false);
        bool(cf, "risk.include.keeper", false);
        bool(cf, "risk.include.common", false);

        Map<String, Def> dd = group("deduplicator");
        list(dd, "scan.location.paths", new JSONArray());
        str(dd, "arbiter.config", defaultArbiter());
        bool(dd, "protection.deleteall.allowed", false);
        num(dd, "skip.minsize.bytes", 512 * 1024L, 0, 104857600L);
        bool(dd, "skip.files.uncommon", true);
        bool(dd, "sleuth.checksum.enabled", true);
        str(dd, "ui.list.layoutmode", "GRID", "GRID", "LINEAR");
        bool(dd, "ui.cluster.directoryview.enabled", false);

        Map<String, Def> g = group(GENERAL);
        bool(g, "oneTapMode", false);
        list(g, "oneTapTools", new JSONArray().put("corpsefinder").put("systemcleaner").put("appcleaner"), "systemcleaner", "appcleaner", "corpsefinder", "deduplicator");
        bool(g, "heroAutoShow", true);
        list(g, "disabledTools", new JSONArray(), "systemcleaner", "appcleaner", "corpsefinder", "deduplicator");
        num(g, "retention.reports", 30, 0, 365);
        num(g, "retention.paths", 7, 0, 365);
        bool(g, "dryRun", false);
    }

    /** The default deletion strategy of spec 5.2 (the criteria in evaluation order). */
    private static String defaultArbiter() {
        return "{\"criteria\":[{\"criteriumType\":\"DUPLICATE_TYPE\",\"mode\":\"PREFER_CHECKSUM\"},{\"criteriumType\":\"PREFERRED_PATH\",\"keepPreferPaths\":[]},"
                + "{\"criteriumType\":\"MEDIA_PROVIDER\",\"mode\":\"PREFER_INDEXED\"},{\"criteriumType\":\"LOCATION\",\"mode\":\"PREFER_PRIMARY\"},"
                + "{\"criteriumType\":\"NESTING\",\"mode\":\"PREFER_SHALLOW\"},{\"criteriumType\":\"MODIFIED\",\"mode\":\"PREFER_OLDER\"},{\"criteriumType\":\"SIZE\",\"mode\":\"PREFER_LARGER\"}]}";
    }

    // ---------------------------------------------------------------------------------------------------------- instance

    private final File file;
    private final JSONObject overrides = new JSONObject();      // {tool: {key: value}} only what differs from the default

    /** @param dir the data folder ({@code settings.json} lives there); null keeps everything in memory only */
    public SdmSettings(File dir) {
        this.file = dir == null ? null : new File(dir, "settings.json");
        load();
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try {
            JSONObject all = new JSONObject(new String(Files.readAllBytes(file.toPath()), UTF8));
            for (String tool : TABLE.keySet()) {
                JSONObject o = all.optJSONObject(tool);
                if (o == null) continue;
                Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String key = it.next();
                    Def d = TABLE.get(tool).get(key);
                    if (d == null) continue;                                   // a key this version does not know is dropped
                    try {
                        Object v = check(d, o.get(key));
                        if (!same(v, d.def)) put(overrides, tool, key, v);
                    } catch (IllegalArgumentException bad) {
                        // a damaged value falls back to the default
                    }
                }
            }
        } catch (IOException e) {
            // unreadable: defaults
        } catch (JSONException e) {
            // damaged: defaults
        }
    }

    private static void put(JSONObject root, String tool, String key, Object v) {
        try {
            JSONObject o = root.optJSONObject(tool);
            if (o == null) { o = new JSONObject(); root.put(tool, o); }
            o.put(key, v);
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private void save() {
        if (file == null) return;
        try {
            write(file, overrides.toString(1));
        } catch (IOException | JSONException e) {
            // the setting stays in memory for this run
        }
    }

    /** Writes a text file next to its final name and renames it into place, so a crash never leaves half a file. */
    static void write(File f, String text) throws IOException {
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
        File tmp = new File(dir, f.getName() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(text.getBytes(UTF8));
            o.getFD().sync();
        } finally {
            o.close();
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), UTF8);
    }

    // ---------------------------------------------------------------------------------------------------------- values

    public static boolean knownTool(String tool) {
        return TABLE.containsKey(tool);
    }

    private static Map<String, Def> defs(String tool) {
        Map<String, Def> m = TABLE.get(tool);
        if (m == null) throw new IllegalArgumentException("unknown settings group: " + tool);
        return m;
    }

    private static Object copy(Object v) {
        try {
            if (v instanceof JSONArray) return new JSONArray(v.toString());
            if (v instanceof JSONObject) return new JSONObject(v.toString());
        } catch (JSONException e) {
            throw new IllegalArgumentException(e);
        }
        return v;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof JSONArray || a instanceof JSONObject) return a.toString().equals(String.valueOf(b));
        if (a instanceof Number && b instanceof Number) return ((Number) a).longValue() == ((Number) b).longValue();
        return a.equals(b);
    }

    /** Type and limit check; returns the value in its stored form (Boolean, Long, String, JSONArray, JSONObject). */
    private static Object check(Def d, Object v) {
        if (v == null || v == JSONObject.NULL) throw new IllegalArgumentException(d.key + ": a value is needed");
        switch (d.type) {
            case BOOL:
                if (!(v instanceof Boolean)) throw new IllegalArgumentException(d.key + ": must be true or false");
                return v;
            case NUM: {
                if (!(v instanceof Number)) throw new IllegalArgumentException(d.key + ": must be a number");
                Number n = (Number) v;
                if (n instanceof Double || n instanceof Float) {
                    double x = n.doubleValue();
                    if (Double.isNaN(x) || Double.isInfinite(x) || x != Math.rint(x)) throw new IllegalArgumentException(d.key + ": must be a whole number");
                } else if (n instanceof java.math.BigDecimal) {
                    if (((java.math.BigDecimal) n).stripTrailingZeros().scale() > 0) throw new IllegalArgumentException(d.key + ": must be a whole number");
                }
                long x = n.longValue();
                if (x < d.min || x > d.max) throw new IllegalArgumentException(d.key + ": must be between " + d.min + " and " + d.max);
                return Long.valueOf(x);
            }
            case STR: {
                if (!(v instanceof String)) throw new IllegalArgumentException(d.key + ": must be text");
                String s = (String) v;
                if (d.allowed != null) {
                    boolean ok = false;
                    for (String a : d.allowed) if (a.equals(s)) ok = true;
                    if (!ok) throw new IllegalArgumentException(d.key + ": not one of " + java.util.Arrays.toString(d.allowed));
                }
                if (d.key.equals("arbiter.config")) {
                    try { new JSONObject(s).getJSONArray("criteria"); } catch (JSONException e) { throw new IllegalArgumentException(d.key + ": must be JSON text with a criteria list"); }
                } else if (s.length() > 4096) throw new IllegalArgumentException(d.key + ": too long");
                return s;
            }
            case LIST: {
                if (!(v instanceof JSONArray)) throw new IllegalArgumentException(d.key + ": must be a list");
                JSONArray a = (JSONArray) v;
                JSONArray out = new JSONArray();
                java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
                for (int i = 0; i < a.length(); i++) {
                    Object o = a.opt(i);
                    if (!(o instanceof String)) throw new IllegalArgumentException(d.key + ": the list holds text only");
                    String s = (String) o;
                    if (d.allowed != null) {
                        boolean ok = false;
                        for (String x : d.allowed) if (x.equals(s)) ok = true;
                        if (!ok) throw new IllegalArgumentException(d.key + ": unknown entry " + s);
                    }
                    if (seen.add(s)) out.put(s);
                }
                if (out.length() > 10000) throw new IllegalArgumentException(d.key + ": too many entries");
                return out;
            }
            case OBJ:
                if (!(v instanceof JSONObject)) throw new IllegalArgumentException(d.key + ": must be an object");
                return copy(v);
            default:
                throw new IllegalArgumentException(d.key);
        }
    }

    /** Converts what a caller passes (a java List of Strings is fine) into the JSON types the table knows. */
    private static Object normalize(Object v) {
        if (v instanceof java.util.Collection) {
            JSONArray a = new JSONArray();
            for (Object o : (java.util.Collection<?>) v) a.put(o);
            return a;
        }
        if (v instanceof java.util.Map) return new JSONObject((java.util.Map<?, ?>) v).toString();
        if (v instanceof JSONObject) return v.toString();
        return v;
    }

    /** Every key of the group with its current value (the default where nothing was set). */
    public synchronized JSONObject values(String tool) {
        Map<String, Def> m = defs(tool);
        JSONObject out = new JSONObject();
        JSONObject set = overrides.optJSONObject(tool);
        try {
            for (Def d : m.values()) {
                Object v = set != null && set.has(d.key) ? set.get(d.key) : d.def;
                out.put(d.key, copy(v));
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public JSONObject tool(Sdm.Tool t) {
        return values(t.id);
    }

    public JSONObject general() {
        return values(GENERAL);
    }

    public synchronized Object get(String tool, String key) {
        Def d = defs(tool).get(key);
        if (d == null) throw new IllegalArgumentException("unknown setting: " + tool + "/" + key);
        JSONObject set = overrides.optJSONObject(tool);
        return copy(set != null && set.has(key) ? set.opt(key) : d.def);
    }

    /** Sets one value; the key must exist and the value must fit its type and limits. */
    public synchronized void set(String tool, String key, Object value) {
        Def d = defs(tool).get(key);
        if (d == null) throw new IllegalArgumentException("unknown setting: " + tool + "/" + key);
        Object v = check(d, normalize(value));
        JSONObject set = overrides.optJSONObject(tool);
        if (same(v, d.def)) {
            if (set != null) { set.remove(key); if (set.length() == 0) overrides.remove(tool); }
        } else {
            put(overrides, tool, key, v);
        }
        save();
    }

    /** Back to the defaults for the whole group; returns the new values. */
    public synchronized JSONObject reset(String tool) {
        defs(tool);
        overrides.remove(tool);
        save();
        return values(tool);
    }

    /** What the page needs to draw a settings screen: {key: {type, default, min?, max?, allowed?}}. */
    public static JSONObject meta(String tool) {
        JSONObject out = new JSONObject();
        try {
            for (Def d : defs(tool).values()) {
                JSONObject o = new JSONObject();
                o.put("type", d.type == Type.BOOL ? "bool" : d.type == Type.NUM ? "number" : d.type == Type.STR ? "string" : d.type == Type.LIST ? "list" : "object");
                o.put("default", copy(d.def));
                if (d.type == Type.NUM) { o.put("min", d.min); o.put("max", d.max); }
                if (d.allowed != null) { JSONArray a = new JSONArray(); for (String s : d.allowed) a.put(s); o.put("allowed", a); }
                out.put(d.key, o);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------- the general ones, typed

    public boolean bool(String tool, String key) {
        return Boolean.TRUE.equals(get(tool, key));
    }

    public long num(String tool, String key) {
        return ((Number) get(tool, key)).longValue();
    }

    public List<String> strings(String tool, String key) {
        JSONArray a = (JSONArray) get(tool, key);
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    public boolean oneTapMode() { return bool(GENERAL, "oneTapMode"); }

    public List<String> oneTapTools() { return strings(GENERAL, "oneTapTools"); }

    /** A tool the main action may use: not in {@code disabledTools}. */
    public boolean toolEnabled(Sdm.Tool t) { return !strings(GENERAL, "disabledTools").contains(t.id); }

    public int retentionReportDays() { return (int) num(GENERAL, "retention.reports"); }

    public int retentionPathDays() { return (int) num(GENERAL, "retention.paths"); }

    public boolean dryRun() { return bool(GENERAL, "dryRun"); }
}
