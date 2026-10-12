package com.bloatware.bingblop;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure rules of the Extra Settings sections Power &amp; reboot, Doze &amp; power governor and Zram &amp; memory: what the
 * phone prints, read into numbers and states. Pure Java, free of Android imports, so it can be tested off the device; the page
 * holds the same rules in JavaScript (xdzToken, xdzParseDump, xzrParseMmStat, ...) and the suite {@code powertweaks} feeds both
 * the same fixtures.
 *
 * <p>Nothing here runs a command. The commands themselves live in the page (XPW_ACTS, XDZ_ACTS, XZR_ACTS) and run only from its
 * confirm sheet; {@link #ROOT_ONLY} names the ones that need Root, which the suite checks against the page.
 */
final class PowerTweaks {

    private PowerTweaks() {}

    /** The deep-Doze states {@code dumpsys deviceidle get deep} can print (DeviceIdleController.stateToString). */
    static final String[] DEEP_STATES = { "ACTIVE", "INACTIVE", "IDLE_PENDING", "SENSING", "LOCATING", "IDLE", "IDLE_MAINTENANCE", "QUICK_DOZE_DELAY" };
    /** The light-Doze states {@code get light} can print (lightStateToString). */
    static final String[] LIGHT_STATES = { "ACTIVE", "INACTIVE", "PRE_IDLE", "IDLE", "WAITING_FOR_NETWORK", "IDLE_MAINTENANCE", "OVERRIDE" };
    /** Commands of the page that only Root may run: the page must mark their rows {@code needsRoot: true}. */
    static final String[] ROOT_ONLY = { "setprop ctl.restart zygote", "/proc/sys/vm/drop_caches" };

    private static boolean in(String[] list, String s) {
        if (s == null) return false;
        for (String x : list) if (x.equals(s)) return true;
        return false;
    }

    /**
     * The state token in what {@code dumpsys deviceidle get deep} (or {@code get light}, with {@code light = true}) printed: the
     * bare token on a line, or the one inside "Stepped to deep: X" / "Light state: X, Deep state: Y". Null when there is none
     * (an error line, "UNKNOWN", nothing).
     */
    static String stateToken(String output, boolean light) {
        if (output == null) return null;
        String[] list = light ? LIGHT_STATES : DEEP_STATES;
        for (String raw : output.split("\r?\n")) {
            String t = raw.trim();
            if (in(list, t)) return t;
            Matcher m = Pattern.compile("^Stepped to (deep|light): ([A-Z_]+)").matcher(t);
            if (m.find() && m.group(1).equals(light ? "light" : "deep") && in(list, m.group(2))) return m.group(2);
            m = Pattern.compile(light ? "Light state: ([A-Z_]+)" : "Deep state: ([A-Z_]+)").matcher(t);
            if (m.find() && in(list, m.group(1))) return m.group(1);
        }
        return null;
    }

    /** Where a deep state sits on the five-step timeline: 0 Active, 1 Inactive (also the quick-doze delay), 2 Idle pending (also Sensing, Locating), 3 Idle, 4 Maintenance; -1 unknown. */
    static int deepPhase(String state) {
        if (state == null) return -1;
        switch (state) {
            case "ACTIVE": return 0;
            case "INACTIVE": case "QUICK_DOZE_DELAY": return 1;
            case "IDLE_PENDING": case "SENSING": case "LOCATING": return 2;
            case "IDLE": return 3;
            case "IDLE_MAINTENANCE": return 4;
            default: return -1;
        }
    }

    /** "true" / "false" on the first line (what {@code get force} prints); null for anything else. */
    static Boolean bool(String output) {
        if (output == null) return null;
        String t = output.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (t.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (t.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    /** What the full {@code dumpsys deviceidle} report says; a field is null when the report does not print it. */
    static final class DozeDump {
        String state, lightState, sensor;        // sensor: the name, "" for mMotionSensor=null, null when not printed
        Boolean forced, useMotion, listening;
    }

    static DozeDump parseDump(String report) {
        DozeDump d = new DozeDump();
        if (report == null) return d;
        Pattern pState = Pattern.compile("\\bmState=([A-Z_]+)"), pLight = Pattern.compile("\\bmLightState=([A-Z_]+)"), pForce = Pattern.compile("\\bmForceIdle=(true|false)");
        Pattern pUse = Pattern.compile("\\bmUseMotionSensor=(true|false)"), pListen = Pattern.compile("\\bmMotionListener\\.active=(true|false)"), pSensor = Pattern.compile("\\bmMotionSensor=\\{Sensor name=\"([^\"]*)\"");
        for (String raw : report.split("\r?\n")) {
            Matcher m;
            if ((m = pState.matcher(raw)).find() && in(DEEP_STATES, m.group(1))) d.state = m.group(1);
            if ((m = pLight.matcher(raw)).find() && in(LIGHT_STATES, m.group(1))) d.lightState = m.group(1);
            if ((m = pForce.matcher(raw)).find()) d.forced = Boolean.valueOf(m.group(1));
            if ((m = pUse.matcher(raw)).find()) d.useMotion = Boolean.valueOf(m.group(1));
            if ((m = pListen.matcher(raw)).find()) d.listening = Boolean.valueOf(m.group(1));
            if ((m = pSensor.matcher(raw)).find()) d.sensor = m.group(1);
            else if (raw.contains("mMotionSensor=null")) d.sensor = "";
        }
        return d;
    }

    /** {@code /sys/block/zram0/mm_stat}: orig_data_size compr_data_size mem_used_total mem_limit mem_used_max same_pages pages_compacted [huge_pages [huge_pages_since]]. */
    static final class MmStat {
        boolean ok;
        String text;            // the line as it came when it is not a counter line (what cat said)
        long orig, compr, memUsed, memLimit, memUsedMax, samePages, compacted, hugePages;
    }

    static MmStat parseMmStat(String output) {
        MmStat r = new MmStat();
        String t = output == null ? "" : output.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        r.text = t;
        String[] f = t.isEmpty() ? new String[0] : t.split("\\s+");
        if (f.length < 3) return r;
        for (int i = 0; i < 3; i++) if (!f[i].matches("\\d+")) return r;
        long[] v = new long[8];
        for (int i = 0; i < v.length; i++) v[i] = i < f.length && f[i].matches("\\d{1,18}") ? Long.parseLong(f[i]) : 0;
        r.ok = true;
        r.orig = v[0]; r.compr = v[1]; r.memUsed = v[2]; r.memLimit = v[3]; r.memUsedMax = v[4]; r.samePages = v[5]; r.compacted = v[6]; r.hugePages = v[7];
        return r;
    }

    /** {@code /sys/block/zram0/disksize} in bytes, or -1 when the text is not a number. */
    static long parseDisksize(String output) {
        String t = output == null ? "" : output.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        return t.matches("\\d{1,18}") ? Long.parseLong(t) : -1;
    }

    /** orig / compr, or -1 when nothing is compressed (compr 0): the "3.0x" of the badge. */
    static double ratio(MmStat m) {
        if (m == null || !m.ok || m.compr <= 0) return -1;
        return (double) m.orig / (double) m.compr;
    }

    /** orig - memUsed, never below 0: what the compressed copy gives back compared with keeping the data plain. */
    static long savedBytes(MmStat m) {
        if (m == null || !m.ok) return 0;
        return Math.max(0, m.orig - m.memUsed);
    }

    /** The three parts of the stacked bar as fractions of the disk size (compressed in RAM, saved, free), or of orig when the size is unknown. */
    static double[] bar(MmStat m, long disksize) {
        if (m == null || !m.ok) return new double[] { 0, 0, 0 };
        double total = disksize > 0 ? disksize : Math.max(m.orig, 1);
        double c = Math.min(m.memUsed, total), s = Math.min(savedBytes(m), Math.max(0, total - c)), f = Math.max(0, total - c - s);
        return new double[] { c / total, s / total, f / total };
    }

    /** The badge text: one decimal under 10, whole above; a dash when there is nothing to divide. */
    static String ratioText(double ratio) {
        if (ratio < 0) return "—";
        return (ratio >= 10 ? String.valueOf(Math.round(ratio)) : String.format(Locale.US, "%.1f", ratio)) + "×";
    }

    /** Whether the {@code cmd power} help names a sub-command; null when the text is no help at all (no service, an error). */
    static Boolean cmdPowerSupports(String help, String sub) {
        if (help == null || sub == null) return null;
        boolean isHelp = help.contains("set-") || Pattern.compile("power manager|commands?:", Pattern.CASE_INSENSITIVE).matcher(help).find();
        if (!isHelp) return null;
        return help.contains(sub);
    }

    /** A fixed-performance line when the phone prints one ("...fixed performance...=true"); null when there is no such line (the usual case). */
    static Boolean parseFixedPerformance(String dumpsysPower) {
        if (dumpsysPower == null) return null;
        Matcher m = Pattern.compile("fixed[ _-]?performance[^\\n]*?(?:=|:)\\s*(true|false|on|off|enabled|disabled)\\b", Pattern.CASE_INSENSITIVE).matcher(dumpsysPower);
        if (!m.find()) return null;
        String v = m.group(1).toLowerCase(Locale.US);
        return v.equals("true") || v.equals("on") || v.equals("enabled");
    }

    /** The profile the read-back names: "saver" for low_power 1, "performance" when fixed performance mode is reported on, else "balanced"; null for an unknown low_power value. */
    static String profileOf(String lowPower, Boolean fixed) {
        String lp = lowPower == null ? "" : lowPower.trim();
        if (lp.equals("1")) return "saver";
        if (Boolean.TRUE.equals(fixed)) return "performance";
        if (lp.isEmpty() || lp.equals("0") || lp.equals("null")) return "balanced";
        return null;
    }
}
