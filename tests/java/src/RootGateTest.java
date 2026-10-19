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
    check("source: probeWorkingMode puts rootWorkingModeActive", probe != null && probe.contains("obj.put(\"rootWorkingModeActive\", rootGrantedNow);"));
    check("source: that verdict is root chosen AND su granted", probe != null && probe.contains("boolean rootGrantedNow = rootAvailable && \"root\".equals(activeWorkingMode) && isRootGranted();"));
    check("source: the fallback answer of a failed probe has no rootWorkingModeActive (the page then stays locked)", probe != null && !bodyOf(m, "private String probeWorkingMode() {").substring(probe.indexOf("catch (Exception e)")).contains("rootWorkingModeActive"));

    // the page: the gate helper, and the verdict read from the working-mode data
    String gate = bodyOf(h, "function isRootActive() {");
    check("source: the page defines isRootActive", gate != null);
    check("source: isRootActive needs a privileged mode that is root", gate != null && gate.contains("isPrivilegedActive") && gate.contains("currentWorkingMode === 'root'"));
    check("source: the page reads rootWorkingModeActive (the Java verdict can veto)", gate != null && gate.contains("rootWorkingModeActive"));
    check("source: the Root Features tab is in the registry, before About", h.contains("{ key: 'rootfeatures', label: 'Root\\nFeatures', name: 'Root Features' }") && h.indexOf("{ key: 'rootfeatures'") < h.indexOf("{ key: 'about'"));
    check("source: the tab re-renders on a mode change and when shown", h.contains("rootOnModeChange()") && h.contains("if (viewName === 'rootfeatures' && typeof onRootFeaturesShown === 'function') onRootFeaturesShown();"));
  }
}
