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

  static void sourceScan() throws Exception {
    File dir = findUp("src/com/bloatware/bingblop");
    File pageFile = findUp("assets/index.html");
    if (dir == null || pageFile == null) { System.out.println("SKIP source scan: the repository was not found above " + System.getProperty("user.dir")); return; }
    String m = read(new File(dir, "MainActivity.java"));
    String h = read(pageFile);

    // MainActivity: the bridge opens the manager with a plain launch intent (no grant mechanics) and reports which one
    check("source: AndroidBridge exposes openRootManager", m.contains("public String openRootManager()"));
    check("source: openRootManager uses a launch intent (hands off, grants nothing)", m.contains("getLaunchIntentForPackage") && m.contains("RootManagers.pick("));
    check("source: AndroidBridge reports the installed manager name", m.contains("public String rootManagerName()"));

    // the page: the button, the pending flag, and the status read-back on resume (feeds RootGate)
    check("source: the page defines rootOpenManager", h.contains("function rootOpenManager("));
    check("source: rootOpenManager calls the bridge", h.contains("AndroidBridge.openRootManager"));
    check("source: a grant that was approved in the manager is read back on resume", h.contains("rootGrantPending") && h.contains("checkAllWorkingModes(true)"));
    check("source: the Root Features su card offers the hand-off button", h.contains("onclick=\"rootOpenManager()\""));
  }
}
