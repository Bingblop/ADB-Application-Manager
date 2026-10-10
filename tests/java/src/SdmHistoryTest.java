package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The history of finished deletions: success / partial / failure rows, newest first with paging, the affected paths (sorted, paged), the totals (a failure adds
 * nothing), the retention rules (reports 30 days, paths 7 days, 0 = not kept, pruned after every report and when a setting changes) against a fake clock, reset,
 * the files surviving a restart and a half written line.
 */
public class SdmHistoryTest {
    static int fails = 0, n = 0;
    static void eq(String what, Object got, Object want) { n++; boolean same = want == null ? got == null : want.equals(got); if (!same) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }
    static void is(String what, boolean got, boolean want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got " + got + " want " + want); } }

    static final long DAY = 86400000L, T0 = 1700000000000L;

    static SdmHistory.Report rep(String tool, String status, long at, long count, long bytes, String primary, String error) {
        SdmHistory.Report r = new SdmHistory.Report();
        r.tool = tool; r.status = status; r.startAt = at - 1000; r.endAt = at; r.count = count; r.bytes = bytes; r.primary = primary; r.secondary = "Freed " + bytes + " B"; r.error = error;
        return r;
    }

    static List<String> ids(JSONObject list) throws Exception {
        List<String> out = new ArrayList<String>();
        JSONArray a = list.getJSONArray("reports");
        for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i).getString("id"));
        return out;
    }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("sdmhist").toFile();
        SdmSettings st = new SdmSettings(dir);
        SdmHistory h = new SdmHistory(dir, st);

        eq("empty list", h.list(0, 10).getInt("total"), 0);
        eq("empty totals", h.stats().getLong("freedBytes"), 0L);

        SdmHistory.Report a = h.add(rep("systemcleaner", SdmHistory.SUCCESS, T0, 3, 3000, "3 matches deleted", ""), Arrays.asList("/b/two", "/a/one", "/c/three"));
        SdmHistory.Report b = h.add(rep("appcleaner", SdmHistory.PARTIAL, T0 + 1000, 2, 500, "2 expendable items deleted", "Cancelled"), Arrays.asList("/x/p"));
        SdmHistory.Report c = h.add(rep("corpsefinder", SdmHistory.FAILURE, T0 + 2000, 0, 0, "", "Can't access /data."), new ArrayList<String>());
        is("ids are assigned", a.id != null && !a.id.isEmpty() && !a.id.equals(b.id), true);

        JSONObject l = h.list(0, 10);
        eq("three rows", l.getInt("total"), 3);
        eq("newest first", ids(l).toString(), "[" + c.id + ", " + b.id + ", " + a.id + "]");
        JSONObject r0 = l.getJSONArray("reports").getJSONObject(0), r2 = l.getJSONArray("reports").getJSONObject(2);
        eq("failure row", r0.getString("status") + "|" + r0.getString("error") + "|" + r0.getString("tool"), "failure|Can't access /data.|corpsefinder");
        eq("success row", r2.getString("status") + "|" + r2.getString("primary") + "|" + r2.getString("secondary") + "|" + r2.getLong("count") + "|" + r2.getLong("bytes"), "success|3 matches deleted|Freed 3000 B|3|3000");
        eq("partial row", l.getJSONArray("reports").getJSONObject(1).getString("status"), "partial");
        eq("hasPaths", r2.getBoolean("hasPaths") + "/" + r0.getBoolean("hasPaths"), "true/false");
        eq("paging: offset", ids(h.list(1, 1)).toString(), "[" + b.id + "]");
        eq("paging: total stays", h.list(1, 1).getInt("total"), 3);
        eq("paging: past the end", h.list(5, 5).getJSONArray("reports").length(), 0);

        JSONObject stats = h.stats();
        eq("totals: space (success + partial)", stats.getLong("freedBytes"), 3500L);
        eq("totals: items", stats.getLong("items"), 5L);
        is("since is set", stats.getLong("sinceMs") > 0, true);

        JSONObject p = h.paths(a.id, 0, 10);
        eq("paths total", p.getInt("total"), 3);
        eq("paths bytes (the report's)", p.getLong("bytes"), 3000L);
        eq("paths are sorted", p.getJSONArray("paths").getJSONObject(0).getString("path") + p.getJSONArray("paths").getJSONObject(2).getString("path"), "/a/one/c/three");
        eq("path action", p.getJSONArray("paths").getJSONObject(1).getString("action"), "deleted");
        eq("paths paging", h.paths(a.id, 2, 5).getJSONArray("paths").length(), 1);
        eq("unknown report", h.paths("nope", 0, 5).getInt("total"), 0);
        eq("get", h.get(b.id).primary, "2 expendable items deleted");

        // a path with a newline cannot be stored (one path per line) and is left out
        SdmHistory.Report nl = h.add(rep("systemcleaner", SdmHistory.SUCCESS, T0 + 3000, 2, 10, "x", ""), Arrays.asList("/ok", "/bad\nname"));
        eq("newline path is left out", h.paths(nl.id, 0, 10).getInt("total"), 1);

        // ---------- survives a restart, a half written line does not hurt
        SdmHistory h2 = new SdmHistory(dir, new SdmSettings(dir));
        eq("after a restart", h2.list(0, 10).getInt("total"), 4);
        eq("totals after a restart", h2.stats().getLong("freedBytes"), 3510L);
        FileOutputStream o = new FileOutputStream(new File(new File(dir, "history"), "reports.jsonl"), true);
        o.write("{\"id\":\"half".getBytes("UTF-8"));
        o.close();
        eq("a half written line is skipped", h2.list(0, 10).getInt("total"), 4);
        SdmHistory.Report after = h2.add(rep("deduplicator", SdmHistory.SUCCESS, T0 + 4000, 1, 5, "1 duplicate deleted", ""), Arrays.asList("/d/1"));
        eq("and the next report is not glued to it", h2.list(0, 10).getInt("total"), 5);
        eq("new row there", h2.get(after.id).tool, "deduplicator");

        // ---------- retention: reports 30 days, paths 7 days (the fake clock is the report's end time)
        File dir2 = Files.createTempDirectory("sdmhist2").toFile();
        SdmSettings st2 = new SdmSettings(dir2);
        SdmHistory r = new SdmHistory(dir2, st2);
        eq("default retention", st2.retentionReportDays() + "/" + st2.retentionPathDays(), "30/7");
        SdmHistory.Report old = r.add(rep("systemcleaner", SdmHistory.SUCCESS, T0, 1, 1, "old", ""), Arrays.asList("/old/path"));
        SdmHistory.Report mid = r.add(rep("systemcleaner", SdmHistory.SUCCESS, T0 + 10 * DAY, 1, 1, "mid", ""), Arrays.asList("/mid/path"));
        eq("10 days later: both rows, the old one lost its paths (older than 7 days)", r.list(0, 10).getInt("total") + "/" + r.get(old.id).primary, "2/old");
        eq("old paths pruned", r.paths(old.id, 0, 5).getInt("total"), 0);
        eq("mid paths kept", r.paths(mid.id, 0, 5).getInt("total"), 1);
        SdmHistory.Report now = r.add(rep("systemcleaner", SdmHistory.SUCCESS, T0 + 31 * DAY, 1, 1, "now", ""), Arrays.asList("/now/path"));
        eq("31 days: the first row is gone, the 21 day old one stays", ids(r.list(0, 10)).toString(), "[" + now.id + ", " + mid.id + "]");
        eq("mid lost its paths by now", r.paths(mid.id, 0, 5).getInt("total"), 0);
        eq("hasPaths follows", r.list(0, 10).getJSONArray("reports").getJSONObject(0).getBoolean("hasPaths") + "/" + r.list(0, 10).getJSONArray("reports").getJSONObject(1).getBoolean("hasPaths"), "true/false");
        eq("totals keep counting what expired", r.stats().getLong("items"), 3L);

        // a changed retention applies when pruning
        st2.set(SdmSettings.GENERAL, "retention.reports", 5);
        r.prune(T0 + 40 * DAY);
        eq("retention 5 days: everything older is gone", r.list(0, 10).getInt("total"), 0);
        st2.set(SdmSettings.GENERAL, "retention.reports", 30);

        // 0 days for paths: not stored at all
        st2.set(SdmSettings.GENERAL, "retention.paths", 0);
        SdmHistory.Report np = r.add(rep("systemcleaner", SdmHistory.SUCCESS, T0 + 50 * DAY, 1, 1, "np", ""), Arrays.asList("/never"));
        eq("paths 0 = not stored", r.paths(np.id, 0, 5).getInt("total") + "/" + r.list(0, 5).getJSONArray("reports").getJSONObject(0).getBoolean("hasPaths"), "0/false");
        st2.set(SdmSettings.GENERAL, "retention.paths", 7);
        boolean threw = false;
        try { st2.set(SdmSettings.GENERAL, "retention.reports", 400); } catch (IllegalArgumentException e) { threw = true; }
        is("retention above 365 days is refused", threw, true);
        // 0 days for reports: nothing is kept
        st2.set(SdmSettings.GENERAL, "retention.reports", 0);
        r.add(rep("systemcleaner", SdmHistory.SUCCESS, T0 + 51 * DAY, 1, 1, "z", ""), Arrays.asList("/z"));
        eq("reports 0 = no rows kept", r.list(0, 5).getInt("total"), 0);
        st2.set(SdmSettings.GENERAL, "retention.reports", 30);

        // ---------- reset
        h2.reset();
        eq("reset: no reports", h2.list(0, 10).getInt("total"), 0);
        eq("reset: totals are zero", h2.stats().getLong("freedBytes") + "/" + h2.stats().getLong("items"), "0/0");
        File[] left = new File(new File(dir, "history"), "paths").listFiles();
        eq("reset: no path files", left == null ? 0 : left.length, 0);
        eq("reset: since is kept", h2.stats().getLong("sinceMs") > 0, true);
        SdmHistory h3 = new SdmHistory(dir, new SdmSettings(dir));
        eq("reset persists", h3.list(0, 10).getInt("total") + "/" + h3.stats().getLong("freedBytes"), "0/0");

        System.out.println((fails == 0 ? "PASS" : "FAIL") + " SdmHistoryTest: " + n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
