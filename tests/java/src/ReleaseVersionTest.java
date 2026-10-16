package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * tools/check-release-version.sh, which release.yml runs before it builds (review BLD-5): a tag whose version is not the app's version in
 * AndroidManifest.xml and CHANGELOG.md used to be built and published anyway, under a version the APKs do not carry. The real script is run
 * against small repository folders made here, then against this repository, and release.yml is scanned for the step that runs it.
 */
public class ReleaseVersionTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + (detail == null ? "" : "\n---\n" + detail + "\n---")); } }

  static File repo() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) if (new File(d, "build.sh").isFile() && new File(d, "AndroidManifest.xml").isFile()) return d;
    return null;
  }

  static boolean onPath(String tool) {
    String p = System.getenv("PATH");
    if (p != null) for (String d : p.split(File.pathSeparator)) if (new File(d, tool).canExecute()) return true;
    return false;
  }

  static void write(File f, String text) throws Exception {
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  static String manifest(String versionCode, String versionName) {
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    package=\"com.bloatware.bingblop\"\n"
        + (versionCode == null ? "" : "    android:versionCode=\"" + versionCode + "\"\n")
        + (versionName == null ? "" : "    android:versionName=\"" + versionName + "\"") + ">\n    <uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"34\" />\n</manifest>\n";
  }

  static final String LOG = "# Changelog\n\n## v7.14.0-Pro (versionCode 859)\n\n- New things.\n\n## v7.13.4-Pro (versionCode 858)\n\n- Older things.\n";

  static int status;

  /** Runs the script on a folder holding the given manifest and changelog; returns its output (stdout and stderr). */
  static String run(File script, File dir, String version, String manifest, String changelog) throws Exception {
    deleteTree(dir);
    dir.mkdirs();
    if (manifest != null) write(new File(dir, "AndroidManifest.xml"), manifest);
    if (changelog != null) write(new File(dir, "CHANGELOG.md"), changelog);
    return run(script, version, dir.getPath());
  }

  static String run(File script, String... args) throws Exception {
    String[] cmd = new String[args.length + 2];
    cmd[0] = "bash";
    cmd[1] = script.getPath();
    System.arraycopy(args, 0, cmd, 2, args.length);
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    byte[] out = p.getInputStream().readAllBytes();
    if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); status = -1; }
    else status = p.exitValue();
    return new String(out, StandardCharsets.UTF_8);
  }

  static void deleteTree(File f) {
    File[] c = f.listFiles();
    if (c != null) for (File x : c) deleteTree(x);
    f.delete();
  }

  public static void main(String[] args) throws Exception {
    File repo = repo();
    if (repo == null) { System.out.println("SKIP the repository (build.sh, AndroidManifest.xml) not found"); System.out.println("PASS ReleaseVersionTest: 0 checks"); return; }
    if (!onPath("bash")) { System.out.println("SKIP needs bash on PATH"); System.out.println("PASS ReleaseVersionTest: 0 checks"); return; }
    File script = new File(repo, "tools/check-release-version.sh");
    if (!script.isFile()) { System.out.println("FAIL tools/check-release-version.sh is missing (release.yml runs it)"); System.out.println("FAILED ReleaseVersionTest: 1 of 1"); System.exit(1); }
    File work = Files.createTempDirectory("relver").toFile();
    try {
      String out = run(script, new File(work, "same"), "7.14.0", manifest("859", "7.14.0-Pro"), LOG);
      check("the tag's version matches the manifest (with -Pro) and the changelog: passes", status == 0, "exit " + status + "\n" + out);
      check("... and says what it read", out.contains("versionName 7.14.0-Pro") && out.contains("## v7.14.0-Pro (versionCode 859)"), out);

      out = run(script, new File(work, "plain"), "7.14.0", manifest("859", "7.14.0"), "## v7.14.0 (versionCode 859)\n");
      check("a manifest and a heading without -Pro also match", status == 0, "exit " + status + "\n" + out);

      out = run(script, new File(work, "crlf"), "7.14.0", manifest("859", "7.14.0-Pro"), LOG.replace("\n", "\r\n"));
      check("a changelog with Windows line ends is read the same", status == 0, "exit " + status + "\n" + out);

      out = run(script, new File(work, "newer"), "7.14.1", manifest("859", "7.14.0-Pro"), LOG);
      check("a tag ahead of the manifest (the release pull request was not merged, or the tag is on the wrong commit) fails", status == 1, "exit " + status + "\n" + out);
      check("... naming both versions", out.contains("versionName 7.14.0-Pro, not 7.14.1"), out);
      check("... and the missing changelog heading", out.contains("no heading \"## v7.14.1\""), out);

      out = run(script, new File(work, "older"), "7.13.4", manifest("859", "7.14.0-Pro"), LOG);
      check("a tag of an older version on a newer commit fails, although the changelog has that version", status == 1 && out.contains("not 7.13.4"), "exit " + status + "\n" + out);
      check("... and its heading's versionCode is compared with the manifest's", out.contains("names versionCode 858, AndroidManifest.xml has 859"), out);

      out = run(script, new File(work, "nolog"), "7.14.1", manifest("860", "7.14.1-Pro"), LOG);
      check("a manifest bumped without a changelog section fails", status == 1 && out.contains("no heading \"## v7.14.1\"") && out.contains("ok   AndroidManifest.xml"), "exit " + status + "\n" + out);

      out = run(script, new File(work, "code"), "7.14.1", manifest("859", "7.14.1-Pro"), "## v7.14.1-Pro (versionCode 860)\n" + LOG);
      check("a versionCode the release forgot to raise in the manifest fails", status == 1 && out.contains("names versionCode 860, AndroidManifest.xml has 859"), "exit " + status + "\n" + out);

      out = run(script, new File(work, "prefix"), "7.14", manifest("859", "7.14-Pro"), LOG);
      check("a heading that only starts with the version (## v7.14.0 for 7.14) is not taken for it", status == 1 && out.contains("no heading \"## v7.14\""), "exit " + status + "\n" + out);

      out = run(script, new File(work, "suffix"), "7.14.0", manifest("859", "7.14.0-Pro"), "## v7.14.0-Pro-beta (versionCode 859)\n");
      check("a heading for another suffix of the version is not taken for it", status == 1, "exit " + status + "\n" + out);

      out = run(script, new File(work, "noname"), "7.14.0", manifest("859", null), LOG);
      check("a manifest without versionName fails in words", status == 1 && out.contains("has no android:versionName"), "exit " + status + "\n" + out);

      out = run(script, new File(work, "nocodeinlog"), "7.14.0", manifest("859", "7.14.0-Pro"), "## v7.14.0-Pro\n\n- Things.\n");
      check("a heading without (versionCode N) fails in words", status == 1 && out.contains("does not say (versionCode N)"), "exit " + status + "\n" + out);

      out = run(script, new File(work, "nofiles"), "7.14.0", null, null);
      check("a folder without AndroidManifest.xml fails", status == 1 && out.contains("not found"), "exit " + status + "\n" + out);

      out = run(script, new File(work, "odd"), "7.14.0;x", manifest("859", "7.14.0-Pro"), LOG);
      check("a version with other characters is a usage error (exit 2)", status == 2, "exit " + status + "\n" + out);
      out = run(script);
      check("no version is a usage error (exit 2)", status == 2 && out.contains("usage:"), "exit " + status + "\n" + out);

      // This repository: the release pull request changes the manifest and the changelog together, so this commit's own version passes.
      String text = new String(Files.readAllBytes(new File(repo, "AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
      Matcher m = Pattern.compile("android:versionName=\"([^\"]*)\"").matcher(text);
      if (m.find()) {
        String v = m.group(1).replaceFirst("-Pro$", "");
        out = run(script, v, repo.getPath());
        check("this repository's AndroidManifest.xml and CHANGELOG.md agree on version " + v, status == 0, "exit " + status + "\n" + out);
      } else check("this repository's AndroidManifest.xml has a versionName", false, null);

      // release.yml runs the check before the build, and only a throwaway-key test run may go on after it fails.
      File yml = new File(repo, ".github/workflows/release.yml");
      if (yml.isFile()) {
        String wf = new String(Files.readAllBytes(yml.toPath()), StandardCharsets.UTF_8);
        int at = wf.indexOf("bash tools/check-release-version.sh \"$V\"");
        int build = wf.indexOf("bash build.sh");
        check("release.yml runs tools/check-release-version.sh on the version", at >= 0, null);
        check("... before it builds anything", at >= 0 && build > at, null);
        String step = at < 0 ? "" : wf.substring(wf.lastIndexOf("- name:", at), build < 0 ? wf.length() : wf.lastIndexOf("- name:", build));
        check("... and stops the run when it fails, unless this is a throwaway-key test run", step.contains("steps.key.outputs.throwaway") && step.contains("::error::") && step.contains("exit 1"), step);
      }
    } finally {
      deleteTree(work);
    }

    System.out.println(fails == 0 ? "PASS ReleaseVersionTest: " + n + " checks" : "FAILED ReleaseVersionTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
