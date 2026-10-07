package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Checks for the System UI Tuner rules: every command's exact text, every refusal, the readers on realistic AOSP output, the quoting
 * (an injected title arrives as one argument, tokenised without running anything) and a real {@code sh -n} (syntax check only, nothing is
 * ever executed) over every command that was built.
 */
public class SysUiRulesTest {
    static int n = 0, fails = 0;
    static final List<String> built = new ArrayList<String>();     // every command the checks produced, for the sh -n pass

    static void ok(String name) { n++; System.out.println("ok   " + name); }
    static void fail(String name, String msg) { n++; fails++; System.out.println("FAIL " + name + " " + msg); }
    static void eq(String name, Object got, Object want) { if (Objects.equals(want, got)) ok(name); else fail(name, "got <" + got + "> want <" + want + ">"); }
    static void is(String name, boolean cond) { if (cond) ok(name); else fail(name, ""); }
    /** A command: exact text, and kept for the shell syntax pass. */
    static void cmd(String name, String got, String want) { built.add(got == null ? "" : got); eq(name, got, want); }
    /** The call must throw IllegalArgumentException with a sentence in it (not an empty message, not another kind of exception). */
    static void iae(String name, Runnable r) {
        try {
            r.run();
            fail(name, "no exception");
        } catch (IllegalArgumentException e) {
            if (e.getMessage() == null || e.getMessage().trim().length() < 8) fail(name, "no plain message: " + e.getMessage());
            else ok(name);
        } catch (RuntimeException e) {
            fail(name, "wrong exception " + e);
        }
    }

    static List<String> list(String... s) { return new ArrayList<String>(Arrays.asList(s)); }
    static Map<String, String> map(String... kv) { Map<String, String> m = new LinkedHashMap<String, String>(); for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }

