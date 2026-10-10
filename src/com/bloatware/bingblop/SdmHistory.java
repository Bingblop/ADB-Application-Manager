package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The history of finished deletions (spec 7.9): one report per finished delete or one-click task, newest first, with the paths that went away,
 * the running totals of freed space and processed items, and the retention rules (reports 30 days, detailed paths 7 days by default, 0 = not kept,
 * up to 365; expired data is pruned after every report and when a setting changes). Written by the engine's task wrapper in Java, so it survives the
 * WebView being destroyed. Scans are never reported; the caller decides what a cancelled or failed task is.
 *
 * <p>Stored in the data folder as {@code history/reports.jsonl} (one JSON object per line), {@code history/paths/<id>.txt} (one path per line) and
 * {@code history/totals.json}; nothing but the JDK and org.json is needed.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se ({@code app-common-stats}). Changes: files
 * instead of SQLite (the two tables become the report line and its path file), no storage-trend snapshots, no AppControl/Swiper rows.
 */
public final class SdmHistory {
    public static final String SUCCESS = "success", PARTIAL = "partial", FAILURE = "failure";
    private static final long DAY_MS = 86400000L;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** One report row. */
    public static final class Report {
        public String id;
        public long startAt, endAt;                 // milliseconds since the epoch
        public String tool = "", status = SUCCESS, primary = "", secondary = "", error = "", extra = "";
        public long count, bytes;                   // affected items and affected space

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("startAt", startAt).put("endAt", endAt).put("tool", tool).put("status", status).put("primary", primary)
                    .put("secondary", secondary).put("error", error).put("extra", extra).put("count", count).put("bytes", bytes);
        }

