package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A backup restore brings a package back with "pm install-existing" only when it really is a system app that was removed for the user.
 * getPackageInfo(pkg, MATCH_UNINSTALLED_PACKAGES) also returns a user app removed with "pm uninstall -k" (data kept); that one has no
 * signing info, so taking the system branch refused the restore ("the system app on this phone is signed with a different key") and, on a
 * phone with several users, asked twice. RestorePlan.bringBackSystemApp is the decision; the source scan checks that runRestore in
 * MainActivity (which needs Android classes) asks it before it takes the branch.
 */
public class RestorePlanTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static final int FLAG_INSTALLED = 0x00800000, FLAG_HAS_CODE = 0x4, FLAG_PERSISTENT = 0x8, FLAG_UPDATED_SYSTEM_APP = 0x80;

  static String blank(String s) {
    return s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
  }

  /** MediaProvider keeps a requested extension only when it maps to the requested MIME type (or is that type's default); a .json asked as text/plain
   *  was stored as .json.txt while the page reported .json. */
  static void checkDownloadMime() throws Exception {
    check("a .json export is saved as application/json", "application/json".equals(BackupScripts.textMimeType("saved-lists-20261003-0400.json")));
    check("a .xml export stays text/xml", "text/xml".equals(BackupScripts.textMimeType("com.example_AndroidManifest.xml")));
    check("a .csv export stays text/csv", "text/csv".equals(BackupScripts.textMimeType("report.csv")));
    check("a .txt export stays text/plain", "text/plain".equals(BackupScripts.textMimeType("logcat_1.txt")));
    check("an unknown extension stays text/plain", "text/plain".equals(BackupScripts.textMimeType("export")));
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    File f = null;
    for (int i = 0; d != null && i < 8 && f == null; i++, d = d.getParentFile()) {
      File c = new File(d, "src/com/bloatware/bingblop/MainActivity.java");
      if (c.isFile()) f = c;
    }
    check("MainActivity.java found (mime)", f != null);
    if (f != null) {
      String src = blank(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      int s = src.indexOf("public String saveTextToDownloads(");
      String body = s < 0 ? "" : src.substring(s, Math.min(src.length(), s + 1200));
      check("saveTextToDownloads asks BackupScripts.textMimeType for the type", body.contains("BackupScripts.textMimeType(safeName)"));
      check("saveTextToDownloads has no local text/plain fallback chain", !body.contains("\"text/plain\""));
    }
  }

  /** A restore of the version that is already installed installs nothing, but with "Also restore app data" it still replaces the app's data as root:
   *  that needs the same native question the install branch asks (once: bringing a system app back has asked it already). */
  static void checkSameVersionConfirm() throws Exception {
    check("same version, data restore, nothing asked yet: ask", RestorePlan.askBeforeSameVersionRestore(false, true));
    check("same version, data restore, system app just brought back (already asked): do not ask twice", !RestorePlan.askBeforeSameVersionRestore(true, true));
    check("same version, no data restore: the install branch's question does not apply", !RestorePlan.askBeforeSameVersionRestore(false, false));
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    File f = null;
    for (int i = 0; d != null && i < 8 && f == null; i++, d = d.getParentFile()) {
      File c = new File(d, "src/com/bloatware/bingblop/MainActivity.java");
      if (c.isFile()) f = c;
    }
    check("MainActivity.java found (same version)", f != null);
    if (f != null) {
      String src = blank(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      int a = src.indexOf("if (installed && sameVersion) {");
      int z = a < 0 ? -1 : src.indexOf("} else {", a);
      String branch = a < 0 || z < 0 ? "" : src.substring(a, z);
      check("runRestore has the same-version branch", !branch.isEmpty());
      check("the same-version branch asks RestorePlan.askBeforeSameVersionRestore", branch.contains("RestorePlan.askBeforeSameVersionRestore("));
      check("and asks in the native dialog (confirmInMode) with the data line (RESTORES_DATA)", branch.contains("confirmInMode(") && branch.contains("InstallConfirm.RESTORES_DATA") && branch.contains("throw new InstallCancelled()"));
    }
  }

  /** "Permissions X of Y granted": Y counted every entry of backup.json, including the ones deliberately skipped (not a dangerous permission on this phone,
   *  e.g. POST_NOTIFICATIONS from a newer Android), so a good restore read as failed grants. Y now counts only the entries that were tried. */
  static void checkPermissionCount() throws Exception {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    File f = null;
    for (int i = 0; d != null && i < 8 && f == null; i++, d = d.getParentFile()) {
      File c = new File(d, "src/com/bloatware/bingblop/MainActivity.java");
      if (c.isFile()) f = c;
    }
    check("MainActivity.java found (permissions)", f != null);
    if (f == null) return;
    String src = blank(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    int a = src.indexOf("JSONArray perms = meta.optJSONArray(\"permissions\");");
    int z = a < 0 ? -1 : src.indexOf("res.put(\"permissionsTotal\"", a);
    String loop = a < 0 || z < 0 ? "" : src.substring(a, z);
    check("runRestore has the permission loop", !loop.isEmpty());
    check("the total is not the length of the list in backup.json", !Pattern.compile("total\\s*=\\s*perms\\s*==\\s*null\\s*\\?\\s*0\\s*:\\s*perms\\.length\\(\\)").matcher(loop).find());
    int skip = loop.indexOf("isDangerousPermission(pm, perm)) continue;");
    int count = loop.indexOf("total++");
    int grant = loop.indexOf("bridge.setPermission(");
    check("the total counts an entry only after the skip of the ones that are not tried, and before its grant", skip > 0 && count > skip && grant > count);
  }

  public static void main(String[] args) throws Exception {
    check("a system app removed for this user is brought back", RestorePlan.bringBackSystemApp(true, RestorePlan.FLAG_SYSTEM | FLAG_HAS_CODE));
    check("a system app that had an update removed for this user is brought back", RestorePlan.bringBackSystemApp(true, RestorePlan.FLAG_SYSTEM | FLAG_UPDATED_SYSTEM_APP));
    check("a user app removed with pm uninstall -k (data kept) is installed from the backup, not brought back", !RestorePlan.bringBackSystemApp(true, FLAG_HAS_CODE));
    check("a user app with no flags at all is installed from the backup", !RestorePlan.bringBackSystemApp(true, 0));
    check("an app that is installed is not brought back this way", !RestorePlan.bringBackSystemApp(true, FLAG_INSTALLED | FLAG_HAS_CODE));
    check("a package the phone does not know is installed from the backup", !RestorePlan.bringBackSystemApp(false, 0));
    check("not found wins over a system flag", !RestorePlan.bringBackSystemApp(false, RestorePlan.FLAG_SYSTEM));
    check("FLAG_SYSTEM is the platform's value", RestorePlan.FLAG_SYSTEM == 1);

    // runRestore asks the helper before the system-app branch
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    File f = null;
    for (int i = 0; d != null && i < 8 && f == null; i++, d = d.getParentFile()) {
      File c = new File(d, "src/com/bloatware/bingblop/MainActivity.java");
      if (c.isFile()) f = c;
    }
    check("MainActivity.java found", f != null);
    if (f != null) {
      String src = blank(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      int start = src.indexOf("MATCH_UNINSTALLED_PACKAGES | sigFlags");
      check("runRestore reads the package with MATCH_UNINSTALLED_PACKAGES", start > 0);
      String after = start > 0 ? src.substring(start, Math.min(src.length(), start + 1200)) : "";
      Matcher m = Pattern.compile("if\\s*\\(\\s*RestorePlan\\.bringBackSystemApp\\(\\s*sysInfo\\s*!=\\s*null\\s*,\\s*sysInfo\\s*==\\s*null\\s*\\|\\|\\s*sysInfo\\.applicationInfo\\s*==\\s*null\\s*\\?\\s*0\\s*:\\s*sysInfo\\.applicationInfo\\.flags\\s*\\)\\s*\\)\\s*\\{").matcher(after);
      check("the system-app branch of runRestore is taken only when RestorePlan.bringBackSystemApp says so", m.find());
      check("no other way into that branch (a bare 'if (sysInfo != null)')", !Pattern.compile("if\\s*\\(\\s*sysInfo\\s*!=\\s*null\\s*\\)").matcher(after).find());
    }
    checkDownloadMime();
    checkSameVersionConfirm();
    checkPermissionCount();
    System.out.println((fails == 0 ? "OK " : "FAILED ") + n + " checks, " + fails + " failed");
    System.exit(fails == 0 ? 0 : 1);
  }
}
