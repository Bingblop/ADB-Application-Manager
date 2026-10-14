package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code /proc/stat}'s CPU-time accounting lines: the aggregate {@code cpu} line, then the per-core {@code cpu0},
 * {@code cpu1}, ... lines that follow it in device order, before the unrelated {@code intr} / {@code ctxt} / {@code btime} /
 * {@code processes} / {@code procs_running} / {@code procs_blocked} / {@code softirq} lines further down the file (all ignored
 * here). Pure Java, free of Android imports, so it can be tested off the device.
 *
 * <p>Every number on a {@code cpu}/{@code cpuN} line is a count of USER_HZ clock ticks since boot, not seconds (every Android
 * kernel reports 100 ticks/sec, but nothing here needs to know that or multiply by it): a load percentage only ever compares
 * the *change* in two counters over the same stretch of time, as a plain ratio, so whatever the tick rate is, it cancels out.
 * That is why {@link Snapshot} keeps the raw tick counts and {@link #percentBusy} works only from deltas between two readings.
 *
 * <p>Defensive throughout: a short, reordered, garbled or entirely blank {@code /proc/stat} text never throws and never comes
 * back null — the worst it does is parse to an all-zero aggregate with no per-core readings.
 */
final class CpuStats {

    private CpuStats() {}

    /** One {@code cpu}/{@code cpuN} line's eight accounted tick counters. Newer kernels add guest/guest_nice fields after
     *  these eight; they are read (so the line still parses) and then dropped, since nothing here needs them. */
    static final class Snapshot {
        public long user, nice, system, idle, iowait, irq, softirq, steal;
        /** The core's number from its label ({@code cpu3} is 3); -1 for the aggregate {@code cpu} line. */
        public int id = -1;

        /** The sum of all eight fields: every tick the kernel accounted for this CPU over the time this snapshot covers. */
        public long total() { return user + nice + system + idle + iowait + irq + softirq + steal; }
    }

    /** The {@code cpu} line's snapshot, and the {@code cpu0}, {@code cpu1}, ... snapshots in the order the kernel printed them.
     *  The kernel prints only the cores that are online, so with cpu2 offline the list is cpu0, cpu1, cpu3: a position in it is not
     *  a core number, {@link Snapshot#id} is. */
    static final class Reading {
        public Snapshot aggregate = new Snapshot();
        public List<Snapshot> perCore = new ArrayList<Snapshot>();
    }

    /**
     * Reads the {@code cpu} line into {@link Reading#aggregate} and every {@code cpuN} line into {@link Reading#perCore}, in
     * the order they appear; every other line — {@code intr}, {@code ctxt}, {@code btime}, {@code processes}, a line that only
     * starts with "cpu" without being one ({@code cpufreq}, say), a blank line — is skipped rather than guessed at. A line with
     * fewer than eight numeric fields is padded with zeros for the missing ones; a non-numeric field reads as zero. No per-core
     * lines at all (an aggregate-only file) comes back with an empty {@code perCore}; blank or unrecognisable text comes back
     * with an all-zero aggregate and an empty {@code perCore}. This never throws and never returns null.
     */
    static Reading parse(String procStatText) {
        Reading r = new Reading();
        if (procStatText == null) return r;
        for (String raw : procStatText.split("\r?\n", -1)) {
            String line = raw.trim();
            if (!line.startsWith("cpu")) continue;
            int i = 3;
            while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
            if (i == line.length() || !Character.isWhitespace(line.charAt(i))) continue;  // e.g. "cpufreq ...": not a cpu-time line
            String label = line.substring(0, i);                                          // "cpu", "cpu0", "cpu1", ...
            String rest = line.substring(i).trim();
            String[] f = rest.isEmpty() ? new String[0] : rest.split("\\s+");
            Snapshot s = new Snapshot();
            s.user = field(f, 0);
            s.nice = field(f, 1);
            s.system = field(f, 2);
            s.idle = field(f, 3);
            s.iowait = field(f, 4);
            s.irq = field(f, 5);
            s.softirq = field(f, 6);
            s.steal = field(f, 7);
            // f[8]/f[9] (guest/guest_nice), if present, are intentionally not read into Snapshot
            if (label.equals("cpu")) r.aggregate = s;
            else {
                try { s.id = Integer.parseInt(label.substring(3)); } catch (NumberFormatException e) { continue; }   // too many digits: not a core
                r.perCore.add(s);
            }
        }
        return r;
    }

    private static long field(String[] f, int i) {
        if (i >= f.length) return 0;
        try {
            return Long.parseLong(f[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 0.0..100.0: {@code 100 * (1 - idleDelta/totalDelta)} between two readings of the same CPU (the aggregate, or one core).
     * A total that did not grow — zero, or negative because a counter was reset or the two readings were passed in swapped —
     * answers 0.0 rather than NaN or a divide-by-zero; an idle count that somehow fell is clamped to a zero delta rather than
     * pushed over 100%. This is meant to be shown straight in a UI, so the answer always stays in 0.0..100.0.
     */
    static double percentBusy(Snapshot before, Snapshot after) {
        if (before == null || after == null) return 0.0;
        long totalDelta = after.total() - before.total();
        if (totalDelta <= 0) return 0.0;
        long idleDelta = after.idle - before.idle;
        if (idleDelta < 0) idleDelta = 0;
        double busy = 100.0 * (1.0 - (double) idleDelta / (double) totalDelta);
        if (busy < 0.0) return 0.0;
        if (busy > 100.0) return 100.0;
        return busy;
    }

    /**
     * {@link #percentBusy} for each core of {@code after} that {@code before} also has, matched by core number (cpu3 against cpu3),
     * in {@code after}'s order. A core that came online between the two readings has nothing to compare with and is left out; one
     * that went offline is not in {@code after}. {@link #perCoreIds} names the core of each value. With CPU hotplug the kernel
     * leaves offline cores out of /proc/stat, so matching by position would compare one core's counters with another's.
     */
    static double[] percentBusyPerCore(Reading before, Reading after) {
        List<Snapshot[]> pairs = pairs(before, after);
        double[] out = new double[pairs.size()];
        for (int i = 0; i < out.length; i++) out[i] = percentBusy(pairs.get(i)[0], pairs.get(i)[1]);
        return out;
    }

    /** The core numbers of {@link #percentBusyPerCore}'s values, in the same order. */
    static int[] perCoreIds(Reading before, Reading after) {
        List<Snapshot[]> pairs = pairs(before, after);
        int[] out = new int[pairs.size()];
        for (int i = 0; i < out.length; i++) out[i] = pairs.get(i)[1].id;
        return out;
    }

    private static List<Snapshot[]> pairs(Reading before, Reading after) {
        List<Snapshot[]> out = new ArrayList<Snapshot[]>();
        if (before == null || after == null || before.perCore == null || after.perCore == null) return out;
        for (Snapshot a : after.perCore) {
            for (Snapshot b : before.perCore) {
                if (b.id == a.id) { out.add(new Snapshot[] {b, a}); break; }
            }
        }
        return out;
    }

    /** How many cores a reading shows: the highest core number + 1 (so an offline core in the middle still counts), and at least
     *  the number of per-core lines; 0 for a null reading or one with no per-core lines at all. */
    static int coreCount(Reading r) {
        if (r == null || r.perCore == null) return 0;
        int n = r.perCore.size();
        for (Snapshot s : r.perCore) if (s.id + 1 > n) n = s.id + 1;
        return n;
    }

    /** One run of adjacent cores that share a clock domain (a big.LITTLE/tri-cluster "cluster"): how many cores,
     *  and that shared domain's lowest, current and highest frequency in Hz. {@code curHz} is core 0 of the run's
     *  reading, the others in the same cluster almost always matching it, since they share one clock. */
    static final class Cluster {
        public int cores;
        public long minHz, curHz, maxHz;
    }

    /**
     * Groups {@code cpuN}'s {@code cpuinfo_min_freq}/{@code scaling_cur_freq}/{@code cpuinfo_max_freq} (one entry per
     * core, core order, Hz) into clusters: a new cluster starts whenever a core's (min, max) pair differs from the
     * previous core's, so a device with uniform cores comes back as a single cluster and one with a dead/offline core
     * reading 0 still groups sensibly rather than throwing. The three arrays must be the same length (one entry per
     * core); a short or empty input comes back as an empty list rather than throwing.
     */
    static List<Cluster> groupClusters(long[] minHz, long[] curHz, long[] maxHz) {
        List<Cluster> out = new ArrayList<Cluster>();
        if (minHz == null || curHz == null || maxHz == null) return out;
        int n = Math.min(minHz.length, Math.min(curHz.length, maxHz.length));
        for (int i = 0; i < n; i++) {
            if (!out.isEmpty()) {
                Cluster last = out.get(out.size() - 1);
                if (last.minHz == minHz[i] && last.maxHz == maxHz[i]) {
                    last.cores++;
                    continue;
                }
            }
            Cluster c = new Cluster();
            c.cores = 1;
            c.minHz = minHz[i];
            c.curHz = curHz[i];
            c.maxHz = maxHz[i];
            out.add(c);
        }
        return out;
    }
}
