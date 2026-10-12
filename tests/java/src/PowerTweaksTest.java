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
        "dumpsys deviceidle force-idle deep", "dumpsys deviceidle step deep", "dumpsys deviceidle unforce", "dumpsys deviceidle motion", "echo 3 > /proc/sys/vm/drop_caches", "echo 1 > /proc/sys/vm/drop_caches" };
    for (String c : cmds) {
      boolean inTable = false;
      for (String t : tables) if (t.contains("'" + c) || t.contains(c + "'") || t.contains(c + " ")) inTable = true;
      is("the command is a row of a table: " + c, inTable);
    }
    // the block's own code never hands a command literal of its own to xqRun: every xqRun of an action takes a.cmd / st.cmd (reads are dumpsys get / cat / pidof / help only)
    Matcher runs = Pattern.compile("xqRun\\(([^,)]+)").matcher(block);
    int actionRuns = 0;
    while (runs.find()) {
      if (block.substring(Math.max(0, runs.start() - 9), runs.start()).equals("function ")) continue;        // the definition itself
      String arg = runs.group(1).trim();
      boolean fromTable = arg.equals("a.cmd") || arg.equals("st.cmd");
      boolean readOnly = arg.equals("XDZ_READ_CMD") || arg.equals("XZR_READ_CMD") || arg.startsWith("'pidof ") || arg.startsWith("'cmd power help") || arg.startsWith("\"dumpsys power | grep") || (arg.startsWith("'settings ") && arg.contains("op === 'put'")) || arg.startsWith("op === 'put'");
      if (fromTable) actionRuns++;
      is("xqRun runs a table row or a read, never a literal action: " + arg, fromTable || readOnly);
    }
    is("the rows are run (three sections)", actionRuns >= 3);
    // the read commands change nothing
    String reads = between(block, "const XDZ_READ_CMD = ", ";\n") + between(block, "const XZR_READ_CMD = ", ";\n");
    is("the read commands are dumpsys get / cat / grep only", !reads.contains("force-idle") && !reads.contains("unforce") && !reads.contains(" step") && !reads.contains("settings put") && !reads.contains("reboot") && !reads.contains("drop_caches") && !reads.contains("echo 1") && !reads.contains("echo 3"));
    // 2. the perform functions (the only readers of the tables' cmd) are called from xqSheetGo's spec.run only
    for (String fn : new String[] { "xpwPerform", "xdzPerform", "xdzProfPerform", "xzrPerform" }) {
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
    for (String fn : new String[] { "xpwPerform", "xdzPerform", "xzrPerform" }) is(fn + " refuses a locked row", between(block, "async function " + fn + "(", "\n        }\n").contains("xqActLock(a)) return { ok: false"));
    is("the profile apply refuses without a mode", between(block, "async function xdzProfPerform(", "\n        }\n").contains("xqActLock(null)) return { ok: false"));
    // 4. the rest of the page does not run these commands on this phone outside their tables: a line of the rest that names one may be the sections' own
    //    notes, a word list (the ADB console's autocompletion, the Connected Devices quick commands, which go to ANOTHER device through cdSh with their own
    //    question), but never a shell call of this phone (executeShell, execShellOk, xqRun). am crash: Task Manager's renderer switch is the known other place.
    for (String c : new String[] { "setprop ctl.restart zygote", "reboot recovery", "reboot bootloader", "reboot -p", "force-idle", "deviceidle unforce", "deviceidle step", "deviceidle motion", "drop_caches" }) {
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
