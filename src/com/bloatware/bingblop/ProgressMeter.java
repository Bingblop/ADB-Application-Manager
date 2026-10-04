package com.bloatware.bingblop;

import java.util.Locale;

/**
 * What a long job tells the person: how far it is, how fast, how long is left, and whether it has stopped moving. Pure Java with the time passed in, so the
 * numbers can be checked without waiting. Speed is smoothed (an exponential average over samples at least 400 ms apart), so one slow file does not make
 * the time left jump about.
 */
public final class ProgressMeter {
    private final long total;                 // bytes expected, or 0 when not known
    private final int totalItems;             // items expected, or 0
    private final long startMs;
    private long lastSampleMs, lastSampleBytes, lastMoveMs;
    private long bytes;
    private int items;
    private double speed;                     // bytes per second, smoothed
    private String current = "";

    public ProgressMeter(long totalBytes, int totalItems, long nowMs) {
        this.total = Math.max(0, totalBytes);
        this.totalItems = Math.max(0, totalItems);
        this.startMs = nowMs;
        this.lastSampleMs = nowMs;
        this.lastMoveMs = nowMs;
    }

    /** Tell it where the job is now (bytes and items finished so far, and the name being worked on). */
    public void update(long bytesDone, int itemsDone, String name, long nowMs) {
        if (bytesDone > bytes || itemsDone > items) lastMoveMs = nowMs;
        bytes = Math.max(bytes, bytesDone);
        items = Math.max(items, itemsDone);
        if (name != null) current = name;
        long dt = nowMs - lastSampleMs;
        if (dt >= 400) {
            double inst = (bytes - lastSampleBytes) * 1000.0 / dt;
            speed = speed <= 0 ? inst : speed * 0.7 + inst * 0.3;
            lastSampleMs = nowMs;
            lastSampleBytes = bytes;
        }
    }

    /** True when nothing has moved for {@code afterMs}. */
    public boolean stalled(long nowMs, long afterMs) { return nowMs - lastMoveMs >= afterMs; }

    public long stalledMs(long nowMs) { return Math.max(0, nowMs - lastMoveMs); }

    /** 0..100, or -1 when the total is not known. */
    public int percent() {
        if (total > 0) return (int) Math.min(100, bytes * 100 / total);
        if (totalItems > 0) return (int) Math.min(100, items * 100L / totalItems);
        return -1;
    }

    public double bytesPerSecond() { return speed; }

    /** Seconds left at the current speed, or -1 when it cannot be told yet. */
    public long etaSeconds() {
        if (total <= 0 || speed < 1) return -1;
        long left = total - bytes;
        if (left <= 0) return 0;
        return (long) Math.ceil(left / speed);
    }

    public static String size(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    public static String duration(long secs) {
        if (secs < 0) return "";
        if (secs < 60) return secs + " s";
        if (secs < 3600) return (secs / 60) + " min " + (secs % 60) + " s";
        return (secs / 3600) + " h " + ((secs % 3600) / 60) + " min";
    }

    /** "Copying 3 of 12: name - 45% - 4.2 MB/s - 8 s left" (the parts that are known). */
    public String line(String verb, long nowMs) {
        StringBuilder s = new StringBuilder(verb);
        if (totalItems > 0) s.append(' ').append(Math.min(items + 1, totalItems)).append(" of ").append(totalItems);
        if (!current.isEmpty()) s.append(totalItems > 0 ? ": " : " ").append(current);
        int p = percent();
        StringBuilder tail = new StringBuilder();
        if (p >= 0) tail.append(p).append('%');
        if (speed >= 1) { if (tail.length() > 0) tail.append(" · "); tail.append(size((long) speed)).append("/s"); }
        long eta = etaSeconds();
        if (eta >= 0 && nowMs - startMs > 1500) { if (tail.length() > 0) tail.append(" · "); tail.append(duration(eta)).append(" left"); }
        if (tail.length() > 0) s.append(" — ").append(tail);
        return s.toString();
    }
}
