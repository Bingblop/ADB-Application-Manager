package com.bloatware.bingblop;

import java.util.Objects;

/** /proc/stat CPU-time parsing: the aggregate and per-core lines, tolerance for missing cores, extra kernel fields and
 *  garbage/blank text, and the busy-percentage math (including counter resets and a core-count mismatch). */
public class CpuStatsTest {
    static int n = 0, fails = 0;
    static void eq(String what, Object got, Object want) { n++; if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }

    static CpuStats.Snapshot snap(long user, long nice, long system, long idle, long iowait, long irq, long softirq, long steal) {
        CpuStats.Snapshot s = new CpuStats.Snapshot();
        s.user = user; s.nice = nice; s.system = system; s.idle = idle; s.iowait = iowait; s.irq = irq; s.softirq = softirq; s.steal = steal;
        return s;
    }

    public static void main(String[] a) {
        // ---- a normal full sample: aggregate + 4 cores, mixed in with the non-cpu lines that must be ignored ----
        String normal = "cpu  140305 2043 46213 1566940 8604 0 1420 0\n"
            + "cpu0 17827 220 6064 194114 1092 0 341 0\n"
            + "cpu1 17026 269 5921 196341 1103 0 268 0\n"
            + "cpu2 17622 258 6066 195151 1098 0 243 0\n"
            + "cpu3 17474 243 5868 196240 1077 0 219 0\n"
            + "intr 59832914 32 0 0 0\n"
            + "ctxt 93482910\n"
            + "btime 1733344000\n"
            + "processes 28391\n"
            + "procs_running 2\n"
            + "procs_blocked 0\n"
            + "softirq 59832914 123 456 789\n";
        CpuStats.Reading r = CpuStats.parse(normal);
        eq("aggregate user", r.aggregate.user, 140305L); eq("aggregate idle", r.aggregate.idle, 1566940L); eq("aggregate steal", r.aggregate.steal, 0L);
        eq("aggregate total", r.aggregate.total(), 1765525L);
        eq("core count", CpuStats.coreCount(r), 4);
        eq("cpu0 user", r.perCore.get(0).user, 17827L); eq("cpu0 idle", r.perCore.get(0).idle, 194114L); eq("cpu0 total", r.perCore.get(0).total(), 219658L);
        eq("cpu3 iowait", r.perCore.get(3).iowait, 1077L);
        is("intr/ctxt/btime/processes/softirq lines did not become cores or change the aggregate", CpuStats.coreCount(r) == 4 && r.aggregate.total() == 1765525L);

        // ---- missing per-core lines: aggregate-only /proc/stat ----
        String aggregateOnly = "cpu  140305 2043 46213 1566940 8604 0 1420 0\n"
            + "intr 59832914 32 0 0 0\n"
            + "ctxt 93482910\n"
            + "btime 1733344000\n";
        r = CpuStats.parse(aggregateOnly);
        eq("aggregate-only: aggregate still parses", r.aggregate.total(), 1765525L);
        eq("aggregate-only: no cores", CpuStats.coreCount(r), 0);
        is("aggregate-only: perCore is empty, not null", r.perCore != null && r.perCore.isEmpty());

        // ---- extra/unknown trailing fields (guest, guest_nice) and an unknown future line, including one that starts with "cpu" but isn't a cpu-time line ----
        String extra = "cpu  200000 3000 50000 1600000 9000 10 1500 5 1200 300\n"
            + "cpu0 25000 375 6250 200000 1125 1 187 0 150 37\n"
            + "cpu1 25000 375 6250 200000 1125 1 187 0 150 37\n"
            + "\n"
            + "cpufreq_transitions 42\n"
            + "intr 1 2 3\n"
            + "ctxt 1000\n"
            + "btime 1700000000\n"
            + "processes 100\n"
            + "procs_running 1\n"
            + "procs_blocked 0\n"
            + "softirq 1 2 3 4 5 6 7 8 9 10\n"
            + "weirdfutureline some new kernel thing we dont know about\n";
        r = CpuStats.parse(extra);
        eq("guest/guest_nice ignored: aggregate total is only the 8 known fields", r.aggregate.total(), 1863515L);
        eq("extra fields: core count", CpuStats.coreCount(r), 2);
        eq("extra fields: cpu0 total also only the 8 known fields", r.perCore.get(0).total(), 232938L);
        eq("extra fields: cpu1 total", r.perCore.get(1).total(), 232938L);
        is("'cpufreq_transitions 42' (starts with \"cpu\" but isn't cpuN) did not become a core", CpuStats.coreCount(r) == 2);

        // ---- blank / garbage input: never throws, never null, comes back all-zero ----
        for (String bad : new String[] {"", "   \n\n  ", "this is not kernel output at all\njust 123 456 789 random text"}) {
            CpuStats.Reading br = CpuStats.parse(bad);
            is("blank/garbage [" + bad.replace("\n", "\\n") + "]: aggregate is all zero", br.aggregate.total() == 0 && br.aggregate.idle == 0);
            is("blank/garbage [" + bad.replace("\n", "\\n") + "]: no cores", CpuStats.coreCount(br) == 0);
        }
        CpuStats.Reading nr = CpuStats.parse(null);
        is("parse(null): all zero, not a crash", nr.aggregate.total() == 0 && CpuStats.coreCount(nr) == 0);

        // ---- percentBusy: the normal case ----
        CpuStats.Snapshot before1 = snap(1000, 0, 500, 8000, 100, 0, 0, 0);     // total 9600
        CpuStats.Snapshot after1 = snap(1100, 0, 600, 8200, 100, 0, 0, 0);      // total 10000, idle +200 of +400
        eq("percentBusy: 200 idle of 400 total growth is 50%", CpuStats.percentBusy(before1, after1), 50.0);

        // ---- percentBusy: counter reset (after's total is lower than before's) must read as 0, not negative/NaN ----
        eq("percentBusy: a backward total (counter reset) is 0.0", CpuStats.percentBusy(after1, before1), 0.0);

        // ---- percentBusy: identical readings (totalDelta == 0) must read as 0, not NaN ----
        eq("percentBusy: no change at all is 0.0", CpuStats.percentBusy(before1, before1), 0.0);

        // ---- percentBusy: idle itself goes backward while the total still grows (clamped, not over 100%) ----
        CpuStats.Snapshot before2 = snap(0, 0, 0, 8000, 0, 0, 0, 0);            // total 8000
        CpuStats.Snapshot after2 = snap(3000, 0, 0, 7000, 0, 0, 0, 0);          // total 10000, idle fell by 1000
        eq("percentBusy: idle falling while total still grows clamps to 100%, not >100", CpuStats.percentBusy(before2, after2), 100.0);

        // ---- percentBusy: defensive against null snapshots ----
        eq("percentBusy(null, x)", CpuStats.percentBusy(null, after1), 0.0);
        eq("percentBusy(x, null)", CpuStats.percentBusy(before1, null), 0.0);

        // ---- percentBusyPerCore: a core going offline between readings (3 cores -> 2) must not throw, and is sized to the smaller count ----
        String beforeCores = "cpu  0 0 0 0 0 0 0 0\n"
            + "cpu0 0 0 0 8000 0 0 0 0\n"
            + "cpu1 0 0 0 8000 0 0 0 0\n"
            + "cpu2 0 0 0 8000 0 0 0 0\n";
        String afterCores = "cpu  0 0 0 0 0 0 0 0\n"
            + "cpu0 400 0 0 8600 0 0 0 0\n"
            + "cpu1 250 0 0 8750 0 0 0 0\n";
        CpuStats.Reading beforeR = CpuStats.parse(beforeCores), afterR = CpuStats.parse(afterCores);
        eq("core-mismatch: before has 3 cores", CpuStats.coreCount(beforeR), 3);
        eq("core-mismatch: after has 2 cores (one went offline)", CpuStats.coreCount(afterR), 2);
        double[] perCore = CpuStats.percentBusyPerCore(beforeR, afterR);
        eq("core-mismatch: result is sized to the smaller (2), not 3 and not a throw", perCore.length, 2);
        eq("core-mismatch: core 0 busy%", perCore[0], 40.0);
        eq("core-mismatch: core 1 busy%", perCore[1], 25.0);
        // the same two readings the other way around (2 cores -> 3) must also just use the smaller count
        double[] perCoreSwapped = CpuStats.percentBusyPerCore(afterR, beforeR);
        eq("core-mismatch (swapped): still sized to the smaller (2)", perCoreSwapped.length, 2);

        // ---- percentBusyPerCore: defensive against null readings ----
        eq("percentBusyPerCore(null, x) length", CpuStats.percentBusyPerCore(null, afterR).length, 0);
        eq("percentBusyPerCore(x, null) length", CpuStats.percentBusyPerCore(beforeR, null).length, 0);
        eq("coreCount(null)", CpuStats.coreCount(null), 0);

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
