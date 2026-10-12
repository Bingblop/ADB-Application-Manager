package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engine of the Updates tab's check. One instance is one run: {@link #check} looks at every app against every source at the same time on one
 * shared pool of workers, tells the listener about each update as it is found, and returns a summary in which no app is missing.
 *
 * <ul>
 * <li><b>One pool, all sources at once.</b> This app's own releases, the sources a person set for an app, the Obtainium catalog, IzzyOnDroid, F-Droid,
 * GitHub, Codeberg and the Galaxy Store are all worked off by the same 8 threads, not one source after the other.</li>
 * <li><b>Short waits.</b> The network handed in is expected to give up after seconds (the app uses 5 s to connect and 8 s to read), not after the
 * 15 and 30 seconds a download is allowed.</li>
 * <li><b>A deadline.</b> After 90 seconds (the default) the run stops; every app that was not finished is in {@code failed} with the reason
 * "timed out" and counted in {@code timedOut}. Nothing is dropped without a word.</li>
 * <li><b>A circuit breaker per host.</b> Three failures in a row (a timeout, no route, a reset) and the host is left alone for the rest of the run;
 * it is named once in {@code hostsDown} and an app that needed it is "unreachable", instead of one slow error per app.</li>
 * <li><b>No internet.</b> If the first requests to several hosts all fail and none was answered, the run stops early and says so ({@code offline}).</li>
 * <li><b>Cancel</b> is cooperative and prompt: {@link #cancel} ends the wait at once; requests still in the air are not waited for and their answers are
 * thrown away.</li>
 * <li><b>Fewer requests.</b> The Obtainium catalog is read once (a few pages, kept 24 hours) and looked up locally; every answer is kept in the
 * {@link UpdateCache} (30 minutes for a result, 7 days for "nobody knows this app") unless the person asks to check again.</li>
 * </ul>
 *
 * No Android classes: the tests run it against a fake network and a fake clock.
 */
public final class UpdateChecker {

    public interface Clock {
        long now();
    }

    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };

    /** Asked once, by the first Galaxy Store check (reading the phone's region needs a few process calls). */
    public interface DeviceProvider {
        UpdateManager.Device get() throws Exception;
    }

    /** Called from the worker threads. */
    public interface Listener {
        /** An update row (the shape the Updates tab has always had), the moment it is found or read from the saved results. */
        void onUpdate(JSONObject row);

        /** {done, total, phase, updates, fromCache}: done and total count apps across all sources. */
        void onProgress(JSONObject progress);
    }

    /** One installed app and which kinds of check apply to it. */
    public static final class App {
        public final String pkg, name, versionName, installer;
        public final long versionCode;
        public final boolean system;
        /** Open-source sources (your own, the Obtainium catalog, IzzyOnDroid, F-Droid, GitHub, Codeberg). */
        public final boolean openSource;
        /** The Galaxy Store. */
        public final boolean galaxy;

        public App(String pkg, String name, String versionName, long versionCode, String installer, boolean system, boolean openSource, boolean galaxy) {
            this.pkg = pkg;
            this.name = name == null || name.isEmpty() ? pkg : name;
            this.versionName = versionName == null || versionName.isEmpty() ? String.valueOf(versionCode) : versionName;
            this.versionCode = versionCode;
            this.installer = installer;
            this.system = system;
            this.openSource = openSource;
            this.galaxy = galaxy;
        }
    }

    /**
     * Numbers the runs of one owner (the activity): a run takes the next number when it starts, and a callback of a run that is no longer the latest
     * is dropped, so a straggler of an old check can never put a row into what a newer check has just cleared.
     */
    public static final class Generation {
        private final AtomicInteger n = new AtomicInteger();

        int begin() {
            return n.incrementAndGet();
        }

        boolean current(int mine) {
            return n.get() == mine;
        }
    }

    public static final class Options {
        /** Shared by the runs of one owner; null: this run is the only one. */
        public Generation generation;
        /** After this long the run ends and what is not finished is reported as timed out. */
        public long deadlineMs = 90000;
        public int threads = 8;
        /** Ignore the saved results (what is found is still saved). */
        public boolean force = false;
        /** Read the Obtainium catalog again even if the saved copy is under 24 hours old. */
        public boolean refreshCatalog = false;
        /** Check only these packages (a retry of the failed ones); null: every app handed in. */
        public Set<String> only = null;
        /** The sources a person set for apps: {pkg: {url, origin, settings}}. */
        public JSONObject sources = new JSONObject();
        public String githubToken = "";
        public String[] abis = new String[]{"arm64-v8a"};
        public DeviceProvider device;
        /** What the saved Galaxy Store answers depend on (the phone model); another value is a miss. */
        public String galaxyTag = "";
        /** This app, to look for its own newer release; null: not checked. */
        public App self;
    }

    public static final class Result {
        public final JSONObject summary;

        Result(JSONObject summary) {
            this.summary = summary;
        }
    }

    // ------------------------------------------------------------------------------------------------

    public static final int BREAKER_FAILURES = 3;
    private static final String LANE_OS = "os", LANE_GX = "gx", LANE_SELF = "self";
    /** How long the end of a run waits for commits that are still saving or calling the listener. */
    private static final long BARRIER_MAX_NANOS = 5_000_000_000L;

    private final UpdateManager.Http net;
    private final Clock clock;
    private final UpdateCache cache;

    private volatile boolean cancelled;
    private volatile boolean expired;
    private volatile boolean offline;
    private volatile boolean closed;                         // the run is over: late answers are thrown away
    private final Object lock = new Object();

    // per run
    private Options opts;
    private Listener listener;
    private Guard guard;
    private final Map<String, JSONObject> updates = new LinkedHashMap<String, JSONObject>();
    private final List<JSONObject> untracked = new ArrayList<JSONObject>();
    private final List<JSONObject> external = new ArrayList<JSONObject>();
    private final Map<String, JSONObject> failed = new LinkedHashMap<String, JSONObject>();
    private final Set<String> finished = new HashSet<String>();
    private int total, osTotal, gxTotal, upToDate, fromCache;
    private int completed;                                   // jobs whose result is in AND whose side effects (saved answer, listener) are done; guarded by lock
    private int inFlight;                                    // commits that have registered a result and are still saving / telling the listener; guarded by lock
    private int myGeneration;
    private volatile boolean abandoned;                      // the barrier gave up waiting for a stuck listener: nothing more is delivered
    private String lastError = "";
    private String selfStatus = "";
    private long lastProgressAt;
    private JSONObject catalog;                              // null: not loaded (the per-app files are used)
    private boolean catalogFromIndex;
    private UpdateManager.Device deviceMemo;
    private final AtomicBoolean githubApiLimited = new AtomicBoolean();

    public UpdateChecker(UpdateManager.Http net, Clock clock, UpdateCache cache) {
        this.net = net;
        this.clock = clock;
        this.cache = cache;
    }

    /** Ends the run as soon as possible; what was found so far is kept. */
    public void cancel() {
        cancelled = true;
    }

    // ------------------------------------------------------------------------------------------------
    // Network guard: the breaker, the offline test, the stop flags
    // ------------------------------------------------------------------------------------------------

    /** Ends a worker quietly: the run is over (cancelled, out of time, offline). */
    private static final class Stop extends RuntimeException {
        Stop() {
            super("stopped", null, false, false);
        }
    }

    /** A failure of the network itself (not an answer of the server); {@code host} is the one that failed. */
    static class NetFailure extends IOException {
        final String host;

        NetFailure(String host, Throwable cause) {
            super(cause == null ? host + " is unreachable" : String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()), cause);
            this.host = host;
        }
    }

    /** The breaker for this host is open: no request was made. */
    static final class HostDown extends NetFailure {
        HostDown(String host) {
            super(host, null);
        }
    }

    private static final class HostState {
        final AtomicInteger consecutive = new AtomicInteger();
        volatile boolean down;
        volatile boolean opened;                  // the host has answered once: no limit on parallel requests any more
        int probing;                              // requests in the air to a host that has not answered yet; guarded by this
    }

    private final class Guard implements UpdateManager.Http {
        final UpdateManager.Http inner;
        final ConcurrentHashMap<String, HostState> hosts = new ConcurrentHashMap<String, HostState>();
        final Set<String> hostsDown = Collections.synchronizedSet(new LinkedHashSet<String>());
        final Set<String> failedHosts = Collections.synchronizedSet(new HashSet<String>());
        final AtomicInteger answers = new AtomicInteger(), netFailures = new AtomicInteger(), requests = new AtomicInteger();

        Guard(UpdateManager.Http inner) {
            this.inner = inner;
        }

        private void stopIfOver() {
            if (cancelled || expired || offline || closed || Thread.currentThread().isInterrupted()) throw new Stop();
        }

        /**
         * Lets a request go. A host that has not answered yet gets at most BREAKER_FAILURES requests in all: one that is in the air or has already failed uses up
         * one of them, so eight workers cannot all go to a dead host before the first failure is seen. The others wait; an answer opens the host for everyone,
         * the third failure marks it down and sends the waiting ones away without a request. Returns whether a probe slot was taken; the caller must call
         * {@link #release} for it.
         */
        private boolean admit(String host, HostState st) throws IOException {
            stopIfOver();
            if (st.opened) return false;
            synchronized (st) {
                while (true) {
                    stopIfOver();
                    if (st.down) throw new HostDown(host);
                    if (st.opened) return false;
                    if (st.probing + st.consecutive.get() < BREAKER_FAILURES) {
                        st.probing++;
                        return true;
                    }
                    try {
                        st.wait(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Stop();
                    }
                }
            }
        }

        private void release(HostState st, boolean probe) {
            if (!probe) return;
            synchronized (st) {
                st.probing--;
                st.notifyAll();
            }
        }

        private HostState stateOf(String host) {
            HostState st = hosts.get(host);
            if (st == null) {
                HostState fresh = new HostState();
                st = hosts.putIfAbsent(host, fresh);
                if (st == null) st = fresh;
            }
            return st;
        }

        private void answered(String host) {
            HostState st = stateOf(host);
            synchronized (st) {
                st.consecutive.set(0);
                st.opened = true;
                st.notifyAll();
            }
            answers.incrementAndGet();
        }

        private NetFailure failedWith(String host, Throwable e) {
            netFailures.incrementAndGet();
            failedHosts.add(host);
            HostState st = stateOf(host);
            synchronized (st) {
                if (st.consecutive.incrementAndGet() >= BREAKER_FAILURES && !st.down) {
                    st.down = true;
                    hostsDown.add(host);
                }
                st.notifyAll();
            }
            // nothing was answered anywhere, and several hosts and requests have failed: the phone has no internet
            int hostsFailed = failedHosts.size(), fails = netFailures.get();
            if (answers.get() == 0 && (hostsFailed >= 3 || (hostsFailed >= 2 && fails >= 6))) offline = true;
            return e instanceof NetFailure ? (NetFailure) e : new NetFailure(host, e);
        }

        @Override
        public byte[] get(String url, String accept, String bearer, int maxBytes) throws Exception {
            String host = hostOf(url);
            HostState st = stateOf(host);
            boolean probe = admit(host, st);
            requests.incrementAndGet();
            try {
                byte[] b = inner.get(url, accept, bearer, maxBytes);
                answered(host);
                return b;
            } catch (IllegalStateException e) {                       // the server answered, with an error status
                answered(host);
                String m = String.valueOf(e.getMessage());
                if ("api.github.com".equals(host) && UpdateManager.isRateLimited(m)) githubApiLimited.set(true);
                throw e;
            } catch (IOException e) {
                throw failedWith(host, e);
            } finally {
                release(st, probe);
            }
        }

        @Override
        public String redirectLocation(String url) throws Exception {
            String host = hostOf(url);
            HostState st = stateOf(host);
            boolean probe = admit(host, st);
            requests.incrementAndGet();
            try {
                String r = inner.redirectLocation(url);
                answered(host);
                return r;
            } catch (IllegalStateException e) {
                answered(host);
                throw e;
            } catch (IOException e) {
                throw failedWith(host, e);
            } finally {
                release(st, probe);
            }
        }
    }

    static String hostOf(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "?" : h.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------------------------------------

    /** What one lane (the open-source sources, or the Galaxy Store) found for one app. */
    private static final class Outcome {
        final String kind;
        final JSONObject row;

        Outcome(String kind, JSONObject row) {
            this.kind = kind;
            this.row = row;
        }
    }

    private static final class Job {
        final App app;
        Outcome os, gx;
        boolean osNeeded, gxNeeded;
        Failure osErr, gxErr;
        boolean osFresh, gxFresh;                    // answered by the network in this run (not read from the saved results)

        Job(App app) {
            this.app = app;
            this.osNeeded = app.openSource;
            this.gxNeeded = app.galaxy;
        }
    }

    /** Why an app was not checked. */
    private static final class Failure {
        String code, reason, host = "", lane;

        Failure(String code, String reason, String host, String lane) {
            this.code = code;
            this.reason = reason;
            this.host = host == null ? "" : host;
            this.lane = lane;
        }
    }

    private Failure classify(Throwable t, String lane) {
        if (t instanceof HostDown) return new Failure("host", ((HostDown) t).host + " unreachable", ((HostDown) t).host, lane);
        if (t instanceof NetFailure) {
            NetFailure nf = (NetFailure) t;
            Throwable c = nf.getCause() != null ? nf.getCause() : nf;
            if (c instanceof SocketTimeoutException) return new Failure("timeout", "timed out", nf.host, lane);
            return new Failure("io", "connection failed", nf.host, lane);
        }
        if (t instanceof SocketTimeoutException) return new Failure("timeout", "timed out", "", lane);
        if (t instanceof IOException) return new Failure("io", "connection failed", "", lane);
        String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (t instanceof IllegalStateException && m.contains("HTTP ")) return new Failure("http", m, "", lane);
        if (t instanceof org.json.JSONException) return new Failure("error", "unexpected answer", "", lane);
        return new Failure("error", m, "", lane);
    }

    // ------------------------------------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------------------------------------

    public Result check(List<App> apps, Options options, Listener l) {
        this.opts = options;
        this.listener = l;
        this.guard = new Guard(net);
        this.myGeneration = options.generation == null ? 0 : options.generation.begin();
        long t0 = clock.now();
        long deadlineAt = t0 + options.deadlineMs;

        List<Job> jobs = new ArrayList<Job>();
        for (App a : apps) {
            if (options.only != null && !options.only.contains(a.pkg)) continue;
            if (!a.openSource && !a.galaxy) continue;
            Job j = new Job(a);
            jobs.add(j);
            if (a.openSource) osTotal++;
            if (a.galaxy) gxTotal++;
        }
        total = jobs.size();
        progress("start");

        // 1. Saved results first: nothing is asked of the network for them
        List<Job> todo = new ArrayList<Job>();
        for (Job j : jobs) {
            if (!options.force) {
                if (j.osNeeded) {
                    Outcome o = fromCache(j.app, LANE_OS);
                    if (o != null) {
                        j.os = o;
                        j.osNeeded = false;
                    }
                }
                if (j.gxNeeded) {
                    Outcome o = fromCache(j.app, LANE_GX);
                    if (o != null) {
                        j.gx = o;
                        j.gxNeeded = false;
                    }
                }
            }
            if (!j.osNeeded && !j.gxNeeded) commit(j);
            else todo.add(j);
        }

        // 2. The rest, on one pool
        ThreadPoolExecutor pool = new ThreadPoolExecutor(Math.max(1, options.threads), Math.max(1, options.threads), 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), new java.util.concurrent.ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "update-check-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
        try {
            if (options.self != null) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        runSelf();
                    }
                });
            }
            final List<Job> osJobs = new ArrayList<Job>();
            for (final Job j : todo) {
                if (j.osNeeded) osJobs.add(j);
                else pool.execute(task(j));                              // Galaxy Store only: no catalog needed
            }
            if (!osJobs.isEmpty()) {
                final ThreadPoolExecutor p = pool;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        loadCatalog();
                        for (Job j : osJobs) {
                            try {
                                p.execute(task(j));
                            } catch (java.util.concurrent.RejectedExecutionException e) {
                                return;                                  // the run is over
                            }
                        }
                    }
                });
            }

            // 3. Wait: until everything is finished, or cancel, or the deadline, or no internet
            while (true) {
                synchronized (lock) {
                    if (finished.size() >= total && selfDone()) break;      // all results are in; the barrier below waits for their side effects
                }
                if (cancelled || offline) break;
                if (clock.now() >= deadlineAt) {
                    expired = true;
                    break;
                }
                try {
                    Thread.sleep(15);
                } catch (InterruptedException e) {
                    cancelled = true;
                    break;
                }
            }
        } finally {
            closed = true;
            pool.getQueue().clear();                // what has not started never will; a worker that is saving or telling the listener is left alone (no interrupt)
            pool.shutdown();
        }

        // 3b. The barrier: a job that has put its result into the run is not over until its answer is saved and the listener has been told. The summary
        // (and the save of the answers) wait for that, so the last job's answer is never missing and no update is delivered after the summary.
        long barrierUntil = System.nanoTime() + BARRIER_MAX_NANOS;
        synchronized (lock) {
            while (inFlight > 0) {
                long left = barrierUntil - System.nanoTime();
                if (left <= 0) {
                    abandoned = true;                                  // a listener that never returns must not hold the check for ever
                    break;
                }
                try {
                    lock.wait(Math.max(1, Math.min(50, left / 1_000_000)));
                } catch (InterruptedException e) {
                    abandoned = true;
                    break;
                }
            }
        }

        pool.shutdownNow();                         // the commits are over: stop whatever is still in the air

        // 4. Everything that was not finished is accounted for
        JSONObject summary;
        synchronized (lock) {
            for (Job j : jobs) {
                if (finished.contains(j.app.pkg)) continue;
                if (cancelled) continue;                               // the person stopped it: counted as not checked below
                String lane = (j.app.openSource ? LANE_OS : "") + (j.app.openSource && j.app.galaxy ? "+" : "") + (j.app.galaxy ? LANE_GX : "");
                addFailure(j.app, offline ? new Failure("offline", "no internet", "", lane) : new Failure("deadline", "timed out", "", lane));
            }
            summary = buildSummary(jobs, clock.now() - t0);
        }
        cache.save();
        progress("done");
        return new Result(summary);
    }

    private volatile boolean selfFinished;

    private boolean selfDone() {
        return opts.self == null || selfFinished;
    }

    private Runnable task(final Job j) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (closed) return;
                    if (j.osNeeded) {
                        try {
                            j.os = resolveOpenSource(j.app);
                            j.osFresh = true;
                        } catch (Stop s) {
                            return;
                        } catch (Exception e) {
                            j.osErr = classify(e, LANE_OS);
                        }
                    }
                    if (j.gxNeeded) {
                        try {
                            j.gx = resolveGalaxy(j.app);
                            j.gxFresh = true;
                        } catch (Stop s) {
                            return;
                        } catch (Exception e) {
                            j.gxErr = classify(e, LANE_GX);
                        }
                    }
                    if (closed) return;
                    commit(j);
                } catch (Throwable t) {
                    if (!closed && !(t instanceof Stop)) {
                        synchronized (lock) {
                            if (!closed && !finished.contains(j.app.pkg)) {
                                addFailure(j.app, classify(t, ""));
                                finished.add(j.app.pkg);
                                completed++;
                            }
                        }
                        progress("checking");
                    }
                }
            }
        };
    }

    // ------------------------------------------------------------------------------------------------
    // Committing results
    // ------------------------------------------------------------------------------------------------

    private void addFailure(App a, Failure f) {
        // caller holds the lock
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", a.pkg);
            o.put("name", a.name);
            o.put("reason", f.reason);
            o.put("code", f.code);
            if (!f.host.isEmpty()) o.put("host", f.host);
            o.put("lane", f.lane == null ? "" : f.lane);
            failed.put(a.pkg, o);
        } catch (Exception ignored) {}
    }

    /** Puts what a job found into the run, saves it for next time and tells the listener. */
    private void commit(Job j) {
        JSONObject toSend = null;
        synchronized (lock) {
            if (closed || finished.contains(j.app.pkg)) return;
            App a = j.app;
            Failure f = j.osErr != null ? j.osErr : j.gxErr;
            if (f != null) {
                lastError = f.reason;
                if (j.osErr != null && j.gxErr != null) f = new Failure(f.code, f.reason, f.host, LANE_OS + "+" + LANE_GX);
                addFailure(a, f);
            }
            JSONObject row = null;
            if (j.os != null) {
                if (UpdateCache.UPDATE.equals(j.os.kind)) row = j.os.row;
                else if (UpdateCache.EXTERNAL.equals(j.os.kind)) external.add(j.os.row);
                else if (UpdateCache.UNTRACKED.equals(j.os.kind)) untracked.add(untrackedRow(a));
                else upToDate++;
            }
            if (j.gx != null && UpdateCache.UPDATE.equals(j.gx.kind)) row = j.gx.row;           // the Galaxy Store's answer wins, as it always has
            if (row != null) {
                updates.put(a.pkg, row);
                toSend = row;
            }
            if (!j.osFresh && !j.gxFresh && f == null) fromCache++;
            finished.add(a.pkg);
            inFlight++;                       // the result is in; the job is not over until the side effects below are done
        }
        try {
            // saved for next time (an error is never stored)
            if (j.osFresh && j.os != null) cache.put(j.app.pkg, j.app.versionCode, LANE_OS, osTag(j.app), j.os.kind, j.os.row);
            if (j.gxFresh && j.gx != null) cache.put(j.app.pkg, j.app.versionCode, LANE_GX, gxTag(), j.gx.kind, j.gx.row);
            emitUpdate(toSend);
        } finally {
            synchronized (lock) {
                inFlight--;
                completed++;
                lock.notifyAll();
            }
        }
        progress("checking");
    }

    /** Tells the listener of an update, unless this run is not the latest any more (or the end of the run gave up waiting). */
    private void emitUpdate(JSONObject row) {
        if (row == null || listener == null || abandoned || !isCurrentRun()) return;
        try {
            listener.onUpdate(row);
        } catch (Throwable ignored) {}
    }

    private boolean isCurrentRun() {
        return opts.generation == null || opts.generation.current(myGeneration);
    }

    private static JSONObject untrackedRow(App a) {
        JSONObject u = new JSONObject();
        try {
            u.put("pkg", a.pkg);
            u.put("name", a.name);
            u.put("installedVersion", a.versionName);
            u.put("installer", a.installer == null ? "" : a.installer);
        } catch (Exception ignored) {}
        return u;
    }

    private void progress(String phase) {
        if (listener == null || abandoned || !isCurrentRun()) return;
        JSONObject p = new JSONObject();
        boolean last;
        synchronized (lock) {
            long now = clock.now();
            last = "start".equals(phase) || "done".equals(phase) || completed >= total;
            if (!last && now - lastProgressAt < 150 && (completed % 10) != 0) return;
            lastProgressAt = now;
            try {
                p.put("done", completed);
                p.put("total", total);
                p.put("phase", phase);
                p.put("updates", updates.size() - (opts.self != null && updates.containsKey(opts.self.pkg) ? 1 : 0));
                p.put("fromCache", fromCache);
            } catch (Exception ignored) {}
        }
        try {
            listener.onProgress(p);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------------------------------------
    // The saved results
    // ------------------------------------------------------------------------------------------------

    private String osTag(App a) {
        JSONObject mine = opts.sources == null ? null : opts.sources.optJSONObject(a.pkg);
        return (mine == null ? "" : "u" + Integer.toHexString(mine.toString().hashCode())) + "|" + (a.installer == null ? "" : a.installer);
    }

    private String gxTag() {
        return opts.galaxyTag == null ? "" : opts.galaxyTag;
    }

    /** The saved answer for this app in this lane, if it is fresh and still makes sense for the installed version. */
    private Outcome fromCache(App a, String lane) {
        JSONObject e = cache.lookup(a.pkg, a.versionCode, lane, LANE_OS.equals(lane) ? osTag(a) : gxTag());
        if (e == null) return null;
        String kind = e.optString("kind");
        JSONObject row = e.optJSONObject("row");
        if (UpdateCache.UPDATE.equals(kind) || UpdateCache.EXTERNAL.equals(kind)) {
            if (row == null) return null;
            try {
                row = new JSONObject(row.toString());
                row.put("name", a.name);                               // the name and the installed version are the phone's, not the saved copy's
                row.put("installedVersion", a.versionName);
                row.put("installedCode", a.versionCode);
                if (UpdateCache.UPDATE.equals(kind) && !stillNewer(row, a)) return null;
            } catch (Exception ex) {
                return null;
            }
        }
        return new Outcome(kind, row);
    }

    /** The same checks as when the answer was fresh: a saved "update" is shown only if it is newer than what is installed now. */
    private static boolean stillNewer(JSONObject row, App a) {
        return stillNewer(row, a.versionCode, a.versionName);
    }

    /**
     * Whether the update a row holds is newer than what is installed now: its version code when the source gave one (F-Droid, IzzyOnDroid, the Galaxy
     * Store), else its version name against the installed one. A row that was saved or kept from an earlier check is shown only if this holds.
     */
    public static boolean stillNewer(JSONObject row, long installedCode, String installedVersionName) {
        long availableCode = row.optLong("availableCode", 0);
        if (availableCode > 0) return availableCode > installedCode;
        return UpdateManager.compareVersions(row.optString("availableVersion"), installedVersionName) > 0;
    }

    // ------------------------------------------------------------------------------------------------
    // Self
    // ------------------------------------------------------------------------------------------------

    private void runSelf() {
        boolean selfEmitting = false;
        try {
            App me = opts.self;
            JSONObject saved = null;
            if (!opts.force) {
                JSONObject e = cache.lookup(me.pkg, me.versionCode, LANE_SELF, "");
                if (e != null) saved = e;
            }
            JSONObject row = null;
            String kind;
            if (saved != null) {
                kind = saved.optString("kind");
                row = saved.optJSONObject("row");
                if (row != null && UpdateManager.compareVersions(row.optString("availableVersion"), me.versionName) <= 0) row = null;
            } else {
                try {
                    JSONObject rel = UpdateManager.githubLatest(guard, UpdateManager.SELF_REPO, opts.abis);
                    if (UpdateManager.compareVersions(rel.optString("version"), me.versionName) > 0) {
                        row = new JSONObject();
                        row.put("pkg", me.pkg);
                        row.put("name", "ADB Application Manager Pro");
                        row.put("installedVersion", me.versionName);
                        row.put("availableVersion", rel.optString("version"));
                        row.put("source", "self");
                        row.put("downloadUrl", rel.optString("url"));
                        row.put("page", rel.optString("page"));
                        row.put("notes", rel.optString("notes"));
                    }
                    kind = row != null ? UpdateCache.UPDATE : UpdateCache.UPTODATE;
                    cache.put(me.pkg, me.versionCode, LANE_SELF, "", kind, row);
                } catch (Stop s) {
                    return;
                } catch (Exception e) {
                    synchronized (lock) {
                        selfStatus = e.getMessage() != null && e.getMessage().contains("404") ? "no_releases" : "error: " + e.getMessage();
                    }
                    return;
                }
            }
            synchronized (lock) {
                if (closed) return;
                selfStatus = "ok";
                if (row != null) {
                    updates.put(me.pkg, row);
                    inFlight++;
                    selfEmitting = true;
                }
            }
            selfFinished = true;                  // its result is in; the barrier waits for the listener
            if (selfEmitting) {
                try {
                    emitUpdate(row);
                } finally {
                    synchronized (lock) {
                        inFlight--;
                        lock.notifyAll();
                    }
                }
            }
        } finally {
            selfFinished = true;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // The Obtainium catalog
    // ------------------------------------------------------------------------------------------------

    private void loadCatalog() {
        try {
            JSONObject saved = opts.refreshCatalog ? null : cache.loadCatalog();
            if (saved != null) {
                catalog = saved;
                catalogFromIndex = true;
                return;
            }
            JSONObject fresh = UpdateManager.obtainiumIndex(guard);
            cache.saveCatalog(fresh);
            catalog = fresh;
            catalogFromIndex = true;
        } catch (Stop s) {
            // the run is over
        } catch (Exception e) {
            // the list is not there: the per-app files answer instead (catalog stays null)
            JSONObject stale = cache.loadCatalogAnyAge();
            if (stale != null) {
                catalog = stale;
                catalogFromIndex = true;
            }
        }
    }

    private JSONObject catalogConfig(String pkg) throws Exception {
        if (catalogFromIndex && catalog != null) return catalog.optJSONObject(pkg);
        return UpdateManager.obtainiumCatalog(guard, pkg);
    }

    // ------------------------------------------------------------------------------------------------
    // Open-source sources for one app (the order and the rows are the ones the tab has always had)
    // ------------------------------------------------------------------------------------------------

    private static final Set<String> FDROID_CLIENTS = new HashSet<String>(java.util.Arrays.asList(
            "org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify", "com.machiav3lli.fdroid",
            "eu.bubu1.fdroidclassic", "in.sunilpaulmathew.izzyondroid"));

    private static JSONObject obtainiumLinkConfig(String pkg, String name, String url, String author) {
        JSONObject c = new JSONObject();
        try {
            c.put("id", pkg);
            c.put("url", url);
            c.put("author", author == null ? "" : author);
            c.put("name", name == null ? pkg : name);
        } catch (Exception ignored) {}
        return c;
    }

    /**
     * Resolves one app against its sources in order. The first source that knows the app decides (so an up-to-date answer from the developer's repo is
     * not overridden by another repo's build). An answer is a kind and a row; "untracked" when no source knows the app. A failure of the F-Droid or
     * IzzyOnDroid lookup lets the other one answer; a failure of the developer's own source is the app's failure.
     */
    private Outcome resolveOpenSource(App app) throws Exception {
        String pkg = app.pkg;
        JSONObject mine = opts.sources == null ? null : opts.sources.optJSONObject(pkg);
        List<JSONObject> candidates = new ArrayList<JSONObject>();
        if (mine != null && mine.optString("url").length() > 0) {
            candidates.add(new JSONObject().put("url", mine.optString("url")).put("origin", mine.optString("origin", "yours"))
                    .put("settings", mine.optJSONObject("settings") != null ? mine.optJSONObject("settings") : new JSONObject()));
        }
        boolean fromFdroidClient = app.installer != null && FDROID_CLIENTS.contains(app.installer);
        if (!fromFdroidClient) {
            JSONObject cfg = catalogConfig(pkg);
            if (cfg != null && cfg.optString("url").length() > 0) {
                candidates.add(new JSONObject().put("url", cfg.optString("url")).put("origin", "obtainium-catalog")
                        .put("settings", UpdateManager.obtainiumSettings(cfg)).put("author", cfg.optString("author")));
            }
        }
        // F-Droid builds are signed by F-Droid, IzzyOnDroid mostly ships the developer's APKs
        if (fromFdroidClient) {
            candidates.add(new JSONObject().put("fdroid", "fdroid"));
            candidates.add(new JSONObject().put("fdroid", "izzy"));
        } else {
            candidates.add(new JSONObject().put("fdroid", "izzy"));
            candidates.add(new JSONObject().put("fdroid", "fdroid"));
        }

        Exception storeFailure = null;
        for (JSONObject c : candidates) {
            String fd = c.optString("fdroid");
            if (!fd.isEmpty()) {
                try {
                    Outcome o = fdroidOutcome(app, "izzy".equals(fd));
                    if (o != null) return o;
                } catch (Stop s) {
                    throw s;
                } catch (Exception e) {
                    if (storeFailure == null) storeFailure = e;
                }
                continue;
            }
            Outcome o = urlOutcome(app, c);
            if (o != null) return o;
        }
        if (storeFailure != null) throw storeFailure;
        return new Outcome(UpdateCache.UNTRACKED, null);
    }

    private JSONObject baseRow(App app) throws Exception {
        JSONObject o = new JSONObject();
        o.put("pkg", app.pkg);
        o.put("name", app.name);
        o.put("installedVersion", app.versionName);
        o.put("installedCode", app.versionCode);
        o.put("installer", app.installer == null ? "" : app.installer);
        return o;
    }

    private Outcome fdroidOutcome(App app, boolean izzy) throws Exception {
        JSONObject latest = UpdateManager.fdroidLatest(guard, izzy ? UpdateManager.IZZY_API : UpdateManager.FDROID_API, app.pkg);
        if (latest == null) return null;
        long remote = latest.optLong("versionCode");
        JSONObject o = baseRow(app);
        o.put("source", izzy ? "izzy" : "fdroid");
        o.put("origin", izzy ? "izzy" : "fdroid");
        o.put("availableVersion", latest.optString("versionName"));
        o.put("availableCode", remote);
        o.put("downloadUrl", (izzy ? UpdateManager.IZZY_REPO : UpdateManager.FDROID_REPO) + app.pkg + "_" + remote + ".apk");
        o.put("page", izzy ? "https://apt.izzysoft.de/fdroid/index/apk/" + app.pkg : "https://f-droid.org/packages/" + app.pkg + "/");
        return new Outcome(remote <= app.versionCode ? UpdateCache.UPTODATE : UpdateCache.UPDATE, remote <= app.versionCode ? null : o);
    }

    private Outcome urlOutcome(App app, JSONObject c) throws Exception {
        String pkg = app.pkg;
        String url = c.optString("url");
        JSONObject settings = c.optJSONObject("settings") != null ? c.optJSONObject("settings") : new JSONObject();
        JSONObject o = baseRow(app);
        o.put("origin", c.optString("origin"));
        o.put("sourceUrl", url);
        o.put("obtainium", "obtainium://app/" + java.net.URLEncoder.encode(obtainiumLinkConfig(pkg, app.name, url, c.optString("author")).toString(), "UTF-8").replace("+", "%20"));

        String fdroidPkg = UpdateManager.parseFdroidUrl(url);
        if (fdroidPkg != null) {
            boolean izzy = url.contains("izzysoft");
            JSONObject latest = UpdateManager.fdroidLatest(guard, izzy ? UpdateManager.IZZY_API : UpdateManager.FDROID_API, pkg);
            if (latest == null) return null;
            long remote = latest.optLong("versionCode");
            o.put("source", izzy ? "izzy" : "fdroid");
            o.put("availableVersion", latest.optString("versionName"));
            o.put("availableCode", remote);
            o.put("downloadUrl", (izzy ? UpdateManager.IZZY_REPO : UpdateManager.FDROID_REPO) + pkg + "_" + remote + ".apk");
            o.put("page", url);
            return new Outcome(remote <= app.versionCode ? UpdateCache.UPTODATE : UpdateCache.UPDATE, remote <= app.versionCode ? null : o);
        }

        JSONObject repo = UpdateManager.parseRepoUrl(url);
        if (repo == null) {
            // e.g. a vendor website: Obtainium can track it, this app can't check it directly
            o.put("source", "external");
            o.put("page", url);
            o.put("unsupported", true);
            return new Outcome(UpdateCache.EXTERNAL, o);
        }
        boolean codeberg = "codeberg.org".equals(repo.optString("host"));
        UpdateManager.Release rel = codeberg
                ? UpdateManager.codebergRelease(guard, repo.optString("owner"), repo.optString("repo"))
                : UpdateManager.githubRelease(guard, repo.optString("owner"), repo.optString("repo"), opts.githubToken,
                        settings.optBoolean("includePrereleases", false), githubApiLimited.get());
        String version = UpdateManager.extractVersion(rel.tag, settings.optString("versionExtractionRegEx", ""), settings.optString("matchGroupToUse", ""));
        String[] apk = UpdateManager.pickApk(rel.assets, settings.optString("apkFilterRegEx", ""), settings.optBoolean("invertAPKFilter", false), opts.abis);
        o.put("source", codeberg ? "codeberg" : "github");
        o.put("availableVersion", version.replaceFirst("^[vV](?=[0-9])", ""));
        o.put("page", rel.page);
        o.put("notes", rel.notes);
        if (apk != null) {
            o.put("downloadUrl", apk[1]);
            o.put("assetName", apk[0]);
        }
        if (UpdateManager.compareVersions(version, app.versionName) <= 0) return new Outcome(UpdateCache.UPTODATE, null);
        if (apk == null) o.put("noApk", true);
        return new Outcome(UpdateCache.UPDATE, o);
    }

    // ------------------------------------------------------------------------------------------------
    // Galaxy Store for one app
    // ------------------------------------------------------------------------------------------------

    private UpdateManager.Device device() throws Exception {
        synchronized (lock) {
            if (deviceMemo == null) {
                if (opts.device == null) throw new IllegalStateException("no device identity for the Galaxy Store");
                deviceMemo = opts.device.get();
            }
            return deviceMemo;
        }
    }

    private Outcome resolveGalaxy(App app) throws Exception {
        UpdateManager.Device d = device();
        JSONObject r = UpdateManager.galaxyCheck(guard, app.pkg, app.versionCode, d);
        if (r.optBoolean("available")) {
            JSONObject o = new JSONObject();
            o.put("pkg", app.pkg);
            o.put("name", app.name);
            o.put("installedVersion", app.versionName);
            o.put("installedCode", app.versionCode);
            o.put("availableVersion", r.optString("versionName"));
            o.put("availableCode", r.optLong("versionCode"));
            o.put("isSystem", app.system);
            o.put("source", "galaxy");
            return new Outcome(UpdateCache.UPDATE, o);
        }
        String code = r.optString("resultCode");
        if (code.isEmpty()) throw new IllegalStateException("unexpected answer from the Galaxy Store");
        if ("0".equals(code)) return new Outcome(UpdateCache.NOT_DISTRIBUTED, null);
        return new Outcome(UpdateCache.UPTODATE, null);
    }

    // ------------------------------------------------------------------------------------------------
    // The summary
    // ------------------------------------------------------------------------------------------------

    private static void sortByName(List<JSONObject> l) {
        Collections.sort(l, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return a.optString("name").compareToIgnoreCase(b.optString("name"));
            }
        });
    }

    private JSONObject buildSummary(List<Job> jobs, long durationMs) {
        // caller holds the lock
        JSONObject s = new JSONObject();
        try {
            List<JSONObject> up = new ArrayList<JSONObject>(updates.values());
            sortByName(up);
            sortByName(untracked);
            sortByName(external);
            List<JSONObject> fl = new ArrayList<JSONObject>(failed.values());
            sortByName(fl);
            int timedOut = 0, osFailed = 0, gxFailed = 0;
            JSONArray failedArr = new JSONArray();
            boolean noInternet = offline && !cancelled;
            for (JSONObject f : fl) {
                if (noInternet && ("host".equals(f.optString("code")) || "io".equals(f.optString("code")) || "timeout".equals(f.optString("code")))) {
                    f.put("code", "offline");
                    f.put("reason", "no internet");
                    f.remove("host");
                } else if ("host".equals(f.optString("code")) || (guard.hostsDown.contains(f.optString("host")) && !f.optString("host").isEmpty())) {
                    String host = f.optString("host");
                    f.put("code", "host");
                    f.put("reason", host + " unreachable");
                }
                if ("deadline".equals(f.optString("code"))) timedOut++;
                String lane = f.optString("lane");
                if (lane.contains(LANE_GX)) gxFailed++;
                if (lane.contains(LANE_OS) || lane.isEmpty()) osFailed++;
                failedArr.put(f);
            }
            int notChecked = 0;
            if (cancelled) for (Job j : jobs) if (!finished.contains(j.app.pkg)) notChecked++;
            s.put("updates", new JSONArray(up));
            s.put("untracked", new JSONArray(untracked));
            s.put("external", new JSONArray(external));
            s.put("failed", failedArr);
            s.put("hostsDown", new JSONArray(noInternet ? new ArrayList<String>() : new ArrayList<String>(guard.hostsDown)));
            s.put("cancelled", cancelled);
            s.put("offline", offline && !cancelled);
            s.put("timedOut", timedOut);
            s.put("fromCache", fromCache);
            s.put("notChecked", notChecked);
            s.put("total", total);
            s.put("checked", Math.max(0, gxTotal - gxFailed - (cancelled ? countUnfinished(jobs, true) : 0)));
            s.put("openSourceChecked", Math.max(0, osTotal - osFailed - (cancelled ? countUnfinished(jobs, false) : 0)));
            s.put("errors", gxFailed);
            s.put("openSourceErrors", osFailed);
            s.put("lastError", lastError);
            s.put("upToDateOpenSource", upToDate);
            s.put("selfStatus", selfStatus);
            s.put("requests", guard.requests.get());
            s.put("durationMs", durationMs);
            if (opts.self != null && selfStatus.isEmpty()) s.put("selfStatus", cancelled ? "cancelled" : "error: timed out");
        } catch (Exception ignored) {}
        return s;
    }

    private int countUnfinished(List<Job> jobs, boolean galaxyLane) {
        int n = 0;
        for (Job j : jobs) if (!finished.contains(j.app.pkg) && (galaxyLane ? j.app.galaxy : j.app.openSource)) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------------
    // Merging a retry into the result before it
    // ------------------------------------------------------------------------------------------------

    /**
     * The result of a run that re-checked only some apps ({@code retried}), folded into the result before it: what the earlier run found for the
     * other apps stays, what is known about the retried ones comes from the new run.
     */
    public static JSONObject merge(JSONObject prev, JSONObject retry, Set<String> retried) {
        try {
            JSONObject m = new JSONObject(prev.toString());
            for (String key : new String[]{"updates", "untracked", "external", "failed"}) {
                JSONArray out = new JSONArray();
                JSONArray old = prev.optJSONArray(key);
                for (int i = 0; old != null && i < old.length(); i++) {
                    JSONObject o = old.optJSONObject(i);
                    if (o != null && !retried.contains(o.optString("pkg"))) out.put(o);
                }
                JSONArray add = retry.optJSONArray(key);
                for (int i = 0; add != null && i < add.length(); i++) out.put(add.get(i));
                m.put(key, out);
            }
            Set<String> hosts = new LinkedHashSet<String>();
            JSONArray hd = retry.optJSONArray("hostsDown");
            for (int i = 0; hd != null && i < hd.length(); i++) hosts.add(hd.optString(i));
            m.put("hostsDown", new JSONArray(new ArrayList<String>(hosts)));
            int errors = 0, osErrors = 0, timedOut = 0;
            JSONArray f = m.optJSONArray("failed");
            for (int i = 0; i < f.length(); i++) {
                JSONObject o = f.optJSONObject(i);
                if (o.optString("lane").contains(LANE_GX)) errors++;
                if (o.optString("lane").contains(LANE_OS) || o.optString("lane").isEmpty()) osErrors++;
                if ("deadline".equals(o.optString("code"))) timedOut++;
            }
            m.put("errors", errors);
            m.put("openSourceErrors", osErrors);
            m.put("timedOut", timedOut);
            m.put("checked", prev.optInt("checked") + retry.optInt("checked"));
            m.put("openSourceChecked", prev.optInt("openSourceChecked") + retry.optInt("openSourceChecked"));
            m.put("fromCache", retry.optInt("fromCache"));
            m.put("cancelled", retry.optBoolean("cancelled"));
            m.put("offline", retry.optBoolean("offline"));
            m.put("notChecked", retry.optInt("notChecked"));
            m.put("lastError", retry.optString("lastError"));
            m.put("requests", retry.optInt("requests"));
            m.put("durationMs", retry.optLong("durationMs"));
            return m;
        } catch (Exception e) {
            return retry;
        }
    }
}