        static Report of(JSONObject o) {
            Report r = new Report();
            r.id = o.optString("id", "");
            r.startAt = o.optLong("startAt"); r.endAt = o.optLong("endAt");
            r.tool = o.optString("tool", ""); r.status = o.optString("status", SUCCESS);
            r.primary = o.optString("primary", ""); r.secondary = o.optString("secondary", ""); r.error = o.optString("error", ""); r.extra = o.optString("extra", "");
            r.count = o.optLong("count"); r.bytes = o.optLong("bytes");
            return r;
        }
    }

    private final File dir, reports, pathsDir, totals;
    private final SdmSettings settings;
    private long freed, items, since;

    /** @param dir the data folder; @param settings supplies the retention days (null: 30 and 7) */
    public SdmHistory(File dir, SdmSettings settings) {
        this.dir = new File(dir, "history");
        this.reports = new File(this.dir, "reports.jsonl");
        this.pathsDir = new File(this.dir, "paths");
        this.totals = new File(this.dir, "totals.json");
        this.settings = settings;
        loadTotals();
    }

    private int reportDays() { return settings == null ? 30 : settings.retentionReportDays(); }

    private int pathDays() { return settings == null ? 7 : settings.retentionPathDays(); }

    private void loadTotals() {
        try {
            if (totals.isFile()) {
                JSONObject o = new JSONObject(SdmSettings.read(totals));
                freed = o.optLong("freedBytes"); items = o.optLong("items"); since = o.optLong("since");
            }
        } catch (IOException e) {
            // zero
        } catch (JSONException e) {
            // zero
        }
        if (since == 0) since = System.currentTimeMillis();
    }

    private void saveTotals() {
        try {
            SdmSettings.write(totals, new JSONObject().put("freedBytes", freed).put("items", items).put("since", since).toString());
        } catch (IOException e) {
            // the totals of this run only
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- write

    /**
     * Stores a report and the paths it affected (when path details are kept at all), adds to the totals unless it failed, and prunes. Returns the stored
     * report (with its id).
     */
    public synchronized Report add(Report r, Collection<String> paths) {
        if (r.id == null || r.id.isEmpty()) r.id = UUID.randomUUID().toString();
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
            boolean needNewline = false;
            if (reports.isFile() && reports.length() > 0) {
                RandomAccessFile raf = new RandomAccessFile(reports, "r");
                try { raf.seek(reports.length() - 1); needNewline = raf.read() != '\n'; } finally { raf.close(); }
            }
            FileOutputStream o = new FileOutputStream(reports, true);
            try {
                if (needNewline) o.write('\n');
                o.write((r.toJson().toString() + "\n").getBytes(UTF8));
            } finally { o.close(); }
            if (paths != null && !paths.isEmpty() && pathDays() > 0) writePaths(r.id, paths);
        } catch (IOException e) {
            // the report of this run is lost, the work itself is done
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        if (!FAILURE.equals(r.status)) { freed += Math.max(0, r.bytes); items += Math.max(0, r.count); }
        saveTotals();
        prune(r.endAt > 0 ? r.endAt : System.currentTimeMillis());
        return r;
    }

    private void writePaths(String id, Collection<String> paths) throws IOException {
        if (!pathsDir.isDirectory() && !pathsDir.mkdirs() && !pathsDir.isDirectory()) throw new IOException("cannot create " + pathsDir);
        StringBuilder sb = new StringBuilder();
        for (String p : new LinkedHashSet<String>(paths)) {
            if (p.indexOf('\n') >= 0 || p.indexOf('\r') >= 0) continue;
            sb.append(p).append('\n');
        }
        SdmSettings.write(new File(pathsDir, id + ".txt"), sb.toString());
    }

    // ---------------------------------------------------------------------------------------------------------- read

    private List<Report> readAll() {
        List<Report> out = new ArrayList<Report>();
        if (!reports.isFile()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(reports), UTF8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    Report x = Report.of(new JSONObject(line));
                    if (!x.id.isEmpty()) out.add(x);
                } catch (JSONException bad) {
                    // a half written line
                }
            }
        } catch (IOException e) {
            // what was read
        } finally {
            if (r != null) try { r.close(); } catch (IOException ignored) {}
        }
        return out;
    }

    private boolean hasPaths(String id) {
        return new File(pathsDir, id + ".txt").isFile();
    }

    public synchronized JSONObject stats() {
        try {
            return new JSONObject().put("freedBytes", freed).put("items", items).put("sinceMs", since);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {total, reports:[{id, tool, startAt, endAt, status, primary, secondary, error, count, bytes, hasPaths}], stats}, newest first. */
    public synchronized JSONObject list(int offset, int limit) {
        List<Report> all = readAll();
        Collections.sort(all, new Comparator<Report>() {
            @Override public int compare(Report a, Report b) { return a.endAt == b.endAt ? 0 : a.endAt < b.endAt ? 1 : -1; }
        });
        JSONArray rows = new JSONArray();
        try {
            for (int i = Math.max(0, offset); i < all.size() && i < Math.max(0, offset) + Math.max(0, limit); i++) {
                Report r = all.get(i);
                rows.put(r.toJson().put("hasPaths", hasPaths(r.id)));
            }
            return new JSONObject().put("total", all.size()).put("reports", rows).put("stats", stats());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public synchronized Report get(String id) {
        for (Report r : readAll()) if (r.id.equals(id)) return r;
        return null;
    }

    /** {total, bytes, paths:[{path, action}]} of one report, sorted by path. */
    public synchronized JSONObject paths(String id, int offset, int limit) {
        Report rep = get(id);
        List<String> all = new ArrayList<String>();
        File f = new File(pathsDir, id + ".txt");
        if (rep != null && f.isFile()) {
            try {
                for (String l : SdmSettings.read(f).split("\n")) if (!l.isEmpty()) all.add(l);
            } catch (IOException e) {
                // empty
            }
        }
        Collections.sort(all);
        JSONArray rows = new JSONArray();
        try {
            for (int i = Math.max(0, offset); i < all.size() && i < Math.max(0, offset) + Math.max(0, limit); i++) rows.put(new JSONObject().put("path", all.get(i)).put("action", "deleted"));
            return new JSONObject().put("total", all.size()).put("bytes", rep == null ? 0 : rep.bytes).put("paths", rows);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- retention and reset

    public synchronized void prune() {
        prune(System.currentTimeMillis());
    }

    /** Drops reports older than the report retention and path files older than the path retention (or orphaned). */
    public synchronized void prune(long nowMs) {
        long reportCut = nowMs - reportDays() * DAY_MS, pathCut = nowMs - pathDays() * DAY_MS;
        boolean noReports = reportDays() == 0, noPaths = pathDays() == 0;
        List<Report> all = readAll();
        List<Report> keep = new ArrayList<Report>();
        for (Report r : all) {
            if (noReports || r.endAt < reportCut) continue;
            keep.add(r);
        }
        if (keep.size() != all.size()) {
            try {
                StringBuilder sb = new StringBuilder();
                for (Report r : keep) sb.append(r.toJson().toString()).append('\n');
                SdmSettings.write(reports, sb.toString());
            } catch (IOException e) {
                return;
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        File[] files = pathsDir.listFiles();
        if (files != null) {
            java.util.Map<String, Report> byId = new java.util.HashMap<String, Report>();
            for (Report r : keep) byId.put(r.id, r);
            for (File f : files) {
                String n = f.getName();
                String id = n.endsWith(".txt") ? n.substring(0, n.length() - 4) : n;
                Report r = byId.get(id);
                if (r == null || noPaths || r.endAt < pathCut) f.delete();
            }
        }
    }

    /** "Reset all": deletes every report and path and sets the totals to zero. */
    public synchronized void reset() {
        reports.delete();
        File[] files = pathsDir.listFiles();
        if (files != null) for (File f : files) f.delete();
        freed = 0; items = 0;
        saveTotals();
    }
}
