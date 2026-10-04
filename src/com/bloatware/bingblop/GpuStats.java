package com.bloatware.bingblop;

import java.util.List;

/**
 * Parses the handful of GPU-load and GPU-frequency text formats that show up on sysfs across Android chipsets. Pure Java,
 * free of Android classes, so it can be tested off the device: the caller does the privileged shell read of one of the
 * paths below (a {@code cat <path> 2>/dev/null} through a root shell) and hands this class only the raw text that came
 * back; this class never touches the filesystem or any Android API itself, and does not even see which path produced the
 * text it is given (the methods below take text alone, never a path) -- tagging a {@link Reading} with the path that
 * matched, and deciding which paths to try and in what order, is the caller's job, not this one.
 *
 * <p>Android has no stable, public API for "current GPU usage" on any version: nothing in {@code android.os} or the
 * public SDK reports it, and what exists instead is a handful of sysfs nodes that chipset vendors expose for their own
 * kernel drivers. None of them are documented by Google, none are guaranteed present on a given device, and the shape of
 * the same vendor's own node has changed between kernel versions. This class is deliberately a best-effort reader of a
 * short, known list of those paths -- not a general solution -- and a device whose vendor is not on the list, or whose
 * kernel reports a shape this class does not recognise, simply reads as "not available" rather than a guess.
 *
 * <p>Known shapes this class recognises, matched from the text alone:
 * <ul>
 * <li>Adreno (Qualcomm) busy ratio, {@link #parseBusyRatio} -- {@code /sys/class/kgsl/kgsl-3d0/gpubusy}: two
 * whitespace-separated integers {@code "busy_cycles total_cycles"} (a ratio over a sampling window, not a percentage).
 * On some kernels this is replaced by {@code /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage}, which instead holds a single
 * number that already is a percentage, written bare ({@code "37"}) or with a percent sign ({@code "37 %"} /
 * {@code "37%"}). Both shapes are read by the same method and told apart from the text alone: two numeric tokens is the
 * ratio, one number (with an optional percent sign attached or as its own token) is the percentage.
 * <li>Adreno frequency, {@link #parseFreqHz} -- {@code /sys/class/kgsl/kgsl-3d0/gpuclk} or
 * {@code /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq}: a single integer, Hz. Context (what clock the GPU is running at),
 * not a load reading by itself.
 * <li>Mali (ARM) utilisation, {@link #parseUtilizationPercent} -- {@code /sys/class/misc/mali0/device/utilization}, or
 * the same leaf under a platform device path such as {@code /sys/devices/platform/*&#47;mali0/utilization}: a single
 * integer, normally already 0-100, with an optional trailing {@code %} and/or surrounding whitespace or a newline.
 * Values a little outside 0..100 are clamped rather than rejected -- some driver builds briefly report 101 or -1 right
 * at a sampling edge, and that is a quirk to tolerate, not a failed read.
 * <li>Generic devfreq frequency, {@link #parseFreqHz} + {@link #freqRatio} -- many SoCs expose their GPU through the
 * generic devfreq framework with no standard busy-time file at all, but reliably have
 * {@code /sys/class/devfreq/<id>/cur_freq} and {@code /sys/class/devfreq/<id>/max_freq} (each a single integer, Hz).
 * {@link #freqRatio} turns the two into a rough {@code cur/max * 100} "load", always marked {@link Reading#approx} so
 * nothing downstream mistakes it for a real busy-time measurement; it is meant to be tried only as a last resort, after
 * every real busy-time shape above has failed.
 * <li>Anything else -- an empty, missing or unreadable path (permission denied, no such file, a shell that could not
 * run at all) must come back {@code available = false}, never a bogus zero: a zero would claim the GPU is idle when
 * really nothing could be read.
 * </ul>
 *
 * <p>Defensive throughout: no method here throws on malformed, truncated, empty or wildly out-of-range input -- the
 * worst a bad path or an unrecognised format does is parse to "unknown" ({@code available = false}).
 */
final class GpuStats {

    private GpuStats() {}

    /**
     * One GPU-load reading, however it was obtained. {@code available = false} means nothing usable was found (an
     * unreadable path, an empty answer, or text in none of the known shapes); every other field is then meaningless and
     * is left at its default.
     */
    static final class Reading {
        public boolean available;
        public Double percent;       // 0..100, null when not known as a true percent
        public Boolean approx;       // true when percent is a frequency-ratio proxy rather than real measured busy time; null/false otherwise
        public Long freqHz;          // null when not known
        public String source = "";   // which known path/format matched, for debugging; "" when none did
    }

