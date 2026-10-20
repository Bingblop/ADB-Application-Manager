package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The Working mode tile's light (ModeTileLight.lit): lit only when the probe resolved to a privileged backend AND found it available (the
 * truth table, null-safe), the preference keys the tile and MainActivity share, and (source scans) that the probe publishes them, that the
 * tile reads them and no longer lights itself unconditionally, that a tap on the tile probes again, and that the tile stays a passive one
 * (no ACTIVE_TILE), so Android refreshes it every time the panel opens.
 */
public class ModeTileLightTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void check(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + detail); } }

  public static void main(String[] args) throws Exception {
    // ---- the truth table
    for (String m : new String[] {"adb_tcp", "adb_wireless", "shizuku", "root"}) {
      check(m + " available: lit", ModeTileLight.lit(m, true));
      check(m + " chosen but not available (not connected, not authorized, su not granted): dim", !ModeTileLight.lit(m, false));
      check(m + " is a privileged backend", ModeTileLight.privileged(m));
    }
    check("Automatic: dim (the probe resolves auto to a backend or to unprivileged; auto itself is never a backend)", !ModeTileLight.lit("auto", true) && !ModeTileLight.privileged("auto"));
    check("Read-Only: dim even when marked available", !ModeTileLight.lit("unprivileged", true) && !ModeTileLight.privileged("unprivileged"));
    check("null or blank mode: dim, no exception", !ModeTileLight.lit(null, true) && !ModeTileLight.lit("", true) && !ModeTileLight.privileged(null) && !ModeTileLight.privileged(""));
    check("odd spellings are not backends", !ModeTileLight.lit("Root", true) && !ModeTileLight.lit(" shizuku", true) && !ModeTileLight.lit("adb-tcp", true) && !ModeTileLight.lit("ADB_TCP", true));
    check("nothing saved yet (a fresh install, the app never opened): dim", !ModeTileLight.lit("", false));
    check("the keys are stable (a rename would orphan the saved values)", "mode_tile_mode".equals(ModeTileLight.PREF_MODE) && "mode_tile_available".equals(ModeTileLight.PREF_AVAILABLE));

    sourceScan();
    docsScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  // ------------------------------------------------------------------------------------------ the wiring, read from the source

  static File findUp(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, rel);
      if (f.exists()) return f;
    }
    return null;
  }

  static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

  static String bodyOf(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0 || src.indexOf(sig, i + 1) >= 0) return null;
    int open = src.indexOf('{', i), depth = 0, k = open;
    for (; k < src.length(); k++) { char ch = src.charAt(k); if (ch == '{') depth++; else if (ch == '}') { depth--; if (depth == 0) break; } }
    return src.substring(i, k + 1);
  }

  /** Both present, a before b. */
  static boolean before(String s, String a, String b) {
    if (s == null) return false;
    int ia = s.indexOf(a), ib = s.indexOf(b);
    return ia >= 0 && ib >= 0 && ia < ib;
  }

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File manifest = findUp("AndroidManifest.xml");
    if (dir == null || manifest == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String tile = read(new File(dir, "ModeTileService.java"));
    String m = read(new File(dir, "MainActivity.java"));
    String ax = read(manifest);

    // the tile: reads the two keys the activity wrote and lights itself through the rule; the old unconditional light is gone
    String refresh = bodyOf(tile, "private void refresh() {");
    check("source: the tile's refresh reads the keys from the preferences the activity writes", refresh != null && refresh.contains("QuickActions.prefs(this)") && refresh.contains("ModeTileLight.PREF_MODE") && refresh.contains("ModeTileLight.PREF_AVAILABLE"));
    check("source: the tile is lit through ModeTileLight.lit, else inactive", refresh != null && refresh.contains("ModeTileLight.lit(") && refresh.contains("Tile.STATE_ACTIVE : Tile.STATE_INACTIVE"));
    check("source: the tile no longer lights itself unconditionally", !tile.contains("t.setState(Tile.STATE_ACTIVE);"));

    // MainActivity: the probe publishes the inputs of the verdict once computed, and not from the fallback answer of a failed probe
    String probe = bodyOf(m, "private String probeWorkingMode() {");
    check("source: probeWorkingMode publishes the resolved mode and whether it is available", probe != null && probe.contains("publishModeTileLight(resolved, available);"));
    check("source: ... after isPrivileged is computed and before the answer is returned", before(probe, "obj.put(\"isPrivileged\"", "publishModeTileLight(resolved, available);") && before(probe, "publishModeTileLight(resolved, available);", "return obj.toString();"));
    check("source: the fallback answer of a failed probe leaves the light as it was", probe != null && probe.indexOf("catch (Exception e)") >= 0 && !probe.substring(probe.indexOf("catch (Exception e)")).contains("publishModeTileLight"));
    String publish = bodyOf(m, "private void publishModeTileLight(String resolved, boolean available) {");
    check("source: publishModeTileLight writes both keys to the preferences the tile reads", publish != null && publish.contains("QuickActions.prefs(this)") && publish.contains("putString(ModeTileLight.PREF_MODE, mode)") && publish.contains("putBoolean(ModeTileLight.PREF_AVAILABLE, available)"));
    check("source: ... only when they changed", publish != null && publish.contains("if (mode.equals(oldMode) && available == oldAvailable) return;"));
    // T-036: TileService.requestListeningState "is only applicable to tiles that have META_DATA_ACTIVE_TILE defined as true ... and will do
    // nothing otherwise" (Android reference); this tile is passive (checked below), so the call would promise a refresh that never comes
    check("source: ... and does not call requestListeningState, which Android ignores for a passive tile", publish != null && !publish.contains("requestListeningState"));
    // T-060: the end of a tile tap (finishQuickAction) asked the same passive tile for a refresh that never comes; the tile shows the new
    // light when the panel is opened again (the tap's startActivityAndCollapse closed it). The widget refresh stays: a widget is redrawn
    String finish = bodyOf(m, "private void finishQuickAction(final String message) {");
    check("source: finishQuickAction still refreshes the home-screen widget", finish != null && finish.contains("QuickWidgetProvider.refreshAll(MainActivity.this);"));
    check("source: ... and sends no requestListeningState to the passive Working mode tile", finish != null && !finish.contains("requestListeningState(") && !finish.contains("ModeTileService"));
    check("source: MainActivity calls requestListeningState nowhere (both tiles are passive, so Android would ignore it)", !m.contains("requestListeningState("));
    String quick = bodyOf(m, "private void runQuickAction(Intent intent) {");
    check("source: a tap on the tile (cycle mode) probes again, so the light follows the new mode", before(quick, "quickCycleMode();", "new AndroidBridge().getWorkingMode();"));

    // T-035: a tile tap never runs su. The headless window's probe takes only a root verdict already known (not asked yet: dim), so no root
    // manager prompt comes up behind the collapsing panel and an unanswered one is never remembered as a refusal; its answer is not kept, so
    // the app's first look asks su itself, in the foreground
    check("source: the probe asks su only from the app's window; the headless tile window uses the remembered verdict", probe != null && probe.contains("boolean rootGrantedNow = rootAvailable && \"root\".equals(activeWorkingMode) && (isHeadless() ? rootGrantedKnown() : isRootGranted());"));
    String known = bodyOf(m, "private boolean rootGrantedKnown() {");
    check("source: rootGrantedKnown reads the remembered verdict (null, not asked yet, is not granted) and runs nothing", known != null && known.contains("Boolean.TRUE.equals(rootGrant)") && !known.contains("ProcessBuilder") && !known.contains("isRootGranted"));
    String ready = bodyOf(m, "private boolean modeReadyQuiet(String mode) {"), cycle = bodyOf(m, "private String quickCycleMode() {");
    check("source: the cycle itself asks no su (modeReadyQuiet, quickCycleMode)", ready != null && cycle != null && !ready.contains("isRootGranted") && !cycle.contains("isRootGranted") && !cycle.contains("getWorkingModeFresh"));
    check("source: the tap's probe answer is not kept, so the app's next look probes (and asks su) itself", before(quick, "new AndroidBridge().getWorkingMode();", "invalidateModeCache();") && before(quick, "invalidateModeCache();", "finishQuickAction(message);"));

    // T-034: coming back to the app (a warm resume, the same process) checks the mode again, whether or not a tile changed it meanwhile, so a
    // connection lost while the app was in the background (Wi-Fi off, Shizuku stopped) dims the tile and the header badge when the app is opened
    String resume = bodyOf(m, "protected void onResume() {");
    String changed = resume == null ? null : bodyOf(resume, "if (!saved.equals(activeWorkingMode)) {");
    String outside = resume == null || changed == null ? null : resume.replace(changed, "");
    String probeJs = "notifyJs(\"window.checkAllWorkingModes && window.checkAllWorkingModes(false)\");";
    check("source: onResume asks the page to check the mode on every resume, not only after a tile changed it", outside != null && outside.contains(probeJs), resume == null ? "onResume not found" : changed == null ? "the saved-mode block not found" : "the probe is only inside the saved-mode block");
    check("source: ... not from the headless tile window (which has no page), and after the mode was read again", before(outside, "if (isHeadless()) return;", probeJs) && before(resume, "activeWorkingMode = saved;", probeJs));

    // the manifest: a passive tile, so Android calls onStartListening whenever the panel opens (an active tile would wait to be asked)
    int at = ax.indexOf("android:name=\".ModeTileService\"");
    String block = at < 0 ? null : ax.substring(at, ax.indexOf("</service>", at));
    check("source: the Working mode tile is declared in the manifest", block != null);
    check("source: the tile stays passive (no ACTIVE_TILE meta-data), so it refreshes on every panel open", block != null && !block.contains("ACTIVE_TILE"));
  }

  // ------------------------------------------------------------------------------------------ the Help Guide row, read from the docs

  /** The text of a guide row or Markdown line: tags and Markdown code marks gone, white space collapsed. */
  static String plain(String s) { return s == null ? null : s.replaceAll("<[^>]+>", "").replace("`", "").replaceAll("\\s+", " "); }

  /** The one line of {@code src} that holds {@code marker}, or null. */
  static String lineWith(String src, String marker) {
    int i = src.indexOf(marker);
    if (i < 0 || src.indexOf(marker, i + 1) >= 0) return null;
    int a = src.lastIndexOf('\n', i) + 1, b = src.indexOf('\n', i);
    return src.substring(a, b < 0 ? src.length() : b);
  }

  // T-061: after T-035 a tap that cycles to Root asks no su, and the headless probe counts Root as granted only with a yes already known in this
  // process (rootGrantedKnown; a new process knows none), so the tile stays dim until the app is opened and asks. The guide's tile row and
  // FULL-GUIDE.md's say so, next to "lit when ... Root granted"
  static void docsScan() throws Exception {
    File guide = findUp("docs/guide/03-apps.html"), full = findUp("docs/FULL-GUIDE.md");
    if (guide == null || full == null) { System.out.println("SKIP docs scan: the guide was not found above " + System.getProperty("user.dir")); return; }
    String marker = "lit when the app last found a working mode usable";
    String row = plain(lineWith(read(guide), marker)), fullRow = plain(lineWith(read(full), marker));
    String said = "A tap that switches to Root asks no su (the root manager's question would come up behind the closing panel). So the tile stays dim after"
        + " that tap, even where root works, until you open the app, which then asks; it is lit at once only when the app is still running and already has su's yes";
    check("docs: the guide's Working mode tile row says a tap to Root asks no su and leaves the tile dim until the app is opened (T-061)", row != null && row.contains(said), row);
    check("docs: ... and FULL-GUIDE.md's tile row says the same", fullRow != null && fullRow.contains(said), fullRow);
  }
}
