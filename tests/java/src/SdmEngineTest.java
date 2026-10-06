package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The SD Maid SE task engine and its bridge against fake tools: at most two tasks at a time (the rest "In queue"), cancel in every phase, the result kept
 * in memory and paged, the receipt after a delete, the History written by the task, exclusions with Undo, the settings and the bridge ops
 * (JSON in, JSON out). Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0; the tests are this port's own.
 */
public class SdmEngineTest {
    static int n = 0, fails = 0;
    static void ok(String name) { n++; }
    static void fail(String name, String msg) { n++; fails++; System.out.println("FAIL " + name + " " + msg); }
    static void is(String name, boolean c, Object info) { if (c) ok(name); else fail(name, String.valueOf(info)); }
    static void eq(String name, Object got, Object want) { if (String.valueOf(want).equals(String.valueOf(got))) ok(name); else fail(name, "got <" + got + "> want <" + want + ">"); }

    /** A fake tool: the scan waits for the latch (so the test decides when it ends), then finds `groups` groups of two items. */
    static final class FakeTool implements Sdm.ToolImpl {
        final Sdm.Tool tool;
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger running = new AtomicInteger();
        volatile boolean started;
        volatile int groups = 2;
        FakeTool(Sdm.Tool t) { tool = t; }
        @Override public Sdm.Tool tool() { return tool; }
        @Override public Sdm.Result scan(Sdm.Ctx ctx) throws Exception {
            started = true;
            running.incrementAndGet();
            try {
                ctx.progress.update("Searching", "/some/path", 1, 4, 100);
                while (!release.await(20, TimeUnit.MILLISECONDS)) if (ctx.cancelled()) throw new CancellationException("cancelled");
            } finally { running.decrementAndGet(); }
            return new FakeResult(tool, groups);
        }
        @Override public Sdm.DeleteReport delete(Sdm.Result result, Sdm.Selection sel, Sdm.Ctx ctx) {
            FakeResult r = (FakeResult) result;
            Sdm.DeleteReport rep = new Sdm.DeleteReport();
            for (int g = 0; g < r.groups; g++) {
                if (sel.dropGroups.contains("g" + g)) continue;
                for (int i = 0; i < 2; i++) {
                    String id = "/data/g" + g + "/f" + i;
                    if (sel.dropItems.contains(id)) continue;
                    rep.deleted.add(new Sdm.Deleted(id, 1000, "g" + g, "Group " + g));
                }
            }
            r.removed(rep);
            return rep;
        }
    }

    static final class FakeResult implements Sdm.Result {
        final Sdm.Tool tool;
        int groups;
        final List<String> gone = new ArrayList<String>();
        FakeResult(Sdm.Tool t, int g) { tool = t; groups = g; }
        void removed(Sdm.DeleteReport r) { for (Sdm.Deleted d : r.deleted) gone.add(d.path); }
        @Override public Sdm.Tool tool() { return tool; }
        int live() { int c = 0; for (int g = 0; g < groups; g++) for (int i = 0; i < 2; i++) if (!gone.contains("/data/g" + g + "/f" + i)) c++; return c; }
        @Override public int groupCount() { return groups; }
        @Override public int itemCount() { return live(); }
        @Override public long bytes() { return live() * 1000L; }
        @Override public JSONArray groups(int offset, int limit) throws Exception {
            JSONArray a = new JSONArray();
            for (int g = offset; g < groups && a.length() < limit; g++) a.put(new JSONObject().put("id", "g" + g).put("label", "Group " + g).put("sub", "sub").put("count", 2).put("bytes", 2000));
            return a;
        }
        @Override public JSONArray items(String gid, int offset, int limit) throws Exception {
            JSONArray a = new JSONArray();
            for (int i = 0; i < 2; i++) { String p = "/data/" + gid + "/f" + i; if (!gone.contains(p)) a.put(new JSONObject().put("id", p).put("path", p).put("name", "f" + i).put("size", 1000).put("mtime", 1).put("type", 0)); }
            return a;
        }
        @Override public JSONObject summary() throws Exception { return new JSONObject().put("itemCount", live()).put("bytes", live() * 1000L); }
    }

