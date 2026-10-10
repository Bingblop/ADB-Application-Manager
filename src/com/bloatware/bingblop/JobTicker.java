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

    public JobTicker(ProgressMeter meter, String verb, long periodMs, long stallAfterMs, Listener listener) {
        this.meter = meter; this.verb = verb; this.periodMs = periodMs; this.stallAfterMs = stallAfterMs; this.listener = listener;
    }

    public synchronized void start() {
        if (ex != null) return;
        ex = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, "job-ticker"); t.setDaemon(true); return t; }
        });
        task = ex.scheduleWithFixedDelay(new Runnable() { @Override public void run() { tick(); } }, 0, periodMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (task != null) task.cancel(false);
        if (ex != null) ex.shutdownNow();
        task = null; ex = null;
    }

    /** One report (also called by the thread). */
    public void tick() {
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
