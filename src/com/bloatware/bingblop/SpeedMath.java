package com.bloatware.bingblop;

import java.util.Arrays;
import java.util.Locale;

/**
 * The arithmetic of the internet speed test (Task Manager > Network > Speed test), apart from any network code so it can be tested on its
 * own: the median and the jitter of the latency samples, the throughput of a phase from a running byte count, the rounding the page shows,
 * and the server's location code from the answer's headers. No Android classes.
 *
 * Latency is the median of the samples (an even count: the mean of the two middle values); jitter is the mean absolute difference of
 * consecutive samples, in the order they were taken (the definition of RFC 3550's "interarrival jitter" without its smoothing, and what
 * most speed tests show). Throughput is bits per second: bytes x 8 x 1000 / milliseconds.
 */
final class SpeedMath {
    private SpeedMath() {}

    /** The median, or NaN for no samples. NaN samples are left out. */
    static double median(double[] v) {
        double[] a = finite(v);
        if (a.length == 0) return Double.NaN;
        Arrays.sort(a);
        int m = a.length / 2;
        return a.length % 2 == 1 ? a[m] : (a[m - 1] + a[m]) / 2.0;
    }

    /** The mean absolute difference of consecutive samples, or NaN with fewer than two. NaN samples are left out. */
    static double jitter(double[] v) {
        double[] a = finite(v);
        if (a.length < 2) return Double.NaN;
        double sum = 0;
        for (int i = 1; i < a.length; i++) sum += Math.abs(a[i] - a[i - 1]);
        return sum / (a.length - 1);
    }

    private static double[] finite(double[] v) {
        if (v == null) return new double[0];
        double[] out = new double[v.length];
        int n = 0;
        for (double d : v) if (!Double.isNaN(d) && !Double.isInfinite(d)) out[n++] = d;
        return Arrays.copyOf(out, n);
    }

    /** Bits per second for {@code bytes} moved in {@code ms} milliseconds; 0 when no time has passed. */
    static double bitsPerSecond(long bytes, long ms) {
        if (ms <= 0 || bytes <= 0) return 0;
        return bytes * 8.0 * 1000.0 / ms;
    }

    /** Megabits per second (10^6, as every speed test counts). */
    static double mbps(double bitsPerSecond) {
        return bitsPerSecond / 1e6;
    }

    /**
     * A speed in Mbps rounded to what the page shows: three significant figures, at most two decimals (9.42, 94.2, 942, 1250).
     * Negative, NaN or infinite is 0.
     */
    static double roundMbps(double mbps) {
        if (Double.isNaN(mbps) || Double.isInfinite(mbps) || mbps <= 0) return 0;
        if (mbps < 10) return Math.round(mbps * 100) / 100.0;
        if (mbps < 100) return Math.round(mbps * 10) / 10.0;
        return Math.round(mbps);
    }

    /** A time in milliseconds as the page shows it: one decimal under 10 ms, whole milliseconds from there. NaN stays NaN. */
    static double roundMs(double ms) {
        if (Double.isNaN(ms) || Double.isInfinite(ms)) return Double.NaN;
        if (ms < 0) return 0;
        if (ms < 10) return Math.round(ms * 10) / 10.0;
        return Math.round(ms);
    }

    /** {@link #roundMbps} as text, without trailing zeros ("9.4", "94.2", "942", "0"). */
    static String formatMbps(double mbps) {
        double r = roundMbps(mbps);
        if (r == Math.rint(r)) return String.valueOf((long) r);
        String s = String.format(Locale.ROOT, "%.2f", r);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * The data centre that answered, as its three-letter airport code: the "colo" header (downloads), else "cf-meta-colo" (uploads),
     * else the end of "cf-ray" ("8c1f0a2b3c4d5e6f-IAD"). "" when none of them holds one.
     */
    static String colo(String colo, String metaColo, String ray) {
        String c = code(colo);
        if (c.isEmpty()) c = code(metaColo);
        if (c.isEmpty() && ray != null) {
            int dash = ray.lastIndexOf('-');
            if (dash >= 0) c = code(ray.substring(dash + 1));
        }
        return c;
    }

    private static String code(String s) {
        if (s == null) return "";
        String t = s.trim().toUpperCase(Locale.ROOT);
        return t.matches("[A-Z]{3}") ? t : "";
    }

    /**
     * A running byte count sampled over time (milliseconds since the phase began, total bytes so far), and the speeds read from it.
     * Times must not go backwards; a sample that does is dropped. Not thread-safe: one thread (the phase's clock) adds and reads.
     */
    static final class Meter {
        private long[] t = new long[64], b = new long[64];
        private int n;

        void add(long tMs, long totalBytes) {
            if (n > 0 && (tMs < t[n - 1] || totalBytes < b[n - 1])) return;
            if (n == t.length) { t = Arrays.copyOf(t, n * 2); b = Arrays.copyOf(b, n * 2); }
            t[n] = tMs; b[n] = totalBytes; n++;
        }

        int size() { return n; }
        long lastTime() { return n == 0 ? 0 : t[n - 1]; }
        long bytes() { return n == 0 ? 0 : b[n - 1]; }

        /** The speed over the last {@code windowMs} (from the newest sample that is at least that old, else the first), in bits per second. */
        double currentBps(long windowMs) {
            if (n < 2) return 0;
            long from = t[n - 1] - windowMs;
            int i = n - 2;
            while (i > 0 && t[i] > from) i--;
            return bitsPerSecond(b[n - 1] - b[i], t[n - 1] - t[i]);
        }

        /**
         * The speed from {@code fromMs} (the end of the ramp-up, which is left out) to the last sample, in bits per second. When the phase
         * ended before or right at fromMs (a fast line that hit the byte cap during the ramp-up), the whole phase counts instead.
         */
        double averageBps(long fromMs) {
            if (n < 2) return 0;
            int i = 0;
            while (i < n - 1 && t[i] < fromMs) i++;
            if (i >= n - 1 || t[n - 1] - t[i] <= 0) i = 0;
            return bitsPerSecond(b[n - 1] - b[i], t[n - 1] - t[i]);
        }
    }
}
