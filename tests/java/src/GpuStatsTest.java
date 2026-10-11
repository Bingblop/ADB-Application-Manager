package com.bloatware.bingblop;

import java.util.*;

/** GPU-load/frequency sysfs text parsing: the Adreno gpubusy/percentage shapes, the Mali utilization shape, the devfreq
 *  frequency-ratio fallback, combine()'s priority rule, and the plain-language label for each state. */
public class GpuStatsTest {
    static int n = 0, fails = 0;
    static void eq(String what, Object got, Object want) { n++; if (!Objects.equals(want, got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }

    public static void main(String[] a) {
        // ---- parseBusyRatio: two-integer ratio shape ("busy total") ----
        GpuStats.Reading r = GpuStats.parseBusyRatio("1 4");
        is("ratio available", r.available); eq("ratio percent", r.percent, 25.0); eq("ratio approx is false", r.approx, Boolean.FALSE);
        r = GpuStats.parseBusyRatio("3 4\n");
        eq("ratio with trailing newline", r.percent, 75.0);
        r = GpuStats.parseBusyRatio("  1   2  ");
        eq("ratio with extra internal/outer whitespace", r.percent, 50.0);
        r = GpuStats.parseBusyRatio("5 0");
        is("ratio with total 0 is unavailable (guard)", !r.available);
        r = GpuStats.parseBusyRatio("5 -1");
        is("ratio with negative total is unavailable (guard)", !r.available);
        r = GpuStats.parseBusyRatio("9 3");
        eq("ratio over 100% is clamped", r.percent, 100.0);
        r = GpuStats.parseBusyRatio("-5 10");
        eq("ratio with a negative busy count is clamped to 0", r.percent, 0.0);
        r = GpuStats.parseBusyRatio("1 2 3");
        is("more than two tokens is unavailable (not a shape we know)", !r.available);
        r = GpuStats.parseBusyRatio("a b");
        is("two non-numeric tokens is unavailable", !r.available);
        r = GpuStats.parseBusyRatio("1 b");
        is("one non-numeric token in a pair is unavailable", !r.available);

        // ---- parseBusyRatio: bare-percent shape ("gpu_busy_percentage" without a % sign) ----
        r = GpuStats.parseBusyRatio("37");
        is("bare percent available", r.available); eq("bare percent value", r.percent, 37.0); eq("bare percent approx is false", r.approx, Boolean.FALSE);
        r = GpuStats.parseBusyRatio(" 37 \n");
        eq("bare percent with surrounding whitespace/newline", r.percent, 37.0);
        r = GpuStats.parseBusyRatio("150");
        eq("bare percent over 100 is clamped", r.percent, 100.0);
        r = GpuStats.parseBusyRatio("-5");
        eq("bare percent negative is clamped", r.percent, 0.0);

        // ---- parseBusyRatio: percent-with-%-sign shape, both as one token and as two ----
        r = GpuStats.parseBusyRatio("37%");
        is("percent-sign (attached) available", r.available); eq("percent-sign (attached) value", r.percent, 37.0);
        r = GpuStats.parseBusyRatio("37 %");
        is("percent-sign (separate token) available", r.available); eq("percent-sign (separate token) value", r.percent, 37.0);
        r = GpuStats.parseBusyRatio("37 %\n");
        eq("percent-sign (separate token) with trailing newline", r.percent, 37.0);

        // ---- parseBusyRatio: garbage / empty / unreadable ----
        r = GpuStats.parseBusyRatio("");
        is("empty text is unavailable, not zero", !r.available); eq("and percent is null, not 0", r.percent, null);
        r = GpuStats.parseBusyRatio("   ");
        is("whitespace-only text is unavailable", !r.available);
        r = GpuStats.parseBusyRatio(null);
        is("null text is unavailable (no NPE)", !r.available);
        r = GpuStats.parseBusyRatio("not a number");
        is("non-numeric single token is unavailable", !r.available);
        r = GpuStats.parseBusyRatio("%");
        is("a lone percent sign is unavailable", !r.available);

        // ---- parseUtilizationPercent: Mali shape ----
        r = GpuStats.parseUtilizationPercent("42");
        is("mali plain available", r.available); eq("mali plain value", r.percent, 42.0); eq("mali plain approx is false", r.approx, Boolean.FALSE);
        r = GpuStats.parseUtilizationPercent("42%");
        is("mali with % available", r.available); eq("mali with % value", r.percent, 42.0);
        r = GpuStats.parseUtilizationPercent("  7\n");
        is("mali with surrounding whitespace/newline available", r.available); eq("mali with surrounding whitespace/newline value", r.percent, 7.0);
        r = GpuStats.parseUtilizationPercent("150");
        is("mali out-of-range high is clamped, not rejected", r.available); eq("mali out-of-range high value", r.percent, 100.0);
        r = GpuStats.parseUtilizationPercent("-1");
        is("mali out-of-range low is clamped, not rejected", r.available); eq("mali out-of-range low value", r.percent, 0.0);
        r = GpuStats.parseUtilizationPercent("");
        is("mali empty text is unavailable", !r.available);
        r = GpuStats.parseUtilizationPercent(null);
        is("mali null text is unavailable (no NPE)", !r.available);
        r = GpuStats.parseUtilizationPercent("nope");
        is("mali non-numeric text is unavailable", !r.available);

        // ---- parseFreqHz ----
        eq("freq plain", GpuStats.parseFreqHz("600000000"), 600000000L);
        eq("freq with whitespace/newline", GpuStats.parseFreqHz("  600000000\n"), 600000000L);
        eq("freq empty is null", GpuStats.parseFreqHz(""), null);
        eq("freq null is null", GpuStats.parseFreqHz(null), null);
        eq("freq non-numeric is null", GpuStats.parseFreqHz("fast"), null);

        // ---- freqRatio: the frequency-ratio fallback ----
        r = GpuStats.freqRatio(600_000_000L, 800_000_000L);
        is("freqRatio normal case available", r.available); eq("freqRatio percent", r.percent, 75.0);
        eq("freqRatio is always approx", r.approx, Boolean.TRUE); eq("freqRatio keeps the current frequency", r.freqHz, 600_000_000L);
        r = GpuStats.freqRatio(900L, 800L);
        eq("freqRatio over 100% is clamped", r.percent, 100.0);
        r = GpuStats.freqRatio(500L, 0L);
        is("freqRatio with maxHz == 0 is unavailable (guard)", !r.available);
        r = GpuStats.freqRatio(500L, -100L);
        is("freqRatio with negative maxHz is unavailable (guard)", !r.available);
        r = GpuStats.freqRatio(null, 800L);
        is("freqRatio with null curHz is unavailable", !r.available);
        r = GpuStats.freqRatio(500L, null);
        is("freqRatio with null maxHz is unavailable", !r.available);
        r = GpuStats.freqRatio(null, null);
        is("freqRatio with both null is unavailable", !r.available);

        // ---- combine(): first-available-wins, in caller-given priority order ----
        GpuStats.Reading unavailA = new GpuStats.Reading();
        GpuStats.Reading unavailB = new GpuStats.Reading();
        GpuStats.Reading availA = new GpuStats.Reading(); availA.available = true; availA.percent = 11.0; availA.source = "A";
        GpuStats.Reading availB = new GpuStats.Reading(); availB.available = true; availB.percent = 22.0; availB.source = "B";
        GpuStats.Reading picked = GpuStats.combine(Arrays.asList(unavailA, unavailB, availA, availB));
        eq("combine skips the unavailable ones and picks the first available", picked.source, "A");
        eq("combine's pick keeps that reading's own percent", picked.percent, 11.0);
        picked = GpuStats.combine(Arrays.asList(availB, availA));
        eq("combine respects list order, not any other ranking", picked.source, "B");
        List<GpuStats.Reading> withNull = new ArrayList<>(); withNull.add(null); withNull.add(availB);
        picked = GpuStats.combine(withNull);
        eq("combine tolerates a null entry and keeps looking", picked.source, "B");
        GpuStats.Reading none = GpuStats.combine(Arrays.asList(unavailA, unavailB));
        is("combine of an all-unavailable list is unavailable", !none.available);
        eq("all-unavailable: percent is null", none.percent, null);
        eq("all-unavailable: source is empty", none.source, "");
        none = GpuStats.combine(new ArrayList<GpuStats.Reading>());
        is("combine of an empty list is unavailable", !none.available);
        none = GpuStats.combine(null);
        is("combine(null) is unavailable, not a crash", !none.available);
        eq("combine(null): percent is null", none.percent, null);
        eq("combine(null): source is empty", none.source, "");

        // ---- label(): the three states ----
        GpuStats.Reading real = new GpuStats.Reading(); real.available = true; real.percent = 37.0; real.approx = Boolean.FALSE;
        eq("label of a real reading", GpuStats.label(real), "37%");
        GpuStats.Reading approxReading = new GpuStats.Reading(); approxReading.available = true; approxReading.percent = 37.0; approxReading.approx = Boolean.TRUE;
        eq("label of an approximate (clock-speed) reading", GpuStats.label(approxReading), "~37% (by clock speed)");
        GpuStats.Reading notAvail = new GpuStats.Reading();
        eq("label of an unavailable reading", GpuStats.label(notAvail), "Not available on this device");
        eq("label of a null reading (defensive)", GpuStats.label(null), "Not available on this device");
        GpuStats.Reading availableButNoPercent = new GpuStats.Reading(); availableButNoPercent.available = true; // percent left null
        eq("label when available but percent is somehow unknown falls back to 'not available'", GpuStats.label(availableButNoPercent), "Not available on this device");
        GpuStats.Reading approxDefaultNull = new GpuStats.Reading(); approxDefaultNull.available = true; approxDefaultNull.percent = 10.0; // approx left null
        eq("label treats a null approx the same as false", GpuStats.label(approxDefaultNull), "10%");
        GpuStats.Reading rounded = new GpuStats.Reading(); rounded.available = true; rounded.percent = 37.6; rounded.approx = Boolean.FALSE;
        eq("label rounds to the nearest whole percent", GpuStats.label(rounded), "38%");

        // ---- a couple of end-to-end sanity checks through the whole pipeline ----
        GpuStats.Reading busy = GpuStats.parseBusyRatio("1 4");
        eq("end-to-end: a real Adreno ratio reading labels as a plain percent", GpuStats.label(busy), "25%");
        GpuStats.Reading fallback = GpuStats.freqRatio(GpuStats.parseFreqHz("600000000"), GpuStats.parseFreqHz("800000000"));
        eq("end-to-end: the devfreq fallback labels as an approximation", GpuStats.label(fallback), "~75% (by clock speed)");
        GpuStats.Reading winner = GpuStats.combine(Arrays.asList(GpuStats.parseBusyRatio(""), busy, fallback));
        eq("end-to-end: combine prefers the real reading over the fallback when both are available", GpuStats.label(winner), "25%");

        System.out.println(n + " checks, " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
