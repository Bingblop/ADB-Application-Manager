package com.bloatware.bingblop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The About tab's self-test: the list of checks is read-only and free of page input, each verdict follows what the command printed,
 *  elevated checks are skipped (not failed) for an app that runs as itself, nothing an address or token could hide in reaches the
 *  report, and the pre-filled issue address stays short however much a phone prints. */
public class SelfTestTest {
    static int n = 0, fails = 0;
    static void is(String what, boolean got) { n++; if (!got) { fails++; System.out.println("FAIL " + what); } }
    static void eq(String what, Object got, Object want) { n++; if (!want.equals(got)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }

    static SelfTest.Probe byId(List<SelfTest.Probe> ps, String id) {
        for (SelfTest.Probe p : ps) if (p.id.equals(id)) return p;
        throw new IllegalStateException("no probe " + id);
    }

    static SelfTest.Result judge(String id, String out) { return SelfTest.judge(byId(SelfTest.probes("com.example.app"), id), out); }

    public static void main(String[] a) {
        List<SelfTest.Probe> ps = SelfTest.probes("com.bloatware.bingblop");

        // ---- the list: unique ids, read-only commands, nothing that changes the phone ----
        Set<String> ids = new HashSet<String>();
        for (SelfTest.Probe p : ps) { is("unique id " + p.id, ids.add(p.id)); is("has a title " + p.id, p.title.length() > 3); }
        is("about thirty checks", ps.size() >= 24 && ps.size() <= 40);
        String[] banned = { " put ", " delete ", " install", " uninstall", " grant ", " revoke ", " disable", " enable", " rm ", " mv ", " cp ", "reboot",
            "kill", " write", "tcpip", " set ", " reset", "clear", ">", " tee ", "svc ", "settings put" };
        for (SelfTest.Probe p : ps) {
            String c = " " + p.cmd + " ";
            for (String b : banned) is("read-only: [" + p.cmd + "] does not contain [" + b + "]", !c.contains(b));
        }
        for (SelfTest.Probe p : ps) if (p.cmd.startsWith("wm ")) eq("wm only reads: " + p.cmd, p.cmd, "wm size");
        is("the first check tells who the commands run as", ps.get(0).id.equals("identity") && !ps.get(0).needsPrivilege);

        // ---- the package name is the only variable part, and it is checked ----
        eq("safe package kept", SelfTest.safePackage("com.example.app_1"), "com.example.app_1");
        eq("a package with a shell word is replaced", SelfTest.safePackage("x; rm -rf /"), "com.bloatware.bingblop");
        eq("empty package is replaced", SelfTest.safePackage(""), "com.bloatware.bingblop");
        eq("null package is replaced", SelfTest.safePackage(null), "com.bloatware.bingblop");
        for (SelfTest.Probe p : SelfTest.probes("a b;c$(d)")) is("no odd characters in [" + p.cmd + "]", p.cmd.indexOf(';') < 0 && p.cmd.indexOf('$') < 0 && p.cmd.indexOf('`') < 0);

        // ---- verdicts ----
        eq("shell uid", judge("identity", "uid=2000(shell) gid=2000(shell) groups=2000(shell),1004(input)").detail, "shell (uid 2000)");
        eq("shell uid passes", judge("identity", "uid=2000(shell) gid=2000(shell)").status, SelfTest.Status.PASS);
        eq("root uid passes", judge("identity", "uid=0(root) gid=0(root)").status, SelfTest.Status.PASS);
        is("app uid is not elevated", SelfTest.isAppItself(judge("identity", "uid=10234(u0_a234) gid=10234(u0_a234)")));
        is("an unreadable id is not taken for the app uid", !SelfTest.isAppItself(judge("identity", "Error: adb is not connected")));
        eq("selinux word", judge("selinux", "Enforcing\n").detail, "enforcing");

        StringBuilder pk = new StringBuilder();
        for (int i = 0; i < 30; i++) pk.append("package:com.x").append(i).append('\n');
        eq("package list passes", judge("pm-list", pk.toString()).status, SelfTest.Status.PASS);
        eq("package count", judge("pm-list", pk.toString()).detail, "30 packages");
        eq("a short package list fails", judge("pm-list", "package:a\npackage:b").status, SelfTest.Status.FAIL);
        eq("an error fails the package list", judge("pm-list", "Error: Wireless Debugging is not configured. Connect it in Working Modes.").status, SelfTest.Status.FAIL);
        eq("zero user apps is still a pass", judge("pm-list-3", "").status, SelfTest.Status.PASS);
        eq("pm path passes", judge("pm-path", "package:/product/app/Settings/Settings.apk").status, SelfTest.Status.PASS);
        eq("pm path without a path fails", judge("pm-path", "").status, SelfTest.Status.FAIL);
        eq("package record", judge("dumpsys-package", "Packages:\n  Package [com.example.app] (abc):").status, SelfTest.Status.PASS);
        eq("dumpsys denied", judge("dumpsys-package", "Permission Denial: can't dump package").status, SelfTest.Status.FAIL);
        SelfTest.Probe dp = byId(ps, "dumpsys-package");
        is("the package record is searched for, not cut to the first lines: " + dp.cmd, dp.cmd.contains("grep -F 'Package ['") && !dp.cmd.contains("head"));
        eq("the record found after the resolver tables passes", judge("dumpsys-package", "  Package [com.bloatware.bingblop] (4f2a1c0):").status, SelfTest.Status.PASS);
        eq("nothing found (grep printed nothing) fails", judge("dumpsys-package", "").status, SelfTest.Status.FAIL);
        eq("appops with nothing to list still ran", judge("appops", "No operations.").status, SelfTest.Status.PASS);

        StringBuilder st = new StringBuilder();
        for (int i = 0; i < 12; i++) st.append("key").append(i).append("=value").append(i).append('\n');
        eq("settings table passes", judge("settings-global", st.toString()).status, SelfTest.Status.PASS);
        eq("settings count shown, never the values", judge("settings-global", st.toString()).detail, "12 entries");
        eq("an empty settings table fails", judge("settings-secure", "").status, SelfTest.Status.FAIL);
        eq("switch on", judge("adb-wifi", "1\n").detail, "on");
        eq("switch off", judge("adb-wifi", "0").detail, "off");
        eq("switch null", judge("adb-wifi", "null").detail, "not set");
        eq("a switch is never a failure", judge("adb-enabled", "Error: x").status, SelfTest.Status.INFO);
        eq("sdk number", judge("getprop-sdk", "34\n").detail, "API 34");
        eq("sdk garbage fails", judge("getprop-sdk", "hello").status, SelfTest.Status.FAIL);
        eq("wm size", judge("wm-size", "Physical size: 1080x2400").status, SelfTest.Status.PASS);
        eq("battery", judge("dumpsys-battery", "Current Battery Service state:\n  AC powered: false").status, SelfTest.Status.PASS);
        eq("shared storage listing", judge("ls-sdcard", "Alarms\nAndroid\nDCIM").status, SelfTest.Status.PASS);
        eq("shared storage listing refused", judge("ls-sdcard", "ls: /sdcard/Android: Permission denied").status, SelfTest.Status.FAIL);
        eq("data folder reachable", judge("ls-android-data", "com.a\ncom.b").detail, "reachable");
        eq("data folder not reachable", judge("ls-android-data", "ls: /sdcard/Android/data: Permission denied").detail, "not reachable");
        eq("capability available", judge("cap-dropbox", "Drop box contents: 5 entries").detail, "available");
        eq("capability missing", judge("cap-dropbox", "Can't find service: dropbox").detail, "not available");
        eq("capability probe is never a failure", judge("cap-netpolicy", "cmd: Can't find service: netpolicy").status, SelfTest.Status.INFO);
        eq("connectivity command listed", judge("cap-connectivity", "Connectivity service commands:\n  set-package-networking-enabled [true|false] PACKAGE").detail, "available");
        eq("connectivity command not listed", judge("cap-connectivity", "Connectivity service commands:\n  help").detail, "command not listed");
        eq("both screen tools", judge("cap-screen-tools", "/system/bin/screencap\n/system/bin/screenrecord").detail, "both present");
        eq("one screen tool", judge("cap-screen-tools", "/system/bin/screencap\nls: /system/bin/screenrecord: No such file or directory").detail, "one of the two present");
        eq("null output does not throw", judge("pm-list", null).status, SelfTest.Status.FAIL);

        // ---- the run: elevated checks are skipped for an app that runs as itself, run in order, the listener hears every row ----
        final List<String> ran = new ArrayList<String>();
        final int[] heard = { 0 };
        SelfTest.Shell appOnly = new SelfTest.Shell() { public String run(String cmd) { ran.add(cmd); return cmd.equals("id") ? "uid=10234(u0_a234) gid=10234" : "package:a"; } };
        List<SelfTest.Row> rows = SelfTest.run(ps, appOnly, new SelfTest.Listener() { public void onRow(int d, int t, SelfTest.Row r) { heard[0]++; } });
        eq("one row per check", rows.size(), ps.size());
        eq("the listener heard every row", heard[0], ps.size());
        for (SelfTest.Row r : rows) {
            if (r.probe.needsPrivilege) { eq("skipped, not failed: " + r.probe.id, r.result.status, SelfTest.Status.SKIP); is("a skipped check never ran: " + r.probe.id, !ran.contains(r.probe.cmd)); }
        }
        is("checks that need nothing special still ran", ran.contains("pm list packages"));

        final List<String> ran2 = new ArrayList<String>();
        SelfTest.Shell shell = new SelfTest.Shell() { public String run(String cmd) { ran2.add(cmd); return cmd.equals("id") ? "uid=2000(shell) gid=2000(shell)" : ""; } };
        List<SelfTest.Row> rows2 = SelfTest.run(ps, shell, null);
        eq("nothing skipped as shell", SelfTest.count(rows2, SelfTest.Status.SKIP), 0);
        eq("every command ran as shell", ran2.size(), ps.size());
        SelfTest.Shell throwing = new SelfTest.Shell() { public String run(String cmd) { throw new IllegalStateException("boom"); } };
        List<SelfTest.Row> rows3 = SelfTest.run(ps, throwing, null);
        eq("a throwing shell yields rows, not a crash", rows3.size(), ps.size());

        // ---- redaction ----
        eq("ipv4 with port", SelfTest.sanitize("failed to connect to 192.168.1.23:37199"), "failed to connect to <ip>");
        eq("ipv6", SelfTest.sanitize("host fe80::1c2d:3eff:fe4f:5a6b refused"), "host <ip> refused");
        eq("mac", SelfTest.sanitize("device aa:bb:cc:dd:ee:ff paired"), "device <mac> paired");
        eq("email", SelfTest.sanitize("account jane.doe@example.com signed in"), "account <email> signed in");
        eq("long token", SelfTest.sanitize("key AbCdEfGhIjKlMnOpQrStUvWxYz0123456789 end"), "key <redacted> end");
        eq("line breaks and pipes", SelfTest.sanitize("a\nb | c"), "a b / c");
        is("cut to the limit", SelfTest.sanitize(repeat("word ", 100)).length() <= SelfTest.MAX_DETAIL);
        eq("null is empty", SelfTest.sanitize(null), "");

        // ---- the report ----
        Map<String, String> facts = new LinkedHashMap<String, String>();
        facts.put("App", "ADB Application Manager Pro 7.13.3 (857)");
        facts.put("Android", "14 (API 34)");
        facts.put("Wi-Fi", "connected to 10.0.0.5");
        List<SelfTest.Row> mixed = new ArrayList<SelfTest.Row>();
        mixed.add(new SelfTest.Row(byId(ps, "pm-list"), new SelfTest.Result(SelfTest.Status.PASS, "30 packages")));
        mixed.add(new SelfTest.Row(byId(ps, "dumpsys-package"), new SelfTest.Result(SelfTest.Status.FAIL, "Permission Denial from 192.168.1.9 | x")));
        mixed.add(new SelfTest.Row(byId(ps, "cap-dropbox"), new SelfTest.Result(SelfTest.Status.INFO, "available")));
        mixed.add(new SelfTest.Row(byId(ps, "appops"), new SelfTest.Result(SelfTest.Status.SKIP, "needs a mode")));
        String rep = SelfTest.report(facts, mixed);
        is("report names the summary", rep.contains("**1 passed, 1 failed, 1 info, 1 skipped**"));
        is("report has the table header", rep.contains("| Check | Result | Detail |"));
        is("report has a row per check", rep.contains("| List all packages | PASS | 30 packages |"));
        is("report blanks an address inside a detail", !rep.contains("192.168.1.9") && !rep.contains("10.0.0.5"));
        is("report keeps the table intact (no stray pipe in a detail)", !rep.contains("| x |") && rep.contains("Permission Denial from <ip> / x"));
        is("report says what it leaves out", rep.contains("No serial number, IP address, account, key or setting value"));
        eq("summary words", SelfTest.summary(mixed), "1 passed, 1 failed, 1 info, 1 skipped");

        // ---- the issue address ----
        String url = SelfTest.issueUrl("shizuku", "7.13.3", "Samsung SM-S911B, Android 14", facts, mixed);
        is("goes to the project's issue form", url.startsWith(SelfTest.REPO + "/issues/new?template=phone-bug-report.yml&"));
        is("fills the working mode", url.contains("&mode=Shizuku&"));
        is("fills the version and phone", url.contains("&version=7.13.3&") && url.contains("&device=Samsung%20SM-S911B%2C%20Android%2014&"));
        is("fills the step", url.contains("&step=About%20tab%2C%20Self-test&"));
        is("fills the report field", url.contains("&output="));
        is("no raw space or line break", url.indexOf(' ') < 0 && url.indexOf('\n') < 0);
        eq("modes map to the form's options", SelfTest.modeOption("adb_wireless"), "Wireless Debugging (Android 11+, paired in the app)");
        eq("unknown mode", SelfTest.modeOption("whatever"), "Not sure");

        List<SelfTest.Row> flood = new ArrayList<SelfTest.Row>();
        for (SelfTest.Probe p : ps) flood.add(new SelfTest.Row(p, new SelfTest.Result(SelfTest.Status.FAIL, repeat("x", 200))));
        String longUrl = SelfTest.issueUrl("adb_tcp", repeat("9", 400), repeat("d", 400), facts, flood);
        is("a phone that fails everything still gives a short address (" + longUrl.length() + ")", longUrl.length() <= SelfTest.MAX_URL);
        is("the short address still names the form", longUrl.startsWith(SelfTest.REPO + "/issues/new?template="));

        System.out.println(n + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }

    static String repeat(String s, int times) { StringBuilder b = new StringBuilder(); for (int i = 0; i < times; i++) b.append(s); return b.toString(); }
}
