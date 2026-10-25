package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * When the permissions sheet's Bluetooth or Notifications request counts as refused for good, so the app opens its App info page: a dialog
 * dismissed with Back or a tap outside on a first ask leaves the same signs as a refusal for good (not granted, no rationale) and must not
 * send the person away; a second refusal, or a permission Android no longer asks for, must. PermRefusal's truth table, and a scan of
 * MainActivity (it needs Android classes): the sheet's branch of onRequestPermissionsResult decides with PermRefusal, and requestRuntimeForSheet
 * reads the rationale and whether it asked before it requests.
 */
public class PermRefusalTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] args) throws Exception {
    // forGood(granted, rationaleBefore, rationaleAfter, askedBefore)
    check("granted is never refused, whatever the rest", !PermRefusal.forGood(true, false, false, false) && !PermRefusal.forGood(true, true, false, true));
    check("a first ask dismissed with Back or a tap outside (no rationale before or after, not asked before): not for good, App info stays shut",
        !PermRefusal.forGood(false, false, false, false));
    check("a first refusal (the rationale appears): not for good", !PermRefusal.forGood(false, false, true, false));
    check("a dismissal after one refusal (the rationale stays): not for good", !PermRefusal.forGood(false, true, true, true));
    check("a second refusal (the rationale was there and is gone): for good", PermRefusal.forGood(false, true, false, true) && PermRefusal.forGood(false, true, false, false));
    check("refused for good already (no dialog, no rationale), and the sheet asked before: for good", PermRefusal.forGood(false, false, false, true));

    File ma = find("src/com/bloatware/bingblop/MainActivity.java");
    if (ma == null) { System.out.println("SKIP MainActivity.java not found"); }
    else {
      String src = new String(Files.readAllBytes(ma.toPath()), StandardCharsets.UTF_8);
      String result = bodyOf(src, "public void onRequestPermissionsResult(");
      check("MainActivity: onRequestPermissionsResult decides the sheet's requests with PermRefusal.forGood", result != null && result.contains("REQ_SHEET_RUNTIME_PERM") && result.contains("PermRefusal.forGood("));
      String ask = bodyOf(src, "private String requestRuntimeForSheet(");
      int rationale = ask == null ? -1 : ask.indexOf("shouldShowRequestPermissionRationale(perm)"), asked = ask == null ? -1 : ask.indexOf("sheet_perm_asked_"),
          request = ask == null ? -1 : ask.indexOf("requestPermissions(");
      check("MainActivity: requestRuntimeForSheet reads the rationale and whether the sheet asked before, before it requests", rationale >= 0 && asked >= 0 && request > rationale && request > asked);
    }

    System.out.println(fails == 0 ? "PASS PermRefusalTest: " + n + " checks" : "FAILED PermRefusalTest: " + fails + " of " + n);
    if (fails > 0) System.exit(1);
  }

  static String bodyOf(String src, String sig) {
    int i = src.indexOf(sig);
    if (i < 0) return null;
    int open = src.indexOf('{', src.indexOf(')', i)), depth = 0;
    for (int j = open; j >= 0 && j < src.length(); j++) {
      char c = src.charAt(j);
      if (c == '{') depth++;
      else if (c == '}' && --depth == 0) return src.substring(open, j + 1);
    }
    return null;
  }

  static File find(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) { File f = new File(d, rel); if (f.isFile()) return f; }
    return null;
  }
}
