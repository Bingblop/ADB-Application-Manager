package com.bloatware.bingblop;

import java.util.List;

/** Where the bundled adb server listens: a private unix socket in the app's folder, the old loopback port only as the fallback. */
public class AdbServerSpecTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  public static void main(String[] args) {
    String files = "/data/user/0/com.bloatware.bingblop/files";
    String p = AdbServerSpec.socketPath(files);
    check("the socket lives in a folder of its own inside the app's files", p.equals(files + "/adb_sock/s"));
    check("that path fits a unix socket address", AdbServerSpec.pathFits(p));
    List<String> a = AdbServerSpec.args(p);
    check("a usable path gives -L localfilesystem:<path>", a.size() == 2 && a.get(0).equals("-L") && a.get(1).equals("localfilesystem:" + p));
    check("and that is a private spec", AdbServerSpec.isPrivate(a));
    check("no path (the phone refused the socket) falls back to -P 5042", AdbServerSpec.args(null).toString().equals("[-P, 5042]") && !AdbServerSpec.isPrivate(AdbServerSpec.args(null)));
    check("an empty path falls back too", AdbServerSpec.args("").get(0).equals("-P"));
    StringBuilder longPath = new StringBuilder("/data/user/0/");
    while (longPath.length() < 120) longPath.append("x");
    check("a path longer than sun_path allows is refused (fallback)", !AdbServerSpec.pathFits(longPath.toString()) && AdbServerSpec.args(longPath.toString()).get(0).equals("-P"));
    check("a path of exactly 100 characters fits, 101 does not", AdbServerSpec.pathFits(new String(new char[100]).replace('\0', 'a')) && !AdbServerSpec.pathFits(new String(new char[101]).replace('\0', 'a')));
    check("a path with a NUL is refused", !AdbServerSpec.pathFits("/a\0b"));
    check("the legacy port is 5042", AdbServerSpec.LEGACY_PORT == 5042);
    check("isPrivate rejects null and the port form", !AdbServerSpec.isPrivate(null) && !AdbServerSpec.isPrivate(java.util.Arrays.asList("-P", "5042")));
    System.out.println(fails == 0 ? "PASS AdbServerSpecTest: " + n + " checks" : "FAILED AdbServerSpecTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
