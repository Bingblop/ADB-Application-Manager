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
    static final String[] ROOT_ONLY = { "setprop ctl.restart zygote", "/proc/sys/vm/drop_caches", "scaling_governor", "/sys/block/zram0/reset", "/proc/sys/vm/swappiness" };

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
        return profileOf(lowPower, fixed, null);
    }

    /**
     * The same with the CPU governor the clusters share (v7.20): "performance" also when every cluster runs the {@code performance}
     * governor, which Root can read back even on a phone that never reports the fixed performance mode. Battery saver still wins.
     */
    static String profileOf(String lowPower, Boolean fixed, String commonGovernor) {
        String lp = lowPower == null ? "" : lowPower.trim();
        if (lp.equals("1")) return "saver";
        if (Boolean.TRUE.equals(fixed) || "performance".equals(commonGovernor)) return "performance";
        if (lp.isEmpty() || lp.equals("0") || lp.equals("null")) return "balanced";
        return null;
    }

    // ---- v7.20: Doze timing, the power governor and the memory settings ----

    /**
     * A duration as {@code TimeUtils.formatDuration} prints it in the Doze report ({@code +4m0s0ms}, {@code +1h0m0s0ms}, {@code +30s0ms},
     * {@code +500ms}, {@code +1d2h37m40s0ms}, also with a leading minus), or a bare number of milliseconds (what {@code device_config}
     * holds): in milliseconds, or -1 when the text is neither.
     */
    static long parseDuration(String text) {
        if (text == null) return -1;
        String t = text.trim();
        if (t.matches("\\d{1,15}")) return Long.parseLong(t);
        Matcher m = Pattern.compile("^([+-])?(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:(\\d+)s)?(?:(\\d+)ms)?$").matcher(t);
        if (!m.find() || t.length() <= (m.group(1) == null ? 0 : 1)) return -1;
        long ms = 0;
        if (m.group(2) != null) ms += Long.parseLong(m.group(2)) * 86400000L;
        if (m.group(3) != null) ms += Long.parseLong(m.group(3)) * 3600000L;
        if (m.group(4) != null) ms += Long.parseLong(m.group(4)) * 60000L;
        if (m.group(5) != null) ms += Long.parseLong(m.group(5)) * 1000L;
        if (m.group(6) != null) ms += Long.parseLong(m.group(6));
        return "-".equals(m.group(1)) ? -ms : ms;
    }

    /**
     * The {@code Settings:} block of the full {@code dumpsys deviceidle} report: every {@code key=value} line indented under it, as the
     * phone printed the values ({@code +4m0s0ms}, {@code 2.0}, {@code true}, {@code 20.0m}). Empty when the report has no such block.
     */
    static java.util.Map<String, String> parseConstants(String report) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (report == null) return out;
        boolean in = false;
        for (String raw : report.split("\r?\n")) {
            if (raw.trim().equals("Settings:")) { in = true; continue; }
            if (!in) continue;
            Matcher m = Pattern.compile("^\\s+([a-z][a-z0-9_]*)=(\\S+)\\s*$").matcher(raw);
            if (m.find()) out.put(m.group(1), m.group(2));
            else break;                                                                 // the first line that is not a constant ends the block
        }
        return out;
    }

    /** One constant of {@link #parseConstants} as milliseconds, or -1 when the report does not print it or it is no duration. */
    static long constantMs(java.util.Map<String, String> constants, String key) {
        return constants == null || !constants.containsKey(key) ? -1 : parseDuration(constants.get(key));
    }

    /** The state lines of the full report that say how the phone is doing right now; a field is null / -1 when the report does not print it. */
    static final class DozeNow {
        Boolean screenOn, charging, network, lightEnabled, deepEnabled, quickDoze;
        long nextAlarmMs = -1, nextLightAlarmMs = -1, nextIdleDelayMs = -1, nextIdlePendingMs = -1, inactiveTimeoutMs = -1;
    }

    static DozeNow parseNow(String report) {
        DozeNow d = new DozeNow();
        if (report == null) return d;
        for (String raw : report.split("\r?\n")) {
            Matcher m;
            if ((m = Pattern.compile("\\bmScreenOn=(true|false)").matcher(raw)).find()) d.screenOn = Boolean.valueOf(m.group(1));
            if ((m = Pattern.compile("\\bmCharging=(true|false)").matcher(raw)).find()) d.charging = Boolean.valueOf(m.group(1));
            if ((m = Pattern.compile("\\bmNetworkConnected=(true|false)").matcher(raw)).find()) d.network = Boolean.valueOf(m.group(1));
            if ((m = Pattern.compile("\\bmLightEnabled=(true|false)").matcher(raw)).find()) d.lightEnabled = Boolean.valueOf(m.group(1));
            if ((m = Pattern.compile("\\bmDeepEnabled=(true|false)").matcher(raw)).find()) d.deepEnabled = Boolean.valueOf(m.group(1));
            if ((m = Pattern.compile("\\bmQuickDozeActivated=(true|false)").matcher(raw)).find()) d.quickDoze = Boolean.valueOf(m.group(1));
            if ((m = Pattern.compile("\\bmNextAlarmTime=(\\S+)").matcher(raw)).find()) d.nextAlarmMs = parseDuration(m.group(1));
            if ((m = Pattern.compile("\\bmNextLightAlarmTime=(\\S+)").matcher(raw)).find()) d.nextLightAlarmMs = parseDuration(m.group(1));
            if ((m = Pattern.compile("\\bmNextIdleDelay=(\\S+)").matcher(raw)).find()) d.nextIdleDelayMs = parseDuration(m.group(1));
            if ((m = Pattern.compile("\\bmNextIdlePendingDelay=(\\S+)").matcher(raw)).find()) d.nextIdlePendingMs = parseDuration(m.group(1));
            if ((m = Pattern.compile("\\bmInactiveTimeout=(\\S+)").matcher(raw)).find()) d.inactiveTimeoutMs = parseDuration(m.group(1));
        }
        return d;
    }

    /** {@code device_config list <namespace>}: the {@code key=value} lines as a map; anything else (an error, an empty answer) is skipped. */
    static java.util.Map<String, String> parseDeviceConfigList(String output) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (output == null) return out;
        for (String raw : output.split("\r?\n")) {
            Matcher m = Pattern.compile("^([a-z][a-z0-9_]*)=(.*)$").matcher(raw.trim());
            if (m.find()) out.put(m.group(1), m.group(2).trim());
        }
        return out;
    }

    /**
     * How the page splits "deep Doze after N minutes still" into Android's constants: {@code inactive_to} and {@code idle_after_inactive_to}
     * take half each (AOSP keeps them equal), {@code motion_inactive_to} (the shorter wait after the phone moved) is the smaller of its
     * default 10 minutes and that half. All in milliseconds; a total under two minutes is lifted to two.
     */
    static long[] deepSplit(long totalMs) {
        long total = Math.max(120000L, totalMs);
        long half = total / 2;
        return new long[] { half, total - half, Math.min(600000L, half) };
    }

    /** One cpufreq policy as the read command prints it: {@code name|governor|cur|min|max|cpus|available governors}. */
    static final class CpuPolicy {
        String name = "", governor = "", cpus = "";
        long curKhz = -1, minKhz = -1, maxKhz = -1;
        String[] available = new String[0];
    }

    /** The policy lines of the read command ({@code policy0|schedutil|1804800|300000|1804800|0-3|performance schedutil}); other lines (an ls error) are skipped. */
    static java.util.List<CpuPolicy> parseCpuPolicies(String output) {
        java.util.List<CpuPolicy> out = new java.util.ArrayList<>();
        if (output == null) return out;
        for (String raw : output.split("\r?\n")) {
            String t = raw.trim();
            if (!t.startsWith("policy") || t.indexOf('|') < 0) continue;
            String[] f = t.split("\\|", -1);
            CpuPolicy p = new CpuPolicy();
            p.name = f[0].trim();
            if (!p.name.matches("policy\\d{1,3}")) continue;
            p.governor = f.length > 1 && f[1].trim().matches("[A-Za-z0-9_-]+") ? f[1].trim() : "";
            p.curKhz = f.length > 2 && f[2].trim().matches("\\d{1,12}") ? Long.parseLong(f[2].trim()) : -1;
            p.minKhz = f.length > 3 && f[3].trim().matches("\\d{1,12}") ? Long.parseLong(f[3].trim()) : -1;
            p.maxKhz = f.length > 4 && f[4].trim().matches("\\d{1,12}") ? Long.parseLong(f[4].trim()) : -1;
            p.cpus = f.length > 5 ? f[5].trim() : "";
            if (f.length > 6 && !f[6].trim().isEmpty()) {
                java.util.List<String> a = new java.util.ArrayList<>();
                for (String g : f[6].trim().split("\\s+")) if (g.matches("[A-Za-z0-9_-]+")) a.add(g);
                p.available = a.toArray(new String[0]);
            }
            out.add(p);
        }
        return out;
    }

    /** The governor every policy runs, or null when they differ, when one is unreadable or when there is none. */
    static String commonGovernor(java.util.List<CpuPolicy> policies) {
        if (policies == null || policies.isEmpty()) return null;
        String g = null;
        for (CpuPolicy p : policies) {
            if (p.governor.isEmpty()) return null;
            if (g == null) g = p.governor;
            else if (!g.equals(p.governor)) return null;
        }
        return g;
    }

    /** The governors any policy offers, in the order first seen, each once. */
    static java.util.List<String> availableGovernors(java.util.List<CpuPolicy> policies) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (policies != null) for (CpuPolicy p : policies) for (String g : p.available) if (!out.contains(g)) out.add(g);
        return out;
    }

    /** {@code dumpsys battery}: whether the phone is plugged in and from what, the level and the status. */
    static final class Battery {
        boolean read;                                  // a battery block was found at all
        boolean ac, usb, wireless, dock;
        int level = -1, status = -1;
        boolean plugged() { return ac || usb || wireless || dock; }
    }

    static Battery parseBattery(String output) {
        Battery b = new Battery();
        if (output == null) return b;
        for (String raw : output.split("\r?\n")) {
            String t = raw.trim();
            Matcher m;
            if ((m = Pattern.compile("^(AC|USB|Wireless|Dock) powered: (true|false)$").matcher(t)).find()) {
                b.read = true;
                boolean v = "true".equals(m.group(2));
                switch (m.group(1)) { case "AC": b.ac = v; break; case "USB": b.usb = v; break; case "Wireless": b.wireless = v; break; default: b.dock = v; }
            } else if ((m = Pattern.compile("^level: (\\d{1,3})$").matcher(t)).find()) { b.read = true; b.level = Integer.parseInt(m.group(1)); }
            else if ((m = Pattern.compile("^status: (\\d{1,2})$").matcher(t)).find()) { b.read = true; b.status = Integer.parseInt(m.group(1)); }
        }
        return b;
    }

    /** The battery saver lines of {@code dumpsys power} (BatterySaverStateMachine.dump); null when the phone does not print one. */
    static final class Saver {
        Boolean enabled, powered, setting;             // Enabled= (what is in force), mIsPowered=, mSettingBatterySaverEnabled=
        Integer automatic;                             // mSettingAutomaticBatterySaver= (0 percentage, 1 dynamic)
    }

    static Saver parseSaver(String dumpsysPower) {
        Saver s = new Saver();
        if (dumpsysPower == null) return s;
        for (String raw : dumpsysPower.split("\r?\n")) {
            String t = raw.trim();
            Matcher m;
            if ((m = Pattern.compile("^Enabled=(true|false)$").matcher(t)).find()) s.enabled = Boolean.valueOf(m.group(1));
            else if ((m = Pattern.compile("^mIsPowered=(true|false)$").matcher(t)).find()) s.powered = Boolean.valueOf(m.group(1));
            else if ((m = Pattern.compile("^mSettingBatterySaverEnabled=(true|false)$").matcher(t)).find()) s.setting = Boolean.valueOf(m.group(1));
            else if ((m = Pattern.compile("^mSettingAutomaticBatterySaver=(\\d)$").matcher(t)).find()) s.automatic = Integer.valueOf(m.group(1));
        }
        return s;
    }

    /** {@code /proc/sys/vm/swappiness}: 0..200, or -1 when the text is no such number (an error, nothing). */
    static int parseSwappiness(String output) {
        String t = output == null ? "" : output.trim();
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl).trim();
        if (!t.matches("\\d{1,3}")) return -1;
        int v = Integer.parseInt(t);
        return v > 200 ? -1 : v;
    }

    /** {@code /proc/pressure/memory}: the {@code some} and {@code full} ten-second averages in percent, or null when the file is not there or reads oddly. */
    static double[] parsePressure(String output) {
        if (output == null) return null;
        Double some = null, full = null;
        for (String raw : output.split("\r?\n")) {
            Matcher m = Pattern.compile("^\\s*(some|full) avg10=(\\d+(?:\\.\\d+)?)").matcher(raw);
            if (!m.find()) continue;
            double v = Double.parseDouble(m.group(2));
            if (m.group(1).equals("some")) some = v; else full = v;
        }
        if (some == null) return null;
        return new double[] { some, full == null ? 0 : full };
    }

    /** The zram sizes the page offers for a phone with this much RAM, in GB: 1, 2, 3, 4, 6, 8, 12 and 16 as far as one and a half times the RAM (at least 1). */
    static int[] zramSizeChoicesGb(long memTotalKb) {
        double ramGb = memTotalKb <= 0 ? 0 : memTotalKb / 1048576.0;
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int gb : new int[] { 1, 2, 3, 4, 6, 8, 12, 16 }) if (out.isEmpty() || gb <= ramGb * 1.5) out.add(gb);
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** {@code /proc/swaps}: whether a zram device is an active swap. */
    static boolean zramSwapOn(String procSwaps) {
        if (procSwaps == null) return false;
        for (String raw : procSwaps.split("\r?\n")) if (raw.trim().startsWith("/dev/block/zram")) return true;
        return false;
    }
}
