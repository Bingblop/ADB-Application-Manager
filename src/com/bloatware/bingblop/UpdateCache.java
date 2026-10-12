package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * What the update check remembers between runs, in three small JSON files in one folder:
 * <ul>
 * <li>{@code update_cache.json}: the answer for one app, keyed by package + installed version code + which check asked (the open-source sources or the
 * Galaxy Store). A positive answer (an update, up to date, a source the app can only track elsewhere) is reused for 30 minutes; a negative one ("no source
 * knows this app", "not distributed through the Galaxy Store") for 7 days. An error is never stored. Installing an update changes the version code, so the
 * key changes with it. Every entry also carries a tag (the source the person set for the app, the phone for the Galaxy Store): another tag is a miss.</li>
 * <li>{@code update_catalog.json}: the Obtainium community catalog, kept for 24 hours.</li>
 * <li>{@code update_last.json}: the last result the page was shown, so it is there again after a tab switch or a restart.</li>
 * </ul>
 * No Android classes; a file that cannot be read or parsed is the same as no file.
 */
public final class UpdateCache {
    public static final long POSITIVE_TTL_MS = 30L * 60 * 1000;
    public static final long NEGATIVE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    public static final long CATALOG_TTL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_ENTRIES = 6000;

    /** What an entry says. */
    public static final String UPDATE = "update", UPTODATE = "uptodate", EXTERNAL = "external", UNTRACKED = "untracked", NOT_DISTRIBUTED = "notdist";

    private final File dir;
    private final UpdateChecker.Clock clock;
    private JSONObject entries;              // key -> {t, kind, tag, row}; guarded by this

    public UpdateCache(File dir, UpdateChecker.Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    static boolean isNegative(String kind) {
        return UNTRACKED.equals(kind) || NOT_DISTRIBUTED.equals(kind);
    }

    static long ttlOf(String kind) {
        return isNegative(kind) ? NEGATIVE_TTL_MS : POSITIVE_TTL_MS;
    }

    static String key(String pkg, long versionCode, String lane) {
        return pkg + "#" + versionCode + "#" + lane;
    }

    private File file(String name) {
        return new File(dir, name);
    }

    private synchronized JSONObject entries() {
        if (entries == null) {
            JSONObject doc = read(file("update_cache.json"));
            JSONObject e = doc == null ? null : doc.optJSONObject("entries");
            entries = e == null ? new JSONObject() : e;
        }
        return entries;
    }

    /** The fresh entry {kind, row, t} for this app and lane, or null (never stored, expired, another tag). */
    public synchronized JSONObject lookup(String pkg, long versionCode, String lane, String tag) {
        JSONObject e = entries().optJSONObject(key(pkg, versionCode, lane));
        if (e == null) return null;
        long t = e.optLong("t", 0);
        long now = clock.now();
        if (t <= 0 || now < t || now - t >= ttlOf(e.optString("kind"))) return null;     // a clock that went back never makes an entry fresh
        if (!tag.equals(e.optString("tag", ""))) return null;
        return e;
    }

    public synchronized void put(String pkg, long versionCode, String lane, String tag, String kind, JSONObject row) {
        try {
            JSONObject e = new JSONObject();
            e.put("t", clock.now());
            e.put("kind", kind);
            e.put("tag", tag);
            if (row != null) e.put("row", row);
            entries().put(key(pkg, versionCode, lane), e);
        } catch (Exception ignored) {}
    }

    /** Forgets what is stored for one package (any version, any lane). */
    public synchronized void forget(String pkg) {
        List<String> drop = new ArrayList<String>();
        Iterator<String> it = entries().keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith(pkg + "#")) drop.add(k);
        }
        for (String k : drop) entries().remove(k);
    }

    public synchronized int size() {
        return entries().length();
    }

    /** Writes the entries that are still fresh. */
    public synchronized boolean save() {
        try {
            long now = clock.now();
            JSONObject keep = new JSONObject();
            Iterator<String> it = entries().keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject e = entries().optJSONObject(k);
                if (e == null) continue;
                long t = e.optLong("t", 0);
                if (t > 0 && now >= t && now - t < ttlOf(e.optString("kind")) && keep.length() < MAX_ENTRIES) keep.put(k, e);
            }
            entries = keep;
            return write(file("update_cache.json"), new JSONObject().put("v", 1).put("entries", keep));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- the Obtainium catalog

    /** The catalog stored less than 24 hours ago ({package: {url, author, additionalSettings}}), else null. */
    public synchronized JSONObject loadCatalog() {
        JSONObject doc = read(file("update_catalog.json"));
        if (doc == null) return null;
        long t = doc.optLong("t", 0), now = clock.now();
        JSONObject apps = doc.optJSONObject("apps");
        if (apps == null || apps.length() < UpdateManager.OBTAINIUM_INDEX_MIN_APPS || t <= 0 || now < t || now - t >= CATALOG_TTL_MS) return null;
        return apps;
    }

    /** The catalog as last stored, however old: better than none when the list cannot be fetched now. */
    public synchronized JSONObject loadCatalogAnyAge() {
        JSONObject doc = read(file("update_catalog.json"));
        JSONObject apps = doc == null ? null : doc.optJSONObject("apps");
        return apps == null || apps.length() < UpdateManager.OBTAINIUM_INDEX_MIN_APPS ? null : apps;
    }

    public synchronized void saveCatalog(JSONObject apps) {
        try {
            write(file("update_catalog.json"), new JSONObject().put("t", clock.now()).put("apps", apps));
        } catch (Exception ignored) {}
    }

    // ---- the last result

    public synchronized JSONObject loadLast() {
        return read(file("update_last.json"));
    }

    public synchronized boolean saveLast(JSONObject summary) {
        return write(file("update_last.json"), summary);
    }

    // ---- files

    private static JSONObject read(File f) {
        try {
            if (!f.isFile() || f.length() > 32L * 1024 * 1024) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new JSONObject(new String(b, "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean write(File f, JSONObject doc) {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(doc.toString().getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
    }
}
