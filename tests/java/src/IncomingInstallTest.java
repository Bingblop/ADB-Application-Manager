package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** MA1-2: a package opened from outside is handed to the Installer once; a re-created or restored window, or Recents, does not hand it over again. */
public class IncomingInstallTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static File repo() {
    for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return d;
    throw new IllegalStateException("repository not found");
  }
  static String body(String src, String head) {
    int i = src.indexOf(head);
    if (i < 0) return "";
    int open = src.indexOf('{', i), depth = 0;
    for (int j = open; j < src.length(); j++) {
      char c = src.charAt(j);
      if (c == '{') depth++;
      else if (c == '}' && --depth == 0) return src.substring(i, j + 1);
    }
    return src.substring(i);
  }

  public static void main(String[] args) throws Exception {
    final String VIEW = "android.intent.action.VIEW", INSTALL = "android.intent.action.INSTALL_PACKAGE", MAIN = "android.intent.action.MAIN";
    final int NEW_TASK = 0x10000000, GRANT_READ = 0x00000001;
    check("a fresh \"Open with\" (VIEW) is handed over", IncomingInstall.handOverAtCreate(VIEW, NEW_TASK | GRANT_READ, false));
    check("a fresh INSTALL_PACKAGE is handed over", IncomingInstall.handOverAtCreate(INSTALL, GRANT_READ, false));
    check("recreate() (saved state) does not hand the old package over again", !IncomingInstall.handOverAtCreate(VIEW, NEW_TASK | GRANT_READ, true));
    check("a restore after the process was killed (saved state) does not either", !IncomingInstall.handOverAtCreate(INSTALL, 0, true));
    check("a launch from Recents with the old intent does not either", !IncomingInstall.handOverAtCreate(VIEW, NEW_TASK | IncomingInstall.FLAG_LAUNCHED_FROM_HISTORY, false));
    check("the launcher's MAIN is not a package", !IncomingInstall.handOverAtCreate(MAIN, NEW_TASK, false) && !IncomingInstall.handOverAtCreate(null, 0, false));
    check("onNewIntent: a package opened while the app runs is handed over", IncomingInstall.handOverAtNewIntent(VIEW) && IncomingInstall.handOverAtNewIntent(INSTALL));
    check("onNewIntent: MAIN is not", !IncomingInstall.handOverAtNewIntent(MAIN) && !IncomingInstall.handOverAtNewIntent(null));
    check("the flag is Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY", IncomingInstall.FLAG_LAUNCHED_FROM_HISTORY == 0x00100000);

    // How MainActivity uses it.
    String m = new String(Files.readAllBytes(new File(repo(), "src/com/bloatware/bingblop/MainActivity.java").toPath()), StandardCharsets.UTF_8);
    String create = body(m, "protected void onCreate(Bundle savedInstanceState)");
    String newIntent = body(m, "protected void onNewIntent(Intent intent)");
    check("onCreate hands the intent over only through the check, with the saved state", create.contains("IncomingInstall.handOverAtCreate(") && create.contains("savedInstanceState != null")
        && !create.contains("handleIncomingIntent(getIntent());"));
    check("onNewIntent still hands a new package over", newIntent.contains("handleIncomingIntent(intent)"));
    check("a package not yet handed to the page when the window is re-created is kept in the saved state", m.contains("onSaveInstanceState(") && m.contains("pendingInstallRef"));

    System.out.println(fails == 0 ? "PASS IncomingInstallTest: " + n + " checks" : "FAILED IncomingInstallTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
