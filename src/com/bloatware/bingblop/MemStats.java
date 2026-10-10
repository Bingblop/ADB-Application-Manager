package com.bloatware.bingblop;

/**
 * Parses {@code /proc/meminfo}: one {@code Key:} / value line per field, in no particular order, the value almost always
 * followed by a {@code kB} unit ({@code HugePages_*} and a few others have none). Pure Java, free of Android imports, so it
 * can be tested off the device.
 *
 * <p>Only the handful of keys the app shows are kept ({@link Reading}'s fields); every other line — and on a newer or older
 * kernel there are dozens — is read and quietly ignored. {@code MemAvailable} in particular is missing on kernels older than
 * 3.14, which {@link #usedKb} falls back for; everything else defaults to 0 when its line never appears.
 *
 * <p>Defensive throughout: a missing, reordered, duplicated, truncated or entirely blank file never throws and never comes
 * back null — the worst it does is leave the matching field at 0.
 */
final class MemStats {

    private MemStats() {}

    /** The fields the app needs, all in kB, each left at 0 when its line was not in the file. */
    static final class Reading {
        public long totalKb, freeKb, availableKb, buffersKb, cachedKb, swapTotalKb, swapFreeKb, swapCachedKb;
    }

    /**
     * Reads the {@code MemTotal} / {@code MemFree} / {@code MemAvailable} / {@code Buffers} / {@code Cached} /
     * {@code SwapTotal} / {@code SwapFree} / {@code SwapCached} lines, however many other (unknown, ignored) lines they are
     * mixed in with and in whatever order. A key with no number after it, or a non-numeric value, reads as 0 rather than
     * throwing; a key seen more than once keeps the last value seen. Blank or garbage text — or text with none of these eight
     * keys in it at all — comes back as an all-zero {@link Reading}, never null.
     */
    static Reading parse(String procMeminfoText) {
        Reading r = new Reading();
        if (procMeminfoText == null) return r;
        for (String raw : procMeminfoText.split("\r?\n", -1)) {
            String line = raw.trim();
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            long value = firstLong(line.substring(colon + 1));
            switch (key) {
                case "MemTotal": r.totalKb = value; break;
                case "MemFree": r.freeKb = value; break;
                case "MemAvailable": r.availableKb = value; break;
                case "Buffers": r.buffersKb = value; break;
                case "Cached": r.cachedKb = value; break;
                case "SwapTotal": r.swapTotalKb = value; break;
                case "SwapFree": r.swapFreeKb = value; break;
                case "SwapCached": r.swapCachedKb = value; break;
                default: break;  // every other key (there are dozens) is of no interest here
            }
        }
        return r;
    }

    /** The first whitespace-delimited token of {@code s}, parsed as a number ({@code "8061648 kB"} or bare {@code "0"}); 0 when
     *  there isn't one or it does not parse (a missing value, a unit with no number, anything unexpected). */
    private static long firstLong(String s) {
        String t = s.trim();
        int sp = 0;
        while (sp < t.length() && !Character.isWhitespace(t.charAt(sp))) sp++;
        try {
            return Long.parseLong(t.substring(0, sp));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Used RAM in kB. When the kernel printed {@code MemAvailable} (every kernel since 3.14, which is effectively every
     * Android device): {@code totalKb - availableKb}, the modern, correct figure (it already accounts for reclaimable cache
     * and buffers, unlike the naive "total minus free"). Otherwise — the fallback for an old, pre-3.14 kernel that has no
     * {@code MemAvailable} line, so {@code availableKb} stayed 0 — the older approximation
     * {@code totalKb - freeKb - buffersKb - cachedKb}. Either way the result is clamped to >= 0, since a reading built from
     * nonsense (fields that don't relate to each other) must not come back negative.
     */
    static long usedKb(Reading r) {
        if (r == null) return 0;
        if (r.availableKb > 0) return Math.max(0, r.totalKb - r.availableKb);
        return Math.max(0, r.totalKb - r.freeKb - r.buffersKb - r.cachedKb);  // fallback for an old kernel with no MemAvailable line
    }

    /**
     * Used swap in kB: {@code max(0, swapTotalKb - swapFreeKb)}. {@code SwapCached} (pages that are swap-backed but currently
     * also sitting in RAM) is deliberately not subtracted a second time here — that memory is already counted as used RAM by
     * {@link #usedKb}, and the plain total-minus-free figure is what most system monitors mean by "swap used" — so this is the
     * simpler of the two reasonable formulas, chosen on purpose rather than by accident.
     */
    static long swapUsedKb(Reading r) {
        if (r == null) return 0;
        return Math.max(0, r.swapTotalKb - r.swapFreeKb);
    }
}