    /**
     * The Adreno {@code gpubusy} family of shapes: either two whitespace-separated integers {@code "busy total"} (a
     * ratio over a sampling window, as the classic {@code gpubusy} node reports it) or a single number that already is
     * a percentage ({@code "37"}, {@code "37%"} or {@code "37 %"}, as {@code gpu_busy_percentage} reports it on newer
     * kernels). Which shape it is is decided from the text alone: two numeric tokens is the ratio; one number, on its
     * own or followed only by a lone {@code %} token, is the percentage. Anything else -- empty text, more than two
     * tokens, a non-numeric token, or a ratio whose total is zero or negative (nothing to divide by) -- is unavailable
     * rather than a wrong number. The resulting percent is always clamped to 0..100, even for the ratio shape, so a
     * sampling-boundary quirk (busy slightly over total, or a negative count) cannot produce a nonsensical value.
     */
    static Reading parseBusyRatio(String rawText) {
        Reading r = new Reading();
        if (rawText == null) return r;
        String t = rawText.trim();
        if (t.isEmpty()) return r;
        String[] tok = t.split("\\s+");
        if (tok.length == 1) {
            String s = tok[0];
            if (s.endsWith("%")) s = s.substring(0, s.length() - 1);
            Long v = parseLongOrNull(s);
            if (v == null) return r;
            r.available = true;
            r.approx = Boolean.FALSE;
            r.percent = clampPercent(v.doubleValue());
            return r;
        }
        if (tok.length == 2 && "%".equals(tok[1])) {
            Long v = parseLongOrNull(tok[0]);
            if (v == null) return r;
            r.available = true;
            r.approx = Boolean.FALSE;
            r.percent = clampPercent(v.doubleValue());
            return r;
        }
        if (tok.length == 2) {
            Long busy = parseLongOrNull(tok[0]);
            Long total = parseLongOrNull(tok[1]);
            if (busy == null || total == null || total <= 0) return r;
            r.available = true;
            r.approx = Boolean.FALSE;
            r.percent = clampPercent(100.0 * busy / total);
            return r;
        }
        return r; // empty, or more than two tokens: not a shape we recognise
    }

    /**
     * The Mali {@code utilization} shape: a single integer, normally already 0-100, maybe with a trailing {@code %}
     * and maybe with surrounding whitespace or a newline. Out-of-range values are clamped into 0..100 rather than
     * rejected -- some driver builds briefly report a value a little outside that range (101, or -1 right at a
     * sampling edge), and that is a quirk to tolerate, not a sign the read failed. Non-numeric or empty text is
     * unavailable.
     */
    static Reading parseUtilizationPercent(String rawText) {
        Reading r = new Reading();
        if (rawText == null) return r;
        String t = rawText.trim();
        if (t.isEmpty()) return r;
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1).trim();
        Long v = parseLongOrNull(t);
        if (v == null) return r;
        r.available = true;
        r.approx = Boolean.FALSE;
        r.percent = clampPercent(v.doubleValue());
        return r;
    }

    /** A single integer, Hz: trims whitespace and a trailing newline; non-numeric or empty text is {@code null}. */
    static Long parseFreqHz(String rawText) {
        if (rawText == null) return null;
        String t = rawText.trim();
        if (t.isEmpty()) return null;
        return parseLongOrNull(t);
    }

    /**
     * A last-resort "load" derived from clock speed alone -- {@code cur/max * 100} -- for SoCs that expose a GPU
     * devfreq frequency but no busy-time file at all. Always marked {@link Reading#approx}: a GPU can be running at a
     * low clock while fully busy, or at a high clock while idle, so this is context, not a measurement. Unavailable
     * when either value is unknown or {@code maxHz} is zero or negative (nothing to divide by); the percent is clamped
     * into 0..100 the same as the other shapes.
     */
    static Reading freqRatio(Long curHz, Long maxHz) {
        Reading r = new Reading();
        if (curHz == null || maxHz == null || maxHz <= 0) return r;
        r.available = true;
        r.approx = Boolean.TRUE;
        r.percent = clampPercent(100.0 * curHz / maxHz);
        r.freqHz = curHz;
        return r;
    }

    /**
     * The first reading in {@code attempts} that is {@code available}, in the order given -- the caller is the one
     * who knows priority (real Adreno/Mali busy-time readings should be listed before the frequency-ratio fallback);
     * this method just picks the first usable one. A {@code null} list, or a list holding {@code null} entries, is
     * tolerated like any other input with nothing usable in it: the result is simply unavailable, never an exception.
     */
    static Reading combine(List<Reading> attempts) {
        if (attempts != null) {
            for (Reading r : attempts) {
                if (r != null && r.available) return r;
            }
        }
        Reading none = new Reading();
        none.available = false;
        none.percent = null;
        none.source = "";
        return none;
    }

    /**
     * A short, plain-language line for the UI: a plain percentage when it is a real reading, a qualified one when it
     * is only the clock-speed approximation, and a plain-language "not available" rather than a bare number with no
     * context.
     */
    static String label(Reading r) {
        if (r == null || !r.available || r.percent == null) return "Not available on this device";
        long rounded = Math.round(r.percent);
        return Boolean.TRUE.equals(r.approx) ? "~" + rounded + "% (by clock speed)" : rounded + "%";
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Keeps a percent inside the only range that makes sense for a load reading, even when the source value briefly
     * is not (a busy/total ratio a hair over 1.0 from a sampling boundary, a buggy driver's 101 or -1, a negative
     * clock reading, ...). This is a deliberate tolerance, not a sign the input was garbage.
     */
    private static double clampPercent(double v) {
        if (v < 0) return 0.0;
        if (v > 100) return 100.0;
        return v;
    }
}
