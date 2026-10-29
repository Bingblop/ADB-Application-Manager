package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The root gate (RootGate.active): the Root Features surface unlocks only when the resolved working mode is root AND su is granted
 * (the truth table, null-safe), and (source scans) that MainActivity reports that verdict to the page as rootWorkingModeActive and
 * that the page has the gate (isRootActive) and reads the verdict.
 */
public class RootGateTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void check(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + detail); } }

  public static void main(String[] args) throws Exception {
    // ---- the truth table
    check("root mode and su granted: unlocked", RootGate.active("root", true));
    check("root mode but su denied (or not asked yet): locked", !RootGate.active("root", false));
    check("Shizuku with su granted elsewhere: locked (only the resolved mode root counts)", !RootGate.active("shizuku", true));
    check("auto with su granted: locked (auto never resolves to root)", !RootGate.active("auto", true));
    check("ADB TCP / Wireless / Read-Only: locked", !RootGate.active("adb_tcp", true) && !RootGate.active("adb_wireless", true) && !RootGate.active("unprivileged", true));
    check("null mode: locked, no exception", !RootGate.active(null, true) && !RootGate.active(null, false));
    check("a blank or odd spelling of root is not root", !RootGate.active("", true) && !RootGate.active("Root", true) && !RootGate.active(" root", true));

    // ---- Automatic's order (v7.19.0): Super User, Shizuku as uid 0 or 1000, ADB over TCP, Wireless Debugging, Shizuku as uid 2000
    check("auto: su granted wins over everything", "root".equals(RootGate.autoPick(true, true, 0, true, true)));
    check("auto: Shizuku uid 1000 and uid 0 come before ADB TCP", "shizuku".equals(RootGate.autoPick(false, true, 1000, true, true)) && "shizuku".equals(RootGate.autoPick(false, true, 0, true, true)));
    check("auto: ADB TCP comes before Wireless Debugging and a uid 2000 Shizuku", "adb_tcp".equals(RootGate.autoPick(false, true, 2000, true, true)));
    check("auto: Wireless Debugging comes before a uid 2000 Shizuku", "adb_wireless".equals(RootGate.autoPick(false, true, 2000, false, true)));
    check("auto: a uid 2000 (or unknown uid) Shizuku is the last pick", "shizuku".equals(RootGate.autoPick(false, true, 2000, false, false)) && "shizuku".equals(RootGate.autoPick(false, true, -1, false, false)));
    check("auto: nothing ready gives null", RootGate.autoPick(false, false, -1, false, false) == null);
    // ---- Super User lost while it is the chosen mode
    check("root chosen, su gone: lost", RootGate.rootLost("root", false, null));
    check("root chosen, su said no: lost", RootGate.rootLost("root", true, Boolean.FALSE));
    check("root chosen, su there and not asked yet or granted: kept", !RootGate.rootLost("root", true, null) && !RootGate.rootLost("root", true, Boolean.TRUE));
    check("another mode is never treated as lost root", !RootGate.rootLost("auto", false, Boolean.FALSE) && !RootGate.rootLost(null, false, null));

    // ---- the privileged tiers above the ADB shell (RootGate.unlocked): Root with su, or Shizuku as the system user (uid 1000) or root (uid 0)
    check("Shizuku authorized as the system user (uid 1000): unlocked", RootGate.unlocked("shizuku", false, true, 1000));
    check("Shizuku authorized as root (uid 0): unlocked", RootGate.unlocked("shizuku", false, true, 0));
    check("Shizuku as the ADB shell user (uid 2000): locked", !RootGate.unlocked("shizuku", false, true, 2000));
    check("Shizuku with an unknown uid (-1): locked", !RootGate.unlocked("shizuku", false, true, -1));
    check("Shizuku not authorized is locked even at uid 1000", !RootGate.unlocked("shizuku", false, false, 1000));
    check("a uid of 1000 outside Shizuku (ADB TCP) does not unlock", !RootGate.unlocked("adb_tcp", false, true, 1000));
    check("Root with su granted: unlocked, the uid ignored", RootGate.unlocked("root", true, false, -1));
    check("null mode with uid 0: locked, no exception", !RootGate.unlocked(null, false, false, 0));

    // ---- the stricter uid-0 tier (RootGate.uid0): Root with su, or Shizuku as root (uid 0); the system user (uid 1000) stays locked
    check("uid0: Shizuku as root (uid 0): unlocked", RootGate.uid0("shizuku", false, true, 0));
    check("uid0: Shizuku as the system user (uid 1000): locked (Android refuses these to anyone but uid 0)", !RootGate.uid0("shizuku", false, true, 1000));
    check("uid0: Shizuku as the ADB shell user (uid 2000): locked", !RootGate.uid0("shizuku", false, true, 2000));
    check("uid0: Shizuku with an unknown uid (-1): locked", !RootGate.uid0("shizuku", false, true, -1));
    check("uid0: Root with su granted: unlocked, the uid ignored", RootGate.uid0("root", true, false, -1));
    check("uid0: null mode with uid 0: locked, no exception", !RootGate.uid0(null, false, false, 0));

    sourceScan();
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

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File pageFile = findUp("assets/index.html");
    if (dir == null || pageFile == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = read(new File(dir, "MainActivity.java"));
    String h = read(pageFile);

    // MainActivity: the probe reports the gate's verdict, computed as root chosen AND su granted (not from rootAvailable alone)
    String probe = bodyOf(m, "private String probeWorkingMode() {");
    check("source: probeWorkingMode puts rootWorkingModeActive", probe != null && probe.contains("obj.put(\"rootWorkingModeActive\", RootGate.active(resolved, rootGrantedNow));"));
    check("source: su is asked while Root or Automatic is chosen (asked in the app's window; a tile tap's headless window only reads a grant already known)", probe != null && probe.contains("boolean suAsked = \"root\".equals(activeWorkingMode) || \"auto\".equals(activeWorkingMode);") && probe.contains("boolean rootGrantedNow = rootAvailable && suAsked && (\"auto\".equals(activeWorkingMode) ? autoRootGranted() : (isHeadless() ? rootGrantedKnown() : isRootGranted()));"));
    check("source: a lost Super User falls back to Automatic before the mode is read", probe != null && probe.contains("if (fallBackFromLostRoot()) obj.put(\"rootFellBack\", true);") && probe.indexOf("fallBackFromLostRoot()") < probe.indexOf("String configured = activeWorkingMode;"));
    check("source: Automatic picks by RootGate.autoPick", probe != null && probe.contains("RootGate.autoPick(rootGrantedNow, shizukuAuthorized,"));
    check("source: the fallback answer of a failed probe has no rootWorkingModeActive (the page then stays locked)", probe != null && !bodyOf(m, "private String probeWorkingMode() {").substring(probe.indexOf("catch (Exception e)")).contains("rootWorkingModeActive"));
    // T-043: outside Root su is not asked, but a known refusal (the last answer, kept in rootGrant) is still reported, so Root Features reads DENIED after
    // Request Super User put the previous mode back; a fresh probe forgets it
    check("source: outside Root, a remembered refusal is reported as rootGranted false (read under the lock, never a fresh su call)", probe != null
        && probe.contains("synchronized (modeCacheLock) { lastAnswer = rootGrant; }") && probe.contains("if (Boolean.FALSE.equals(lastAnswer)) obj.put(\"rootGranted\", false);")
        && probe.indexOf("else if (rootAvailable) {") > probe.indexOf("if (rootAvailable && suAsked) obj.put(\"rootGranted\", rootGrantedNow);"));
    String fresh = bodyOf(m, "private String probeAndCacheMode(boolean recheckRoot) {");
    check("source: a fresh probe forgets su's last answer while Super User is chosen (Automatic keeps it, so its prompt does not come back)", fresh != null && fresh.contains("if (recheckRoot) { rootChecked = false; if (\"root\".equals(activeWorkingMode)) { rootGrant = null; rootGrantUnclear = false; } }"));

    // the page: the gate helper, and the verdict read from the working-mode data
    String gate = bodyOf(h, "function isRootActive() {");
    check("source: the page defines isRootActive", gate != null);
    check("source: isRootActive needs a privileged mode that is root", gate != null && gate.contains("isPrivilegedActive") && gate.contains("currentWorkingMode === 'root'"));
    check("source: the page reads rootWorkingModeActive (the Java verdict can veto)", gate != null && gate.contains("rootWorkingModeActive"));
    check("source: the Root Features tab is in the registry, before About", h.contains("{ key: 'rootfeatures', label: 'Root\\nFeatures', name: 'Root Features' }") && h.indexOf("{ key: 'rootfeatures'") < h.indexOf("{ key: 'about'"));
    check("source: the tab re-renders on a mode change and when shown", h.contains("rootOnModeChange()") && h.contains("if (viewName === 'rootfeatures' && typeof onRootFeaturesShown === 'function') onRootFeaturesShown();"));
    // T-043: Request Super User and the attempt-to-prompt funnel ask su through rootAskSu, which puts the mode chosen before back after a refusal
    String ask = bodyOf(h, "function rootAskSu(afterSelect) {");
    check("source: rootAskSu remembers the chosen mode, then switches to Root", ask != null && ask.indexOf("const prev = (lastModesData || {}).configuredMode;") >= 0
        && ask.indexOf("const prev = (lastModesData || {}).configuredMode;") < ask.indexOf("selectModeUI('root',"));
    check("source: only a refusal of a switch from another mode puts it back, with setWorkingMode (no prompt, no reconnect), then reads the modes again", ask != null
        && ask.contains("if (res && !res.ok && prev && prev !== 'root' && MODE_LABELS[prev]") && ask.contains("window.AndroidBridge.setWorkingMode(prev);")
        && ask.indexOf("checkAllWorkingModes(false);") > ask.indexOf("window.AndroidBridge.setWorkingMode(prev);"));
    check("source: the toast says the mode stays only when the check read it back", ask != null && ask.contains("if ((lastModesData || {}).configuredMode === prev) showToast("));
    String req = bodyOf(h, "function rootRequestSuperUser() {"), funnel = bodyOf(h, "function promptRootThenOpen(featureName) {");
    check("source: Request Super User and promptRootThenOpen go through rootAskSu, not a bare switch to Root", req != null && funnel != null
        && req.contains("rootAskSu()") && funnel.contains("rootAskSu(") && !req.contains("selectModeUI('root'") && !funnel.contains("selectModeUI('root'"));
    // T-052: so does the offer to switch to Root after Open root manager (a yes that su refuses puts the mode chosen before back)
    String offer = bodyOf(h, "function rootOfferSwitchAfterManager() {");
    check("source: the switch-to-Root offer after Open root manager goes through rootAskSu, not a bare switch to Root", offer != null
        && offer.contains("rootAskSu()") && !offer.contains("selectModeUI('root'"));
  }
}
