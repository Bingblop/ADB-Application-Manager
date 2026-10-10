package com.bloatware.bingblop;

/** /proc/meminfo parsing: the handful of kept keys in any order, a kernel too old for MemAvailable, unknown extra keys and
 *  lines, garbled/missing values, and the usedKb/swapUsedKb formulas (including their defensive clamps). */
public class MemStatsTest {
    static int n = 0, fails = 0;
    static void eq(String what, long got, long want) { n++; if (got != want) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) {
        // ---- a normal full sample, with MemAvailable ----
        String normal = "MemTotal:        8061648 kB\n"
            + "MemFree:         1532044 kB\n"
            + "MemAvailable:    4200000 kB\n"
            + "Buffers:           102400 kB\n"
            + "Cached:          2450000 kB\n"
            + "SwapCached:            0 kB\n"
            + "Active:          2200000 kB\n"
            + "Inactive:        1800000 kB\n"
            + "SwapTotal:       2097152 kB\n"
            + "SwapFree:        1500000 kB\n"
            + "Dirty:               120 kB\n"
            + "HugePages_Total:       0\n"
            + "HugePages_Free:        0\n";
        MemStats.Reading r = MemStats.parse(normal);
        eq("MemTotal", r.totalKb, 8061648L); eq("MemFree", r.freeKb, 1532044L); eq("MemAvailable", r.availableKb, 4200000L);
        eq("Buffers", r.buffersKb, 102400L); eq("Cached", r.cachedKb, 2450000L);
        eq("SwapTotal", r.swapTotalKb, 2097152L); eq("SwapFree", r.swapFreeKb, 1500000L); eq("SwapCached", r.swapCachedKb, 0L);
        eq("usedKb uses the modern total-minus-available formula when MemAvailable is present", MemStats.usedKb(r), 3861648L);
        eq("swapUsedKb", MemStats.swapUsedKb(r), 597152L);

        // ---- missing MemAvailable: an older (pre-3.14) kernel ----
        String noAvailable = "MemTotal:        2097152 kB\n"
            + "MemFree:          512000 kB\n"
            + "Buffers:           64000 kB\n"
            + "Cached:           300000 kB\n"
            + "SwapTotal:        524288 kB\n"
            + "SwapFree:         400000 kB\n"
            + "SwapCached:         5000 kB\n"
            + "Active:           900000 kB\n";
        r = MemStats.parse(noAvailable);
        eq("no MemAvailable line: availableKb stays 0", r.availableKb, 0L);
        eq("usedKb falls back to total-free-buffers-cached on an old kernel", MemStats.usedKb(r), 1221152L);
        eq("swapUsedKb (SwapCached intentionally not subtracted again)", MemStats.swapUsedKb(r), 124288L);

        // ---- unknown extra keys and lines, and the kept keys out of order ----
        String extra = "Cached:          1000000 kB\n"
            + "MemFree:          800000 kB\n"
            + "WeirdFutureField:      42 kB\n"
            + "MemTotal:        4000000 kB\n"
            + "VmallocTotal:    123456789 kB\n"
            + "MemAvailable:    2500000 kB\n"
            + "Buffers:           50000 kB\n"
            + "SwapFree:         100000 kB\n"
            + "SwapTotal:        200000 kB\n"
            + "SwapCached:         1000 kB\n";
        r = MemStats.parse(extra);
        eq("reordered + unknown keys: MemTotal still found", r.totalKb, 4000000L);
        eq("reordered + unknown keys: MemAvailable still found", r.availableKb, 2500000L);
        eq("reordered + unknown keys: usedKb", MemStats.usedKb(r), 1500000L);
        eq("reordered + unknown keys: swapUsedKb", MemStats.swapUsedKb(r), 100000L);

        // ---- garbled / missing values must not throw, and must not make usedKb negative ----
        String garbled = "MemTotal: 1000000 kB\n"
            + "MemAvailable:\n"
            + "MemFree: 200000 kB\n"
            + "WeirdNonNumeric: abc kB\n";
        r = MemStats.parse(garbled);
        eq("a key with nothing after the colon reads as 0, not a crash", r.availableKb, 0L);
        eq("usedKb fallback still computes", MemStats.usedKb(r), 800000L);

        String nonNumericTotal = "MemTotal:        abc kB\n"
            + "MemFree:         1000000 kB\n";
        r = MemStats.parse(nonNumericTotal);
        eq("a non-numeric value reads as 0, not a crash", r.totalKb, 0L);
        is("usedKb never goes negative even from a nonsensical reading", MemStats.usedKb(r) == 0L);

        // ---- blank / garbage input ----
        for (String bad : new String[] {"", "   \n\n  ", "no colons here\njust some random text"}) {
            MemStats.Reading br = MemStats.parse(bad);
            is("blank/garbage [" + bad.replace("\n", "\\n") + "]: all-zero reading",
                br.totalKb == 0 && br.freeKb == 0 && br.availableKb == 0 && br.buffersKb == 0 && br.cachedKb == 0
                    && br.swapTotalKb == 0 && br.swapFreeKb == 0 && br.swapCachedKb == 0);
            eq("blank/garbage [" + bad.replace("\n", "\\n") + "]: usedKb", MemStats.usedKb(br), 0L);
            eq("blank/garbage [" + bad.replace("\n", "\\n") + "]: swapUsedKb", MemStats.swapUsedKb(br), 0L);
        }
        MemStats.Reading nr = MemStats.parse(null);
        is("parse(null): all zero, not a crash", nr.totalKb == 0 && nr.availableKb == 0);
        is("usedKb(null) is 0, not a crash", MemStats.usedKb(null) == 0L);
        is("swapUsedKb(null) is 0, not a crash", MemStats.swapUsedKb(null) == 0L);

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
