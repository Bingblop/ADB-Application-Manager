package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A3 (v7.15.1): enabling or disabling a component is Super User (Root) only, for every app (user and system alike). The bridge
 * requires the root backend and decides success by reading the component's real state back (AGENTS.md: the state, not pm's printed
 * text); the page locks the ENABLE/DISABLE control off root and funnels a tap through promptRootThenOpen, and confirms before
 * disabling a system app's component. Read from the source: setComponentEnabled needs Android classes, so it cannot be unit-run here.
 */
public class ComponentGateTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] args) throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File pageFile = findUp("assets/index.html");
    if (dir == null || pageFile == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = read(new File(dir, "MainActivity.java"));
    String h = read(pageFile);

    // ---- the bridge: Root-only, success read back from the component's real state
    String setc = bodyOf(m, "String setComponentEnabled(String pkg, String component, boolean enable) {");
    check("source: setComponentEnabled is Root only (the resolved backend must be root)", setc != null && setc.contains("!\"root\".equals(resolveExecMode())"));
    check("source: off root it answers with a Super User message and runs no pm command", setc != null && setc.contains("needs Super User (Root)") && setc.contains("needsRoot"));
    check("source: success is read from the component's real state, not pm's printed text", setc != null && setc.contains("componentOverrideState(") && !setc.contains("low.contains(\"new state\")"));
    check("source: a leading-dot class is resolved the same way for the read-back as for the command", setc != null && setc.contains("component.startsWith(\".\") ? pkg + component : component"));

    String ov = bodyOf(m, "private Boolean componentOverrideState(String pkg, String name) {");
    check("source: componentOverrideState reads getComponentEnabledSetting, returns the explicit override or null", ov != null && ov.contains("getComponentEnabledSetting") && ov.contains("COMPONENT_ENABLED_STATE_ENABLED") && ov.contains("COMPONENT_ENABLED_STATE_DISABLED") && ov.contains("return null;"));

    // ---- the page: the gate helper, the locked control, the funnel and the system-app confirm
    String ctl = bodyOf(h, "function compToggleLocked() {");
    check("source: the page gates the component toggle on Root (compToggleLocked = !isRootActive())", ctl != null && ctl.contains("!isRootActive()"));

    String row = bodyOf(h, "function compRowHtml(c, k) {");
    check("source: the inspector ENABLE/DISABLE button uses the Root lock (compToggleLocked, NEEDS_ROOT_COMP)", row != null && row.contains("compToggleLocked()") && row.contains("NEEDS_ROOT_COMP"));

    String tog = bodyOf(h, "function toggleComponent(name, enable) {");
    check("source: toggleComponent funnels through promptRootThenOpen off root", tog != null && tog.contains("if (!isRootActive())") && tog.contains("promptRootThenOpen("));
    check("source: toggleComponent confirms before disabling a system app's component", tog != null && tog.contains("isSystemApp") && tog.contains("the system app"));

    String bulk = bodyOf(h, "function alsBulkComp(enable) {");
    check("source: the bulk activity toggle is Root-gated too", bulk != null && bulk.contains("isRootActive()") && bulk.contains("promptRootThenOpen("));

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
  static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
  static String bodyOf(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0 || src.indexOf(sig, i + 1) >= 0) return null;
    int open = src.indexOf('{', i), depth = 0, k = open;
    for (; k < src.length(); k++) { char ch = src.charAt(k); if (ch == '{') depth++; else if (ch == '}') { depth--; if (depth == 0) break; } }
    return src.substring(i, k + 1);
  }
}
