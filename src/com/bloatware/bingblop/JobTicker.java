package com.bloatware.bingblop;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tells the page and the notification how a long job is doing every second, on its own thread, so the numbers keep coming (and a stall is noticed)
 * even when the worker is stuck inside a read that never returns. The worker only feeds the {@link ProgressMeter}.
 */
public final class JobTicker {
    public interface Listener {
        /** pct is -1 when unknown. stalledMs is 0 while the job moves. */
        void onTick(String text, int pct, long stalledMs);
    }

    private final ProgressMeter meter;
    private final String verb;
    private final long periodMs, stallAfterMs;
    private final Listener listener;
    private ScheduledExecutorService ex;
    private ScheduledFuture<?> task;
    private volatile boolean stopped;
    private final Object tickLock = new Object();     // held while a report is made: stop() waits for the one under way and no later one starts

    public JobTicker(ProgressMeter meter, String verb, long periodMs, long stallAfterMs, Listener listener) {
        this.meter = meter; this.verb = verb; this.periodMs = periodMs; this.stallAfterMs = stallAfterMs; this.listener = listener;
    }

    public synchronized void start() {
        if (ex != null) return;
        stopped = false;
        ex = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "job-ticker"); t.setDaemon(true); return t; }
        });
        task = ex.scheduleWithFixedDelay(new Runnable() { @Override public void run() { tick(); } }, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    /** Ends the reports: when this returns no report is under way and none follows, so the result that comes next is not written over by a stale one. */
    public void stop() {
        synchronized (this) {
            stopped = true;
            if (task != null) task.cancel(false);
            if (ex != null) ex.shutdownNow();
            task = null; ex = null;
        }
        synchronized (tickLock) { }                   // a report that had already started finishes first (reentrant: harmless from the listener itself)
    }

    /** One report (also called by the thread); nothing after stop(). */
    public void tick() {
        synchronized (tickLock) {
            if (stopped) return;
            long now = System.currentTimeMillis();
            String text;
            int pct;
            long stalled;
            synchronized (meter) {
                text = meter.line(verb, now);
                pct = meter.percent();
                stalled = meter.stalled(now, stallAfterMs) ? meter.stalledMs(now) : 0;
            }
            if (stalled > 0) text += " — nothing has moved for " + ProgressMeter.duration(stalled / 1000) + ". Cancel stops it.";
            try { listener.onTick(text, pct, stalled); } catch (RuntimeException ignored) {}
        }
    }
}
