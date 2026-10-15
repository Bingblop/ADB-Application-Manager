package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A patch outlives the window that started it (Back, a restart of the window, a crash of the WebView). The runner, the slot and the events of the
 * patch that is going belong to the process: a second window attaches to them, its page is told what has happened so far, and the patch's
 * events and result go to the newest window.
 */
public class MorpheSharedTest {
    static int failed;

    static void check(String name, boolean ok, String extra) {
        if (ok) System.out.println("ok   " + name);
        else { failed++; System.out.println("FAIL " + name + (extra == null ? "" : " " + extra)); }
    }

    static class Collect implements MorpheRunner.EventSink {
        final List<JSONObject> got = Collections.synchronizedList(new ArrayList<JSONObject>());
        @Override public void accept(JSONObject e) { got.add(e); }
    }

    static final MorpheRunner.Launcher NOOP = new MorpheRunner.Launcher() {
        @Override public boolean start(String cmd, File job, File events, String title) { return true; }
        @Override public void cancel(File events) {}
        @Override public boolean alive() { return false; }
    };

    static JSONObject ev(String t, String k, String v) throws Exception { return new JSONObject().put("t", t).put(k, v); }

    public static void main(String[] args) throws Exception {
        // one holder per process: two bridges (two windows) get the same runner and slot
        MorpheShared a = MorpheShared.get(NOOP), b = MorpheShared.get(NOOP);
        check("every window gets the same holder, so the same runner and the same slot", a == b && a.runner == b.runner && a.slot == b.slot, null);
        check("a patch the first window started keeps the slot busy for the second", a.slot.tryStart("j1") && !b.slot.tryStart("j2") && b.slot.current().equals("j1"), null);
        a.slot.finish("j1");

        // the events go to the newest window
        MorpheShared s = MorpheShared.create(NOOP, MorpheRunner.DEFAULT);
        Collect w1 = new Collect(), w2 = new Collect();
        s.slot.tryStart("job1");
        s.begin("job1", new JSONObject().put("pkg", "com.x").put("name", "X"));
        s.bind(w1);
        s.emit(ev("step", "name", "Preparing"));
        s.emit(ev("log", "text", "one"));
        s.bind(w2);                                                    // the window was recreated: its bridge binds
        s.emit(ev("log", "text", "two"));
        s.emit(new JSONObject().put("t", "result").put("result", new JSONObject().put("success", true)));
        check("before the second window the first one gets the events", w1.got.size() == 2 && "one".equals(w1.got.get(1).optString("text")), w1.got.toString());
        check("after it, the patch's events and its result go to the newest window only", w2.got.size() == 2 && "two".equals(w2.got.get(0).optString("text")) && "result".equals(w2.got.get(1).optString("t")), w2.got.toString());
        check("every event is numbered, in order", w1.got.get(0).optLong("seq") == 1 && w2.got.get(1).optLong("seq") == 4, w2.got.toString());

        // a window that goes away stops being pointed at, a newer one stays
        MorpheShared u = MorpheShared.create(NOOP, MorpheRunner.DEFAULT);
        Collect old1 = new Collect(), new1 = new Collect();
        u.bind(old1);
        u.unbind(old1);
        u.emit(ev("log", "text", "nobody"));
        check("after the window is released nothing is delivered (it is only kept)", old1.got.isEmpty() && u.status().length() >= 0, old1.got.toString());
        u.bind(old1); u.bind(new1); u.unbind(old1);
        u.emit(ev("log", "text", "newest"));
        check("an old window's release does not unbind the newer window", new1.got.size() == 1 && old1.got.isEmpty(), new1.got.toString());

        // a page that opens late is told what has happened so far
        JSONObject st = s.status();
        check("the status of a patch that is going names the job and the request that started it", st.optBoolean("running") && "job1".equals(st.optString("job")) && "com.x".equals(st.getJSONObject("request").optString("pkg")), st.toString());
        JSONArray ev = st.getJSONArray("events");
        check("... and carries every event so far, in order", ev.length() == 4 && ev.getJSONObject(0).optString("t").equals("step") && ev.getJSONObject(3).optString("t").equals("result"), ev.toString());
        s.slot.finish("job1");
        check("a patch that has ended is not reported as going", !s.status().optBoolean("running") && !s.status().has("events"), s.status().toString());
        check("nothing going before any patch: not running", !MorpheShared.create(NOOP, MorpheRunner.DEFAULT).status().optBoolean("running"), null);

        // the log is capped, the other events are not lost to it, and a new patch starts clean
        MorpheShared c = MorpheShared.create(NOOP, MorpheRunner.DEFAULT);
        c.slot.tryStart("big");
        c.begin("big", new JSONObject());
        c.emit(ev("step", "name", "Patching"));
        for (int i = 0; i < MorpheShared.KEEP_LOG + 500; i++) c.emit(ev("log", "text", "l" + i));
        JSONArray big = c.status().getJSONArray("events");
        check("a long log keeps its last lines and the steps", big.length() == MorpheShared.KEEP_LOG + 1 && big.getJSONObject(0).optString("t").equals("step") && big.getJSONObject(big.length() - 1).optString("text").equals("l" + (MorpheShared.KEEP_LOG + 499)), "len=" + big.length());
        c.slot.finish("big");
        c.slot.tryStart("next");
        c.begin("next", new JSONObject().put("pkg", "y"));
        check("a new patch starts with nothing of the old one", c.status().getJSONArray("events").length() == 0 && "y".equals(c.status().getJSONObject("request").optString("pkg")), c.status().toString());
        c.slot.finish("next");

        // no gap, no overlap: a page that asks while events are being emitted gets each event exactly once
        final MorpheShared d = MorpheShared.create(NOOP, MorpheRunner.DEFAULT);
        d.slot.tryStart("race");
        d.begin("race", new JSONObject());
        final List<Long> pageSeen = Collections.synchronizedList(new ArrayList<Long>());   // what the page ends up with, after its own "drop numbers I have seen" rule
        final long[] last = { 0 };
        final AtomicBoolean adopted = new AtomicBoolean();
        d.bind(new MorpheRunner.EventSink() { @Override public void accept(JSONObject e) {
            if (!adopted.get()) return;                                         // the page is not looking yet: it drops events
            long n = e.optLong("seq");
            if (n <= last[0]) return;
            last[0] = n; pageSeen.add(n);
        } });
        final int total = 1800;
        final java.util.concurrent.atomic.AtomicInteger emitted = new java.util.concurrent.atomic.AtomicInteger();
        Thread producer = new Thread(new Runnable() { @Override public void run() {
            try { for (int i = 0; i < total; i++) { d.emit(ev("patch", "name", "p" + i)); emitted.incrementAndGet(); Thread.yield(); } } catch (Exception ignored) {}
        } });
        producer.start();
        while (emitted.get() < 500) Thread.sleep(0, 100000);
        d.atomically(new Runnable() { @Override public void run() {
            try {
                JSONArray snap = d.status().getJSONArray("events");
                for (int i = 0; i < snap.length(); i++) { long n = snap.getJSONObject(i).optLong("seq"); if (n > last[0]) { last[0] = n; pageSeen.add(n); } }
                adopted.set(true);
            } catch (Exception ignored) {}
        } });
        producer.join();
        boolean contiguous = pageSeen.size() == total;
        for (int i = 0; i < pageSeen.size() && contiguous; i++) if (pageSeen.get(i) != i + 1) contiguous = false;
        check("the events a late page is told about and the ones after join with no gap and no repeat", contiguous, "got " + pageSeen.size() + " of " + total);

        // MOR-1: the window a patch that is going asks for the installer. Window A starts the patch; A is recreated as B; B's page has not made a
        // Morphe call yet (so B has no bridge). Before: the newest bridge was A's, A's release() left "no newest", and the install fell back to A's
        // destroyed window, whose question is a silent No.
        MorpheShared.Windows<String> w = new MorpheShared.Windows<String>();
        check("no window open: nobody to ask", w.newest() == null, null);
        w.opened("A");
        check("the window that started the patch is asked while it is the only one", "A".equals(w.newest()), w.newest());
        w.opened("B");                                                                // B's onCreate, before its page makes any Morphe call
        w.closed("A");                                                                // A's onDestroy (its bridge's release())
        check("A recreated as B, B has made no Morphe call: B is asked, not the destroyed A", "B".equals(w.newest()), w.newest());
        w.opened("A");
        w.opened("B");
        check("a window that registers again (its bridge is made later) keeps its place", "A".equals(w.newest()), w.newest());
        w.closed("A");
        w.opened("C"); w.closed("C");
        check("the newest window closes while an older one is open: the older one is asked", "B".equals(w.newest()), w.newest());
        w.closed("B");
        check("every window closed: nobody (the caller then uses its own)", w.newest() == null, null);

        File src = null;
        for (File dd = new File(System.getProperty("user.dir")).getAbsoluteFile(); dd != null && src == null; dd = dd.getParentFile()) { File f = new File(dd, "src/com/bloatware/bingblop/MorpheBridge.java"); if (f.isFile()) src = f; }
        check("the bridge source was found", src != null, null);
        if (src != null) {
            String m = new String(java.nio.file.Files.readAllBytes(src.toPath()), "UTF-8");
            check("the bridge takes its runner and slot from the process-wide holder, not its own", m.contains("MorpheShared.get(") && m.contains("this.runner = shared.runner") && m.contains("this.slot = shared.slot") && !m.contains("new MorpheRunner(") && !m.contains("new MorpheSlot("), null);
            check("a new bridge binds itself as the sink for the patch's events", m.contains("shared.bind("), null);
            check("the patch's events are numbered and kept through the holder", m.contains("shared.emit(e)") && m.contains("shared.begin(jobId, a)"), null);
            check("the run's install goes to the newest open window (MOR-1)", m.contains("Host asks = windows.newest();") && m.contains("(asks != null ? asks : host).install(") && !m.contains("MorpheBridge latest"), null);
            check("a bridge registers its window and release() takes it out", m.contains("windows.opened(host)") && m.contains("windows.closed(host)") && m.contains("public static void windowOpened(Host h)") && m.contains("public static void windowClosed(Host h)"), null);
            int js = m.indexOf("case \"jobStatus\":");
            String jb = js < 0 ? "" : m.substring(js, Math.min(m.length(), js + 900));
            check("the status answer is built and posted inside the holder's lock (no event can slip between)", jb.contains("shared.atomically(") && jb.contains("shared.status()") && jb.contains("NO_REPLY"), jb);
            String main = new String(java.nio.file.Files.readAllBytes(new File(src.getParentFile(), "MainActivity.java").toPath()), "UTF-8");
            int od = main.indexOf("protected void onDestroy()");
            check("the window releases its bridge when it is destroyed (the holder must not keep it alive)", od > 0 && main.substring(od, od + 600).contains("mb.release()") && m.contains("public void release()") && m.contains("shared.unbind(sink)"), null);
            int oc = main.indexOf("protected void onCreate(Bundle"), hl = main.indexOf("if (isHeadless()) {", oc), wo = main.indexOf("MorpheBridge.windowOpened(morpheHost())", oc), oe = main.indexOf("\n    }\n", oc);
            check("onCreate names the window as the newest one for Morphe before its page loads, not for a headless start (MOR-1)", oc > 0 && hl > oc && wo > hl && wo < oe, "onCreate=" + oc + " headless=" + hl + " opened=" + wo);
            check("onDestroy takes the window out even when it never made its bridge", od > 0 && main.substring(od, od + 900).contains("MorpheBridge.windowClosed(mh)") && main.contains("private synchronized MorpheBridge.Host morpheHost()") && main.contains("new MorpheBridge(morpheHost(),"), null);
            check("the launcher the process keeps is a static class with the application context only (an inner class would keep the first window alive)", m.contains("getApplicationContext()") && m.contains("private static final class ServiceLauncher") && m.contains("MorpheShared.get(new ServiceLauncher(app))") && !m.contains("MorpheService.start(host.context()"), null);
        }
        if (failed > 0) { System.out.println(failed + " FAILED"); System.exit(1); }
        System.out.println("all passed");
    }
}
