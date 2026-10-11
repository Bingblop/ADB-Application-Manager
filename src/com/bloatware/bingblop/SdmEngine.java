package com.bloatware.bingblop;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The task manager of the SD Maid SE tab (spec section 7): runs the four tools' scans, deletions and one-click runs, keeps their results in memory
 * and answers the page about them.
 *
 * <ul>
 * <li>At most two tool tasks run at the same time (one fair {@code Semaphore(2)}); the others show "In queue". Each tool also has its own lock, so two tasks
 *     of one tool never overlap and the second waits behind the first without holding one of the two slots.</li>
 * <li>Cancel works in every phase: a task that waits leaves the queue, a running one gets {@link Sdm.Cancel#cancelled()} and, for a shell, its process killed.
 *     A scan that is cancelled (or fails, or times out) leaves no data. A cancelled delete keeps the snapshot, is not written to the history when it removed
 *     nothing, and is PARTIAL when it removed something. Timeouts: 4 hours, 6 for the Deduplicator.</li>
 * <li>Progress goes to {@link Events#progress} at most about four times a second; results live in this class, not in the WebView ({@link #state} lets a rebuilt
 *     page re-attach); the result texts are exactly those of spec 7.3 (the page translates them).</li>
 * <li>The history (spec 7.9) is written here, by the task wrapper, for finished deletes and one-clicks.</li>
 * <li>Safety (spec 8.6): every tool gets a file system whose delete refuses paths that fail {@link SdmSafety} or break an exclusion (excluded, or holding an
 *     excluded path), whatever the tool asks for; a dry-run setting turns deletes into no-ops that report success.</li>
 * <li>"Exclude" creates tool-tagged exclusions and hides the paths / packages from the current result at once (a view over the tool's own result, which keeps
 *     counts and sizes right); "Undo" takes the exclusions back and shows them again.</li>
 * </ul>
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se ({@code TaskManager}, the tools' task result
 * texts, {@code StatsRepo.report}). Changes: Java threads instead of coroutines; the result view and the guard file system are this port's additions.
 * Pure Java, no android.* classes.
 */
public final class SdmEngine {

    /** What the engine needs from the phone; implemented by SdmBridge on the device and by a fake in the tests. Each call answers for the working mode of this moment. */
    public interface Env {
        Sdm.Fs fs();
        Sdm.Areas areas();
        Sdm.Packages pkgs();
        Sdm.Shell shell();
        /** Null when the accessibility service is not connected or there is no consent. */
        Sdm.Automation automation();
        /** Where settings, exclusions and the history live. */
        File dataDir();
        long nowSec();
    }

    /** Answers the page; SdmBridge forwards each call to window.onSdm(...). */
    public interface Events {
        /** {tool, primary, secondary, countType: percent|counter|size|indeterminate|none, current, max, bytes, queued} */
        void progress(String tool, JSONObject p);
        /** The tool's state changed: the same object {@link #state} returns. */
        void state(String tool, JSONObject s);
        /** {tool, kind: scan|delete|oneclick, status: ok|partial|error|cancelled, primary, secondary, error, count, bytes} */
        void done(String tool, JSONObject d);
        /** A history row was added. */
        default void history() {}
    }

    /** A {@link Sdm.Fs} whose walk can leave subtrees out inside the process that reads them (the shell's {@code find -prune}). */
    public interface PruningFs extends Sdm.Fs {
        void walk(String root, Collection<String> prune, Sdm.EntrySink sink, Sdm.Cancel cancel) throws IOException;
    }

    /**
     * A tool throws this after doing part of the work: the engine records what the report says (PARTIAL when something was deleted) together with the
     * error. Any other exception from a delete is treated the same way, using what went through the guarded file system.
     */
    public static final class PartialResultException extends Exception {
        public final Sdm.DeleteReport report;
        public PartialResultException(String message, Throwable cause, Sdm.DeleteReport report) { super(message, cause); this.report = report; }
    }

    // ---------------------------------------------------------------------------------------------------------- texts (spec 7.3, 7.7, 9.11)

    private static final String[] EGGS = {
        "Still faster than an iPhone", "Downloading more RAM", "Soon(tm)", "The CPU is on a coffee break", "Awaiting data from Pepe Silvia",
        "Turbo encabulator is initiating", "Passing the butter", "So. How are you holding up?", "Beeping boop bop", "Buffering life choices",
        "Summoning Cthulhu", "This is fine", "Please hold", "At least you're not on hold" };

    private static final long THROTTLE_MS = 250;
    private static final long TIMEOUT_MS = 4L * 3600 * 1000, TIMEOUT_DEDUP_MS = 6L * 3600 * 1000;
    private static final Pattern AUTOMATION_NOTE = Pattern.compile("(?i)^automation[_: -]*(SCREEN_UNAVAILABLE|NO_CONSENT|ERROR)\\D*(\\d*)");

    /** Binary units, "12.3 MB": one decimal below 100, none above. */
    public static String formatSize(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";
        String[] u = { "KB", "MB", "GB", "TB", "PB", "EB" };
        double v = bytes;
        int i = -1;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        String s = v >= 100 ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.1f", v);
        if (Double.parseDouble(s) >= 1024 && i < u.length - 1) { v /= 1024; i++; s = String.format(Locale.ROOT, "%.1f", v); }
        return s + " " + u[i];
    }

    private static String plural(long n, String one, String other) {
        return n + " " + (n == 1 ? one : other);
    }

    /** primary / secondary of a scan result (spec 7.3), with the string keys of the upstream resources. */
    static String[] scanTexts(Sdm.Tool t, long items, long groups, long bytes) {
        String size = formatSize(bytes);
        switch (t) {
            case SYSTEMCLEANER: return new String[] { plural(items, "filter match", "filter matches"), size + " can be freed", "systemcleaner_result_x_items_found", "x_space_can_be_freed", String.valueOf(items) };
            case APPCLEANER: return new String[] { plural(items, "expendable item found", "expendable items found"), size + " can be freed", "appcleaner_result_x_items_found", "x_space_can_be_freed", String.valueOf(items) };
            case CORPSEFINDER: return new String[] { plural(groups, "app remnant discovered", "app remnants discovered"), size + " can be freed", "corpsefinder_result_x_corpses_found", "x_space_can_be_freed", String.valueOf(groups) };
            default: {
                double value = 0;
                try { value = Double.parseDouble(size.substring(0, size.indexOf(' '))); } catch (RuntimeException ignored) {}
                boolean one = Math.round(value) == 1;
                return new String[] { plural(groups, "duplicate set found", "duplicate sets found"), size + (one ? " is" : " are") + " occupied by duplicates", "deduplicator_result_x_clusters_found", "deduplicator_x_space_occupied_by_duplicates_msg", String.valueOf(groups) };
            }
        }
    }

    /** primary / secondary of a deletion receipt (spec 7.3); {@code variant} is "", SCREEN_UNAVAILABLE, ERROR or NO_CONSENT (AppCleaner only). */
    static String[] deleteTexts(Sdm.Tool t, long count, long bytes, String variant, int skipped) {
        String freed = "Freed " + formatSize(bytes) + " space.";
        switch (t) {
            case SYSTEMCLEANER: return new String[] { plural(count, "match deleted", "matches deleted"), freed, "systemcleaner_result_x_items_deleted", "general_result_x_space_freed" };
            case APPCLEANER: {
                if ("SCREEN_UNAVAILABLE".equals(variant)) return new String[] { plural(count, "expendable item deleted, stopped because the screen was off or locked", "expendable items deleted, stopped because the screen was off or locked"), freed, "appcleaner_result_x_items_deleted_stopped_screen", "general_result_x_space_freed" };
                if ("ERROR".equals(variant)) return new String[] { plural(count, "expendable item deleted, stopped by an error", "expendable items deleted, stopped by an error"), freed, "appcleaner_result_x_items_deleted_stopped_error", "general_result_x_space_freed" };
                String plain = plural(count, "expendable item deleted", "expendable items deleted");
                if ("NO_CONSENT".equals(variant)) return new String[] { plain + ", " + skipped + (skipped == 1 ? " cache still needs the accessibility service" : " caches still need the accessibility service"), freed, "appcleaner_result_x_items_deleted_stopped_automation", "general_result_x_space_freed" };
                return new String[] { plain, freed, "appcleaner_result_x_items_deleted", "general_result_x_space_freed" };
            }
            case CORPSEFINDER: return new String[] { plural(count, "app remnant deleted", "app remnants deleted"), freed, "corpsefinder_result_x_corpses_deleted", "general_result_x_space_freed" };
            default: return new String[] { plural(count, "duplicate deleted", "duplicates deleted"), freed, "deduplicator_result_x_clusters_processed", "general_result_x_space_freed" };
        }
    }

    // ---------------------------------------------------------------------------------------------------------- state

    private final Env env;
    private final Events events;
    private final SdmSettings settings;
    private final SdmExclusions exclusions;
    private final SdmHistory history;
    private final Semaphore slots = new Semaphore(2, true);
    private final Map<Sdm.Tool, Sdm.ToolImpl> impls = new EnumMap<Sdm.Tool, Sdm.ToolImpl>(Sdm.Tool.class);
    private final Map<Sdm.Tool, ToolState> states = new EnumMap<Sdm.Tool, ToolState>(Sdm.Tool.class);
    private final Object lock = new Object();
    private final Random random = new Random();
    private final LinkedHashMap<String, Undo> undos = new LinkedHashMap<String, Undo>();
    private final ExecutorService pool = Executors.newCachedThreadPool(new ThreadFactory() {
        private int n;
        @Override public synchronized Thread newThread(Runnable r) { Thread t = new Thread(r, "sdm-task-" + (++n)); t.setDaemon(true); return t; }
    });
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "sdm-timer"); t.setDaemon(true); return t; }
    });
    private int undoCounter;
    private volatile long timeoutOverrideMs = -1;
    private volatile boolean shut;

    private static final class ToolState {
        final Sdm.Tool tool;
        final Semaphore toolLock = new Semaphore(1, true);
        final List<Task> tasks = new ArrayList<Task>();        // queued or running; guarded by SdmEngine.lock
        Sdm.Result raw;
        View view;
        JSONObject lastResult;
        JSONObject progress;
        JSONObject summary;                                     // of the view as of the last time nothing was running (see refreshSummary)
        boolean hasData;
        ToolState(Sdm.Tool t) { tool = t; }
    }

    private static final class Task {
        final Sdm.Tool tool;
        final String kind;                                      // scan | delete | oneclick
        final Sdm.Selection selection;
        volatile boolean cancelled, timedOut, finished;
        volatile String phase = "queued";                       // queued | scanning | deleting
        final String egg;
        Task(Sdm.Tool tool, String kind, Sdm.Selection sel, String egg) { this.tool = tool; this.kind = kind; this.selection = sel; this.egg = egg; }
    }

    private static final class Undo {
        final Sdm.Tool tool;
        final SdmExclusions.Created created;
        final View view;
        Undo(Sdm.Tool t, SdmExclusions.Created c, View v) { tool = t; created = c; view = v; }
    }

    public SdmEngine(Env env, Events events) {
        if (env.dataDir() == null) throw new IllegalArgumentException("a data folder is needed");
        this.env = env;
        this.events = events;
        this.settings = new SdmSettings(env.dataDir());
        this.exclusions = new SdmExclusions(env.dataDir());
        this.history = new SdmHistory(env.dataDir(), settings);
        for (Sdm.Tool t : Sdm.Tool.values()) states.put(t, new ToolState(t));
        String[] names = { "SdmSystemCleaner", "SdmAppCleaner", "SdmCorpseFinder", "SdmDedup" };
        for (String n : names) {
            try {
                Object o = Class.forName("com.bloatware.bingblop." + n).newInstance();
                if (o instanceof Sdm.ToolImpl) register((Sdm.ToolImpl) o);
            } catch (Throwable missingOrBroken) {
                // the tool is not part of this build: its card says so
            }
        }
    }

    /** Adds (or replaces) the implementation of a tool; the engine registers the four real ones itself, tests register fakes. */
    public void register(Sdm.ToolImpl impl) {
        synchronized (lock) { impls.put(impl.tool(), impl); }
    }

    public boolean has(Sdm.Tool t) {
        synchronized (lock) { return impls.containsKey(t); }
    }

    public SdmSettings settings() { return settings; }
    public SdmExclusions exclusions() { return exclusions; }
    public SdmHistory history() { return history; }

    /** For the tests: a timeout for every task, in milliseconds (-1 for the real ones). */
    void setTimeoutMs(long ms) { timeoutOverrideMs = ms; }

    private long timeoutFor(Sdm.Tool t) {
        return timeoutOverrideMs > 0 ? timeoutOverrideMs : t == Sdm.Tool.DEDUPLICATOR ? TIMEOUT_DEDUP_MS : TIMEOUT_MS;
    }

    private long nowMs() { return env.nowSec() * 1000L; }

    // ---------------------------------------------------------------------------------------------------------- entry points

    public void scan(Sdm.Tool t) { submit(t, "scan", null); }

    public void delete(Sdm.Tool t, Sdm.Selection s) { submit(t, "delete", s == null ? new Sdm.Selection() : s); }

    /** Scan and delete everything inside ONE task, no confirmation. */
    public void oneClick(Sdm.Tool t) { submit(t, "oneclick", new Sdm.Selection()); }

    /** Cancels every task of the tool that is not cancelled yet, the running one and the ones in the queue. */
    public void cancel(Sdm.Tool t) {
        synchronized (lock) {
            for (Task k : states.get(t).tasks) k.cancelled = true;
        }
    }

    public void cancelAll() {
        for (Sdm.Tool t : Sdm.Tool.values()) cancel(t);
    }

    /** True while a task of the tool is queued or running. */
    public boolean busy(Sdm.Tool t) {
        synchronized (lock) { return !states.get(t).tasks.isEmpty(); }
    }

    public boolean hasData(Sdm.Tool t) {
        synchronized (lock) { return states.get(t).hasData; }
    }

    /**
     * Recomputes the summary line and the has-data flag of a tool from its result. Called whenever the result changed (scan or delete done, exclude, undo,
     * discard), never while a task of the tool runs, and outside the engine lock because reading a result can take a moment.
     */
    private void refreshSummary(Sdm.Tool t) {
        View v;
        synchronized (lock) { v = states.get(t).view; }
        JSONObject sum = null;
        boolean has = false;
        if (v != null) {
            long items = v.itemCount(), groups = v.groupCount(), bytes = v.bytes();
            String[] tx = scanTexts(t, items, groups, bytes);
            try {
                sum = new JSONObject().put("primary", tx[0]).put("secondary", tx[1]).put("primaryKey", tx[2]).put("secondaryKey", tx[3]).put("n", tx[4])
                        .put("size", formatSize(bytes)).put("itemCount", items).put("groupCount", groups).put("bytes", bytes);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            has = groups > 0;
        }
        synchronized (lock) {
            ToolState ts = states.get(t);
            if (ts.view == v) { ts.summary = sum; ts.hasData = has; }
        }
    }

    private void submit(Sdm.Tool t, String kind, Sdm.Selection sel) {
        if (shut) return;
        Sdm.ToolImpl impl;
        Task task;
        synchronized (lock) {
            impl = impls.get(t);
            ToolState ts = states.get(t);
            if (impl != null) {
                if (!"delete".equals(kind)) {
                    for (Task o : ts.tasks) if (!"delete".equals(o.kind) && !o.cancelled) return;      // a scan of this tool is already on its way
                }
                task = new Task(t, kind, sel, EGGS[random.nextInt(EGGS.length)]);
                ts.tasks.add(task);
            } else {
                task = null;
            }
        }
        if (task == null) {
            JSONObject d = new JSONObject();
            try { d.put("tool", t.id).put("kind", kind).put("status", "error").put("primary", "").put("secondary", "").put("error", "This tool is not part of this build.").put("count", 0).put("bytes", 0); } catch (JSONException ignored) {}
            fireDone(t, d);
            return;
        }
        final Task fTask = task;
        final Sdm.ToolImpl fImpl = impl;
        try {
            pool.execute(new Runnable() { @Override public void run() { runTask(fTask, fImpl); } });
        } catch (RuntimeException rejected) {
            synchronized (lock) { states.get(t).tasks.remove(task); }
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the task wrapper

    private void runTask(Task task, Sdm.ToolImpl impl) {
        ToolState ts = states.get(task.tool);
        boolean gotLock = false, gotSlot = false;
        try {
            gotLock = ts.toolLock.tryAcquire();
            if (gotLock) gotSlot = slots.tryAcquire();
            if (!gotLock || !gotSlot) {
                publishQueued(task);
                while (!gotLock) {
                    if (task.cancelled || shut) { finishWithoutRunning(task); return; }
                    gotLock = ts.toolLock.tryAcquire(100, TimeUnit.MILLISECONDS);
                }
                while (!gotSlot) {
                    if (task.cancelled || shut) { finishWithoutRunning(task); return; }
                    gotSlot = slots.tryAcquire(100, TimeUnit.MILLISECONDS);
                }
            }
            if (task.cancelled || shut) { finishWithoutRunning(task); return; }
            execute(task, ts, impl);
        } catch (InterruptedException e) {
            finishWithoutRunning(task);
        } catch (Throwable unexpected) {
            Outcome o = new Outcome();
            o.status = "error";
            o.error = message(unexpected);
            complete(task, ts, o);
        } finally {
            if (gotSlot) slots.release();
            if (gotLock) ts.toolLock.release();
        }
    }

    private void publishQueued(Task task) {
        JSONObject p = progressJson(task.tool, "In queue", "", 0, -1, 0, true);
        synchronized (lock) { states.get(task.tool).progress = p; }
        fireProgress(task.tool, p);
        fireState(task.tool);
    }

    /** A task that was cancelled (or lost its thread) while it waited: nothing ran, nothing was touched. */
    private void finishWithoutRunning(Task task) {
        Outcome o = new Outcome();
        o.status = "cancelled";
        complete(task, states.get(task.tool), o);
    }

    private static final class Outcome {
        String status = "ok";                                   // ok | partial | error | cancelled
        String primary = "", secondary = "", error = "";
        String primaryKey = "", secondaryKey = "", n = "";
        long count, bytes;
        boolean record;                                         // write a history row
        boolean setLast;                                        // remember as the tool's last result
        List<String> paths = new ArrayList<String>();
        long startAt, endAt;
    }

    private void execute(final Task task, ToolState ts, Sdm.ToolImpl impl) {
        final Sdm.Tool tool = task.tool;
        task.phase = "delete".equals(task.kind) ? "deleting" : "scanning";
        Outcome out = new Outcome();
        out.startAt = nowMs();
        ScheduledFuture<?> timeout = null;
        try {
            timeout = timer.schedule(new Runnable() { @Override public void run() { task.timedOut = true; task.cancelled = true; } }, timeoutFor(tool), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {}
        ProgressSink ps = new ProgressSink(task);
        Sdm.Cancel cancel = new Sdm.Cancel() { @Override public boolean cancelled() { return task.cancelled; } };
        TaskFs tfs = new TaskFs(env.fs(), tool, env.areas(), settings.dryRun());
        Sdm.Ctx ctx = new Sdm.Ctx(tfs, env.areas(), env.pkgs(), exclusions, env.shell(), settings.tool(tool), ps, cancel, env.nowSec());
        try {
            Sdm.Automation a = env.automation();
            if (a != null && a.ready()) ctx.automation = a;
        } catch (RuntimeException ignored) {}
        ps.update("Loading", "", 0, -1, 0);
        fireState(tool);
        try {
            if ("scan".equals(task.kind)) doScan(task, ts, impl, ctx, out);
            else if ("delete".equals(task.kind)) doDelete(task, ts, impl, ctx, tfs, out, task.selection);
            else {
                doScan(task, ts, impl, ctx, out);
                if ("ok".equals(out.status)) {
                    task.phase = "deleting";
                    fireState(tool);
                    out = new Outcome();
                    out.startAt = nowMs();
                    doDelete(task, ts, impl, ctx, tfs, out, new Sdm.Selection());
                } else if ("error".equals(out.status)) {
                    out.record = true;                                   // a one-click that failed is a finished task
                }
            }
        } catch (Throwable e) {
            out.status = "error";
            out.error = message(e);
        } finally {
            if (timeout != null) timeout.cancel(false);
            ps.stop();
        }
        out.endAt = nowMs();
        complete(task, ts, out);
    }

    private static boolean isCancellation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof CancellationException || t instanceof InterruptedException) return true;
        return false;
    }

    private static String message(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty() || t instanceof java.lang.reflect.InvocationTargetException)) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void doScan(Task task, ToolState ts, Sdm.ToolImpl impl, Sdm.Ctx ctx, Outcome out) {
        synchronized (lock) { ts.raw = null; ts.view = null; ts.summary = null; ts.hasData = false; }   // the previous data is gone as a scan starts (a cancelled re-scan loses it)
        Sdm.Result r = null;
        Throwable err = null;
        try {
            r = impl.scan(ctx);
        } catch (Throwable e) {
            err = e;
        }
        if (task.timedOut) { out.status = "error"; out.error = "The task took too long and was stopped."; return; }
        if (task.cancelled || (err != null && isCancellation(err))) { out.status = "cancelled"; return; }
        if (err != null) { out.status = "error"; out.error = message(err); return; }
        if (r == null) { out.status = "error"; out.error = "The scan returned nothing."; return; }
        View v = new View(r, ts.tool);
        synchronized (lock) { ts.raw = r; ts.view = v; }
        refreshSummary(ts.tool);
        long items = v.itemCount(), groups = v.groupCount(), bytes = v.bytes();
        String[] t = scanTexts(ts.tool, items, groups, bytes);
        out.status = "ok"; out.primary = t[0]; out.secondary = t[1]; out.primaryKey = t[2]; out.secondaryKey = t[3]; out.n = t[4];
        out.count = ts.tool == Sdm.Tool.CORPSEFINDER || ts.tool == Sdm.Tool.DEDUPLICATOR ? groups : items;
        out.bytes = bytes;
        out.setLast = true;
    }

    private Sdm.Selection prepare(Task task, Sdm.Selection in, ToolState ts, Sdm.Ctx ctx) {
        Sdm.Selection s = new Sdm.Selection();
        s.dropGroups.addAll(in.dropGroups);
        s.dropItems.addAll(in.dropItems);
        try { s.options = new JSONObject(in.options.toString()); } catch (JSONException e) { s.options = new JSONObject(); }
        try {
            if (task.tool == Sdm.Tool.APPCLEANER) {
                if (!s.options.has("includeInaccessible")) s.options.put("includeInaccessible", ctx.bool("include.inaccessible.enabled", true));
                if (!s.options.has("useAutomation")) s.options.put("useAutomation", ctx.automation != null);
            }
        } catch (JSONException ignored) {}
        View v = ts.view;
        if (v != null) { s.dropGroups.addAll(v.hiddenGroupIds()); s.dropItems.addAll(v.hiddenItemIds()); }
        return s;
    }

    private void doDelete(Task task, ToolState ts, Sdm.ToolImpl impl, Sdm.Ctx ctx, TaskFs tfs, Outcome out, Sdm.Selection requested) {
        final Sdm.Tool tool = task.tool;
        Sdm.Result raw;
        View view;
        synchronized (lock) { raw = ts.raw; view = ts.view; }
        if (raw == null || view == null) { out.status = "error"; out.error = "There is no scan result to delete from."; return; }
        out.record = true;
        long before = view.itemCount();
        Sdm.Selection sel = prepare(task, requested, ts, ctx);
        Sdm.DeleteReport rep = null;
        Throwable err = null;
        try {
            rep = impl.delete(raw, sel, ctx);
        } catch (PartialResultException p) {
            rep = p.report;
            err = p;
        } catch (Throwable e) {
            err = e;
        }
        view.invalidate();
        long after = view.itemCount();
        refreshSummary(tool);

        String variant = "";
        int skipped = 0;
        if (err instanceof Sdm.AutomationError) variant = ((Sdm.AutomationError) err).code;
        if (rep == null) {
            rep = new Sdm.DeleteReport();
            for (String p : tfs.gone()) rep.deleted.add(new Sdm.Deleted(p, 0, "", ""));
        }
        for (String note : rep.notes) {
            Matcher m = AUTOMATION_NOTE.matcher(note);
            if (m.find()) {
                variant = m.group(1).toUpperCase(Locale.ROOT);
                if (!m.group(2).isEmpty()) { try { skipped = Integer.parseInt(m.group(2)); } catch (NumberFormatException ignored) {} }
            }
        }
        long deleted = rep.deleted.size(), bytes = rep.bytes();
        long count = deleted;
        if (tool == Sdm.Tool.SYSTEMCLEANER || tool == Sdm.Tool.APPCLEANER) count = before - after > 0 ? before - after : deleted;
        for (Sdm.Deleted d : rep.deleted) out.paths.add(d.path);
        String[] tx = deleteTexts(tool, count, bytes, variant, skipped);
        // a tool may write its own receipt into the report notes (AppCleaner does: "primary: ", "secondary: ", "count: ", "bytes: ", "stopped: ", "skipped: ", "error: ")
        String notePrimary = null, noteSecondary = null, noteError = null;
        for (String note : rep.notes) {
            if (note.startsWith("primary: ")) notePrimary = note.substring(9);
            else if (note.startsWith("secondary: ")) noteSecondary = note.substring(11);
            else if (note.startsWith("count: ")) { try { count = Long.parseLong(note.substring(7).trim()); } catch (NumberFormatException ignored) {} }
            else if (note.startsWith("stopped: ")) {
                String c = note.substring(9).trim();
                variant = c.startsWith("AUTOMATION_") ? c.substring(11) : c;
            } else if (note.startsWith("skipped: ")) { try { skipped = Integer.parseInt(note.substring(9).trim()); } catch (NumberFormatException ignored) {} }
            else if (note.startsWith("error: ")) noteError = note.substring(7);
        }
        tx = deleteTexts(tool, count, bytes, variant, skipped);
        if (notePrimary != null) tx[0] = notePrimary;
        if (noteSecondary != null) tx[1] = noteSecondary;
        out.primary = tx[0]; out.secondary = tx[1]; out.primaryKey = tx[2]; out.secondaryKey = tx[3];
        out.count = count; out.bytes = bytes;
        out.setLast = true;

        boolean cancelled = task.cancelled && !task.timedOut;
        boolean failedSome = !rep.failed.isEmpty();
        if (cancelled) {
            if (deleted == 0) { out.status = "cancelled"; out.record = false; out.setLast = false; }
            else { out.status = "partial"; out.error = "Cancelled"; }
        } else if (task.timedOut) {
            out.status = deleted > 0 ? "partial" : "error";
            out.error = "The task took too long and was stopped.";
        } else if (err != null && !(err instanceof Sdm.AutomationError)) {
            out.status = deleted > 0 ? "partial" : "error";
            out.error = message(err);
        } else if ("SCREEN_UNAVAILABLE".equals(variant) || "ERROR".equals(variant)) {
            out.status = "partial";
            out.error = noteError != null ? noteError : err != null ? message(err) : "SCREEN_UNAVAILABLE".equals(variant) ? "The operation was aborted because the screen became unavailable (e.g. display off or lockscreen active)." : "The cleaning was stopped by an error.";
        } else if (noteError != null && count == 0 && bytes == 0) {
            out.status = "error";
            out.error = noteError;
        } else if (failedSome) {
            out.status = deleted > 0 ? "partial" : "error";
            out.error = plural(rep.failed.size(), "item could not be deleted", "items could not be deleted");
        } else {
            out.status = "ok";
        }
    }

    private void complete(Task task, ToolState ts, Outcome o) {
        JSONObject d;
        JSONObject last = null;
        long at = nowMs();
        try {
            d = new JSONObject().put("tool", task.tool.id).put("kind", task.kind).put("status", o.status).put("primary", o.primary).put("secondary", o.secondary)
                    .put("error", o.error).put("count", o.count).put("bytes", o.bytes).put("primaryKey", o.primaryKey).put("secondaryKey", o.secondaryKey).put("n", o.n)
                    .put("size", formatSize(o.bytes)).put("at", at);
            if (o.setLast && !"cancelled".equals(o.status)) last = new JSONObject(d.toString());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        if (o.record && !"scan".equals(task.kind)) {
            SdmHistory.Report r = new SdmHistory.Report();
            r.startAt = o.startAt == 0 ? at : o.startAt; r.endAt = o.endAt == 0 ? at : o.endAt;
            r.tool = task.tool.id;
            r.status = "ok".equals(o.status) ? SdmHistory.SUCCESS : "partial".equals(o.status) ? SdmHistory.PARTIAL : SdmHistory.FAILURE;
            r.primary = o.primary; r.secondary = o.secondary; r.error = o.error; r.count = o.count; r.bytes = o.bytes;
            if (!"cancelled".equals(o.status)) {
                history.add(r, o.paths);
                fireHistory();
            }
        }
        synchronized (lock) {
            ts.tasks.remove(task);
            task.finished = true;
            if (last != null) ts.lastResult = last;
            if (ts.tasks.isEmpty()) ts.progress = null;
        }
        fireDone(task.tool, d);
        fireState(task.tool);
    }

    // ---------------------------------------------------------------------------------------------------------- progress

    private static JSONObject progressJson(Sdm.Tool tool, String primary, String secondary, long done, long total, long bytes, boolean queued) {
        JSONObject p = new JSONObject();
        try {
            String type = queued ? "indeterminate" : total > 0 ? "percent" : done > 0 ? "counter" : "indeterminate";
            p.put("tool", tool.id).put("primary", primary).put("secondary", secondary).put("countType", type).put("current", Math.max(0, done)).put("max", Math.max(0, total))
                    .put("bytes", bytes).put("queued", queued);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    /** Receives the tool's progress and passes it on at most four times a second (the last update of a burst follows when the pause is over). */
    private final class ProgressSink implements Sdm.Progress {
        private final Task task;
        private long lastEmit;
        private JSONObject pending;
        private ScheduledFuture<?> flush;
        private boolean stopped;

        ProgressSink(Task task) { this.task = task; }

        @Override
        public void update(String primary, String secondary, long done, long total, long bytes) {
            String p = primary == null || primary.isEmpty() ? "Loading" : primary;
            String s = secondary == null || secondary.isEmpty() ? task.egg : secondary;
            JSONObject j = progressJson(task.tool, p, s, done, total, bytes, false);
            long now = System.currentTimeMillis();
            boolean emit = false;
            synchronized (this) {
                if (stopped) return;
                synchronized (lock) { states.get(task.tool).progress = j; }
                if (now - lastEmit >= THROTTLE_MS) {
                    lastEmit = now; pending = null; emit = true;
                    if (flush != null) { flush.cancel(false); flush = null; }
                } else {
                    pending = j;
                    if (flush == null) {
                        try {
                            flush = timer.schedule(new Runnable() { @Override public void run() { flushPending(); } }, THROTTLE_MS - (now - lastEmit), TimeUnit.MILLISECONDS);
                        } catch (RuntimeException ignored) {}
                    }
                }
            }
            if (emit) fireProgress(task.tool, j);
        }

        private void flushPending() {
            JSONObject j;
            synchronized (this) {
                if (stopped) return;
                j = pending; pending = null; flush = null; lastEmit = System.currentTimeMillis();
            }
            if (j != null) fireProgress(task.tool, j);
        }

        void stop() {
            synchronized (this) {
                stopped = true;
                if (flush != null) flush.cancel(false);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------- events

    private void fireProgress(Sdm.Tool t, JSONObject p) {
        try { events.progress(t.id, p); } catch (RuntimeException ignored) {}
    }

    private void fireState(Sdm.Tool t) {
        try { events.state(t.id, state(t)); } catch (RuntimeException ignored) {}
    }

    private void fireDone(Sdm.Tool t, JSONObject d) {
        try { events.done(t.id, d); } catch (RuntimeException ignored) {}
    }

    private void fireHistory() {
        try { events.history(); } catch (RuntimeException ignored) {}
    }

    // ---------------------------------------------------------------------------------------------------------- what the page asks

    /** {tool, state: idle|queued|scanning|ready|deleting, hasData, running, progress?, summary?, lastResult?} */
    public JSONObject state(Sdm.Tool t) {
        synchronized (lock) {
            ToolState ts = states.get(t);
            String state;
            boolean running = false;
            Task active = null, waiting = null;
            for (Task k : ts.tasks) {
                if (k.phase.equals("queued")) { if (waiting == null) waiting = k; }
                else { active = k; running = true; }
            }
            if (active != null) state = active.phase;
            else if (waiting != null) state = "queued";
            else state = ts.hasData ? "ready" : "idle";
            JSONObject o = new JSONObject();
            try {
                o.put("tool", t.id).put("state", state).put("hasData", ts.hasData).put("running", running).put("available", impls.containsKey(t));
                if (ts.progress != null && !ts.tasks.isEmpty()) o.put("progress", new JSONObject(ts.progress.toString()));
                if (ts.summary != null) o.put("summary", new JSONObject(ts.summary.toString()));
                if (ts.lastResult != null) o.put("lastResult", new JSONObject(ts.lastResult.toString()));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            return o;
        }
    }

    /** {tools: {id: state}, running: n, queued: n} */
    public JSONObject states() {
        JSONObject tools = new JSONObject();
        int running = 0, queued = 0;
        try {
            for (Sdm.Tool t : Sdm.Tool.values()) {
                tools.put(t.id, state(t));
                synchronized (lock) {
                    for (Task k : states.get(t).tasks) { if (k.phase.equals("queued")) queued++; else running++; }
                }
            }
            return new JSONObject().put("tools", tools).put("running", running).put("queued", queued);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private View viewIfIdle(Sdm.Tool t) {
        synchronized (lock) {
            ToolState ts = states.get(t);
            for (Task k : ts.tasks) if ("deleting".equals(k.phase) || "scanning".equals(k.phase)) return null;
            return ts.view;
        }
    }

    /** {groups: [...], total}; while the tool is busy (the result is changing) {groups: [], total: 0, busy: true}. */
    public JSONObject groups(Sdm.Tool t, int offset, int limit) {
        return groups(t, offset, limit, null, null);
    }

    /** Same with a text filter (label, sub, id contain q) and a sort ("size" = largest first, "name", "count"; null = the tool's own order). */
    public JSONObject groups(Sdm.Tool t, int offset, int limit, String sort, String q) {
        try {
            View v = viewIfIdle(t);
            if (v == null) {
                boolean busy = busy(t);
                return new JSONObject().put("groups", new JSONArray()).put("total", 0).put("busy", busy);
            }
            boolean filter = q != null && !q.trim().isEmpty();
            boolean sorted = sort != null && !sort.isEmpty() && !sort.equals("default");
            if (!filter && !sorted) {
                return new JSONObject().put("groups", v.groups(offset, limit)).put("total", v.groupCount());
            }
            JSONArray all = v.groups(0, Math.max(1, v.groupCount()));
            List<JSONObject> rows = new ArrayList<JSONObject>();
            String needle = filter ? q.trim().toLowerCase(Locale.ROOT) : "";
            for (int i = 0; i < all.length(); i++) {
                JSONObject r = all.getJSONObject(i);
                if (filter && !(r.optString("label").toLowerCase(Locale.ROOT).contains(needle) || r.optString("sub").toLowerCase(Locale.ROOT).contains(needle)
                        || r.optString("id").toLowerCase(Locale.ROOT).contains(needle) || r.optString("pkg").toLowerCase(Locale.ROOT).contains(needle))) continue;
                rows.add(r);
            }
            final String key = sort == null ? "" : sort;
            if (sorted) {
                Collections.sort(rows, new Comparator<JSONObject>() {
                    @Override public int compare(JSONObject a, JSONObject b) {
                        if (key.equals("name")) return a.optString("label").compareToIgnoreCase(b.optString("label"));
                        if (key.equals("count")) return Long.compare(b.optLong("count"), a.optLong("count"));
                        return Long.compare(b.optLong("bytes"), a.optLong("bytes"));
                    }
                });
            }
            JSONArray page = new JSONArray();
            for (int i = Math.max(0, offset); i < rows.size() && i < Math.max(0, offset) + Math.max(0, limit); i++) page.put(rows.get(i));
            return new JSONObject().put("groups", page).put("total", rows.size());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
        }
    }

    /** {items: [...], total}. */
    public JSONObject items(Sdm.Tool t, String groupId, int offset, int limit) {
        try {
            View v = viewIfIdle(t);
            if (v == null) return new JSONObject().put("items", new JSONArray()).put("total", 0).put("busy", busy(t));
            return v.itemsPage(groupId, offset, limit);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(message(e), e);
        }
    }

    /** Forgets the result and the last result of the tool (the card goes back to idle); ignored while the tool is busy. */
    public void discard(Sdm.Tool t) {
        synchronized (lock) {
            ToolState ts = states.get(t);
            if (!ts.tasks.isEmpty()) return;
            ts.raw = null; ts.view = null; ts.lastResult = null; ts.progress = null; ts.summary = null; ts.hasData = false;
        }
        fireState(t);
    }

    // ---------------------------------------------------------------------------------------------------------- exclude / undo

    /**
     * Creates tool-tagged exclusions for {paths: [...], pkgs: [...]} and hides them from the current result at once (counts and sizes follow).
     * Returns {ids: [...], handle}; {@link #undoExclude} takes it back.
     */
    public JSONObject exclude(Sdm.Tool t, JSONObject targets) {
        List<String> paths = new ArrayList<String>(), pkgs = new ArrayList<String>();
        JSONArray pa = targets.optJSONArray("paths"), ka = targets.optJSONArray("pkgs");
        if (pa != null) for (int i = 0; i < pa.length(); i++) paths.add(pa.optString(i));
        if (ka != null) for (int i = 0; i < ka.length(); i++) pkgs.add(ka.optString(i));
        if (paths.isEmpty() && pkgs.isEmpty()) throw new IllegalArgumentException("Nothing to exclude");
        String handle;
        SdmExclusions.Created c;
        synchronized (lock) {
            ToolState ts = states.get(t);
            for (Task k : ts.tasks) if (!k.phase.equals("queued")) throw new IllegalStateException("The tool is busy");
            c = exclusions.create(t, paths, pkgs);
            handle = "x" + (++undoCounter) + "-" + Integer.toHexString(random.nextInt());
            View v = ts.view;
            if (v != null) v.hide(handle, paths, pkgs);
            undos.put(handle, new Undo(t, c, v));
            while (undos.size() > 32) undos.remove(undos.keySet().iterator().next());
        }
        refreshSummary(t);
        fireState(t);
        try {
            return new JSONObject().put("handle", handle).put("ids", new JSONArray(c.ids)).put("count", c.ids.size());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    public JSONObject undoExclude(String handle) {
        Undo u;
        synchronized (lock) {
            u = undos.remove(handle);
            if (u == null) throw new IllegalArgumentException("There is nothing to undo.");
            exclusions.revert(u.created);
            if (u.view != null) u.view.unhide(handle);
        }
        refreshSummary(u.tool);
        fireState(u.tool);
        try {
            return new JSONObject().put("undone", u.created.ids.size());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------------- shutdown

    public void shutdown() {
        shut = true;
        cancelAll();
        pool.shutdownNow();
        timer.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------------------- the guarded file system

    /**
     * What a task's tool gets as {@code ctx.fs}: every read goes to the phone's file system, every delete first passes {@link SdmSafety} and the exclusions of
     * the tool (a path that is excluded, or has an excluded path below it, is refused), and a dry run reports success without touching anything. It remembers
     * what it removed, which is what the engine falls back on when a tool fails without a report. A walk is pruned at the excluded paths inside the shell process.
     */
    private final class TaskFs implements PruningFs {
        private final Sdm.Fs in;
        private final Sdm.Tool tool;
        private final Sdm.Areas areas;
        private final boolean dry;
        private final Set<String> gone = Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

        TaskFs(Sdm.Fs in, Sdm.Tool tool, Sdm.Areas areas, boolean dry) { this.in = in; this.tool = tool; this.areas = areas; this.dry = dry; }

        List<String> gone() { synchronized (gone) { return new ArrayList<String>(gone); } }

        private boolean allowed(String path) {
            return SdmSafety.check(path, areas) == null && !exclusions.blocksDelete(tool, path);
        }

        @Override public String[] list(String dir) throws IOException { return in.list(dir); }
        @Override public Sdm.Entry stat(String path) throws IOException { return in.stat(path); }
        @Override public boolean exists(String path) { return in.exists(path); }
        @Override public String sha256(String path, Sdm.Cancel cancel) throws IOException { return in.sha256(path, cancel); }
        @Override public byte[] head(String path, int max) throws IOException { return in.head(path, max); }

        @Override public void walk(String root, Sdm.EntrySink sink, Sdm.Cancel cancel) throws IOException {
            if (in instanceof PruningFs) {
                List<String> prune = new ArrayList<String>();
                for (String p : exclusions.paths(tool)) if (Sdm.isInside(p, root)) prune.add(p);
                ((PruningFs) in).walk(root, prune, sink, cancel);
            } else {
                in.walk(root, sink, cancel);
            }
        }

        @Override public void walk(String root, Collection<String> prune, Sdm.EntrySink sink, Sdm.Cancel cancel) throws IOException {
            List<String> all = new ArrayList<String>(prune);
            for (String p : exclusions.paths(tool)) if (Sdm.isInside(p, root) && !all.contains(p)) all.add(p);
            if (in instanceof PruningFs) ((PruningFs) in).walk(root, all, sink, cancel);
            else in.walk(root, sink, cancel);
        }

        @Override public boolean delete(String path) {
            if (!allowed(path)) return false;
            if (dry) { gone.add(path); return true; }
            boolean ok = in.delete(path);
            if (ok) gone.add(path);
            return ok;
        }

        @Override public Set<String> deleteAll(Collection<String> paths, Sdm.Cancel cancel) {
            List<String> ok = new ArrayList<String>();
            for (String p : paths) if (allowed(p)) ok.add(p);
            Set<String> res;
            if (dry) res = new HashSet<String>(ok);
            else res = ok.isEmpty() ? new HashSet<String>() : in.deleteAll(ok, cancel);
            gone.addAll(res);
            return res;
        }
    }

    // ---------------------------------------------------------------------------------------------------------- the view of a result without the excluded parts

    /**
     * What the page sees of a tool's {@link Sdm.Result}: the same, minus what the user excluded since the scan (paths below an excluded path, the strict
     * ancestors of excluded paths, whole packages), with counts and sizes of the groups adjusted and empty groups gone. Without exclusions it simply forwards.
     */
    private static final class View implements Sdm.Result {
        private final Sdm.Result in;
        private final Sdm.Tool tool;
        private final LinkedHashMap<String, Object[]> batches = new LinkedHashMap<String, Object[]>();   // handle -> {paths, pkgs}
        private Set<String> hidPaths = new HashSet<String>(), hidAncestors = new HashSet<String>(), hidPkgs = new HashSet<String>();
        // caches, valid until invalidate()
        private JSONArray groupCache;
        private long itemsCache, bytesCache;
        private final Map<String, int[]> visible = new java.util.HashMap<String, int[]>();
        private final Set<String> hiddenGroups = new HashSet<String>(), hiddenItems = new HashSet<String>();
        private Map<String, Long> countCache;

        View(Sdm.Result in, Sdm.Tool tool) { this.in = in; this.tool = tool; }

        private boolean plain() { return hidPaths.isEmpty() && hidPkgs.isEmpty(); }

        synchronized void hide(String handle, List<String> paths, List<String> pkgs) {
            batches.put(handle, new Object[] { new ArrayList<String>(paths), new ArrayList<String>(pkgs) });
            recompute();
        }

        synchronized void unhide(String handle) {
            batches.remove(handle);
            recompute();
        }

        @SuppressWarnings("unchecked")
        private void recompute() {
            Set<String> p = new HashSet<String>(), a = new HashSet<String>(), k = new HashSet<String>();
            for (Object[] b : batches.values()) {
                for (String x : (List<String>) b[0]) {
                    String path = x.length() > 1 && x.endsWith("/") ? x.substring(0, x.length() - 1) : x;
                    p.add(path);
                    for (int i = path.length() - 1; i > 0; i--) if (path.charAt(i) == '/') a.add(path.substring(0, i));
                    if (path.length() > 1) a.add("/");
                }
                k.addAll((List<String>) b[1]);
            }
            hidPaths = p; hidAncestors = a; hidPkgs = k;
            invalidate();
        }

        synchronized void invalidate() {
            groupCache = null; countCache = null; visible.clear(); hiddenGroups.clear(); hiddenItems.clear();
        }

        private boolean hiddenPath(String path) {
            if (path == null || path.isEmpty()) return false;
            if (hidPaths.contains(path) || hidAncestors.contains(path)) return true;
            for (int i = path.length() - 1; i > 0; i--) if (path.charAt(i) == '/' && hidPaths.contains(path.substring(0, i))) return true;
            return false;
        }

        private boolean hiddenGroup(JSONObject row) {
            if (!hidPkgs.isEmpty()) {
                if (hidPkgs.contains(row.optString("pkg", "")) || hidPkgs.contains(row.optString("owner", ""))) return true;
                if (tool == Sdm.Tool.APPCLEANER && hidPkgs.contains(row.optString("id", ""))) return true;
            }
            String path = row.optString("path", "");
            return !path.isEmpty() && hiddenPath(path);
        }

        private synchronized void build() throws Exception {
            if (groupCache != null) return;
            JSONArray rows = in.groups(0, Math.max(1, in.groupCount()));
            JSONArray out = new JSONArray();
            long items = 0, bytes = 0;
            hiddenGroups.clear(); hiddenItems.clear();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject g = rows.getJSONObject(i);
                String id = g.optString("id");
                if (hiddenGroup(g)) { hiddenGroups.add(id); continue; }
                long count = g.optLong("count"), gb = g.optLong("bytes");
                if (!hidPaths.isEmpty() && count > 0) {
                    long hc = 0, hb = 0;
                    for (int off = 0; ; off += 1000) {
                        JSONArray page = in.items(id, off, 1000);
                        if (page == null || page.length() == 0) break;
                        for (int k = 0; k < page.length(); k++) {
                            JSONObject it = page.getJSONObject(k);
                            if (hiddenPath(it.optString("path"))) { hc++; hb += it.optLong("size"); hiddenItems.add(it.optString("id", it.optString("path"))); }
                        }
                        if (page.length() < 1000) break;
                    }
                    if (hc > 0) {
                        count -= hc; gb = Math.max(0, gb - hb);
                        if (count <= 0) { hiddenGroups.add(id); continue; }
                        g.put("count", count); g.put("bytes", gb);
                    }
                }
                out.put(g);
                items += count; bytes += gb;
            }
            groupCache = out; itemsCache = items; bytesCache = bytes;
        }

        Set<String> hiddenGroupIds() {
            if (plain()) return new HashSet<String>();
            try { build(); } catch (Exception e) { return new HashSet<String>(); }
            synchronized (this) { return new HashSet<String>(hiddenGroups); }
        }

        Set<String> hiddenItemIds() {
            if (plain()) return new HashSet<String>();
            try { build(); } catch (Exception e) { return new HashSet<String>(); }
            synchronized (this) { return new HashSet<String>(hiddenItems); }
        }

        @Override public Sdm.Tool tool() { return in.tool(); }

        @Override public int groupCount() {
            if (plain()) return in.groupCount();
            try { build(); synchronized (this) { return groupCache.length(); } } catch (Exception e) { return in.groupCount(); }
        }

        @Override public int itemCount() {
            if (plain()) return in.itemCount();
            try { build(); synchronized (this) { return (int) itemsCache; } } catch (Exception e) { return in.itemCount(); }
        }

        @Override public long bytes() {
            if (plain()) return in.bytes();
            try { build(); synchronized (this) { return bytesCache; } } catch (Exception e) { return in.bytes(); }
        }

        @Override public JSONArray groups(int offset, int limit) throws Exception {
            if (plain()) return in.groups(offset, limit);
            build();
            JSONArray out = new JSONArray();
            synchronized (this) {
                for (int i = Math.max(0, offset); i < groupCache.length() && i < Math.max(0, offset) + Math.max(0, limit); i++) out.put(groupCache.get(i));
            }
            return out;
        }

        @Override public JSONArray items(String groupId, int offset, int limit) throws Exception {
            return itemsPage(groupId, offset, limit).getJSONArray("items");
        }

        JSONObject itemsPage(String groupId, int offset, int limit) throws Exception {
            if (plain()) {
                JSONArray a = in.items(groupId, offset, limit);
                int total = total(groupId);
                return new JSONObject().put("items", a).put("total", total);
            }
            build();
            synchronized (this) { if (hiddenGroups.contains(groupId)) return new JSONObject().put("items", new JSONArray()).put("total", 0); }
            int[] idx;
            synchronized (this) { idx = visible.get(groupId); }
            if (idx == null) {
                int[] tmp = new int[16];
                int n = 0, pos = 0;
                for (int off = 0; ; off += 1000) {
                    JSONArray page = in.items(groupId, off, 1000);
                    if (page == null || page.length() == 0) break;
                    for (int k = 0; k < page.length(); k++, pos++) {
                        if (hiddenPath(page.getJSONObject(k).optString("path"))) continue;
                        if (n == tmp.length) tmp = java.util.Arrays.copyOf(tmp, n * 2);
                        tmp[n++] = pos;
                    }
                    if (page.length() < 1000) break;
                }
                idx = java.util.Arrays.copyOf(tmp, n);
                synchronized (this) { visible.put(groupId, idx); }
            }
            int from = Math.max(0, offset), to = Math.min(idx.length, from + Math.max(0, limit));
            JSONArray out = new JSONArray();
            if (from < to) {
                int lo = idx[from], hi = idx[to - 1];
                JSONArray raw = in.items(groupId, lo, hi - lo + 1);
                int k = 0;
                for (int i = from; i < to; i++) {
                    int want = idx[i] - lo;
                    if (raw != null && want < raw.length()) out.put(raw.get(want));
                    k++;
                }
            }
            return new JSONObject().put("items", out).put("total", idx.length);
        }

        /** The number of items of a group without exclusions: the group row's count (all group rows are read once and kept until the result changes). */
        private synchronized int total(String groupId) throws Exception {
            if (countCache == null) {
                Map<String, Long> m = new java.util.HashMap<String, Long>();
                JSONArray rows = in.groups(0, Math.max(1, in.groupCount()));
                for (int i = 0; i < rows.length(); i++) m.put(rows.getJSONObject(i).optString("id"), rows.getJSONObject(i).optLong("count"));
                countCache = m;
            }
            Long c = countCache.get(groupId);
            return c == null ? 0 : (int) (long) c;
        }

        @Override public JSONObject summary() throws Exception {
            return in.summary();
        }
    }
}
