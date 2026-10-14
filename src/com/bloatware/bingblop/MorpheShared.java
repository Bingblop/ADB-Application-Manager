package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * What the Morphe tab shares between every window of the app. A patch runs in the :morphe process and is followed by threads of the app process,
 * and the app process outlives the window that started the patch (Back, a restart of the window, a crash of the WebView). So the things that say
 * "a patch is going" belong to the process, not to the window: the one runner (its engine lock), the one slot, and the events of the patch that is
 * going, which go to the NEWEST window's page and are also kept, so a page that opens meanwhile can be told what has happened so far.
 *
 * Every event of the patch gets a number ({@code seq}). The page drops numbers it has already seen, so the events it is told about and the events
 * that arrive after cannot overlap or leave a gap (see {@link #atomically}).
 */
public final class MorpheShared {
    /** How many log lines are kept for a page that opens late (the page itself shows the last 900). */
    static final int KEEP_LOG = 2000;
    /** How many other events (steps, patches, saved ...) are kept. */
    static final int KEEP_OTHER = 2000;

    private static MorpheShared instance;

    /** The one holder of this process. The launcher is used only by the first call. */
    public static synchronized MorpheShared get(MorpheRunner.Launcher launcher) {
        if (instance == null) instance = new MorpheShared(launcher, MorpheRunner.DEFAULT);
        return instance;
    }

    /** A holder of its own (tests). */
    static MorpheShared create(MorpheRunner.Launcher launcher, MorpheRunner.Timing timing) { return new MorpheShared(launcher, timing); }

    public final MorpheSlot slot = new MorpheSlot();
    public final MorpheRunner runner;

    private final Object lock = new Object();
    private MorpheRunner.EventSink sink;
    private long seq;
    private String job = "";
    private JSONObject request;
    private final LinkedList<JSONObject> logs = new LinkedList<JSONObject>();
    private final LinkedList<JSONObject> others = new LinkedList<JSONObject>();

    private MorpheShared(MorpheRunner.Launcher launcher, MorpheRunner.Timing timing) {
        this.runner = new MorpheRunner(launcher, timing);
    }

    /** From now on the events of the patch go to this sink: the newest window's. */
    public void bind(MorpheRunner.EventSink s) {
        synchronized (lock) { sink = s; }
    }

    /** The window that bound {@code s} is going away: the events are only kept until the next window binds. (A newer window's binding stays.) */
    public void unbind(MorpheRunner.EventSink s) {
        synchronized (lock) { if (sink == s) sink = null; }
    }

    /** A new patch starts: what was kept of the last one is dropped. */
    public void begin(String jobId, JSONObject req) {
        synchronized (lock) {
            job = jobId == null ? "" : jobId;
            request = req;
            logs.clear();
            others.clear();
        }
    }

    /** One event of the patch: numbered, kept and handed to the newest window. */
    public void emit(JSONObject e) {
        synchronized (lock) {
            try { e.put("seq", ++seq); } catch (JSONException ignored) {}
            LinkedList<JSONObject> into = "log".equals(e.optString("t")) ? logs : others;
            into.add(e);
            int cap = into == logs ? KEEP_LOG : KEEP_OTHER;
            while (into.size() > cap) into.removeFirst();
            MorpheRunner.EventSink s = sink;
            if (s != null) s.accept(e);
        }
    }

    /**
     * Runs {@code r} while no event can be numbered or handed on. The answer to "what is going on" is built and posted to the page inside it, so
     * every event is either in the answer or comes after it.
     */
    public void atomically(Runnable r) {
        synchronized (lock) { r.run(); }
    }

    /** {running, job, request, events}: whether a patch is going and, if so, the request that started it and everything kept of it so far, in order. */
    public JSONObject status() throws JSONException {
        synchronized (lock) {
            JSONObject o = new JSONObject();
            String cur = slot.current();
            boolean running = !cur.isEmpty() && cur.equals(job) && request != null;
            o.put("running", running);
            if (!running) return o;
            o.put("job", cur);
            o.put("request", request);
            List<JSONObject> all = new ArrayList<JSONObject>(logs.size() + others.size());
            all.addAll(logs);
            all.addAll(others);
            java.util.Collections.sort(all, new java.util.Comparator<JSONObject>() {
                @Override public int compare(JSONObject a, JSONObject b) { return Long.compare(a.optLong("seq"), b.optLong("seq")); }
            });
            JSONArray ev = new JSONArray();
            for (JSONObject e : all) ev.put(e);
            o.put("events", ev);
            return o;
        }
    }
}
