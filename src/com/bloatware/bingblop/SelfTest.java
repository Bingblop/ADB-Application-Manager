package com.bloatware.bingblop;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The About tab's self-test: a fixed list of read-only shell checks, a verdict for each from what it printed, and a report (and
 * a pre-filled GitHub issue address) that says what works in the current working mode. Pure Java, free of Android imports, so
 * the verdicts, the redaction and the report can be tested off the device.
 *
 * <p>Nothing here changes the phone: every command only reads. Nothing typed by a person reaches a command (the only variable
 * part is the app's own package name). The report holds no serial number, IP or MAC address, account name, key or setting
 * value: details are counts, yes/no answers and, for a failed check, the first line of the error with addresses and long
 * tokens blanked out ({@link #sanitize}).
 *
 * <p>A check that needs elevated access is SKIPped, not failed, when the app only runs as itself, so a phone in Read-Only mode
 * does not fill the report with failures that are expected.
 */
final class SelfTest {

    private SelfTest() {}

    enum Status { PASS, FAIL, INFO, SKIP }

    /** What a check proved, and the short words shown next to it. */
    static final class Result {
        final Status status;
        final String detail;
        Result(Status status, String detail) { this.status = status; this.detail = detail == null ? "" : detail; }
    }

    /** One check: what to run, how to judge it, and whether it can only work with elevated access. */
    static final class Probe {
        final String id, group, title, cmd;
        final Kind kind;
        final boolean needsPrivilege;
        Probe(String id, String group, String title, String cmd, Kind kind, boolean needsPrivilege) {
            this.id = id; this.group = group; this.title = title; this.cmd = cmd; this.kind = kind; this.needsPrivilege = needsPrivilege;
        }
    }

    /** One finished check. */
    static final class Row {
        final Probe probe;
        final Result result;
        Row(Probe probe, Result result) { this.probe = probe; this.result = result; }
    }

    /** Runs one command in the current working mode and returns what it printed (an "Error: ..." line when it could not run). */
    interface Shell { String run(String cmd); }

    /** How a check's output is turned into a verdict. */
    enum Kind { IDENTITY, SELINUX, PKG_COUNT, PKG_COUNT_ANY, PKG_PATH, PKG_SELF, RUNS, SETTINGS_COUNT, SETTING_VALUE,
        WM_SIZE, BATTERY, SDK, LOGCAT, READABLE, REACHABLE, AVAILABLE, LISTS_COMMAND, FILES_PRESENT }

    static final String GROUP_PRIV = "Access", GROUP_PKG = "Packages", GROUP_SET = "Settings", GROUP_SYS = "System",
        GROUP_FILES = "Files", GROUP_CAP = "Other features";

    /** Where the pre-filled issue goes; the form is .github/ISSUE_TEMPLATE/phone-bug-report.yml. */
    static final String REPO = "https://github.com/Bingblop/ADB-Application-Manager";
    static final String ISSUE_TEMPLATE = "phone-bug-report.yml";

    /** The checks, in the order they run. {@code ownPackage} is this app's package name (a fixed, validated word, never page input). */
    static List<Probe> probes(String ownPackage) {
        String own = safePackage(ownPackage);
        List<Probe> p = new ArrayList<Probe>();
        p.add(new Probe("identity", GROUP_PRIV, "Who the commands run as", "id", Kind.IDENTITY, false));
        p.add(new Probe("selinux", GROUP_PRIV, "SELinux mode", "getenforce", Kind.SELINUX, false));

        p.add(new Probe("pm-list", GROUP_PKG, "List all packages", "pm list packages", Kind.PKG_COUNT, false));
        p.add(new Probe("pm-list-3", GROUP_PKG, "List user-installed packages", "pm list packages -3", Kind.PKG_COUNT_ANY, false));
        p.add(new Probe("pm-list-d", GROUP_PKG, "List disabled packages", "pm list packages -d", Kind.PKG_COUNT_ANY, false));
        p.add(new Probe("pm-path", GROUP_PKG, "Find an app's APK path", "pm path com.android.settings", Kind.PKG_PATH, false));
        p.add(new Probe("dumpsys-package", GROUP_PKG, "Read one app's package record", "dumpsys package " + own + " | head -n 12", Kind.PKG_SELF, true));
        p.add(new Probe("appops", GROUP_PKG, "Read one app's App Ops", "cmd appops get " + own, Kind.RUNS, true));

        p.add(new Probe("settings-global", GROUP_SET, "Read the Global settings table", "settings list global", Kind.SETTINGS_COUNT, false));
        p.add(new Probe("settings-secure", GROUP_SET, "Read the Secure settings table", "settings list secure", Kind.SETTINGS_COUNT, false));
        p.add(new Probe("settings-system", GROUP_SET, "Read the System settings table", "settings list system", Kind.SETTINGS_COUNT, false));
        p.add(new Probe("dev-options", GROUP_SET, "Developer options switch", "settings get global development_settings_enabled", Kind.SETTING_VALUE, false));
        p.add(new Probe("adb-enabled", GROUP_SET, "USB debugging switch", "settings get global adb_enabled", Kind.SETTING_VALUE, false));
        p.add(new Probe("adb-wifi", GROUP_SET, "Wireless debugging switch", "settings get global adb_wifi_enabled", Kind.SETTING_VALUE, false));

        p.add(new Probe("getprop-sdk", GROUP_SYS, "Read a system property", "getprop ro.build.version.sdk", Kind.SDK, false));
        p.add(new Probe("wm-size", GROUP_SYS, "Read the screen size", "wm size", Kind.WM_SIZE, true));
        p.add(new Probe("dumpsys-battery", GROUP_SYS, "Read the battery service", "dumpsys battery | head -n 3", Kind.BATTERY, true));
        p.add(new Probe("logcat", GROUP_SYS, "Read the log", "logcat -d -t 1", Kind.LOGCAT, false));

        p.add(new Probe("ls-sdcard", GROUP_FILES, "List shared storage", "ls /sdcard/Android", Kind.READABLE, false));
        p.add(new Probe("ls-tmp", GROUP_FILES, "Reach the shell's own folder", "ls -d /data/local/tmp", Kind.REACHABLE, false));
        p.add(new Probe("ls-android-data", GROUP_FILES, "List other apps' storage folders", "ls /sdcard/Android/data", Kind.REACHABLE, false));

        p.add(new Probe("cap-dropbox", GROUP_CAP, "Crash and ANR history (dropbox)", "dumpsys dropbox | head -n 4", Kind.AVAILABLE, true));
        p.add(new Probe("cap-batterystats", GROUP_CAP, "Battery statistics", "dumpsys batterystats | head -n 4", Kind.AVAILABLE, true));
        p.add(new Probe("cap-netpolicy", GROUP_CAP, "Network policy commands", "cmd netpolicy help", Kind.AVAILABLE, true));
        p.add(new Probe("cap-connectivity", GROUP_CAP, "Per-app network switch (set-package-networking-enabled)",
            "cmd connectivity help", Kind.LISTS_COMMAND, true));
        p.add(new Probe("cap-jobscheduler", GROUP_CAP, "Job scheduler commands", "cmd jobscheduler help", Kind.AVAILABLE, true));
        p.add(new Probe("cap-screen-tools", GROUP_CAP, "Screenshot and screen-record tools", "ls /system/bin/screencap /system/bin/screenrecord", Kind.FILES_PRESENT, true));
        return p;
    }

    /** A package name made of letters, digits, dots and underscores; anything else becomes a harmless placeholder (so a command never carries odd characters). */
    static String safePackage(String pkg) {
        if (pkg != null && pkg.length() > 0 && pkg.length() <= 150 && pkg.matches("[A-Za-z0-9_.]+")) return pkg;
        return "com.bloatware.bingblop";
    }

    // ---------------------------------------------------------------- running

    /**
     * Runs the checks in order. {@code listener} (may be null) hears each finished row. Elevated checks are skipped, not failed,
     * once the first check shows the commands run as the app itself.
     */
    static List<Row> run(List<Probe> probes, Shell shell, Listener listener) {
        List<Row> rows = new ArrayList<Row>();
        boolean elevated = true;
        int i = 0;
        for (Probe pr : probes) {
            Result r;
            if (pr.needsPrivilege && !elevated) {
                r = new Result(Status.SKIP, "needs ADB, Wireless Debugging, Shizuku or Root");
            } else {
                String out;
                try { out = shell.run(pr.cmd); } catch (Throwable t) { out = "Error: " + t.getClass().getSimpleName(); }
                r = judge(pr, out);
                if (pr.kind == Kind.IDENTITY && isAppItself(r)) elevated = false;
            }
            Row row = new Row(pr, r);
            rows.add(row);
            i++;
            if (listener != null) listener.onRow(i, probes.size(), row);
        }
        return rows;
    }

    interface Listener { void onRow(int done, int total, Row row); }

    // ---------------------------------------------------------------- verdicts

    /** Turns what a command printed into a verdict. Never throws, never keeps raw output beyond a sanitized first line of an error. */
    static Result judge(Probe p, String output) {
        String out = output == null ? "" : output;
        boolean failed = looksFailed(out);
        switch (p.kind) {
            case IDENTITY: return judgeIdentity(out);
            case SELINUX: {
                String w = firstWord(out).toLowerCase(Locale.ROOT);
                if (failed || w.isEmpty()) return new Result(Status.INFO, "not readable");
                return new Result(Status.INFO, w);
            }
            case PKG_COUNT: {
                if (failed) return fail(out);
                int n = countPrefixed(out, "package:");
                return n >= 20 ? new Result(Status.PASS, n + " packages") : new Result(Status.FAIL, n + " packages listed, expected many more");
            }
            case PKG_COUNT_ANY: {
                if (failed) return fail(out);
                return new Result(Status.PASS, countPrefixed(out, "package:") + " packages");
            }
            case PKG_PATH: {
                if (failed) return fail(out);
                return out.trim().startsWith("package:") ? new Result(Status.PASS, "found") : new Result(Status.FAIL, "no path printed");
            }
            case PKG_SELF: {
                if (failed) return fail(out);
                return out.contains("Package [") ? new Result(Status.PASS, "read") : new Result(Status.FAIL, "no package record printed");
            }
            case RUNS: {
                if (failed) return fail(out);
                return new Result(Status.PASS, "ran");
            }
            case SETTINGS_COUNT: {
                if (failed) return fail(out);
                int n = countLinesWith(out, '=');
                return n >= 5 ? new Result(Status.PASS, n + " entries") : new Result(Status.FAIL, n + " entries read, expected more");
            }
            case SETTING_VALUE: {
                if (failed) return new Result(Status.INFO, "not readable");
                String v = firstWord(out);
                if (v.equals("1")) return new Result(Status.INFO, "on");
                if (v.equals("0")) return new Result(Status.INFO, "off");
                if (v.equals("null") || v.isEmpty()) return new Result(Status.INFO, "not set");
                return new Result(Status.INFO, "set");
            }
            case WM_SIZE: {
                if (failed) return fail(out);
                return out.contains("Physical size") ? new Result(Status.PASS, "read") : new Result(Status.FAIL, "no size printed");
            }
            case BATTERY: {
                if (failed) return fail(out);
                return out.contains("Battery Service") || out.contains("powered") ? new Result(Status.PASS, "read") : new Result(Status.FAIL, "no battery state printed");
            }
            case SDK: {
                String v = firstWord(out);
                if (!failed && v.matches("[0-9]{1,3}")) return new Result(Status.PASS, "API " + v);
                return failed ? fail(out) : new Result(Status.FAIL, "no number printed");
            }
            case LOGCAT: {
                if (failed) return fail(out);
                return new Result(Status.PASS, "read");
            }
            case READABLE: {
                if (failed) return fail(out);
                return new Result(Status.PASS, "readable");
            }
            case REACHABLE: {
                return new Result(Status.INFO, failed ? "not reachable" : "reachable");
            }
            case AVAILABLE: {
                if (failed || out.trim().isEmpty()) return new Result(Status.INFO, "not available");
                return new Result(Status.INFO, "available");
            }
            case LISTS_COMMAND: {
                if (failed || out.trim().isEmpty()) return new Result(Status.INFO, "not available");
                return out.contains("set-package-networking-enabled") ? new Result(Status.INFO, "available") : new Result(Status.INFO, "command not listed");
            }
            case FILES_PRESENT: {
                int n = countPrefixed(out, "/system/bin/");
                return new Result(Status.INFO, n >= 2 ? "both present" : n == 1 ? "one of the two present" : "not available");
            }
            default: return new Result(Status.INFO, "");
        }
    }

    /** True when the identity check found the commands run as the app itself (not shell or root). */
    static boolean isAppItself(Result r) { return r.status == Status.INFO && r.detail.startsWith("the app itself"); }

    private static Result judgeIdentity(String out) {
        if (looksFailed(out) || out.trim().isEmpty()) return new Result(Status.INFO, "could not tell");
        java.util.regex.Matcher m = Pattern.compile("uid=(\\d+)").matcher(out);
        if (!m.find()) return new Result(Status.INFO, "could not tell");
        int uid;
        try { uid = Integer.parseInt(m.group(1)); } catch (NumberFormatException e) { return new Result(Status.INFO, "could not tell"); }
        if (uid == 0) return new Result(Status.PASS, "root (uid 0)");
        if (uid == 2000) return new Result(Status.PASS, "shell (uid 2000)");
        return new Result(Status.INFO, "the app itself (uid " + uid + "): no elevated access");
    }

    private static Result fail(String out) { return new Result(Status.FAIL, sanitize(firstLine(out))); }

    /** True when the output is an error rather than an answer: an "Error:" line from the app, a denied permission, a missing command or file. */
    static boolean looksFailed(String out) {
        if (out == null) return true;
        String t = out.trim();
        if (t.isEmpty()) return false;
        String l = t.toLowerCase(Locale.ROOT);
        return l.startsWith("error") || l.contains("permission denial") || l.contains("securityexception") || l.contains("permission denied")
            || l.contains("exception occurred") || l.contains("can't find service") || l.contains("unknown command")
            || l.contains("inaccessible or not found") || l.contains(": not found") || l.contains("no such file")
            || l.contains("not allowed") || l.contains("operation not permitted") || l.contains("[process timed out");
    }

    // ---------------------------------------------------------------- redaction

    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}\\b|(?<![0-9a-z:])[0-9a-f]{0,4}::[0-9a-f:]*(?![0-9a-z])");
    private static final Pattern MAC = Pattern.compile("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9+/_=-]{24,}");

    /** One short, safe line: addresses, emails and long tokens blanked, white space collapsed, at most {@link #MAX_DETAIL} characters. */
    static String sanitize(String s) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        t = MAC.matcher(t).replaceAll("<mac>");
        t = EMAIL.matcher(t).replaceAll("<email>");
        t = IPV4.matcher(t).replaceAll("<ip>");
        t = IPV6.matcher(t).replaceAll("<ip>");
        t = TOKEN.matcher(t).replaceAll("<redacted>");
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replace("|", "/").replace("`", "'");
        if (t.length() > MAX_DETAIL) t = t.substring(0, MAX_DETAIL - 1) + "…";
        return t;
    }

    static final int MAX_DETAIL = 90;

    // ---------------------------------------------------------------- report

    static int count(List<Row> rows, Status s) {
        int n = 0;
        for (Row r : rows) if (r.result.status == s) n++;
        return n;
    }

    /** "22 passed, 1 failed, 5 info, 2 skipped". */
    static String summary(List<Row> rows) {
        return count(rows, Status.PASS) + " passed, " + count(rows, Status.FAIL) + " failed, "
            + count(rows, Status.INFO) + " info, " + count(rows, Status.SKIP) + " skipped";
    }

    /** The whole report as Markdown, ready to paste into an issue. {@code facts} are the app's own lines (version, Android, mode ...), already free of addresses. */
    static String report(Map<String, String> facts, List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Self-test report\n");
        sb.append("Read-only checks run by the app. No serial number, IP address, account, key or setting value is included.\n\n");
        if (facts != null) for (Map.Entry<String, String> e : facts.entrySet()) sb.append("- ").append(e.getKey()).append(": ").append(sanitize(e.getValue())).append('\n');
        sb.append("\n**").append(summary(rows)).append("**\n\n");
        sb.append("| Check | Result | Detail |\n|---|---|---|\n");
        String group = "";
        for (Row r : rows) {
            if (!r.probe.group.equals(group)) {
                group = r.probe.group;
                sb.append("| **").append(group).append("** | | |\n");
            }
            sb.append("| ").append(r.probe.title).append(" | ").append(r.result.status.name()).append(" | ").append(sanitize(r.result.detail)).append(" |\n");
        }
        return sb.toString();
    }

    /** The short version for an address: the facts, the summary and every row that is not a plain pass or info, cut to {@code maxChars}. */
    static String compactReport(Map<String, String> facts, List<Row> rows, int maxChars) {
        StringBuilder sb = new StringBuilder();
        if (facts != null) for (Map.Entry<String, String> e : facts.entrySet()) sb.append(e.getKey()).append(": ").append(sanitize(e.getValue())).append('\n');
        sb.append("Self-test: ").append(summary(rows)).append('\n');
        for (Row r : rows) {
            if (r.result.status == Status.FAIL) sb.append("FAIL ").append(r.probe.id).append(": ").append(sanitize(r.result.detail)).append('\n');
        }
        sb.append("(The full table is in the report the app copied; paste it here.)");
        String s = sb.toString();
        return s.length() <= maxChars ? s : s.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    /** The form's option for the active working mode ("Not sure" for anything else). */
    static String modeOption(String activeMode) {
        if ("adb_tcp".equals(activeMode)) return "ADB over TCP (adb tcpip 5555)";
        if ("adb_wireless".equals(activeMode)) return "Wireless Debugging (Android 11+, paired in the app)";
        if ("shizuku".equals(activeMode)) return "Shizuku";
        if ("root".equals(activeMode)) return "Root";
        if ("unprivileged".equals(activeMode)) return "Read-Only (no working mode)";
        return "Not sure";
    }

    /** Longest address the page opens: GitHub refuses very long ones, and the browser may too. */
    static final int MAX_URL = 6000;

    /**
     * The address of the phone bug report form with the fields filled in: working mode, app version, phone, a step line and the
     * compact report. The form's other fields stay empty for the person to fill in. Always at most {@link #MAX_URL} characters.
     */
    static String issueUrl(String activeMode, String appVersion, String device, Map<String, String> facts, List<Row> rows) {
        int budget = 1800;
        while (true) {
            String url = REPO + "/issues/new?template=" + ISSUE_TEMPLATE
                + "&title=" + enc("[Phone] Self-test: " + summary(rows))
                + "&mode=" + enc(modeOption(activeMode))
                + "&version=" + enc(sanitize(appVersion))
                + "&device=" + enc(sanitize(device))
                + "&step=" + enc("About tab, Self-test")
                + "&what=" + enc("Ran the About tab's Self-test. The result is below; the full table is in the report the app copied.")
                + "&output=" + enc(compactReport(facts, rows, budget));
            if (url.length() <= MAX_URL || budget <= 200) return url.length() <= MAX_URL ? url : url.substring(0, MAX_URL);
            budget -= 400;
        }
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (UnsupportedEncodingException e) { return ""; }
    }

    // ---------------------------------------------------------------- small helpers

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String l : s.split("\r?\n")) if (!l.trim().isEmpty()) return l.trim();
        return "";
    }

    private static String firstWord(String s) {
        String l = firstLine(s);
        int sp = l.indexOf(' ');
        return sp < 0 ? l : l.substring(0, sp);
    }

    private static int countPrefixed(String out, String prefix) {
        int n = 0;
        for (String l : out.split("\r?\n")) if (l.trim().startsWith(prefix)) n++;
        return n;
    }

    private static int countLinesWith(String out, char c) {
        int n = 0;
        for (String l : out.split("\r?\n")) if (l.indexOf(c) > 0) n++;
        return n;
    }

    /** Facts in the order a report shows them. */
    static LinkedHashMap<String, String> facts() { return new LinkedHashMap<String, String>(); }
}
