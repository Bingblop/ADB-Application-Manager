package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extra Settings > Power &amp; reboot, Doze &amp; power governor, Zram &amp; memory: the parsers against real-looking
 * {@code dumpsys deviceidle}, {@code mm_stat}, {@code /proc/meminfo} and {@code cmd power} output (the fixtures the UI script
 * t251 uses too), and a scan of the page: every command of the three sections is a row of its table, runs only from the confirm
 * sheet (xqSheetGo), and the ones that need Root are marked so.
 */
public class PowerTweaksTest {
  static int n = 0, fails = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void eq(String what, Object got, Object want) { n++; if (got == null ? want != null : !got.equals(want)) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }

  static File repo() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return d;
    return null;
  }
  static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
  static String fixture(String name) throws Exception {
    String dir = System.getProperty("fixtures");
    File f = dir != null ? new File(dir, "powertweaks/" + name) : null;
    if (f == null || !f.isFile()) { File r = repo(); f = r == null ? null : new File(r, "tests/java/fixtures/powertweaks/" + name); }
    if (f == null || !f.isFile()) throw new Exception("fixture not found: " + name);
    return read(f);
  }

  public static void main(String[] args) throws Exception {
    // ---- the state token of `get deep` / `get light` ----
    eq("get deep prints the bare token", PowerTweaks.stateToken("ACTIVE\n", false), "ACTIVE");
    eq("with spaces and CRLF", PowerTweaks.stateToken("  IDLE_PENDING \r\n", false), "IDLE_PENDING");
    eq("every deep token", PowerTweaks.stateToken("QUICK_DOZE_DELAY", false), "QUICK_DOZE_DELAY");
    eq("a light token is not a deep one", PowerTweaks.stateToken("PRE_IDLE", false), null);
    eq("a light token", PowerTweaks.stateToken("WAITING_FOR_NETWORK\n", true), "WAITING_FOR_NETWORK");
    eq("UNKNOWN is no state", PowerTweaks.stateToken("UNKNOWN", false), null);
    eq("an error line is no state", PowerTweaks.stateToken("Unknown command: get\n", false), null);
    eq("lower case is no state", PowerTweaks.stateToken("active", false), null);
    eq("null", PowerTweaks.stateToken(null, false), null);
    eq("the `step` answer", PowerTweaks.stateToken("Stepped to deep: IDLE_PENDING\n", false), "IDLE_PENDING");
    eq("the `step light` answer, asked for deep", PowerTweaks.stateToken("Stepped to light: IDLE\n", false), null);
    eq("the `unforce` answer, deep", PowerTweaks.stateToken("Light state: ACTIVE, Deep state: ACTIVE\n", false), "ACTIVE");
    eq("the `unforce` answer, light", PowerTweaks.stateToken("Light state: OVERRIDE, Deep state: IDLE\n", true), "OVERRIDE");
    eq("the forced answer carries no token", PowerTweaks.stateToken("Now forced in to deep idle mode\n", false), null);
    eq("the first token wins", PowerTweaks.stateToken("IDLE\nACTIVE\n", false), "IDLE");

    // ---- the timeline ----
    eq("Active", PowerTweaks.deepPhase("ACTIVE"), 0); eq("Inactive", PowerTweaks.deepPhase("INACTIVE"), 1); eq("quick doze delay sits with Inactive", PowerTweaks.deepPhase("QUICK_DOZE_DELAY"), 1);
    eq("Idle pending", PowerTweaks.deepPhase("IDLE_PENDING"), 2); eq("Sensing", PowerTweaks.deepPhase("SENSING"), 2); eq("Locating", PowerTweaks.deepPhase("LOCATING"), 2);
    eq("Idle", PowerTweaks.deepPhase("IDLE"), 3); eq("Maintenance", PowerTweaks.deepPhase("IDLE_MAINTENANCE"), 4); eq("unknown", PowerTweaks.deepPhase("OVERRIDE"), -1); eq("null", PowerTweaks.deepPhase(null), -1);
    eq("get force true", PowerTweaks.bool("true\n"), Boolean.TRUE); eq("get force false", PowerTweaks.bool("False"), Boolean.FALSE); eq("get force junk", PowerTweaks.bool("Unknown command"), null); eq("get force empty", PowerTweaks.bool(""), null);

    // ---- the full report, three phones ----
    PowerTweaks.DozeDump a = PowerTweaks.parseDump(fixture("deviceidle-active.txt"));
    eq("active phone: state", a.state, "ACTIVE"); eq("active phone: light", a.lightState, "ACTIVE"); eq("active phone: not forced", a.forced, Boolean.FALSE);
    eq("active phone: uses the motion sensor", a.useMotion, Boolean.TRUE); eq("active phone: not listening", a.listening, Boolean.FALSE); eq("active phone: the sensor's name", a.sensor, "LSM6DSO Accelerometer -Wakeup");
    PowerTweaks.DozeDump f = PowerTweaks.parseDump(fixture("deviceidle-forced-idle.txt"));
    eq("forced phone: state", f.state, "IDLE"); eq("forced phone: light", f.lightState, "OVERRIDE"); eq("forced phone: forced", f.forced, Boolean.TRUE);
    eq("forced phone: listening", f.listening, Boolean.TRUE); eq("forced phone: sensor", f.sensor, "BMI260 Accelerometer Wakeup");
    PowerTweaks.DozeDump o = PowerTweaks.parseDump(fixture("deviceidle-old-pending.txt"));
    eq("old phone: state", o.state, "IDLE_PENDING"); eq("old phone: light", o.lightState, "IDLE"); eq("old phone: not forced", o.forced, Boolean.FALSE);
    eq("old phone: no mUseMotionSensor line means unknown", o.useMotion, null); eq("old phone: no listener line means unknown", o.listening, null); eq("old phone: mMotionSensor=null reads as no sensor", o.sensor, "");
    PowerTweaks.DozeDump e = PowerTweaks.parseDump("");
    is("an empty report: everything unknown", e.state == null && e.lightState == null && e.forced == null && e.useMotion == null && e.listening == null && e.sensor == null);
    PowerTweaks.DozeDump g = PowerTweaks.parseDump("  mState=BOGUS mLightState=ACTIVE\n  mSomeState=IDLE\n");
    is("a token that is not a state is dropped; the light one on the same line is kept; mSomeState is not mState", g.state == null && "ACTIVE".equals(g.lightState));
    eq("null report", PowerTweaks.parseDump(null).state, null);

    // ---- mm_stat ----
    PowerTweaks.MmStat m = PowerTweaks.parseMmStat(fixture("mm_stat.txt"));
    is("a 9-field mm_stat", m.ok); eq("orig", m.orig, 1587396608L); eq("compr", m.compr, 523767808L); eq("memUsed", m.memUsed, 561664000L); eq("memLimit", m.memLimit, 0L); eq("memUsedMax", m.memUsedMax, 889876480L); eq("samePages", m.samePages, 8402L); eq("compacted", m.compacted, 15110L); eq("hugePages", m.hugePages, 236L);
    is("ratio ~3.03", Math.abs(PowerTweaks.ratio(m) - 3.0307) < 0.001); eq("ratio text", PowerTweaks.ratioText(PowerTweaks.ratio(m)), "3.0×");
    eq("saved = orig - memUsed", PowerTweaks.savedBytes(m), 1587396608L - 561664000L);
    double[] bar = PowerTweaks.bar(m, 6442450944L);
    is("the bar: compressed + saved + free = 1, in the disk size", Math.abs(bar[0] + bar[1] + bar[2] - 1) < 1e-9 && Math.abs(bar[0] - 561664000.0 / 6442450944.0) < 1e-9 && bar[2] > 0.7);
    PowerTweaks.MmStat m7 = PowerTweaks.parseMmStat(fixture("mm_stat-old.txt"));
    is("a 7-field mm_stat (older kernel, leading spaces)", m7.ok && m7.orig == 2147483648L && m7.compr == 838860800L && m7.memUsed == 867123200L && m7.hugePages == 0);
    eq("ratio text of a 2.56", PowerTweaks.ratioText(PowerTweaks.ratio(m7)), "2.6×");
    eq("ratio text at 12.4 is whole", PowerTweaks.ratioText(12.4), "12×");
    PowerTweaks.MmStat z = PowerTweaks.parseMmStat("0 0 0 0 0 0 0 0 0\n");
    is("all zeros: ok, nothing to divide", z.ok && PowerTweaks.ratio(z) < 0 && PowerTweaks.savedBytes(z) == 0 && "—".equals(PowerTweaks.ratioText(PowerTweaks.ratio(z))));
    PowerTweaks.MmStat bad = PowerTweaks.parseMmStat("cat: /sys/block/zram0/mm_stat: No such file or directory\n");
    is("cat's error is not a counter line and is kept as text", !bad.ok && bad.text.startsWith("cat: /sys/block/zram0/mm_stat"));
    is("permission denied", !PowerTweaks.parseMmStat("cat: /sys/block/zram0/mm_stat: Permission denied").ok);
    is("two numbers are not enough", !PowerTweaks.parseMmStat("12 34").ok);
    is("empty and null", !PowerTweaks.parseMmStat("").ok && !PowerTweaks.parseMmStat(null).ok);
    is("a bar from a bad reading is empty", PowerTweaks.bar(bad, 1).length == 3 && PowerTweaks.bar(bad, 1)[0] == 0);
    eq("disksize", PowerTweaks.parseDisksize(fixture("disksize.txt")), 6442450944L);
    eq("disksize junk", PowerTweaks.parseDisksize("cat: /sys/block/zram0/disksize: No such file or directory"), -1L);
    is("the bar with an unknown disk size uses orig", Math.abs(PowerTweaks.bar(m, -1)[2]) < 1e-9);

    // ---- /proc/meminfo through MemStats (the existing parser) ----
    MemStats.Reading mi = MemStats.parse(fixture("meminfo.txt"));
    eq("SwapTotal", mi.swapTotalKb, 6291452L); eq("SwapFree", mi.swapFreeKb, 4741296L); eq("MemTotal", mi.totalKb, 7818100L);

    // ---- cmd power ----
    String h11 = fixture("cmd-power-help-11.txt"), h10 = fixture("cmd-power-help-10.txt"), hNone = fixture("cmd-power-none.txt");
    eq("Android 11 help knows fixed performance", PowerTweaks.cmdPowerSupports(h11, "set-fixed-performance-mode-enabled"), Boolean.TRUE);
    eq("and adaptive", PowerTweaks.cmdPowerSupports(h11, "set-adaptive-power-saver-enabled"), Boolean.TRUE);
    eq("Android 10 help does not know fixed performance", PowerTweaks.cmdPowerSupports(h10, "set-fixed-performance-mode-enabled"), Boolean.FALSE);
    eq("no power service: unknown", PowerTweaks.cmdPowerSupports(hNone, "set-fixed-performance-mode-enabled"), null);
    eq("empty: unknown", PowerTweaks.cmdPowerSupports("", "set-fixed-performance-mode-enabled"), null);
    eq("null: unknown", PowerTweaks.cmdPowerSupports(null, "x"), null);
    eq("dumpsys power says nothing about fixed performance: null, never false", PowerTweaks.parseFixedPerformance("POWER MANAGER (dumpsys power)\nPower Manager State:\n  mDirty=0x0\n  mWakefulness=Awake\n"), null);
    eq("a phone that prints it on", PowerTweaks.parseFixedPerformance("  mFixedPerformanceModeEnabled=true\n"), Boolean.TRUE);
    eq("a phone that prints it off", PowerTweaks.parseFixedPerformance("  Fixed performance mode: off\n"), Boolean.FALSE);
    eq("profile from low_power 1", PowerTweaks.profileOf("1\n", null), "saver");
    eq("profile from low_power 0", PowerTweaks.profileOf("0", null), "balanced");
    eq("profile from low_power null (never set)", PowerTweaks.profileOf("null", null), "balanced");
    eq("profile from fixed on", PowerTweaks.profileOf("0", Boolean.TRUE), "performance");
    eq("saver wins over a stale fixed flag", PowerTweaks.profileOf("1", Boolean.TRUE), "saver");
    eq("an odd low_power value marks nothing", PowerTweaks.profileOf("2", null), null);

    // ---- v7.20: durations, the Settings block, the state lines ----
    eq("+4m0s0ms", PowerTweaks.parseDuration("+4m0s0ms"), 240000L); eq("+1h0m0s0ms", PowerTweaks.parseDuration("+1h0m0s0ms"), 3600000L); eq("+30s0ms", PowerTweaks.parseDuration("+30s0ms"), 30000L);
    eq("+500ms", PowerTweaks.parseDuration("+500ms"), 500L); eq("+1d2h37m40s0ms", PowerTweaks.parseDuration("+1d2h37m40s0ms"), 86400000L + 2 * 3600000L + 37 * 60000L + 40000L); eq("-6m12s500ms (an alarm in the past)", PowerTweaks.parseDuration("-6m12s500ms"), -372500L);
    eq("+7m30s0ms", PowerTweaks.parseDuration("+7m30s0ms"), 450000L); eq("bare milliseconds (device_config)", PowerTweaks.parseDuration("120000"), 120000L); eq("a factor is no duration", PowerTweaks.parseDuration("2.0"), -1L);
    eq("an accuracy is no duration", PowerTweaks.parseDuration("20.0m"), -1L); eq("true is no duration", PowerTweaks.parseDuration("true"), -1L); eq("empty", PowerTweaks.parseDuration(""), -1L); eq("null", PowerTweaks.parseDuration(null), -1L); eq("a sign alone", PowerTweaks.parseDuration("+"), -1L);
    java.util.Map<String, String> ca = PowerTweaks.parseConstants(fixture("deviceidle-active.txt"));
    eq("the Settings block: 34 constants on the Pixel-like report", ca.size(), 34); eq("light_after_inactive_to as printed", ca.get("light_after_inactive_to"), "+3m0s0ms"); eq("as ms", PowerTweaks.constantMs(ca, "light_after_inactive_to"), 180000L);
    eq("inactive_to", PowerTweaks.constantMs(ca, "inactive_to"), 1800000L); eq("idle_to", PowerTweaks.constantMs(ca, "idle_to"), 3600000L); eq("max_idle_to", PowerTweaks.constantMs(ca, "max_idle_to"), 21600000L); eq("light_idle_factor is kept as text", ca.get("light_idle_factor"), "2.0");
    eq("a factor asked as ms is -1", PowerTweaks.constantMs(ca, "light_idle_factor"), -1L); eq("a key the phone does not print is -1", PowerTweaks.constantMs(ca, "nonsense_to"), -1L); eq("null map", PowerTweaks.constantMs(null, "idle_to"), -1L);
    is("the whitelist after the block is not read as constants", !ca.containsKey("com.whatsapp") && !ca.containsValue("com.whatsapp"));
    java.util.Map<String, String> co = PowerTweaks.parseConstants(fixture("deviceidle-old-pending.txt"));
    eq("the older report: 20 constants", co.size(), 20); eq("old light_after_inactive_to", PowerTweaks.constantMs(co, "light_after_inactive_to"), 300000L);
    java.util.Map<String, String> ct = PowerTweaks.parseConstants(fixture("deviceidle-s-tuned.txt"));
    eq("the tuned phone: light 2 min", PowerTweaks.constantMs(ct, "light_after_inactive_to"), 120000L); eq("inactive 7.5 min", PowerTweaks.constantMs(ct, "inactive_to"), 450000L); eq("idle_to 2 h", PowerTweaks.constantMs(ct, "idle_to"), 7200000L); eq("max_idle_to 12 h", PowerTweaks.constantMs(ct, "max_idle_to"), 43200000L);
    is("a report without the block gives nothing", PowerTweaks.parseConstants("  mState=ACTIVE\n").isEmpty() && PowerTweaks.parseConstants(null).isEmpty() && PowerTweaks.parseConstants("").isEmpty());
    PowerTweaks.DozeNow na = PowerTweaks.parseNow(fixture("deviceidle-active.txt"));
    is("active phone now: screen on, not charging, network, both Doze kinds enabled, no alarm line", Boolean.TRUE.equals(na.screenOn) && Boolean.FALSE.equals(na.charging) && Boolean.TRUE.equals(na.network) && Boolean.TRUE.equals(na.lightEnabled) && Boolean.TRUE.equals(na.deepEnabled) && na.nextAlarmMs == -1 && na.quickDoze == null && na.inactiveTimeoutMs == 1800000L);
    PowerTweaks.DozeNow nt = PowerTweaks.parseNow(fixture("deviceidle-s-tuned.txt"));
    is("tuned phone now: screen off, next step in 6 min 12.5 s, light alarm in 3 min 2 s, next idle period 2 h, quick doze off", Boolean.FALSE.equals(nt.screenOn) && nt.nextAlarmMs == 372500L && nt.nextLightAlarmMs == 182000L && nt.nextIdleDelayMs == 7200000L && nt.nextIdlePendingMs == 300000L && Boolean.FALSE.equals(nt.quickDoze) && nt.inactiveTimeoutMs == 450000L);
    PowerTweaks.DozeNow no = PowerTweaks.parseNow(fixture("deviceidle-old-pending.txt"));
    is("old phone now: screen off, next alarm 4 min 12 s", Boolean.FALSE.equals(no.screenOn) && no.nextAlarmMs == 252000L);
    is("an empty report: nothing known", PowerTweaks.parseNow("").screenOn == null && PowerTweaks.parseNow(null).nextAlarmMs == -1);
    java.util.Map<String, String> dc = PowerTweaks.parseDeviceConfigList(fixture("device_config-device_idle.txt"));
    is("device_config list: three keys, bare ms", dc.size() == 3 && "120000".equals(dc.get("light_after_inactive_to")) && "450000".equals(dc.get("inactive_to")));
    is("an empty list, an error and junk give nothing", PowerTweaks.parseDeviceConfigList("").isEmpty() && PowerTweaks.parseDeviceConfigList("Error: Unknown command: list\n").isEmpty() && PowerTweaks.parseDeviceConfigList("/system/bin/sh: device_config: inaccessible or not found\n").isEmpty() && PowerTweaks.parseDeviceConfigList(null).isEmpty());
    long[] sp = PowerTweaks.deepSplit(15 * 60000L);
    is("15 minutes to deep Doze: 7.5 + 7.5 min, motion wait 7.5 min", sp[0] == 450000L && sp[1] == 450000L && sp[2] == 450000L);
    long[] sp60 = PowerTweaks.deepSplit(60 * 60000L);
    is("60 minutes: 30 + 30, motion wait stays at its 10-minute default", sp60[0] == 1800000L && sp60[1] == 1800000L && sp60[2] == 600000L);
    long[] sp1 = PowerTweaks.deepSplit(10000L);
    is("under two minutes is lifted to two: 1 + 1 min", sp1[0] == 60000L && sp1[1] == 60000L && sp1[2] == 60000L);
    long[] sp5 = PowerTweaks.deepSplit(5 * 60000L);
    is("5 minutes: 2.5 + 2.5", sp5[0] == 150000L && sp5[1] == 150000L && sp5[0] + sp5[1] == 300000L);

    // ---- the CPU clusters, the battery, the saver, swappiness, pressure, the zram sizes ----
    java.util.List<PowerTweaks.CpuPolicy> cps = PowerTweaks.parseCpuPolicies(fixture("cpufreq-policies.txt"));
    eq("three policies", cps.size(), 3); eq("policy0 name", cps.get(0).name, "policy0"); eq("policy0 governor", cps.get(0).governor, "schedutil"); eq("policy0 cur kHz", cps.get(0).curKhz, 1804800L); eq("policy0 min", cps.get(0).minKhz, 400000L); eq("policy0 cpus", cps.get(0).cpus, "0-3");
    eq("policy7 governor", cps.get(2).governor, "performance"); eq("policy7 max", cps.get(2).maxKhz, 3187200L); eq("four available governors on policy0", cps.get(0).available.length, 4);
    eq("the clusters differ: no common governor", PowerTweaks.commonGovernor(cps), null);
    eq("the union of the governors offered", String.join(",", PowerTweaks.availableGovernors(cps)), "conservative,powersave,performance,schedutil");
    java.util.List<PowerTweaks.CpuPolicy> same = PowerTweaks.parseCpuPolicies("policy0|performance|1|1|1|0-3|performance schedutil\npolicy4|performance|2|2|2|4-7|performance schedutil\n");
    eq("every cluster on performance: the common governor", PowerTweaks.commonGovernor(same), "performance");
    java.util.List<PowerTweaks.CpuPolicy> den = PowerTweaks.parseCpuPolicies(fixture("cpufreq-denied.txt"));
    is("a shell that may not read the folder: the ls error is skipped and the unexpanded glob line is no policy", den.isEmpty());
    is("an unreadable governor on one cluster: no common governor", PowerTweaks.commonGovernor(PowerTweaks.parseCpuPolicies("policy0||||||\npolicy4|schedutil|1|1|1|4|schedutil")) == null);
    is("empty and null", PowerTweaks.parseCpuPolicies("").isEmpty() && PowerTweaks.parseCpuPolicies(null).isEmpty() && PowerTweaks.commonGovernor(null) == null && PowerTweaks.availableGovernors(null).isEmpty());
    PowerTweaks.Battery bu = PowerTweaks.parseBattery(fixture("dumpsys-battery-usb.txt"));
    is("USB powered, 87 %, status 2 (charging)", bu.read && bu.plugged() && bu.usb && !bu.ac && !bu.wireless && bu.level == 87 && bu.status == 2);
    PowerTweaks.Battery bn = PowerTweaks.parseBattery(fixture("dumpsys-battery-unplugged.txt"));
    is("unplugged, 54 %, status 3 (discharging)", bn.read && !bn.plugged() && bn.level == 54 && bn.status == 3);
    is("no battery service: nothing read", !PowerTweaks.parseBattery("Can't find service: battery\n").read && !PowerTweaks.parseBattery("").read && !PowerTweaks.parseBattery(null).read);
    PowerTweaks.Saver sv = PowerTweaks.parseSaver(fixture("dumpsys-power-saver.txt"));
    is("the saver block: not in force, powered, the setting off, automatic 0", Boolean.FALSE.equals(sv.enabled) && Boolean.TRUE.equals(sv.powered) && Boolean.FALSE.equals(sv.setting) && Integer.valueOf(0).equals(sv.automatic));
    PowerTweaks.Saver sn = PowerTweaks.parseSaver("POWER MANAGER (dumpsys power)\n  mWakefulness=Awake\n");
    is("a dumpsys power without the saver block: nothing known (never false)", sn.enabled == null && sn.powered == null && sn.setting == null && sn.automatic == null);
    is("  full enabled= is not Enabled=", PowerTweaks.parseSaver("  Enabled=true\n    full enabled=false\n").enabled == Boolean.TRUE);
    eq("swappiness 100", PowerTweaks.parseSwappiness("100\n"), 100); eq("swappiness 60", PowerTweaks.parseSwappiness(" 60 "), 60); eq("swappiness 200", PowerTweaks.parseSwappiness("200"), 200); eq("201 is out of range", PowerTweaks.parseSwappiness("201"), -1);
    eq("swappiness denied", PowerTweaks.parseSwappiness("cat: /proc/sys/vm/swappiness: Permission denied"), -1); eq("swappiness empty", PowerTweaks.parseSwappiness(""), -1); eq("swappiness null", PowerTweaks.parseSwappiness(null), -1);
    double[] ps = PowerTweaks.parsePressure(fixture("pressure-memory.txt"));
    is("memory pressure: some 0.00, full 0.00", ps != null && ps[0] == 0 && ps[1] == 0);
    double[] ps2 = PowerTweaks.parsePressure("some avg10=12.50 avg60=3.00 avg300=1.00 total=1\nfull avg10=4.25 avg60=1.00 avg300=0.50 total=1\n");
    is("some 12.5, full 4.25", ps2 != null && ps2[0] == 12.5 && ps2[1] == 4.25);
    is("no PSI file: null", PowerTweaks.parsePressure("cat: /proc/pressure/memory: No such file or directory") == null && PowerTweaks.parsePressure("") == null && PowerTweaks.parsePressure(null) == null);
    eq("zram sizes for 7.5 GB of RAM: up to 8 GB (1.5x)", java.util.Arrays.toString(PowerTweaks.zramSizeChoicesGb(7818100L)), "[1, 2, 3, 4, 6, 8]");
    eq("zram sizes for 12 GB: up to 16", java.util.Arrays.toString(PowerTweaks.zramSizeChoicesGb(12L * 1048576L)), "[1, 2, 3, 4, 6, 8, 12, 16]");
    eq("zram sizes for 2 GB: 1, 2, 3", java.util.Arrays.toString(PowerTweaks.zramSizeChoicesGb(2L * 1048576L)), "[1, 2, 3]");
    eq("zram sizes with no RAM figure: at least 1", java.util.Arrays.toString(PowerTweaks.zramSizeChoicesGb(0)), "[1]");
    is("/proc/swaps names zram0 as swap", PowerTweaks.zramSwapOn(fixture("proc-swaps.txt")) && !PowerTweaks.zramSwapOn("Filename\tType\tSize\tUsed\tPriority\n") && !PowerTweaks.zramSwapOn(null));
    eq("profile: every cluster on performance reads as Performance even with no fixed line", PowerTweaks.profileOf("0", null, "performance"), "performance");
    eq("profile: the saver wins over the governor", PowerTweaks.profileOf("1", null, "performance"), "saver");
    eq("profile: schedutil everywhere is Balanced", PowerTweaks.profileOf("0", null, "schedutil"), "balanced");
    eq("profile: no common governor, no fixed line: Balanced", PowerTweaks.profileOf("null", null, null), "balanced");

    // ---- the page: commands only from the tables, run only from the confirm sheet, Root-only rows marked ----
    File repo = repo();
    if (repo == null) System.out.println("SKIP the page was not found (assets/index.html)");
    else scanPage(read(new File(repo, "assets/index.html")));

    System.out.println(fails == 0 ? "PASS PowerTweaksTest: " + n + " checks" : "FAILED PowerTweaksTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }

  static String between(String s, String from, String to) {
    int a = s.indexOf(from);
    int b = a < 0 ? -1 : s.indexOf(to, a);
    return a >= 0 && b > a ? s.substring(a, b) : "";
  }

  static void scanPage(String page) {
    String block = between(page, "// ---- Extra Settings: Power & reboot (xpw)", "// ---- end of Power & reboot, Doze & power governor, Zram & memory ----");
    is("the block of the three sections was found", !block.isEmpty());
    if (block.isEmpty()) return;
    String rest = page.replace(block, "");
    // 1. every action command lives in one of the three tables
    String[] tables = { between(block, "const XPW_ACTS = {", "};"), between(block, "const XDZ_ACTS = {", "};"), between(block, "const XZR_ACTS = {", "};") };
    is("the three tables were found", !tables[0].isEmpty() && !tables[1].isEmpty() && !tables[2].isEmpty());
    String[] cmds = { "reboot", "setprop ctl.restart zygote", "reboot recovery", "reboot -p", "reboot bootloader", "am crash com.android.systemui",
        "dumpsys deviceidle force-idle deep", "dumpsys deviceidle step deep", "dumpsys deviceidle unforce", "dumpsys deviceidle motion", "echo 3 > /proc/sys/vm/drop_caches", "echo 1 > /proc/sys/vm/drop_caches",
        "device_config put device_idle {key} {ms}", "device_config delete device_idle {key}", "settings put global device_idle_constants {kv}", "settings delete global device_idle_constants",
        "echo {gov} > $p/scaling_governor", "swapoff /dev/block/zram0; echo 1 > /sys/block/zram0/reset; echo {bytes} > /sys/block/zram0/disksize; mkswap /dev/block/zram0; swapon /dev/block/zram0",
        "echo {n} > /proc/sys/vm/swappiness", "device_config put lmkd_native {key} {val}", "device_config delete lmkd_native {key}", "setprop lmkd.reinit 1" };
    for (String c : cmds) {
      boolean inTable = false;
      for (String t : tables) if (t.contains("'" + c) || t.contains(c + "'") || t.contains(c + " ") || t.contains(c + ";")) inTable = true;
      is("the command is a row of a table: " + c, inTable);
    }
    // a row with {name} holes declares the pattern of every hole, and the holes are filled by xqRowCmd only (a value that fails its pattern gives no command at all)
    Matcher holes = Pattern.compile("cmd: '([^']*\\{[a-z]+\\}[^']*)'([^\n]*)").matcher(String.join("\n", tables));
    int holeRows = 0;
    while (holes.find()) {
      holeRows++;
      Matcher h = Pattern.compile("\\{([a-z]+)\\}").matcher(holes.group(1));
      while (h.find()) is("the hole has a pattern in the row: {" + h.group(1) + "} of " + holes.group(1), holes.group(2).contains(h.group(1) + ": /"));
    }
    is("the rows with holes were found (Doze constants, governor, zram size, swappiness, lmkd)", holeRows >= 8);
    is("xqRowCmd fills only the row's own holes and checks every value against the row's pattern", block.contains("function xqRowCmd(a, vars)") && block.contains("if (!re || v == null || !re.test(String(v))) { bad = true; return m; }") && block.contains("return bad || /\\{[a-z]+\\}/.test(cmd) ? null : cmd;"));
    is("xqRun refuses a command that did not pass (null)", block.contains("if (cmd == null) { done({ ok: false, output: '', error: txT('It did not work.') }); return; }"));
    // the block's own code never hands a command literal of its own to xqRun: every xqRun of an action takes a.cmd / st.cmd / a row through xqRowCmd (reads are dumpsys get / cat / pidof / help only)
    Matcher runs = Pattern.compile("xqRun\\(([^,)]+)").matcher(block);
    int actionRuns = 0;
    while (runs.find()) {
      if (block.substring(Math.max(0, runs.start() - 9), runs.start()).equals("function ")) continue;        // the definition itself
      String arg = runs.group(1).trim();
      boolean fromTable = arg.equals("a.cmd") || arg.equals("st.cmd") || arg.startsWith("xqRowCmd(");
      boolean readOnly = arg.equals("XDZ_READ_CMD") || arg.equals("XZR_READ_CMD") || arg.equals("XDZ_POWER_CMD") || arg.startsWith("'pidof ") || arg.startsWith("'cmd power help") || (arg.startsWith("'settings ") && arg.contains("op === 'put'")) || arg.startsWith("op === 'put'");
      if (fromTable) actionRuns++;
      is("xqRun runs a table row or a read, never a literal action: " + arg, fromTable || readOnly);
    }
    is("the rows are run (three sections, the settings rows, the governor)", actionRuns >= 6);
    // the read commands change nothing
    String reads = between(block, "const XDZ_READ_CMD = ", ";\n") + between(block, "const XZR_READ_CMD = ", ";\n");
    is("the read commands are dumpsys get / cat / getprop / device_config list / settings get only", !reads.isEmpty() && !reads.contains("force-idle") && !reads.contains("unforce") && !reads.contains(" step") && !reads.contains("settings put") && !reads.contains("device_config put") && !reads.contains("reboot") && !reads.contains("drop_caches") && !reads.contains(" > ") && !reads.contains("echo 1") && !reads.contains("echo 3"));
    String power = between(block, "const XDZ_POWER_CMD = ", ";\n");
    is("the power read is dumpsys / cat / settings get only (no redirection, no set)", !power.isEmpty() && !power.contains(" > ") && !power.contains("settings put") && !power.contains("set-") && !power.contains("reboot") && power.contains("scaling_governor 2>/dev/null"));
    // 2. the perform functions (the only readers of the tables' cmd) are called from xqSheetGo's spec.run only
    for (String fn : new String[] { "xpwPerform", "xdzPerform", "xdzProfPerform", "xzrPerform", "xdzTunePerform", "xdzGovPerform", "xzrSetPerform" }) {
      Matcher calls = Pattern.compile("\\b" + fn + "\\(").matcher(block);
      int defs = 0, inRun = 0, other = 0;
      while (calls.find()) {
        int at = calls.start();
        String before = block.substring(Math.max(0, at - 60), at);
        if (before.endsWith("async function ")) defs++;
        else if (before.contains("run: say => ")) inRun++;
        else other++;
      }
      is(fn + " is defined once, called only as the sheet's run (" + inRun + " sheets), never directly", defs == 1 && inRun >= 1 && other == 0);
    }
    is("the sheet's run is called from xqSheetGo only", count(block, "spec.run(say)") == 1 && between(block, "async function xqSheetGo()", "\n        }\n").contains("spec.run(say)"));
    is("xqSheetGo is reached by the Confirm button and by a completed hold only", count(block, "xqSheetGo()") == 3 && block.contains("onclick=\"xqSheetGo()\"") && block.contains("hold.classList.add('is-done'); haptic(); xqSheetGo();"));
    is("a short tap on the hold button is swallowed", block.contains("hold.addEventListener('click', e => e.preventDefault())"));
    is("the hold needs the whole time", block.contains("if (p >= 1) {") && block.contains("const XQ_HOLD_MS = 1200"));
    is("xqSheetGo checks the mode again before it runs", between(block, "async function xqSheetGo()", "spec.run(say)").contains("if (!xqCanRun()) { xqSheetClose(); guardPrivilege(spec.title); return; }"));
    is("the three dangerous tiles need a hold, the others a tap", tables[0].contains("recovery:   { cmd: 'reboot recovery', hue: 38, hold: true") && tables[0].contains("poweroff:   { cmd: 'reboot -p || svc power shutdown', hue: 0, hold: true") && tables[0].contains("bootloader: { cmd: 'reboot bootloader', hue: 24, hold: true") && tables[0].contains("reboot:     { cmd: 'reboot', hue: 210, hold: false") && tables[0].contains("sysui:      { cmd: 'am crash com.android.systemui', hue: 265, hold: false"));
    // 3. Root-only commands are marked, and the lock reads the mode
    for (String t : tables) for (String line : t.split("\n")) {
      for (String root : PowerTweaks.ROOT_ONLY) if (line.contains(root)) is("a Root-only row is marked needsRoot: true: " + line.trim(), line.contains("needsRoot: true"));
      if (line.contains("needsRoot: true")) { boolean known = false; for (String root : PowerTweaks.ROOT_ONLY) if (line.contains(root)) known = true; is("a row marked needsRoot really is one: " + line.trim(), known); }
    }
    is("the lock: a row that needs Root is locked unless the active mode is root", block.contains("if (a && a.needsRoot && !xqIsRoot()) return 'root';") && block.contains("function xqIsRoot() { return !!isPrivilegedActive && currentWorkingMode === 'root'; }"));
    for (String fn : new String[] { "xpwPerform", "xdzPerform", "xzrPerform", "xdzGovPerform" }) is(fn + " refuses a locked row", between(block, "async function " + fn + "(", "\n        }\n").contains("xqActLock(a)) return { ok: false"));
    for (String fn : new String[] { "xdzProfPerform", "xdzTunePerform", "xzrSetPerform" }) is(fn + " refuses without a mode", between(block, "async function " + fn + "(", "\n        }\n").contains("xqActLock(null)) return { ok: false"));
    for (String fn : new String[] { "xdzTunePerform", "xzrSetPerform" }) is(fn + " checks every row's lock again before it runs it", between(block, "async function " + fn + "(", "\n        }\n").contains("if (!a || xqActLock(a)) { fail = {"));
    // 4. the rest of the page does not run these commands on this phone outside their tables: a line of the rest that names one may be the sections' own
    //    notes, a word list (the ADB console's autocompletion, the Connected Devices quick commands, which go to ANOTHER device through cdSh with their own
    //    question), but never a shell call of this phone (executeShell, execShellOk, xqRun). am crash: Task Manager's renderer switch is the known other place.
    for (String c : new String[] { "setprop ctl.restart zygote", "reboot recovery", "reboot bootloader", "reboot -p", "force-idle", "deviceidle unforce", "deviceidle step", "deviceidle motion", "drop_caches",
        "scaling_governor", "zram0/reset", "/proc/sys/vm/swappiness", "device_config put device_idle", "device_idle_constants", "lmkd_native", "lmkd.reinit" }) {
      for (String line : rest.split("\n")) {
        if (!line.contains(c)) continue;
        boolean shell = line.contains("executeShell") || line.contains("execShellOk(") || line.contains("xqRun(") || line.contains("runTerminalCmd(");
        is("outside the block no shell call of this phone carries the command: " + c + " | " + line.trim().substring(0, Math.min(90, line.trim().length())), !shell);
      }
    }
    is("the trim path of the Settings page is split: the question, then trimCachesStart (what the Zram section calls after its own sheet)", rest.contains("function trimCachesStart() {") && rest.contains("if (trimRun || !isPrivilegedActive || !window.AndroidBridge) return false;") && block.contains("trimCachesStart()"));
    is("the page's shell answer reaches the sections first", rest.contains("if (typeof xqShellDone === 'function' && xqShellDone(id, out)) return;"));
  }

  static int count(String s, String sub) { int c = 0; for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) c++; return c; }
}