    static boolean waitFor(java.util.concurrent.Callable<Boolean> c, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.call()) return true; Thread.sleep(15); }
        return c.call();
    }

    static SdmEngine engine(File dir, final List<String> events) {
        SdmEngine.Env env = new SdmEngine.Env() {
            @Override public Sdm.Fs fs() { return null; }
            @Override public Sdm.Areas areas() { return null; }
            @Override public Sdm.Packages pkgs() { return null; }
            @Override public Sdm.Shell shell() { return null; }
            @Override public Sdm.Automation automation() { return null; }
            @Override public File dataDir() { return dir; }
            @Override public long nowSec() { return System.currentTimeMillis() / 1000; }
        };
        SdmEngine.Events ev = new SdmEngine.Events() {
            @Override public void progress(String tool, JSONObject p) { synchronized (events) { events.add("progress:" + tool); } }
            @Override public void state(String tool, JSONObject s) { synchronized (events) { events.add("state:" + tool + ":" + s.optString("state")); } }
            @Override public void done(String tool, JSONObject d) { synchronized (events) { events.add("done:" + tool + ":" + d.optString("kind") + ":" + d.optString("status")); } }
        };
        return new SdmEngine(env, ev);
    }

    static String st(SdmEngine e, Sdm.Tool t) { return e.state(t).optString("state"); }

    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("sdmengine").toFile();
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        SdmEngine e = engine(dir, events);
        e.setTimeoutMs(60000);
        FakeTool sc = new FakeTool(Sdm.Tool.SYSTEMCLEANER), ac = new FakeTool(Sdm.Tool.APPCLEANER), cf = new FakeTool(Sdm.Tool.CORPSEFINDER), dd = new FakeTool(Sdm.Tool.DEDUPLICATOR);
        e.register(sc); e.register(ac); e.register(cf); e.register(dd);
        for (Sdm.Tool t : Sdm.Tool.values()) eq("idle at the start: " + t, st(e, t), "idle");

        // ---- at most two at a time, the others in the queue
        e.scan(Sdm.Tool.SYSTEMCLEANER); e.scan(Sdm.Tool.APPCLEANER); e.scan(Sdm.Tool.CORPSEFINDER); e.scan(Sdm.Tool.DEDUPLICATOR);
        is("two tools start", waitFor(() -> sc.started && ac.started, 3000), "");
        Thread.sleep(150);
        is("the third and the fourth did not start", !cf.started && !dd.started, cf.started + " " + dd.started);
        eq("the first is scanning", st(e, Sdm.Tool.SYSTEMCLEANER), "scanning");
        eq("the third is queued", st(e, Sdm.Tool.CORPSEFINDER), "queued");
        eq("the states say 2 running, 2 queued", e.states().optInt("running") + "/" + e.states().optInt("queued"), "2/2");
        JSONObject pr = e.state(Sdm.Tool.CORPSEFINDER).optJSONObject("progress");
        is("a queued task reports 'In queue'", pr != null && "In queue".equals(pr.optString("primary")) && pr.optBoolean("queued"), pr);
        JSONObject pr1 = e.state(Sdm.Tool.SYSTEMCLEANER).optJSONObject("progress");
        is("a running task reports its step and path", pr1 != null && "Searching".equals(pr1.optString("primary")) && "/some/path".equals(pr1.optString("secondary")), pr1);

        // ---- a cancel of a queued task leaves it without data and lets the next one start
        e.cancel(Sdm.Tool.CORPSEFINDER);
        sc.release.countDown();
        is("the first finishes with data", waitFor(() -> e.hasData(Sdm.Tool.SYSTEMCLEANER), 3000), st(e, Sdm.Tool.SYSTEMCLEANER));
        is("the queued one that was cancelled never ran and has no data", waitFor(() -> !e.busy(Sdm.Tool.CORPSEFINDER), 3000) && !cf.started && !e.hasData(Sdm.Tool.CORPSEFINDER), cf.started);
        is("the fourth took the free place", waitFor(() -> dd.started, 3000), "");
        ac.release.countDown(); dd.release.countDown();
        is("all the others finish", waitFor(() -> e.hasData(Sdm.Tool.APPCLEANER) && e.hasData(Sdm.Tool.DEDUPLICATOR), 3000), "");
        eq("the result lines of a scan", e.state(Sdm.Tool.SYSTEMCLEANER).optJSONObject("summary").optString("primary"), "4 filter matches");
        is("the size line", e.state(Sdm.Tool.SYSTEMCLEANER).optJSONObject("summary").optString("secondary").contains("can be freed"), e.state(Sdm.Tool.SYSTEMCLEANER).optJSONObject("summary"));
        is("done events were sent for the scans", events.contains("done:systemcleaner:scan:ok"), events);

        // ---- the result is paged from memory
        JSONObject g = e.groups(Sdm.Tool.SYSTEMCLEANER, 0, 1);
        eq("groups: total", g.optInt("total"), 2);
        eq("groups: a page of one", g.optJSONArray("groups").length(), 1);
        JSONObject it = e.items(Sdm.Tool.SYSTEMCLEANER, "g1", 0, 10);
        eq("items of a group", it.optJSONArray("items").length(), 2);

        // ---- a cancel while it works leaves nothing
        FakeTool sc2 = new FakeTool(Sdm.Tool.SYSTEMCLEANER);
        e.register(sc2);
        e.scan(Sdm.Tool.SYSTEMCLEANER);
        is("a re-scan has started and dropped the old result", waitFor(() -> sc2.started, 3000) && !e.hasData(Sdm.Tool.SYSTEMCLEANER), "");
        e.cancel(Sdm.Tool.SYSTEMCLEANER);
        is("cancel stops it", waitFor(() -> !e.busy(Sdm.Tool.SYSTEMCLEANER), 3000), "");
        is("a cancelled scan leaves no data", !e.hasData(Sdm.Tool.SYSTEMCLEANER), "");
        is("it says cancelled", events.contains("done:systemcleaner:scan:cancelled"), events);

        // ---- delete: a selection, the receipt, the history
        Sdm.Selection sel = new Sdm.Selection();
        sel.dropGroups.add("g0");
        sel.dropItems.add("/data/g1/f0");
        e.delete(Sdm.Tool.APPCLEANER, sel);
        is("the delete finishes", waitFor(() -> !e.busy(Sdm.Tool.APPCLEANER), 3000), "");
        JSONObject lr = e.state(Sdm.Tool.APPCLEANER).optJSONObject("lastResult");
        is("a receipt is kept", lr != null && "delete".equals(lr.optString("kind")) && "ok".equals(lr.optString("status")), lr);
        eq("only the selected item went (1 of 4)", lr == null ? "" : lr.optString("primary"), "1 expendable item deleted");
        is("the secondary line says how much was freed", lr != null && lr.optString("secondary").startsWith("Freed "), lr);
        eq("the remaining result shrank", e.groups(Sdm.Tool.APPCLEANER, 0, 10).optInt("total"), 2);
        JSONObject h = e.history().list(0, 10);
        is("the delete is in the history", h.optJSONArray("reports") != null && h.optJSONArray("reports").length() == 1 && "appcleaner".equals(h.optJSONArray("reports").getJSONObject(0).optString("tool")), h);
        is("scans were not written", h.optInt("total") == 1, h);

        // ---- exclusions: the item leaves the result at once, Undo brings it back
        JSONObject ex = e.exclude(Sdm.Tool.DEDUPLICATOR, new JSONObject().put("paths", new JSONArray().put("/data/g0/f0")));
        is("exclude answers a handle", ex.optString("handle").length() > 0, ex);
        eq("the excluded item is gone from the result", e.items(Sdm.Tool.DEDUPLICATOR, "g0", 0, 10).optJSONArray("items").length(), 1);
        e.undoExclude(ex.optString("handle"));
        eq("Undo brings it back", e.items(Sdm.Tool.DEDUPLICATOR, "g0", 0, 10).optJSONArray("items").length(), 2);

        // ---- discard
        e.discard(Sdm.Tool.DEDUPLICATOR);
        is("discard forgets the result", !e.hasData(Sdm.Tool.DEDUPLICATOR) && "idle".equals(st(e, Sdm.Tool.DEDUPLICATOR)), st(e, Sdm.Tool.DEDUPLICATOR));

        // ---- one-click: scan and delete in one task
        FakeTool oc = new FakeTool(Sdm.Tool.CORPSEFINDER);
        oc.release.countDown();
        e.register(oc);
        e.oneClick(Sdm.Tool.CORPSEFINDER);
        is("a one-click task finishes", waitFor(() -> !e.busy(Sdm.Tool.CORPSEFINDER) && e.state(Sdm.Tool.CORPSEFINDER).optJSONObject("lastResult") != null, 3000), "");
        JSONObject ol = e.state(Sdm.Tool.CORPSEFINDER).optJSONObject("lastResult");
        is("its receipt is a one-click one that deleted everything", ol != null && "oneclick".equals(ol.optString("kind")) && ol.optInt("count") == 4, ol);
        is("it is in the history", e.history().list(0, 10).optInt("total") == 2, e.history().list(0, 10));

        // ---- the bridge: JSON in, JSON out
        final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        SdmBridge.Host host = new SdmBridge.Host() {
            @Override public Sdm.Fs fs() { return null; }
            @Override public Sdm.Areas areas() { return null; }
            @Override public Sdm.Packages pkgs() { return null; }
            @Override public Sdm.Shell shell() { return null; }
            @Override public Sdm.Automation automation() { return null; }
            @Override public void emit(String json) { sent.add(json); }
            @Override public JSONObject acs(String op, JSONObject a) { try { return new JSONObject().put("enabled", false).put("connected", false).put("consent", "consent".equals(op)); } catch (Exception x) { return null; } }
            @Override public String modeName() { return "standard"; }
            @Override public boolean hasStorageAccess() { return true; }
            @Override public boolean hasUsageAccess() { return false; }
            @Override public File filesDir() { return new File(dir, "files"); }
        };
        SdmBridge b = new SdmBridge(host);
        b.engine().register(new FakeTool(Sdm.Tool.SYSTEMCLEANER));
        JSONObject r = new JSONObject(b.call("state", "{}"));
        is("bridge state: ok with four tools", r.optBoolean("ok") && r.optJSONObject("tools") != null && r.optJSONObject("tools").length() == 4, r);
        r = new JSONObject(b.call("settingsGet", "{\"tool\":\"general\"}"));
        is("bridge settingsGet general has the retention", r.optBoolean("ok") && r.optJSONObject("values").has("retention.reports"), r);
        eq("the default retention of reports is 30 days", r.optJSONObject("values").opt("retention.reports"), 30);
        r = new JSONObject(b.call("settingsSet", "{\"tool\":\"systemcleaner\",\"key\":\"filter.thumbnails.enabled\",\"value\":true}"));
        is("bridge settingsSet answers the values", r.optBoolean("ok") && r.optJSONObject("values").optBoolean("filter.thumbnails.enabled"), r);
        r = new JSONObject(b.call("settingsSet", "{\"tool\":\"systemcleaner\",\"key\":\"filter.thumbnails.enabled\",\"value\":\"nonsense\"}"));
        is("a value of the wrong type is refused in words", !r.optBoolean("ok") && r.optString("error").length() > 5, r);
        r = new JSONObject(b.call("settingsSet", "{\"tool\":\"systemcleaner\",\"key\":\"no.such.key\",\"value\":true}"));
        is("an unknown key is refused", !r.optBoolean("ok"), r);
        SdmBridge b2 = new SdmBridge(host);
        r = new JSONObject(b2.call("settingsGet", "{\"tool\":\"systemcleaner\"}"));
        is("a setting is still there for the next bridge (saved on disk)", r.optJSONObject("values").optBoolean("filter.thumbnails.enabled"), r);
        r = new JSONObject(b.call("exclusions", "{}"));
        is("bridge exclusions: the stock defaults are listed", r.optBoolean("ok") && r.optJSONArray("list").length() >= 3, r);
        r = new JSONObject(b.call("exclusionSave", "{\"exclusion\":{\"kind\":\"path\",\"path\":\"/storage/emulated/0/DCIM\",\"tags\":[\"general\"]}}"));
        is("bridge exclusionSave answers an id", r.optBoolean("ok") && r.optString("id").length() > 0, r);
        r = new JSONObject(b.call("exclusionSave", "{\"exclusion\":{\"kind\":\"path\",\"path\":\"relative/path\",\"tags\":[\"general\"]}}"));
        is("a path that is not absolute is refused", !r.optBoolean("ok"), r);
        r = new JSONObject(b.call("acs", "{\"op\":\"consent\"}"));
        is("bridge acs goes to the host", r.optBoolean("ok") && r.optBoolean("consent"), r);
        r = new JSONObject(b.call("no.such.op", "{}"));
        is("an unknown op is refused without an exception", !r.optBoolean("ok"), r);
        r = new JSONObject(b.call("scan", "{\"tool\":\"nonsense\"}"));
        is("an unknown tool is refused", !r.optBoolean("ok"), r);
        r = new JSONObject(b.call("historyList", "{\"offset\":0,\"limit\":10}"));
        is("bridge historyList", r.optBoolean("ok") && r.has("reports") && r.has("stats"), r);
        b.shutdown();
        b2.shutdown();
        e.shutdown();
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " SdmEngineTest: " + n + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
