package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The root-manager hand-off ({@link RootManagers}): which installed manager is opened so the user grants this app su there, the preference order,
 * and (source scans) that MainActivity opens it with a plain launch intent and reads the status back through the working-mode check, and that the
 * page has the button and re-checks on resume. No grant or root mechanics are tested because there are none - the grant happens inside the manager.
 */
public class RootManagersTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void check(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + detail); } }

  static Set<String> set(String... pkgs) {
    Set<String> s = new HashSet<String>();
    Collections.addAll(s, pkgs);
    return s;
  }

  public static void main(String[] args) throws Exception {
    // ---- pick(): the best installed manager, or null
    check("no managers installed: null", RootManagers.pick(set("com.android.chrome", "com.whatsapp")) == null);
    check("null set: null, no exception", RootManagers.pick(null) == null);
    check("empty set: null", RootManagers.pick(Collections.<String>emptySet()) == null);
    check("KernelSU installed: KernelSU", named(RootManagers.pick(set("me.weishu.kernelsu"))).equals("KernelSU"));
    check("APatch installed: APatch", named(RootManagers.pick(set("me.bmax.apatch"))).equals("APatch"));
    check("Magisk installed: Magisk", named(RootManagers.pick(set("com.topjohnwu.magisk"))).equals("Magisk"));
    check("KernelSU Next installed: KernelSU Next", named(RootManagers.pick(set("com.rifsxd.ksunext"))).equals("KernelSU Next"));
    // preference order: KernelSU wins over Magisk when both are present (the kernel-level manager Kyle named comes first)
    check("KernelSU preferred over Magisk", named(RootManagers.pick(set("com.topjohnwu.magisk", "me.weishu.kernelsu"))).equals("KernelSU"));
    check("APatch preferred over Magisk", named(RootManagers.pick(set("com.topjohnwu.magisk", "me.bmax.apatch"))).equals("APatch"));

    // ---- installedManagers(): every known one that is present, best first; unknowns ignored
    List<RootManagers.Manager> inst = RootManagers.installedManagers(set("com.topjohnwu.magisk", "me.weishu.kernelsu", "com.whatsapp"));
    check("installedManagers lists the two known, best first", inst.size() == 2 && inst.get(0).label.equals("KernelSU") && inst.get(1).label.equals("Magisk"),
        inst.size() + " entries");
    check("installedManagers of none is empty", RootManagers.installedManagers(set("com.whatsapp")).isEmpty());

    // every known manager has a non-blank package and label
    for (RootManagers.Manager m : RootManagers.known()) check("known manager well-formed", m.pkg != null && m.pkg.contains(".") && m.label != null && !m.label.isEmpty());

    sourceScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static String named(RootManagers.Manager m) { return m == null ? "" : m.label; }

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

  /** The source of the method whose declaration starts with {@code sig}, through its closing brace ("" when it is not there). */
  static String methodBody(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0) return "";
    int depth = 0;
    for (int j = src.indexOf('{', i); j >= 0 && j < src.length(); j++) {
      char c = src.charAt(j);
      if (c == '{') depth++;
      else if (c == '}' && --depth == 0) return src.substring(i, j + 1);
    }
    return "";
  }

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File pageFile = findUp("assets/index.html");
    if (dir == null || pageFile == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = read(new File(dir, "MainActivity.java"));
    String h = read(pageFile);

    // MainActivity: the bridge opens the manager with a plain launch intent (no grant mechanics) and reports which one
    check("source: AndroidBridge exposes openRootManager", m.contains("public String openRootManager()"));
    check("source: openRootManager uses a launch intent (hands off, grants nothing)", m.contains("getLaunchIntentForPackage") && m.contains("RootManagers.pick("));
    // T-024: the page takes the manager's name from openRootManager's answer; no separate bridge method nobody calls, no stand-in string no answer leaves room for
    check("source: no rootManagerName bridge method (the page never called it)", !m.contains("rootManagerName"));
    check("source: the ok answer carries the manager's name", methodBody(m, "public String openRootManager()").contains("return \"{\\\"ok\\\":true,\\\"name\\\":\" + name + \"}\";"));
    check("source: the page toasts use that name with no unreachable stand-in", !h.contains("txT('your root manager')"));
    // T-021: the answer follows the start. openRootManager starts the manager itself (not posted to the UI thread with ok sent at once), answers
    // ok:true only after startActivity returned, and answers ok:false when the launch intent is gone or Android refuses the start
    String open = methodBody(m, "public String openRootManager()");
    int started = open.indexOf("startActivity(in)"), okTrue = open.indexOf("\"{\\\"ok\\\":true"), caught = open.indexOf("} catch (Exception e) {");
    check("source: openRootManager starts the manager on the bridge thread, not in a posted Runnable whose failure is dropped",
        started > 0 && !open.contains("runOnUiThread") && !open.contains("catch (Exception ignored)"), open);
    check("source: openRootManager answers ok:true only after startActivity returned", started > 0 && okTrue > started, open);
    check("source: a refused start answers ok:false", caught > started && open.substring(caught, Math.min(open.length(), caught + 80)).contains("return \"{\\\"ok\\\":false"), open);
    check("source: a launch intent that is gone by then answers ok:false", open.contains("if (in == null) return \"{\\\"ok\\\":false"), open);
    // T-026: both failed starts say so (reason "launch", with the manager's name), so the page does not answer "install a root manager" to a
    // phone that has one, and both hand-off buttons on the page tell that case apart
    String launchFailed = "return \"{\\\"ok\\\":false,\\\"reason\\\":\\\"launch\\\",\\\"name\\\":\" + name + \"}\";";
    check("source: the null intent and the refused start both answer reason launch with the name",
        open.split(java.util.regex.Pattern.quote(launchFailed), -1).length - 1 == 2, open);
    String jsOpen = methodBody(h, "function rootOpenManager("), jsApps = methodBody(h, "function rootManageSuperuser(");
    check("source: Open root manager and Open manager both have a branch for a manager that did not start",
        jsOpen.contains("res.reason === 'launch'") && jsApps.contains("res.reason === 'launch'"), jsOpen + "\n" + jsApps);

    // the page: the button, the pending flag, and the status read-back on resume (feeds RootGate)
    check("source: the page defines rootOpenManager", h.contains("function rootOpenManager("));
    check("source: rootOpenManager calls the bridge", h.contains("AndroidBridge.openRootManager"));
    check("source: a grant that was approved in the manager is read back on resume", h.contains("rootGrantPending") && h.contains("checkAllWorkingModes(true)"));
    check("source: the Root Features su card offers the hand-off button", h.contains("onclick=\"rootOpenManager()\""));
  }
}