    /** What sh makes of a line made only of words and single-quoted pieces: its arguments. Anything else outside quotes is an error, so a leak is seen. */
    static List<String> argv(String line) {
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean word = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\'') {
                int j = line.indexOf('\'', i + 1);
                if (j < 0) throw new IllegalStateException("unterminated quote in " + line);
                cur.append(line, i + 1, j);
                word = true;
                i = j + 1;
            } else if (c == '\\' && i + 1 < line.length()) {
                cur.append(line.charAt(i + 1));
                word = true;
                i += 2;
            } else if (c == ' ' || c == '\n' || c == '\t') {
                if (word) { out.add(cur.toString()); cur.setLength(0); word = false; }
                i++;
            } else if (";&|<>()$`\"*?[]#~{}!".indexOf(c) >= 0) {
                throw new IllegalStateException("shell character " + c + " outside quotes in " + line);
            } else {
                cur.append(c);
                word = true;
                i++;
            }
        }
        if (word) out.add(cur.toString());
        return out;
    }

    static boolean shellAvailable(String sh) {
        try {
            return runSh(sh, "true") == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** sh -n -c: reads the text, runs nothing. Returns the exit status (0 = it parses). */
    static int runSh(String sh, String script) throws Exception { return runSh(sh, script, false); }
    static int runSh(String sh, String script, boolean quiet) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(sh, "-n", "-c", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getOutputStream().close();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        byte[] buf = new byte[4096];
        int k;
        while ((k = in.read(buf)) > 0) bo.write(buf, 0, k);
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1; }
        if (p.exitValue() != 0 && !quiet) System.out.println("     " + sh + ": " + new String(bo.toByteArray(), StandardCharsets.UTF_8).trim());
        return p.exitValue();
    }

    // ---- sample outputs, as the phones print them ----

    static final String BATTERY_NORMAL = "Current Battery Service state:\n  AC powered: false\n  USB powered: true\n  Wireless powered: false\n  Dock powered: false\n"
            + "  Max charging current: 500000\n  Max charging voltage: 5000000\n  Charge counter: 2851000\n  status: 2\n  health: 2\n  present: true\n"
            + "  level: 87\n  scale: 100\n  voltage: 4350\n  temperature: 301\n  technology: Li-ion\n  Charging state: 1\n  Charging policy: 1\n  Capacity level: 0\n";
    static final String BATTERY_FROZEN = "Current Battery Service state:\n  (UPDATES STOPPED -- use 'reset' to restart)\n  AC powered: true\n  USB powered: false\n"
            + "  Wireless powered: false\n  Dock powered: false\n  Max charging current: 0\n  Charge counter: 1000\n  status: 3\n  health: 2\n  present: false\n"
            + "  level: 15\n  scale: 100\n  voltage: 3900\n  temperature: -20\n  technology: Li-ion\n";
    static final String STATUSBAR_DUMP = "STATUS BAR MANAGER (statusbar)\n  displayId=0\n    mDisabled1=0x820000\n    mDisabled2=0x0\n  mDisableRecords.size=1\n"
            + "    [0] userId=0 what1=0x00820000 what2=0x00000000 pkg=android token=android.os.Binder@5c1f2d9\n  mCurrentUserId=0\n  mIcons=\n    \n"
            + "alarm_clock -> StatusBarIcon(icon=Icon(typ=RESOURCE pkg=com.google.android.deskclock id=0x7f0802e1) visible user=0 )\n"
            + "wifi -> StatusBarIcon(icon=Icon(typ=RESOURCE pkg=android id=0x01080b9a) visible user=0 )\n    \n"
            + "volume -> StatusBarIcon(icon=Icon(typ=RESOURCE pkg=android id=0x010806f2) visible user=0 ) \"Vibrate\"\n"
            + "  mCurrentRequestAddTilePackages=[\n  ]\n";
    static final String OVERLAYS = "android\n[x] com.android.internal.systemui.navbar.gestural\n[ ] com.android.internal.systemui.navbar.threebutton\n"
            + "[ ] com.android.internal.systemui.navbar.twobutton\n[ ] com.android.internal.display.cutout.emulation.corner\n"
            + "[ ] com.android.internal.display.cutout.emulation.double\n[ ] com.android.internal.display.cutout.emulation.hole\n";
    static final String SLOTS = "no_calling\ncall_strength\nalarm_clock\nrotate\nheadset\ndata_saver\nime\nsync_failing\nsync_active\nnfc\ntty\nspeakerphone\n"
            + "cdma_eri\ndata_connection\nphone_evdo_signal\nphone_signal\nsecure\nmanaged_profile\nconnected_display\nvpn\nbluetooth\ncamera\nmicrophone\n"
            + "location\nmute\nvolume\nzen\nscreen_record\ncast\nethernet\noem_satellite\nwifi\nhotspot\nmobile\nairplane\nbattery\nsensors_off\n";
    static final String NOTES = "0|com.android.shell|2020|tag1|2000\n0|com.google.android.gm|4097|null|10150\n0|android|-2147483648|null|1000\n"
            + "10|com.whatsapp|1|chat|10201\n0|com.spotify.music|412|media notification|10160\n10|com.whatsapp|1|chat|1|10201|ignored\n";
    static final String DISPLAYS = "Display Manager State:\n  Display Adapters: size=1\n    mSupportedModes=\n"
            + "      Display.Mode{id=1, width=1080, height=2400, fps=60.000004, alternativeRefreshRates=[90.0, 120.0]}\n"
            + "      Display.Mode{id=2, width=1080, height=2400, fps=90.0, alternativeRefreshRates=[60.000004, 120.0]}\n"
            + "      Display.Mode{id=3, width=1080, height=2400, fps=120.0, alternativeRefreshRates=[60.000004, 90.0]}\n"
            + "      Display.Mode{id=4, width=720, height=1600, fps=59.94, alternativeRefreshRates=[]}\n"
            + "    mActiveMode=Display.Mode{id=2, width=1080, height=2400, fps=90.0, alternativeRefreshRates=[60.000004, 120.0]}\n";

    public static void main(String[] args) throws Exception {
        safeForShell();
        demoAllow();
        demoApply();
        demoRejections();
        shadeAndFlags();
        slots();
        notifications();
        systemActions();
        battery();
        navigation();
        displayAndWindow();
        info();
        quotingEverywhere();
        syntaxPass();
        System.out.println(n + " checks, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }

    // ---- safeForShell ----

    static void safeForShell() {
        eq("safeForShell plain", SysUiRules.safeForShell("Hello"), "'Hello'");
        eq("safeForShell empty", SysUiRules.safeForShell(""), "''");
        eq("safeForShell apostrophe", SysUiRules.safeForShell("it's"), "'it'\\''s'");
        eq("safeForShell injection stays one argument", argv("x " + SysUiRules.safeForShell("'; rm -rf /; '")).get(1), "'; rm -rf /; '");
        eq("safeForShell dollar and backtick stay text", argv("x " + SysUiRules.safeForShell("$(id) `id` \"q\" \\ * ; | &")).get(1), "$(id) `id` \"q\" \\ * ; | &");
        eq("safeForShell keeps unicode", SysUiRules.safeForShell("café 中文"), "'café 中文'");
        eq("safeForShell accepts the longest text", SysUiRules.safeForShell(rep('a', SysUiRules.MAX_TEXT)).length(), SysUiRules.MAX_TEXT + 2);
        iae("safeForShell null", () -> SysUiRules.safeForShell(null));
        iae("safeForShell too long", () -> SysUiRules.safeForShell(rep('a', SysUiRules.MAX_TEXT + 1)));
        iae("safeForShell newline", () -> SysUiRules.safeForShell("a\nb"));
        iae("safeForShell carriage return", () -> SysUiRules.safeForShell("a\rb"));
        iae("safeForShell tab", () -> SysUiRules.safeForShell("a\tb"));
        iae("safeForShell NUL", () -> SysUiRules.safeForShell("a\u0000b"));
        iae("safeForShell escape", () -> SysUiRules.safeForShell("a\u001bb"));
        iae("safeForShell DEL", () -> SysUiRules.safeForShell("a\u007fb"));
        iae("safeForShell C1 control", () -> SysUiRules.safeForShell("a\u0085b"));
        iae("safeForShell line separator", () -> SysUiRules.safeForShell("a b"));
    }

    static String rep(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(c);
        return sb.toString();
    }

    // ---- demo mode ----

    static void demoAllow() {
        cmd("demo allow on", SysUiRules.demoAllowCommands(true), "settings put global sysui_demo_allowed 1");
        cmd("demo allow off", SysUiRules.demoAllowCommands(false), "settings delete global sysui_demo_allowed");
        cmd("demo allowed read", SysUiRules.demoAllowedRead(), "settings get global sysui_demo_allowed");
        eq("demo allowed 1", SysUiRules.parseDemoAllowed("1\n"), Boolean.TRUE);
        eq("demo allowed 0", SysUiRules.parseDemoAllowed("0"), Boolean.FALSE);
        eq("demo allowed null (not set)", SysUiRules.parseDemoAllowed("null\r\n"), Boolean.FALSE);
        eq("demo allowed other number", SysUiRules.parseDemoAllowed("2"), Boolean.TRUE);
        eq("demo allowed after a warning line", SysUiRules.parseDemoAllowed("WARNING: linker\n1\n"), Boolean.TRUE);
        eq("demo allowed error text is unknown", SysUiRules.parseDemoAllowed("Error: closed"), null);
        eq("demo allowed empty is unknown", SysUiRules.parseDemoAllowed("  \n"), null);
        eq("demo allowed no answer is unknown", SysUiRules.parseDemoAllowed(null), null);
        eq("demo allowed true word is unknown", SysUiRules.parseDemoAllowed("true"), null);
        cmd("demo enter", SysUiRules.demoEnter(), "am broadcast -a com.android.systemui.demo -e command enter");
        cmd("demo exit", SysUiRules.demoExit(), "am broadcast -a com.android.systemui.demo -e command exit");
    }

    static final String AM = "am broadcast -a com.android.systemui.demo -e command ";

    static void demoApply() {
        cmd("demoApply empty state sends nothing", SysUiRules.demoApply(new LinkedHashMap<String, String>()), "");
        cmd("demoApply clock", SysUiRules.demoApply(map("clock.hhmm", "1200")), AM + "clock -e hhmm 1200");
        cmd("demoApply clock with colon", SysUiRules.demoApply(map("clock.hhmm", "9:05")), AM + "clock -e hhmm 0905");
        cmd("demoApply clock three digits", SysUiRules.demoApply(map("clock.hhmm", "930")), AM + "clock -e hhmm 0930");
        cmd("demoApply clock midnight", SysUiRules.demoApply(map("clock.hhmm", "00:00")), AM + "clock -e hhmm 0000");
        cmd("demoApply clock last minute", SysUiRules.demoApply(map("clock.hhmm", "2359")), AM + "clock -e hhmm 2359");
        cmd("demoApply battery all three", SysUiRules.demoApply(map("battery.level", "42", "battery.plugged", "true", "battery.powersave", "false")),
                AM + "battery -e level 42 -e plugged true -e powersave false");
        cmd("demoApply battery level only", SysUiRules.demoApply(map("battery.level", "0")), AM + "battery -e level 0");
        cmd("demoApply battery 100", SysUiRules.demoApply(map("battery.level", "100")), AM + "battery -e level 100");
        cmd("demoApply battery level 007 is 7", SysUiRules.demoApply(map("battery.level", "007")), AM + "battery -e level 7");
        cmd("demoApply bars", SysUiRules.demoApply(map("bars.mode", "semi-transparent")), AM + "bars -e mode semi-transparent");
        for (String b : SysUiRules.BAR_MODES) cmd("demoApply bars " + b, SysUiRules.demoApply(map("bars.mode", b)), AM + "bars -e mode " + b);
        cmd("demoApply notifications hidden", SysUiRules.demoApply(map("notifications.visible", "false")), AM + "notifications -e visible false");
        cmd("demoApply notifications shown", SysUiRules.demoApply(map("notifications.visible", "true")), AM + "notifications -e visible true");
        cmd("demoApply volume vibrate", SysUiRules.demoApply(map("status.volume", "vibrate")), AM + "status -e volume vibrate");
        cmd("demoApply bluetooth connected", SysUiRules.demoApply(map("status.bluetooth", "connected")), AM + "status -e bluetooth connected");
        cmd("demoApply zen dnd", SysUiRules.demoApply(map("status.zen", "dnd")), AM + "status -e zen dnd");
        for (String i : new String[] {"location", "alarm", "tty", "mute", "speakerphone", "cast", "hotspot"}) {
            cmd("demoApply status " + i + " show", SysUiRules.demoApply(map("status." + i, "show")), AM + "status -e " + i + " show");
            cmd("demoApply status " + i + " hide", SysUiRules.demoApply(map("status." + i, "hide")), AM + "status -e " + i + " hide");
        }
        cmd("demoApply status icons share one line", SysUiRules.demoApply(map("status.volume", "vibrate", "status.alarm", "show", "status.hotspot", "hide")),
                AM + "status -e volume vibrate -e alarm show -e hotspot hide");
        cmd("demoApply airplane", SysUiRules.demoApply(map("status.airplane", "show")), AM + "network -e airplane show");
        cmd("demoApply sims and nosim", SysUiRules.demoApply(map("network.sims", "2", "network.nosim", "hide")), AM + "network -e sims 2 -e nosim hide");
        cmd("demoApply wifi shown", SysUiRules.demoApply(map("wifi.show", "show", "wifi.level", "4", "wifi.fully", "true", "wifi.activity", "inout")),
                AM + "network -e wifi show -e level 4 -e fully true -e activity inout");
        cmd("demoApply wifi with a name", SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", "Cafe Wi-Fi")), AM + "network -e wifi show -e ssid 'Cafe Wi-Fi'");
        cmd("demoApply wifi hidden drops the details", SysUiRules.demoApply(map("wifi.show", "hide", "wifi.level", "3", "wifi.ssid", "x")), AM + "network -e wifi hide");
        cmd("demoApply wifi level 0", SysUiRules.demoApply(map("wifi.show", "show", "wifi.level", "0")), AM + "network -e wifi show -e level 0");
        cmd("demoApply mobile shown", SysUiRules.demoApply(map("mobile.show", "show", "mobile.level", "3", "mobile.fully", "true", "mobile.datatype", "lte+",
                "mobile.roam", "hide", "mobile.carriernetworkchange", "hide", "mobile.inflate", "false", "mobile.activity", "out")),
                AM + "network -e mobile show -e level 3 -e fully true -e datatype lte+ -e roam hide -e carriernetworkchange hide -e inflate false -e activity out");
        cmd("demoApply mobile with a carrier", SysUiRules.demoApply(map("mobile.show", "show", "mobile.networkname", "My Carrier")), AM + "network -e mobile show -e networkname 'My Carrier'");
        cmd("demoApply mobile hidden drops the details", SysUiRules.demoApply(map("mobile.show", "hide", "mobile.level", "2", "mobile.datatype", "5g")), AM + "network -e mobile hide");
        cmd("demoApply mobile empty data type is none", SysUiRules.demoApply(map("mobile.show", "show", "mobile.datatype", "")), AM + "network -e mobile show -e datatype none");
        for (String t : SysUiRules.DATA_TYPES) cmd("demoApply data type " + t, SysUiRules.demoApply(map("mobile.show", "show", "mobile.datatype", t)), AM + "network -e mobile show -e datatype " + t);
        cmd("demoApply wifi and mobile are separate lines", SysUiRules.demoApply(map("wifi.show", "show", "wifi.level", "4", "mobile.show", "show", "mobile.level", "2")),
                AM + "network -e wifi show -e level 4\n" + AM + "network -e mobile show -e level 2");
        cmd("demoApply skips the keys no System UI handles", SysUiRules.demoApply(map("status.sync", "show", "status.eri", "show", "status.secure", "show")), "");
        cmd("demoApply skips them among others", SysUiRules.demoApply(map("status.sync", "show", "clock.hhmm", "0800")), AM + "clock -e hhmm 0800");
        cmd("demoApply null value means send nothing", SysUiRules.demoApply(map("clock.hhmm", null, "bars.mode", "opaque")), AM + "bars -e mode opaque");
        Map<String, String> full = new LinkedHashMap<String, String>();
        full.put("wifi.show", "show"); full.put("wifi.level", "4"); full.put("wifi.fully", "true"); full.put("wifi.activity", "none");
        full.put("mobile.show", "show"); full.put("mobile.level", "4"); full.put("mobile.fully", "true"); full.put("mobile.datatype", "lte"); full.put("mobile.activity", "none");
        full.put("battery.level", "100"); full.put("battery.plugged", "false"); full.put("clock.hhmm", "1200"); full.put("bars.mode", "opaque");
        full.put("notifications.visible", "false"); full.put("status.volume", "hide"); full.put("status.bluetooth", "hide"); full.put("status.airplane", "hide");
        String all = SysUiRules.demoApply(full);
        cmd("demoApply the whole page in the order clock, battery, bars, notifications, status, network", all,
                AM + "clock -e hhmm 1200\n" + AM + "battery -e level 100 -e plugged false\n" + AM + "bars -e mode opaque\n" + AM + "notifications -e visible false\n"
                + AM + "status -e volume hide -e bluetooth hide\n" + AM + "network -e airplane hide\n"
                + AM + "network -e wifi show -e level 4 -e fully true -e activity none\n" + AM + "network -e mobile show -e level 4 -e fully true -e datatype lte -e activity none");
        is("demoApply never uses --ei or --ez", !all.contains("--ei") && !all.contains("--ez") && !all.contains("--es"));
        is("demoApply spells the key activity (Tweaker's typo is not copied)", all.contains("-e activity ") && !all.contains("activiy"));
        for (String line : all.split("\n")) {
            List<String> a = argv(line);
            is("demoApply line is an am broadcast: " + a.get(0) + " " + a.get(1) + " " + a.get(5), a.get(0).equals("am") && a.get(1).equals("broadcast") && a.get(2).equals("-a") && a.get(3).equals("com.android.systemui.demo") && a.get(4).equals("-e") && a.get(5).equals("command"));
        }
        eq("demoApply ssid injection arrives as one argument", argv(SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", "'; rm -rf /; '"))).get(12), "'; rm -rf /; '");
        eq("demoApply carrier injection arrives as one argument", argv(SysUiRules.demoApply(map("mobile.show", "show", "mobile.networkname", "$(reboot)`id`"))).get(12), "$(reboot)`id`");
        cmd("demoApply keeps an apostrophe in a name", SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", "Joe's")), AM + "network -e wifi show -e ssid 'Joe'\\''s'");
        cmd("demoApply the longest Wi-Fi name", SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", rep('w', SysUiRules.MAX_SSID))), AM + "network -e wifi show -e ssid '" + rep('w', 32) + "'");
    }

    static void demoRejections() {
        iae("demoApply null state", () -> SysUiRules.demoApply(null));
        iae("demoApply unknown key", () -> SysUiRules.demoApply(map("clock", "1200")));
        iae("demoApply unknown key with quote", () -> SysUiRules.demoApply(map("a'; reboot; '", "1")));
        iae("demoApply null key", () -> SysUiRules.demoApply(map(null, "1")));
        iae("demoApply clock letters", () -> SysUiRules.demoApply(map("clock.hhmm", "noon")));
        iae("demoApply clock hour 24", () -> SysUiRules.demoApply(map("clock.hhmm", "2400")));
        iae("demoApply clock minute 60", () -> SysUiRules.demoApply(map("clock.hhmm", "1260")));
        iae("demoApply clock empty", () -> SysUiRules.demoApply(map("clock.hhmm", "")));
        iae("demoApply clock injection", () -> SysUiRules.demoApply(map("clock.hhmm", "12; reboot")));
        iae("demoApply battery 101", () -> SysUiRules.demoApply(map("battery.level", "101")));
        iae("demoApply battery -1", () -> SysUiRules.demoApply(map("battery.level", "-1")));
        iae("demoApply battery letters", () -> SysUiRules.demoApply(map("battery.level", "full")));
        iae("demoApply battery decimal", () -> SysUiRules.demoApply(map("battery.level", "50.5")));
        iae("demoApply battery huge", () -> SysUiRules.demoApply(map("battery.level", "99999999999")));
        iae("demoApply battery empty", () -> SysUiRules.demoApply(map("battery.level", "")));
        iae("demoApply plugged yes", () -> SysUiRules.demoApply(map("battery.plugged", "yes")));
        iae("demoApply plugged upper case", () -> SysUiRules.demoApply(map("battery.plugged", "True")));
        iae("demoApply powersave 1", () -> SysUiRules.demoApply(map("battery.powersave", "1")));
        iae("demoApply bars unknown", () -> SysUiRules.demoApply(map("bars.mode", "clear")));
        iae("demoApply notifications show (not true/false)", () -> SysUiRules.demoApply(map("notifications.visible", "show")));
        iae("demoApply volume show (Tweaker's value never worked)", () -> SysUiRules.demoApply(map("status.volume", "show")));
        iae("demoApply bluetooth show", () -> SysUiRules.demoApply(map("status.bluetooth", "show")));
        iae("demoApply zen unknown", () -> SysUiRules.demoApply(map("status.zen", "on")));
        iae("demoApply location yes", () -> SysUiRules.demoApply(map("status.location", "yes")));
        iae("demoApply airplane true", () -> SysUiRules.demoApply(map("status.airplane", "true")));
        iae("demoApply sims 0", () -> SysUiRules.demoApply(map("network.sims", "0")));
        iae("demoApply sims 9", () -> SysUiRules.demoApply(map("network.sims", "9")));
        iae("demoApply nosim true", () -> SysUiRules.demoApply(map("network.nosim", "true")));
        iae("demoApply wifi value", () -> SysUiRules.demoApply(map("wifi.show", "on")));
        iae("demoApply wifi level 5", () -> SysUiRules.demoApply(map("wifi.show", "show", "wifi.level", "5")));
        iae("demoApply wifi level -1", () -> SysUiRules.demoApply(map("wifi.show", "show", "wifi.level", "-1")));
        iae("demoApply wifi bad level is refused even when hidden", () -> SysUiRules.demoApply(map("wifi.show", "hide", "wifi.level", "9")));
        iae("demoApply wifi fully yes", () -> SysUiRules.demoApply(map("wifi.show", "show", "wifi.fully", "yes")));
        iae("demoApply wifi activity unknown", () -> SysUiRules.demoApply(map("wifi.show", "show", "wifi.activity", "both")));
        iae("demoApply wifi name too long", () -> SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", rep('w', SysUiRules.MAX_SSID + 1))));
        iae("demoApply wifi name empty", () -> SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", "")));
        iae("demoApply wifi name control char", () -> SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", "a\nb")));
        iae("demoApply wifi details without the switch", () -> SysUiRules.demoApply(map("wifi.level", "3")));
        iae("demoApply mobile value", () -> SysUiRules.demoApply(map("mobile.show", "1")));
        iae("demoApply mobile level 5", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.level", "5")));
        iae("demoApply mobile fully 1", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.fully", "1")));
        iae("demoApply data type unknown", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.datatype", "6g")));
        iae("demoApply data type injection", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.datatype", "lte; reboot")));
        iae("demoApply roam true", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.roam", "true")));
        iae("demoApply carrier network change true", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.carriernetworkchange", "true")));
        iae("demoApply inflate show", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.inflate", "show")));
        iae("demoApply mobile activity unknown", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.activity", "up")));
        iae("demoApply carrier too long", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.networkname", rep('c', SysUiRules.MAX_CARRIER + 1))));
        iae("demoApply carrier control char", () -> SysUiRules.demoApply(map("mobile.show", "show", "mobile.networkname", "a\u0007b")));
        iae("demoApply mobile details without the switch", () -> SysUiRules.demoApply(map("mobile.datatype", "lte")));
    }

    // ---- shade and status bar flags ----

    static void shadeAndFlags() {
        cmd("shade expand-notifications", SysUiRules.shadeCommand("expand-notifications"), "cmd statusbar expand-notifications");
        cmd("shade expand-settings", SysUiRules.shadeCommand("expand-settings"), "cmd statusbar expand-settings");
        cmd("shade collapse", SysUiRules.shadeCommand("collapse"), "cmd statusbar collapse");
        cmd("shade check-support", SysUiRules.shadeCommand("check-support"), "cmd statusbar check-support");
        iae("shade unknown", () -> SysUiRules.shadeCommand("add-tile"));
        iae("shade injection", () -> SysUiRules.shadeCommand("collapse; reboot"));
        iae("shade null", () -> SysUiRules.shadeCommand(null));
        iae("shade empty", () -> SysUiRules.shadeCommand(""));

        cmd("flags none for an empty list", SysUiRules.disableFlagsCommand(list()), "cmd statusbar send-disable-flag none");
        cmd("flags none by name", SysUiRules.disableFlagsCommand(list("none")), "cmd statusbar send-disable-flag none");
        cmd("flags one", SysUiRules.disableFlagsCommand(list("clock")), "cmd statusbar send-disable-flag clock");
        cmd("flags two in listing order whatever the order given", SysUiRules.disableFlagsCommand(list("notification-icons", "clock")),
                "cmd statusbar send-disable-flag clock notification-icons");
        cmd("flags repeats count once", SysUiRules.disableFlagsCommand(list("home", "home", "search")), "cmd statusbar send-disable-flag search home");
        cmd("flags all nine", SysUiRules.disableFlagsCommand(Arrays.asList(SysUiRules.DISABLE_FLAGS)),
                "cmd statusbar send-disable-flag search home recents notification-alerts statusbar-expansion system-icons clock notification-icons quick-settings");
        for (String f : SysUiRules.DISABLE_FLAGS) cmd("flag " + f, SysUiRules.disableFlagsCommand(list(f)), "cmd statusbar send-disable-flag " + f);
        iae("flags unknown", () -> SysUiRules.disableFlagsCommand(list("wifi")));
        iae("flags the help text's word notification-peek is not the parser's", () -> SysUiRules.disableFlagsCommand(list("notification-peek")));
        iae("flags injection", () -> SysUiRules.disableFlagsCommand(list("clock; reboot")));
        iae("flags null list", () -> SysUiRules.disableFlagsCommand(null));
        iae("flags null entry", () -> SysUiRules.disableFlagsCommand(list((String) null)));
        iae("flags none with others", () -> SysUiRules.disableFlagsCommand(list("none", "clock")));
        iae("flags upper case", () -> SysUiRules.disableFlagsCommand(list("Clock")));
        eq("risky: none for harmless flags", SysUiRules.disableFlagsRisky(list("clock", "search", "notification-alerts", "system-icons")), list());
        eq("risky: the four that can strand the user", SysUiRules.disableFlagsRisky(list("quick-settings", "recents", "statusbar-expansion", "home", "clock")),
                list("home", "recents", "statusbar-expansion", "quick-settings"));
        eq("risky: empty list", SysUiRules.disableFlagsRisky(list()), list());
        iae("risky: unknown flag", () -> SysUiRules.disableFlagsRisky(list("bogus")));
        iae("risky: null", () -> SysUiRules.disableFlagsRisky(null));
        eq("min sdk of the first group", SysUiRules.disableFlagMinSdk("home"), 29);
        eq("min sdk of the second group", SysUiRules.disableFlagMinSdk("clock"), 30);
        eq("min sdk of quick-settings", SysUiRules.disableFlagMinSdk("quick-settings"), 36);
        iae("min sdk unknown", () -> SysUiRules.disableFlagMinSdk("bogus"));

        eq("disable state from a dumpsys statusbar", SysUiRules.parseDisableState(STATUSBAR_DUMP), list("clock", "notification-icons"));
        eq("disable state nothing off", SysUiRules.parseDisableState("  displayId=0\n    mDisabled1=0x0\n    mDisabled2=0x0\n  mDisableRecords.size=0\n"), list());
        eq("disable state search and expansion", SysUiRules.parseDisableState("  displayId=0\n    mDisabled1=0x2010000\n    mDisabled2=0x0\n"), list("search", "statusbar-expansion"));
        eq("disable state quick-settings is in word 2", SysUiRules.parseDisableState("  displayId=0\n    mDisabled1=0x0\n    mDisabled2=0x1\n"), list("quick-settings"));
        eq("disable state every bit of word 1", SysUiRules.parseDisableState("    mDisabled1=0x3b70000\n    mDisabled2=0x0\n"),
                list("search", "home", "recents", "notification-alerts", "statusbar-expansion", "system-icons", "clock", "notification-icons"));
        eq("disable state reads the first display only", SysUiRules.parseDisableState("  displayId=0\n    mDisabled1=0x200000\n    mDisabled2=0x0\n  displayId=2\n    mDisabled1=0x800000\n    mDisabled2=0x1\n"), list("home"));
        eq("disable state the older single word", SysUiRules.parseDisableState("  mDisabled=0x00040000\n  mDisableRecords.size=0\n"), list("notification-alerts"));
        eq("disable state from the records when there is no mDisabled", SysUiRules.parseDisableState("  [0] userId=0 what1=0x00200000 what2=0x00000001 pkg=android token=x\n"), list("home", "quick-settings"));
        eq("disable state records are ored", SysUiRules.parseDisableState("  [0] userId=0 what=0x00200000 pkg=a\n  [1] userId=0 what=0x00800000 pkg=b\n"), list("home", "clock"));
        eq("disable state unrelated bits are not flags", SysUiRules.parseDisableState("    mDisabled1=0x80000\n    mDisabled2=0x4\n"), list());
        eq("disable state garbage", SysUiRules.parseDisableState("cmd: Can't find service: statusbar"), list());
        eq("disable state null", SysUiRules.parseDisableState(null), list());
        eq("disable state empty", SysUiRules.parseDisableState(""), list());
    }

    // ---- icon slots ----

    static void slots() {
        cmd("slots command", SysUiRules.statusIconSlotsCommand(), "cmd statusbar get-status-icons");
        cmd("slots fallback command", SysUiRules.statusIconSlotsFallbackCommand(), "dumpsys statusbar");
        List<String> s = SysUiRules.parseStatusIconSlots(SLOTS);
        eq("slots count", s.size(), 37);
        eq("slots first and last", s.get(0) + "," + s.get(s.size() - 1), "no_calling,sensors_off");
        is("slots hold alarm_clock and wifi", s.contains("alarm_clock") && s.contains("wifi"));
        eq("slots with blanks, CRLF and an error", SysUiRules.parseStatusIconSlots("\r\nwifi\r\n\r\nmobile\r\nException occurred while executing 'get-status-icons':\r\njava.lang.SecurityException: Neither user 2000 nor current process has android.permission.STATUS_BAR\r\n"), list("wifi", "mobile"));
        eq("slots from a cmd that does not exist", SysUiRules.parseStatusIconSlots("cmd: Can't find service: statusbar\n"), list());
        eq("slots no repeats", SysUiRules.parseStatusIconSlots("wifi\nwifi\nmobile\nwifi\n"), list("wifi", "mobile"));
        eq("slots from dumpsys statusbar mIcons", SysUiRules.parseStatusIconSlots(STATUSBAR_DUMP), list("alarm_clock", "wifi", "volume"));
        eq("slots from the Android 9 icon text", SysUiRules.parseStatusIconSlots("  mIcons=\n    \nbluetooth -> StatusBarIcon(pkg=android user=0 id=0x1080a3f level=0 visible=true num=0 )\n"), list("bluetooth"));
        eq("slots ignore dumpsys lines that are not icons", SysUiRules.parseStatusIconSlots("  mCurrentUserId=0\n  mIcons=\n  mDisableRecords.size=0\n"), list());
        eq("slots null", SysUiRules.parseStatusIconSlots(null), list());
        eq("slots empty", SysUiRules.parseStatusIconSlots(""), list());
        eq("slots a line with a shell character is not a slot", SysUiRules.parseStatusIconSlots("wifi; reboot\n$(id)\n`id`\nok.slot-1\n"), list("ok.slot-1"));
    }

    // ---- notifications ----

    static void notifications() {
        cmd("notify basic", SysUiRules.notifyPostCommand("basic", "Hello", "World", "t1", null, null, null), "cmd notification post -t 'Hello' 't1' 'World'");
        cmd("notify basic without a title", SysUiRules.notifyPostCommand("basic", null, "World", "t1", null, null, null), "cmd notification post 't1' 'World'");
        cmd("notify basic with an empty title", SysUiRules.notifyPostCommand("basic", "", "World", "t1", null, null, null), "cmd notification post 't1' 'World'");
        cmd("notify bigtext", SysUiRules.notifyPostCommand("bigtext", "Big", "A long body", "t2", null, null, null), "cmd notification post -t 'Big' -S bigtext 't2' 'A long body'");
        cmd("notify inbox", SysUiRules.notifyPostCommand("inbox", "Inbox", "Summary", "t3", list("one", "two"), null, null),
                "cmd notification post -t 'Inbox' -S inbox --line 'one' --line 'two' 't3' 'Summary'");
        cmd("notify messaging", SysUiRules.notifyPostCommand("messaging", null, "x", "t4", null, list("Ann:Hello", "Bob:Hi"), "Chat"),
                "cmd notification post -S messaging --conversation 'Chat' --message 'Ann:Hello' --message 'Bob:Hi' 't4' 'x'");
        cmd("notify messaging without a conversation", SysUiRules.notifyPostCommand("messaging", "T", "x", "t4", null, list("Just text"), null),
                "cmd notification post -t 'T' -S messaging --message 'Just text' 't4' 'x'");
        cmd("notify messaging empty conversation", SysUiRules.notifyPostCommand("messaging", null, "x", "t4", null, list("A:b"), ""), "cmd notification post -S messaging --message 'A:b' 't4' 'x'");
        cmd("notify lines are ignored by other styles", SysUiRules.notifyPostCommand("basic", "T", "x", "t", list("a"), list("b"), "c"), "cmd notification post -t 'T' 't' 'x'");
        cmd("notify a tag that starts with a dash gets --", SysUiRules.notifyPostCommand("basic", "T", "x", "-t", null, null, null), "cmd notification post -t 'T' -- '-t' 'x'");
        cmd("notify a text that starts with a dash gets --", SysUiRules.notifyPostCommand("basic", null, "-S bigtext", "t", null, null, null), "cmd notification post -- 't' '-S bigtext'");
        cmd("notify title with an apostrophe", SysUiRules.notifyPostCommand("basic", "It's", "x", "t", null, null, null), "cmd notification post -t 'It'\\''s' 't' 'x'");
        cmd("notify the longest title, text, tag", SysUiRules.notifyPostCommand("basic", rep('T', SysUiRules.MAX_TITLE), rep('x', SysUiRules.MAX_TEXT), rep('g', SysUiRules.MAX_TAG), null, null, null),
                "cmd notification post -t '" + rep('T', 100) + "' '" + rep('g', 64) + "' '" + rep('x', 500) + "'");
        cmd("notify inbox with the most lines", SysUiRules.notifyPostCommand("inbox", null, "s", "t", Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8"), null, null),
                "cmd notification post -S inbox --line '1' --line '2' --line '3' --line '4' --line '5' --line '6' --line '7' --line '8' 't' 's'");
        List<String> argvOut = argv(SysUiRules.notifyPostCommand("basic", "'; rm -rf /; '", "$(reboot) `id` \"x\"", "a b|c", null, null, null));
        eq("notify injected title is one argument", argvOut.get(4), "'; rm -rf /; '");
        eq("notify injected text is one argument", argvOut.get(6), "$(reboot) `id` \"x\"");
        eq("notify tag with a space and a bar is one argument", argvOut.get(5), "a b|c");
        eq("notify the argument count did not grow", argvOut.size(), 7);
        List<String> chat = argv(SysUiRules.notifyPostCommand("messaging", null, "x", "t", null, list("Eve:'; reboot; '"), "'; id; '"));
        eq("notify injected conversation and message stay single arguments", chat.get(6) + "|" + chat.get(8), "'; id; '|Eve:'; reboot; '");
        iae("notify unknown style", () -> SysUiRules.notifyPostCommand("media", "T", "x", "t", null, null, null));
        iae("notify bigpicture is not offered", () -> SysUiRules.notifyPostCommand("bigpicture", "T", "x", "t", null, null, null));
        iae("notify null style", () -> SysUiRules.notifyPostCommand(null, "T", "x", "t", null, null, null));
        iae("notify null text", () -> SysUiRules.notifyPostCommand("basic", "T", null, "t", null, null, null));
        iae("notify empty text", () -> SysUiRules.notifyPostCommand("basic", "T", "", "t", null, null, null));
        iae("notify null tag", () -> SysUiRules.notifyPostCommand("basic", "T", "x", null, null, null, null));
        iae("notify empty tag", () -> SysUiRules.notifyPostCommand("basic", "T", "x", "", null, null, null));
        iae("notify title too long", () -> SysUiRules.notifyPostCommand("basic", rep('T', SysUiRules.MAX_TITLE + 1), "x", "t", null, null, null));
        iae("notify text too long", () -> SysUiRules.notifyPostCommand("basic", "T", rep('x', SysUiRules.MAX_TEXT + 1), "t", null, null, null));
        iae("notify tag too long", () -> SysUiRules.notifyPostCommand("basic", "T", "x", rep('g', SysUiRules.MAX_TAG + 1), null, null, null));
        iae("notify title newline", () -> SysUiRules.notifyPostCommand("basic", "a\nb", "x", "t", null, null, null));
        iae("notify text control char", () -> SysUiRules.notifyPostCommand("basic", "T", "a\u0001b", "t", null, null, null));
        iae("notify tag tab", () -> SysUiRules.notifyPostCommand("basic", "T", "x", "a\tb", null, null, null));
        iae("notify inbox without lines", () -> SysUiRules.notifyPostCommand("inbox", "T", "x", "t", null, null, null));
        iae("notify inbox with an empty list", () -> SysUiRules.notifyPostCommand("inbox", "T", "x", "t", list(), null, null));
        iae("notify inbox with too many lines", () -> SysUiRules.notifyPostCommand("inbox", "T", "x", "t", Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9"), null, null));
        iae("notify inbox line too long", () -> SysUiRules.notifyPostCommand("inbox", "T", "x", "t", list(rep('l', SysUiRules.MAX_LINE + 1)), null, null));
        iae("notify inbox line empty", () -> SysUiRules.notifyPostCommand("inbox", "T", "x", "t", list(""), null, null));
        iae("notify inbox null line", () -> SysUiRules.notifyPostCommand("inbox", "T", "x", "t", list((String) null), null, null));
        iae("notify messaging without messages", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, null, null));
        iae("notify messaging with too many", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"), null));
        iae("notify message starting with a colon", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, list(":hello"), null));
        iae("notify message with a name and no text", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, list("Ann:"), null));
        iae("notify message name too long", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, list(rep('n', 41) + ":hi"), null));
        iae("notify message too long", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, list("A:" + rep('m', SysUiRules.MAX_MESSAGE)), null));
        iae("notify conversation too long", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, list("A:b"), rep('c', 101)));
        iae("notify message control char", () -> SysUiRules.notifyPostCommand("messaging", "T", "x", "t", null, list("A:b\nc"), null));

        cmd("notify list", SysUiRules.notifyListCommand(), "cmd notification list");
        String key = "0|com.android.shell|2020|tag1|2000";
        cmd("notify snooze", SysUiRules.notifySnoozeCommand(key, 60000), "cmd notification snooze --for 60000 '0|com.android.shell|2020|tag1|2000'");
        cmd("notify snooze shortest", SysUiRules.notifySnoozeCommand(key, 1000), "cmd notification snooze --for 1000 '0|com.android.shell|2020|tag1|2000'");
        cmd("notify snooze longest", SysUiRules.notifySnoozeCommand(key, 86400000L), "cmd notification snooze --for 86400000 '0|com.android.shell|2020|tag1|2000'");
        cmd("notify unsnooze", SysUiRules.notifyUnsnoozeCommand(key), "cmd notification unsnooze '0|com.android.shell|2020|tag1|2000'");
        iae("notify snooze too short", () -> SysUiRules.notifySnoozeCommand(key, 999));
        iae("notify snooze zero", () -> SysUiRules.notifySnoozeCommand(key, 0));
        iae("notify snooze negative", () -> SysUiRules.notifySnoozeCommand(key, -5));
        iae("notify snooze too long", () -> SysUiRules.notifySnoozeCommand(key, 86400001L));
        iae("notify snooze bad key", () -> SysUiRules.notifySnoozeCommand("x'; reboot; '", 5000));
        iae("notify snooze null key", () -> SysUiRules.notifySnoozeCommand(null, 5000));
        iae("notify unsnooze bad key", () -> SysUiRules.notifyUnsnoozeCommand("0|a b|1|t|2"));
        iae("notify unsnooze empty key", () -> SysUiRules.notifyUnsnoozeCommand(""));

        is("key: the shell's own", SysUiRules.notifyKeyValid(key));
        is("key: null tag", SysUiRules.notifyKeyValid("0|com.google.android.gm|4097|null|10150"));
        is("key: negative id", SysUiRules.notifyKeyValid("0|android|-2147483648|null|1000"));
        is("key: a tag with a space", SysUiRules.notifyKeyValid("0|com.spotify.music|412|media notification|10160"));
        is("key: a tag with a bar in it", SysUiRules.notifyKeyValid("10|com.whatsapp|1|chat|1|10201"));
        is("key: empty tag", SysUiRules.notifyKeyValid("0|a.b|1||2"));
        is("key: not a key", !SysUiRules.notifyKeyValid("hello"));
        is("key: null", !SysUiRules.notifyKeyValid(null));
        is("key: empty", !SysUiRules.notifyKeyValid(""));
        is("key: a quote in the package", !SysUiRules.notifyKeyValid("0|a'b|1|t|2"));
        is("key: a newline in the tag", !SysUiRules.notifyKeyValid("0|a.b|1|t\nx|2"));
        is("key: a missing field", !SysUiRules.notifyKeyValid("0|a.b|1|t"));
        is("key: letters in the user id", !SysUiRules.notifyKeyValid("x|a.b|1|t|2"));
        is("key: too long", !SysUiRules.notifyKeyValid("0|a.b|1|" + rep('t', 400) + "|2"));
        is("key: injected tag", !SysUiRules.notifyKeyValid("0|a.b|1|t\u0000|2"));
        List<String> keys = SysUiRules.parseNotificationKeys(NOTES);
        eq("keys count", keys.size(), 5);
        eq("keys first and a spaced tag", keys.get(0) + "," + keys.get(4), "0|com.android.shell|2020|tag1|2000,0|com.spotify.music|412|media notification|10160");
        eq("keys from an error", SysUiRules.parseNotificationKeys("Can't find service: notification\n"), list());
        eq("keys from the old Android with no list command", SysUiRules.parseNotificationKeys("Unknown command: list\r\n"), list());
        eq("keys no repeats, CRLF", SysUiRules.parseNotificationKeys("0|a.b|1|t|2\r\n0|a.b|1|t|2\r\n"), list("0|a.b|1|t|2"));
        eq("keys null", SysUiRules.parseNotificationKeys(null), list());
        eq("keys empty", SysUiRules.parseNotificationKeys("\n\n"), list());
    }

    // ---- system actions ----

    static void systemActions() {
        for (int id = 1; id <= 22; id++) {
            cmd("system action " + id, SysUiRules.systemActionCommand(id), "cmd accessibility call-system-action " + id);
            is("system action " + id + " has a label", SysUiRules.actionLabel(id) != null && !SysUiRules.actionLabel(id).isEmpty());
        }
        eq("system action ids", SysUiRules.systemActionIds().length, 22);
        eq("system action labels of the common ones", SysUiRules.actionLabel(1) + "," + SysUiRules.actionLabel(2) + "," + SysUiRules.actionLabel(3) + "," + SysUiRules.actionLabel(9),
                "Back,Home,Recents,Take screenshot");
        for (int bad : new int[] {0, -1, 23, 100, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            iae("system action " + bad + " is refused", () -> SysUiRules.systemActionCommand(bad));
            iae("system action label " + bad + " is refused", () -> SysUiRules.actionLabel(bad));
            iae("system action fallback " + bad + " is refused", () -> SysUiRules.systemActionFallback(bad));
        }
        cmd("fallback back", SysUiRules.systemActionFallback(1), "input keyevent 4");
        cmd("fallback home", SysUiRules.systemActionFallback(2), "input keyevent 3");
        cmd("fallback recents", SysUiRules.systemActionFallback(3), "input keyevent 187");
        cmd("fallback notifications", SysUiRules.systemActionFallback(4), "cmd statusbar expand-notifications");
        cmd("fallback quick settings", SysUiRules.systemActionFallback(5), "cmd statusbar expand-settings");
        cmd("fallback power menu", SysUiRules.systemActionFallback(6), "input keyevent --longpress 26");
        cmd("fallback lock", SysUiRules.systemActionFallback(8), "input keyevent 223");
        cmd("fallback screenshot", SysUiRules.systemActionFallback(9), "input keyevent 120");
        cmd("fallback dismiss shade", SysUiRules.systemActionFallback(15), "cmd statusbar collapse");
        cmd("fallback d-pad center", SysUiRules.systemActionFallback(20), "input keyevent 23");
        eq("fallback none for split screen", SysUiRules.systemActionFallback(7), null);
        eq("fallback none for the accessibility ones", SysUiRules.systemActionFallback(11) + "," + SysUiRules.systemActionFallback(14), "null,null");
        for (int id = 1; id <= 22; id++) {
            String f = SysUiRules.systemActionFallback(id);
            if (f != null) built.add(f);
        }
        cmd("restart System UI", SysUiRules.restartSystemUiCommand(), "am crash com.android.systemui");
    }

    // ---- battery simulator ----

    static void battery() {
        cmd("battery level", SysUiRules.batterySetCommand("level", 15), "dumpsys battery set level 15");
        cmd("battery level 0", SysUiRules.batterySetCommand("level", 0), "dumpsys battery set level 0");
        cmd("battery level 100", SysUiRules.batterySetCommand("level", 100), "dumpsys battery set level 100");
        cmd("battery status 1", SysUiRules.batterySetCommand("status", 1), "dumpsys battery set status 1");
        cmd("battery status 5", SysUiRules.batterySetCommand("status", 5), "dumpsys battery set status 5");
        for (String k : new String[] {"ac", "usb", "wireless", "dock", "present"}) {
            cmd("battery " + k + " 0", SysUiRules.batterySetCommand(k, 0), "dumpsys battery set " + k + " 0");
            cmd("battery " + k + " 1", SysUiRules.batterySetCommand(k, 1), "dumpsys battery set " + k + " 1");
            iae("battery " + k + " 2", () -> SysUiRules.batterySetCommand(k, 2));
            iae("battery " + k + " -1", () -> SysUiRules.batterySetCommand(k, -1));
        }
        cmd("battery temp", SysUiRules.batterySetCommand("temp", 450), "dumpsys battery set temp 450");
        cmd("battery temp lowest", SysUiRules.batterySetCommand("temp", -200), "dumpsys battery set temp -200");
        cmd("battery temp highest", SysUiRules.batterySetCommand("temp", 1000), "dumpsys battery set temp 1000");
        iae("battery level 101", () -> SysUiRules.batterySetCommand("level", 101));
        iae("battery level -1", () -> SysUiRules.batterySetCommand("level", -1));
        iae("battery status 0", () -> SysUiRules.batterySetCommand("status", 0));
        iae("battery status 6", () -> SysUiRules.batterySetCommand("status", 6));
        iae("battery temp -201", () -> SysUiRules.batterySetCommand("temp", -201));
        iae("battery temp 1001", () -> SysUiRules.batterySetCommand("temp", 1001));
        iae("battery unknown key", () -> SysUiRules.batterySetCommand("counter", 5));
        iae("battery invalid key (AOSP's own)", () -> SysUiRules.batterySetCommand("invalid", 1));
        iae("battery key injection", () -> SysUiRules.batterySetCommand("level 5; reboot #", 5));
        iae("battery null key", () -> SysUiRules.batterySetCommand(null, 5));
        cmd("battery unplug", SysUiRules.batteryUnplugCommand(), "dumpsys battery unplug");
        cmd("battery reset", SysUiRules.batteryResetCommand(), "dumpsys battery reset");
        cmd("battery read", SysUiRules.batteryReadCommand(), "dumpsys battery");
        Map<String, Integer> b = SysUiRules.parseBatteryState(BATTERY_NORMAL);
        eq("battery parse normal", b.toString(), "{ac=0, usb=1, wireless=0, dock=0, status=2, present=1, level=87, temp=301}");
        eq("battery parse ignores Capacity level", b.get("level"), 87);
        is("battery normal is not frozen", !SysUiRules.isFrozen(BATTERY_NORMAL));
        Map<String, Integer> f = SysUiRules.parseBatteryState(BATTERY_FROZEN);
        eq("battery parse frozen", f.toString(), "{ac=1, usb=0, wireless=0, dock=0, status=3, present=0, level=15, temp=-20}");
        is("battery frozen is frozen", SysUiRules.isFrozen(BATTERY_FROZEN));
        is("battery frozen text alone", SysUiRules.isFrozen("  (UPDATES STOPPED -- use 'reset' to restart)"));
        is("battery frozen in lower case", SysUiRules.isFrozen("(updates stopped)"));
        is("battery frozen null", !SysUiRules.isFrozen(null));
        is("battery frozen empty", !SysUiRules.isFrozen(""));
        eq("battery parse garbage", SysUiRules.parseBatteryState("Can't find service: battery").size(), 0);
        eq("battery parse null", SysUiRules.parseBatteryState(null).size(), 0);
        eq("battery parse CRLF", SysUiRules.parseBatteryState("  AC powered: true\r\n  level: 50\r\n").toString(), "{ac=1, level=50}");
        eq("battery parse the first value wins", SysUiRules.parseBatteryState("  level: 10\n  level: 20\n").get("level"), 10);
        eq("battery parse a word where a number belongs", SysUiRules.parseBatteryState("  level: unknown\n  status: 3\n").toString(), "{status=3}");
    }

    // ---- navigation mode ----

    static void navigation() {
        cmd("nav list", SysUiRules.navListCommand(), "cmd overlay list --user 0 android");
        Map<String, String> m = SysUiRules.parseNavModes(OVERLAYS);
        eq("nav parse the three modes", m.toString(), "{gestural=on, threebutton=off, twobutton=off}");
        eq("nav enabled", SysUiRules.navEnabled(m), "gestural");
        Map<String, String> m2 = SysUiRules.parseNavModes("android\n[ ] com.android.internal.systemui.navbar.gestural\n[x] com.android.internal.systemui.navbar.threebutton\n");
        eq("nav parse two modes, the order of NAV_MODES", m2.toString(), "{gestural=off, threebutton=on}");
        eq("nav enabled three-button", SysUiRules.navEnabled(m2), "threebutton");
        eq("nav parse an unavailable overlay", SysUiRules.parseNavModes("android\n--- com.android.internal.systemui.navbar.twobutton\n").toString(), "{twobutton=unavailable}");
        eq("nav enabled when none is on", SysUiRules.navEnabled(SysUiRules.parseNavModes("android\n[ ] com.android.internal.systemui.navbar.gestural\n")), "");
        eq("nav parse a phone without them (Samsung)", SysUiRules.parseNavModes("android\n[x] com.samsung.internal.systemui.navbar.gestural\n[ ] com.android.internal.display.cutout.emulation.hole\n").size(), 0);
        eq("nav parse ignores a similar name", SysUiRules.parseNavModes("[x] com.android.internal.systemui.navbar.gestural.extra\n").size(), 0);
        eq("nav parse an error", SysUiRules.parseNavModes("cmd: Can't find service: overlay\n").size(), 0);
        eq("nav parse null", SysUiRules.parseNavModes(null).size(), 0);
        eq("nav parse CRLF", SysUiRules.parseNavModes("android\r\n[x] com.android.internal.systemui.navbar.twobutton\r\n").toString(), "{twobutton=on}");
        eq("nav parse an upper-case X", SysUiRules.parseNavModes("[X] com.android.internal.systemui.navbar.threebutton\n").toString(), "{threebutton=on}");
        eq("nav enabled null", SysUiRules.navEnabled(null), "");
        cmd("nav enable gestural", SysUiRules.navEnableCommand("gestural"), "cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.gestural");
        cmd("nav enable threebutton", SysUiRules.navEnableCommand("threebutton"), "cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.threebutton");
        cmd("nav enable twobutton", SysUiRules.navEnableCommand("twobutton"), "cmd overlay enable-exclusive --user 0 --category com.android.internal.systemui.navbar.twobutton");
        iae("nav enable unknown", () -> SysUiRules.navEnableCommand("fullscreen"));
        iae("nav enable a package name", () -> SysUiRules.navEnableCommand("com.android.internal.systemui.navbar.gestural"));
        iae("nav enable injection", () -> SysUiRules.navEnableCommand("gestural; reboot"));
        iae("nav enable null", () -> SysUiRules.navEnableCommand(null));
    }

    // ---- display mode and window behaviour ----

    static void displayAndWindow() {
        cmd("display mode get", SysUiRules.displayModeGetCommand(), "cmd display get-user-preferred-display-mode");
        cmd("display mode set", SysUiRules.displayModeSetCommand(1080, 2400, 60), "cmd display set-user-preferred-display-mode 1080 2400 60.0");
        cmd("display mode set 120", SysUiRules.displayModeSetCommand(1440, 3200, 120), "cmd display set-user-preferred-display-mode 1440 3200 120.0");
        cmd("display mode set 59.94", SysUiRules.displayModeSetCommand(1080, 2400, 59.94), "cmd display set-user-preferred-display-mode 1080 2400 59.94");
        cmd("display mode set 60.000004", SysUiRules.displayModeSetCommand(1080, 2400, 60.000004), "cmd display set-user-preferred-display-mode 1080 2400 60.0");
        cmd("display mode set 72.5", SysUiRules.displayModeSetCommand(720, 1600, 72.5), "cmd display set-user-preferred-display-mode 720 1600 72.5");
        cmd("display mode clear", SysUiRules.displayModeClearCommand(), "cmd display clear-user-preferred-display-mode");
        cmd("display modes read", SysUiRules.displayModesReadCommand(), "dumpsys display");
        iae("display mode width too small", () -> SysUiRules.displayModeSetCommand(199, 2400, 60));
        iae("display mode width too big", () -> SysUiRules.displayModeSetCommand(20001, 2400, 60));
        iae("display mode height too small", () -> SysUiRules.displayModeSetCommand(1080, 0, 60));
        iae("display mode height negative", () -> SysUiRules.displayModeSetCommand(1080, -2400, 60));
        iae("display mode refresh 0", () -> SysUiRules.displayModeSetCommand(1080, 2400, 0));
        iae("display mode refresh 481", () -> SysUiRules.displayModeSetCommand(1080, 2400, 481));
        iae("display mode refresh NaN", () -> SysUiRules.displayModeSetCommand(1080, 2400, Double.NaN));
        iae("display mode refresh infinite", () -> SysUiRules.displayModeSetCommand(1080, 2400, Double.POSITIVE_INFINITY));
        eq("display modes parsed", SysUiRules.parseDisplayModes(DISPLAYS), list("1080x2400@60.0", "1080x2400@90.0", "1080x2400@120.0", "720x1600@59.94"));
        eq("display modes of an older Android", SysUiRules.parseDisplayModes("DisplayModeRecord{mMode={id=1, width=1080, height=1920, fps=60.0, alternativeRefreshRates=[]}}\n"), list("1080x1920@60.0"));
        eq("display modes none", SysUiRules.parseDisplayModes("Display Manager State:\n"), list());
        eq("display modes null", SysUiRules.parseDisplayModes(null), list());

        cmd("wm ignore orientation on", SysUiRules.wmIgnoreOrientationCommand(true), "wm set-ignore-orientation-request true");
        cmd("wm ignore orientation off", SysUiRules.wmIgnoreOrientationCommand(false), "wm set-ignore-orientation-request false");
        cmd("wm ignore orientation read", SysUiRules.wmIgnoreOrientationReadCommand(), "wm get-ignore-orientation-request");
        cmd("wm scaling off", SysUiRules.wmScalingCommand("off"), "wm scaling off");
        cmd("wm scaling auto", SysUiRules.wmScalingCommand("auto"), "wm scaling auto");
        iae("wm scaling unknown", () -> SysUiRules.wmScalingCommand("on"));
        iae("wm scaling injection", () -> SysUiRules.wmScalingCommand("off; reboot"));
        iae("wm scaling null", () -> SysUiRules.wmScalingCommand(null));
        cmd("wm size", SysUiRules.wmSizeCommand(1080, 2400), "wm size 1080x2400");
        cmd("wm size smallest", SysUiRules.wmSizeCommand(100, 100), "wm size 100x100");
        cmd("wm size biggest", SysUiRules.wmSizeCommand(99999, 99999), "wm size 99999x99999");
        iae("wm size too small", () -> SysUiRules.wmSizeCommand(99, 1000));
        iae("wm size too big", () -> SysUiRules.wmSizeCommand(1000, 100000));
        iae("wm size zero", () -> SysUiRules.wmSizeCommand(0, 0));
        iae("wm size negative", () -> SysUiRules.wmSizeCommand(-1080, 2400));
        cmd("wm density", SysUiRules.wmDensityCommand(420), "wm density 420");
        cmd("wm density lowest", SysUiRules.wmDensityCommand(72), "wm density 72");
        cmd("wm density highest", SysUiRules.wmDensityCommand(1000), "wm density 1000");
        iae("wm density 71", () -> SysUiRules.wmDensityCommand(71));
        iae("wm density 1001", () -> SysUiRules.wmDensityCommand(1001));
        iae("wm density negative", () -> SysUiRules.wmDensityCommand(-420));
        cmd("wm size reset", SysUiRules.wmSizeResetCommand(), "wm size reset");
        cmd("wm density reset", SysUiRules.wmDensityResetCommand(), "wm density reset");
        cmd("wm reset both", SysUiRules.wmResetCommand(), "wm size reset; wm density reset");
        cmd("wm read", SysUiRules.wmReadCommand(), "wm size; wm density");
        eq("wm state with overrides", SysUiRules.parseWmState("Physical size: 1080x2400\nOverride size: 720x1600\nPhysical density: 420\nOverride density: 280\n").toString(),
                "{size=1080x2400, overrideSize=720x1600, density=420, overrideDensity=280}");
        eq("wm state without overrides", SysUiRules.parseWmState("Physical size: 1080x2400\nPhysical density: 440\r\n").toString(), "{size=1080x2400, density=440}");
        eq("wm state garbage", SysUiRules.parseWmState("Error: no window manager\n").size(), 0);
        eq("wm state a size where a density belongs", SysUiRules.parseWmState("Physical density: 1080x2400\nPhysical size: 420\n").size(), 0);
        eq("wm state null", SysUiRules.parseWmState(null).size(), 0);
    }

    // ---- info ----

    static void info() {
        cmd("info statusbar", SysUiRules.infoCommand("statusbar"), "dumpsys statusbar");
        cmd("info status-icons", SysUiRules.infoCommand("status-icons"), "cmd statusbar get-status-icons");
        cmd("info window-displays", SysUiRules.infoCommand("window-displays"), "dumpsys window displays");
        cmd("info display", SysUiRules.infoCommand("display"), "dumpsys display");
        cmd("info uimode", SysUiRules.infoCommand("uimode"), "dumpsys uimode");
        eq("info keys", SysUiRules.INFO_KEYS.length, 5);
        iae("info notification is not offered", () -> SysUiRules.infoCommand("notification"));
        iae("info a whole command is not a name", () -> SysUiRules.infoCommand("dumpsys notification --noredact"));
        iae("info injection", () -> SysUiRules.infoCommand("display; reboot"));
        iae("info null", () -> SysUiRules.infoCommand(null));
        iae("info empty", () -> SysUiRules.infoCommand(""));
        iae("info upper case", () -> SysUiRules.infoCommand("Display"));
        for (String k : SysUiRules.INFO_KEYS) is("info " + k + " is not the notification dump", !SysUiRules.infoCommand(k).contains("notification") && !SysUiRules.infoCommand(k).contains("--noredact"));
        String w = SysUiRules.notificationDumpWarning();
        is("the notification dump warning says what the dump holds", w.contains("dumpsys notification") && w.contains("private") && w.length() > 60);
    }

    // ---- quoting, across every free text and a set of hostile strings ----

    static void quotingEverywhere() {
        String[] hostile = {"'; rm -rf /; '", "a' && reboot && echo '", "$(reboot)", "`reboot`", "\"; reboot; \"", "\\'", "''''", "a\\", "* ? [x] {a,b} ~ #",
            "x | y > z < w & v", "!!", "-rf", "--help", "é中😀"};
        for (String h : hostile) {
            String label = h.length() > 12 ? h.substring(0, 12) : h;
            eq("hostile title [" + label + "] is one argument", argv(SysUiRules.notifyPostCommand("basic", h, "x", "t", null, null, null)).get(4), h);
            eq("hostile text [" + label + "] is one argument", argv(SysUiRules.notifyPostCommand("bigtext", null, h, "t", null, null, null)).get(argv(SysUiRules.notifyPostCommand("bigtext", null, h, "t", null, null, null)).size() - 1), h);
            List<String> a = argv(SysUiRules.notifyPostCommand("inbox", null, "x", h, list(h), null, null));
            eq("hostile tag and inbox line [" + label + "] are single arguments", a.get(6) + "|" + a.get(a.size() - 2), h + "|" + h);
            eq("hostile ssid [" + label + "] is one argument", argv(SysUiRules.demoApply(map("wifi.show", "show", "wifi.ssid", h.length() <= 32 ? h : h.substring(0, 32)))).get(12), h.length() <= 32 ? h : h.substring(0, 32));
            built.add(SysUiRules.notifyPostCommand("messaging", h, "x", "t", null, list("Ann:" + h), h));
        }
    }

    // ---- a real sh -n over everything that was built ----

    static void syntaxPass() throws Exception {
        List<String> shells = new ArrayList<String>();
        for (String sh : new String[] {"sh", "mksh"}) if (shellAvailable(sh)) shells.add(sh);
        is("a real sh is available for the syntax check", shells.contains("sh"));
        // the checker itself: it must see a broken line (an unclosed quote) and accept a good one
        for (String sh : shells) {
            eq("sh -n [" + sh + "] refuses an unclosed quote", runSh(sh, "cmd notification post 'oops", true) != 0, true);
            eq("sh -n [" + sh + "] accepts a good line", runSh(sh, "cmd notification post 'ok'"), 0);
        }
        List<String> done = new ArrayList<String>();
        for (String c : built) {
            if (c.isEmpty() || done.contains(c)) continue;
            done.add(c);
        }
        String label = shells.toString();
        for (String c : done) {
            boolean good = true;
            for (String sh : shells) if (runSh(sh, c) != 0) good = false;
            String shown = c.replace('\n', ' ');
            if (good) ok("parses in " + label + ": " + (shown.length() > 70 ? shown.substring(0, 70) + "..." : shown));
            else fail("parses in " + label, shown);
        }
        is("the syntax pass covered a good number of commands (" + done.size() + ")", done.size() > 150);
    }
}
