package com.bloatware.bingblop;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Extra Settings tab's native side: Telemetry.build (RAM, /data, battery, uptime, model, Android, One UI, kernel; what cannot be
 * read is left out, never 0 or null), Telemetry.kernelShort, and a scan of the sources: the page writes exactly Android's own setting
 * keys in their own tables (window_animation_scale, transition_animation_scale, animator_duration_scale in global;
 * screen_auto_brightness_adj and screen_brightness_mode in system), reads them back after a write, and the three bridge methods
 * (settingsPeek, getTelemetry, openDisplaySettings) have the shape the page relies on; openDisplaySettings fires ACTION_DISPLAY_SETTINGS
 * and never changes the adaptive brightness switch itself.
 */
public class TelemetryTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void eq(String what, Object got, Object want) { n++; if (!String.valueOf(got).equals(String.valueOf(want))) { fails++; System.out.println("FAIL " + what + ": got <" + got + "> want <" + want + ">"); } }

  static final String MEMINFO = "MemTotal:       16534728 kB\nMemFree:         1532044 kB\nMemAvailable:    7605975 kB\nBuffers:          102400 kB\nCached:          2450000 kB\n"
      + "SwapCached:            0 kB\nSwapTotal:       8388604 kB\nSwapFree:        7077884 kB\nHugePages_Total:       0\n";

  static JSONObject battery(double percent) throws Exception {
    JSONObject b = new JSONObject();
    b.put("percent", percent); b.put("tempTenthsC", 312); b.put("voltageMv", 3912); b.put("plugged", 0); b.put("health", 2); b.put("status", 3);
    b.put("technology", "Li-ion"); b.put("present", true);
    return b;
  }

  public static void main(String[] a) throws Exception {
    // ---- kernelShort ----
    eq("kernelShort: the word after 'Linux version '", Telemetry.kernelShort("Linux version 5.15.94-android13-8-27940483-abS918BXXS4CXF2 (build@host) (clang 17) #1 SMP PREEMPT Thu Jun 6 2024\n"), "5.15.94-android13-8-27940483-abS918BXXS4CXF2");
    eq("kernelShort: only the first line counts", Telemetry.kernelShort("Linux version 6.1.57-x (a) #1\nsecond line"), "6.1.57-x");
    eq("kernelShort: a text that does not start with the usual words gives its first word", Telemetry.kernelShort("  4.14.190-perf+ extra"), "4.14.190-perf+");
    eq("kernelShort: nothing for nothing", Telemetry.kernelShort(""), "");
    eq("kernelShort: nothing for null", Telemetry.kernelShort(null), "");
    eq("kernelShort: nothing for blank", Telemetry.kernelShort("   \n"), "");

    // ---- build: a full phone ----
    JSONObject r = Telemetry.build(MEMINFO, 254722064384L, 134212849664L, battery(49), 186120000L, "SM-S918B", "samsung", "14", 34, "6.1",
        "Linux version 5.15.94-android13-8-27940483-abS918BXXS4CXF2 (build@host) #1 SMP");
    check("build: ok", r.getBoolean("ok"));
    JSONObject mem = r.getJSONObject("mem");
    eq("build: mem.totalKb from MemTotal", mem.getLong("totalKb"), 16534728L);
    eq("build: mem.usedKb is total minus MemAvailable (MemStats.usedKb)", mem.getLong("usedKb"), 16534728L - 7605975L);
    eq("build: mem.availableKb", mem.getLong("availableKb"), 7605975L);
    eq("build: mem.swapTotalKb", mem.getLong("swapTotalKb"), 8388604L);
    eq("build: mem.swapUsedKb is SwapTotal minus SwapFree (MemStats.swapUsedKb)", mem.getLong("swapUsedKb"), 8388604L - 7077884L);
    JSONObject st = r.getJSONObject("storage");
    eq("build: storage.totalBytes", st.getLong("totalBytes"), 254722064384L);
    eq("build: storage.freeBytes", st.getLong("freeBytes"), 134212849664L);
    eq("build: storage.usedBytes is total minus free", st.getLong("usedBytes"), 254722064384L - 134212849664L);
    eq("build: the battery object is passed through as the Task Manager reads it", r.getJSONObject("battery").getInt("tempTenthsC"), 312);
    eq("build: battery percent", r.getJSONObject("battery").getDouble("percent"), 49.0);
    eq("build: uptimeMs", r.getLong("uptimeMs"), 186120000L);
    eq("build: model", r.getString("model"), "SM-S918B");
    eq("build: manufacturer", r.getString("manufacturer"), "samsung");
    eq("build: android release", r.getString("android"), "14");
    eq("build: sdk", r.getInt("sdk"), 34);
    eq("build: oneUi", r.getString("oneUi"), "6.1");
    eq("build: kernel is the short form", r.getString("kernel"), "5.15.94-android13-8-27940483-abS918BXXS4CXF2");

    // ---- build: what could not be read is left out, never written as 0 or null ----
    r = Telemetry.build("", -1, -1, null, -1, "", null, "", 0, "", "");
    check("build: ok even when nothing could be read", r.getBoolean("ok"));
    check("build: no mem without MemTotal", !r.has("mem"));
    check("build: no storage without a total", !r.has("storage"));
    check("build: no battery for null", !r.has("battery"));
    check("build: no uptime for a negative value", !r.has("uptimeMs"));
    check("build: no model / manufacturer / android / sdk / oneUi / kernel for empty values", !r.has("model") && !r.has("manufacturer") && !r.has("android") && !r.has("sdk") && !r.has("oneUi") && !r.has("kernel"));
    JSONObject noPct = battery(-1);
    r = Telemetry.build(MEMINFO, 1000, 2000, noPct, 5, " Pixel 8 ", "Google", "15", 35, "", "garbage");
    check("build: a battery whose percent is below 0 (no sticky broadcast) is left out", !r.has("battery"));
    check("build: free bytes above the total (nonsense from StatFs) gives no storage", !r.has("storage"));
    eq("build: model is trimmed", r.getString("model"), "Pixel 8");
    check("build: an empty One UI (not a Samsung) is left out", !r.has("oneUi"));
    eq("build: a kernel text with no 'Linux version' still gives its first word", r.getString("kernel"), "garbage");
    r = Telemetry.build("MemTotal: 0 kB\nSwapTotal: 100 kB\n", 10, 0, battery(100), 0, "m", "m", "r", 1, "x", "");
    check("build: MemTotal 0 means no mem block (a division by zero on the page is impossible)", !r.has("mem"));
    eq("build: storage with nothing free: usedBytes is the total", r.getJSONObject("storage").getLong("usedBytes"), 10L);
    eq("build: uptime 0 is a value", r.getLong("uptimeMs"), 0L);
    r = Telemetry.build(null, 0, 0, battery(50), 1, null, null, null, -3, null, null);
    check("build: nulls everywhere never throw and give ok", r.getBoolean("ok") && !r.has("mem") && !r.has("storage") && !r.has("kernel") && !r.has("sdk"));

    sourceScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static File findUp(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, rel);
      if (f.exists()) return f;
    }
    return null;
  }

  static String bodyOf(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0 || src.indexOf(sig, i + 1) >= 0) return null;
    int open = src.indexOf('{', i), depth = 0, k = open;
    for (; k < src.length(); k++) { char c = src.charAt(k); if (c == '{') depth++; else if (c == '}') { depth--; if (depth == 0) break; } }
    return src.substring(i, k + 1);
  }

  static int count(String s, String what) { int c = 0, i = 0; while ((i = s.indexOf(what, i)) >= 0) { c++; i += what.length(); } return c; }

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File page = findUp("assets/index.html");
    if (dir == null || page == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = new String(Files.readAllBytes(new File(dir, "MainActivity.java").toPath()), StandardCharsets.UTF_8);
    String t = new String(Files.readAllBytes(new File(dir, "Telemetry.java").toPath()), StandardCharsets.UTF_8);
    String h = new String(Files.readAllBytes(page.toPath()), StandardCharsets.UTF_8);

    // the page: Android's own keys, in Android's own tables, written through settingsOp (the working mode) and read back
    check("page: the three animation scales are exactly Android's keys, in order, in one table", h.contains("{ id: 'window', key: 'window_animation_scale',") && h.contains("{ id: 'transition', key: 'transition_animation_scale',") && h.contains("{ id: 'animator', key: 'animator_duration_scale',"));
    check("page: the animation scales are read and written in the global table", h.contains("await xsAsk('get', 'global', sc.key, '')") && h.contains("await xsAsk('put', 'global', sc.key, xsFloatText(sent[sc.id]))") && h.contains("xsPeek('global', sc.key)"));
    check("page: the brightness bias is exactly screen_auto_brightness_adj and the switch is screen_brightness_mode", h.contains("const BR_KEY = 'screen_auto_brightness_adj', BR_MODE_KEY = 'screen_brightness_mode';"));
    check("page: both brightness keys live in the system table", h.contains("await xsAsk('get', 'system', BR_KEY, '')") && h.contains("await xsAsk('get', 'system', BR_MODE_KEY, '')") && h.contains("await xsAsk('put', 'system', BR_KEY, xsFloatText(sent))") && h.contains("xsPeek('system', BR_KEY)") && h.contains("xsPeek('system', BR_MODE_KEY)"));
    check("page: no other table is ever named for these keys", !h.contains("'secure', BR_KEY") && !h.contains("'secure', sc.key") && !h.contains("'system', sc.key") && !h.contains("'global', BR_KEY"));
    int xs0 = h.indexOf("// ---- Extra Settings: Animation speed, Adaptive brightness bias, Device telemetry"), xs1 = h.indexOf("// ---- Extra Settings: Power & reboot (xpw)", xs0);
    if (xs1 < 0) xs1 = h.indexOf("function fmtCount(n)", xs0);
    String xs = xs0 >= 0 && xs1 > xs0 ? h.substring(xs0, xs1) : "";
    check("page: every write goes through the Hidden Settings tab's settingsOp (no command text is built for these keys in the sections' own code)", xs.contains("function xsAsk(op, ns, key, value) {\n            return new Promise(done => { sdbRequest(id => window.AndroidBridge.settingsOp(id, op, ns, key, value), done, 60000); });") && !xs.contains("'settings put") && !xs.contains("'settings get") && !xs.contains("\"settings ") && !xs.contains("executeShell") && !xs.contains("runShell"));
    String applyA = bodyOf(h, "async function animApply() {");
    check("page: animApply writes, then reads the scales back (animReadBack) before it says anything", applyA != null && applyA.indexOf("xsAsk('put'") >= 0 && applyA.indexOf("await animReadBack(seq)") > applyA.indexOf("xsAsk('put'") && applyA.indexOf("Applied, and read back from the phone.") > applyA.indexOf("await animReadBack(seq)"));
    String applyB = bodyOf(h, "async function brApply() {");
    check("page: brApply writes, then reads the bias back (brReadBack) and compares before it says anything", applyB != null && applyB.indexOf("await brReadBack(seq)") > applyB.indexOf("xsAsk('put'") && applyB.contains("const matches = !!back && !back.err && xsSameNum(got == null ? 0 : got, sent);"));
    check("page: a bias outside -1..1 is refused in words, never clamped", h.contains("if (n < -1 || n > 1) return { ok: false, why: txT('Only values from −1 to 1 work here. Nothing was sent.') };") && !applyB.contains("Math.max(-1, Math.min(1, sent))"));
    check("page: a negative animation scale is refused (0 or more)", h.contains("const n = animWant[sc.id], ok = typeof n === 'number' && isFinite(n) && n >= 0;"));
    check("page: the number written is Developer-options style (1.0, 0.5)", h.contains("function xsFloatText(n) {") && h.contains("return /[.e]/.test(s) ? s : s + '.0';"));
    check("page: adaptive brightness is never switched on by the page (no write of screen_brightness_mode)", !h.contains("'put', 'system', BR_MODE_KEY") && !h.contains("put', 'system', 'screen_brightness_mode'"));
    check("page: the display settings are opened through the bridge method openDisplaySettings", h.contains("window.AndroidBridge.openDisplaySettings()"));
    check("page: the telemetry poll is 5 seconds and runs only while the tab is on screen (and not under the full-screen speed test)", h.contains("const TEL_MS = 5000") && h.contains("function telOnScreen() { return currentViewName() === 'extrasettings' && !document.hidden && !stCovering(); }") && h.contains("telTimer = setInterval(() => { if (telOnScreen()) telRefresh(false); else telStop(); }, TEL_MS);"));

    // the bridge
    check("source: settingsPeek is a bridge method with the exact signature", m.contains("@JavascriptInterface\n        public String settingsPeek(String ns, String key) {"));
    String peek = bodyOf(m, "public String settingsPeek(String ns, String key)");
    check("source: settingsPeek validates the table and the key like settingsOp", peek != null && peek.contains("SettingsDb.namespaceProblem(ns)") && peek.contains("SettingsDb.keyProblem(key)"));
    check("source: settingsPeek reads with the app's own rights (ContentResolver, the three tables) and no shell or mode", peek != null && peek.contains("android.provider.Settings.Global.getString(cr, key)") && peek.contains("android.provider.Settings.Secure.getString(cr, key)") && peek.contains("android.provider.Settings.System.getString(cr, key)") && !peek.contains("Shell") && !peek.contains("resolveExecMode") && !peek.contains("putString") && !peek.contains("putInt") && !peek.contains("putFloat"));
    check("source: settingsPeek answers \"null\" for a key that is not there", peek != null && peek.contains("res.put(\"value\", v == null ? \"null\" : v);"));
    check("source: getTelemetry is a bridge method with the exact signature", m.contains("@JavascriptInterface\n        public String getTelemetry() {"));
    String tel = bodyOf(m, "public String getTelemetry()");
    check("source: getTelemetry reuses the existing readers: /proc/meminfo, StatFs on the data directory, tmBatteryJson, elapsedRealtime, /proc/version, oneUiVersion", tel != null && tel.contains("readWorldReadable(\"/proc/meminfo\")") && tel.contains("android.os.StatFs sfs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());") && tel.contains("tmBatteryJson()") && tel.contains("android.os.SystemClock.elapsedRealtime()") && tel.contains("readWorldReadable(\"/proc/version\")") && tel.contains("oneUiVersion()"));
    check("source: getTelemetry builds its answer with Telemetry.build and needs no working mode (no shell, no mode)", tel != null && tel.contains("Telemetry.build(") && !tel.contains("executeShell") && !tel.contains("resolveExecMode") && !tel.contains("runPrivilegedShell"));
    check("source: getTelemetry never throws into the page", tel != null && tel.contains("catch (Throwable t)") && tel.contains("return \"{\\\"ok\\\":false}\";"));
    check("source: openDisplaySettings is a bridge method with the exact signature", m.contains("@JavascriptInterface\n        public void openDisplaySettings() {"));
    String disp = bodyOf(m, "public void openDisplaySettings()");
    check("source: openDisplaySettings fires ACTION_DISPLAY_SETTINGS (plain Settings as the fallback) and nothing else", disp != null && disp.contains("new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)") && disp.contains("new Intent(android.provider.Settings.ACTION_SETTINGS)") && !disp.contains("putInt") && !disp.contains("screen_brightness_mode") && !disp.contains("SCREEN_BRIGHTNESS_MODE"));
    check("source: nothing in MainActivity writes the adaptive brightness switch", !m.contains("SCREEN_BRIGHTNESS_MODE") && !m.contains("\"screen_brightness_mode\""));
    check("source: Telemetry imports nothing from Android and never logs", !t.contains("import android.") && !t.contains("System.out") && !t.contains("Log."));
    check("source: the MemStats formulas are reused, not rewritten", t.contains("MemStats.usedKb(mem)") && t.contains("MemStats.swapUsedKb(mem)") && t.contains("MemStats.parse(meminfo)"));
  }
}
