package com.bloatware.bingblop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Installer's work folders (MA1-1): a package read while another installs gets its own folder; the running install keeps its files and
 * its own data files (OBB), and its end removes only what is still its own. Run against real folders, plus a scan of how MainActivity uses it.
 */
public class InstallerStageTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static File read(InstallerStage<String> st, String name, List<File> deleted) {
    List<File> stale = new ArrayList<File>();
    File d = st.beginRead(name, stale);
    for (File f : stale) { del(f); deleted.add(f); }
    d.mkdirs();
    return d;
  }
  static void del(File f) { File[] k = f.listFiles(); if (k != null) for (File x : k) del(x); f.delete(); }
  static File touch(File d, String name) throws Exception { File f = new File(d, name); Files.write(f.toPath(), new byte[] { 1 }); return f; }

  static File repo() {
    for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null; d = d.getParentFile())
      if (new File(d, "src/com/bloatware/bingblop/MainActivity.java").isFile()) return d;
    throw new IllegalStateException("repository not found");
  }
  /** The text of the method that starts at {@code head}: from the head to its matching closing brace. */
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
    File root = Files.createTempDirectory("instage").toFile().getCanonicalFile();
    try {
      touch(root, "source.zip");                                  // a leftover of an older version (files straight in the folder)
      InstallerStage<String> st = new InstallerStage<String>(root);
      List<File> deleted = new ArrayList<File>();

      // Package A (an .xapk with OBB data) is read and its install starts.
      File a = read(st, "A.xapk", deleted);
      check("a leftover in the cache folder is cleared by the first read", !new File(root, "source.zip").exists());
      File aBase = touch(a, "0__base.apk"), aSplit = touch(a, "1__split.apk"), aZip = touch(a, "source.zip");
      st.loaded(st.generation(), Arrays.asList("Android/obb/com.a/main.1.com.a.obb"), aZip);
      InstallerStage.Snapshot<String> sa = st.beginInstall(a);
      check("an install of the package loaded now gets its snapshot", sa != null && sa.dir.equals(a));
      check("the snapshot has A's data files, archive and name", sa != null && sa.extras.equals(Arrays.asList("Android/obb/com.a/main.1.com.a.obb")) && aZip.equals(sa.zip) && "A.xapk".equals(sa.sourceName));

      // While A installs, package B (a plain APK) is read.
      deleted.clear();
      File b = read(st, "B.apk", deleted);
      check("B gets a folder of its own", !b.equals(a));
      check("reading B does not delete A's folder while A installs", !deleted.contains(a) && aBase.exists() && aSplit.exists() && aZip.exists());
      check("A is in use", st.inUse(a));
      File bBase = touch(b, "base.apk");
      st.loaded(st.generation(), new ArrayList<String>(), null);
      check("A's snapshot still has A's data files after B was read (not B's none)", sa.extras.size() == 1 && sa.extras.get(0).contains("com.a") && aZip.equals(sa.zip));
      check("A's files can no longer start another install (the package shown is B)", st.beginInstall(a) == null);

      // A ends: only A's folder goes; B stays loaded and can be installed.
      File gone = st.endInstall(sa, true);
      check("the end of A's install hands back A's folder, not B's", a.equals(gone));
      if (gone != null) del(gone);
      check("B's files are still there after A's install ended", bBase.exists());
      InstallerStage.Snapshot<String> sb = st.beginInstall(b);
      check("B can still be installed after A ended", sb != null && sb.extras.isEmpty() && sb.zip == null && "B.apk".equals(sb.sourceName));
      check("B's install ends and B's folder is used up (pick it again to install it again)", b.equals(st.endInstall(sb, true)) && st.beginInstall(b) == null);

      // The other way round: B is an .xapk with its own OBB data, read while A installs: A's snapshot keeps A's data.
      File a2 = read(st, "A.xapk", deleted);
      st.loaded(st.generation(), Arrays.asList("Android/obb/com.a/main.1.com.a.obb"), touch(a2, "source.zip"));
      InstallerStage.Snapshot<String> sa2 = st.beginInstall(a2);
      File b2 = read(st, "B.xapk", deleted);
      File b2Zip = touch(b2, "source.zip");
      st.loaded(st.generation(), Arrays.asList("Android/obb/com.b/main.7.com.b.obb"), b2Zip);
      check("A's install copies A's data files, not B's", sa2.extras.size() == 1 && sa2.extras.get(0).contains("com.a") && !b2Zip.equals(sa2.zip));
      check("a failed A (kept for another try) after B was read hands back A's folder: nobody uses it now", a2.equals(st.endInstall(sa2, false)));
      InstallerStage.Snapshot<String> sb2 = st.beginInstall(b2);
      check("B's snapshot has B's data files", sb2 != null && sb2.extras.get(0).contains("com.b") && b2Zip.equals(sb2.zip));

      // Asked whether to uninstall first, or after an error: the files stay for the next try.
      check("an install that is not finished keeps its files", st.endInstall(sb2, false) == null);
      InstallerStage.Snapshot<String> again = st.beginInstall(b2);
      check("and the same package can be installed again", again != null && again.extras.equals(sb2.extras));
      check("two ends of one install do not free a folder twice", b2.equals(st.endInstall(again, true)) && st.endInstall(again, true) == null);
      check("an end without a snapshot does nothing", st.endInstall(null, true) == null);

      // A stale answer: the data files of an older read never replace those of the package loaded now.
      File c = read(st, "C.xapk", deleted);
      long cGen = st.generation();
      st.loaded(cGen - 1, Arrays.asList("old"), null);
      InstallerStage.Snapshot<String> sc = st.beginInstall(c);
      check("loaded() for an older read is ignored", sc != null && sc.extras.isEmpty());
      st.endInstall(sc, false);
      // MA1-3: the snapshot carries the file its package was read from; a later read does not change an install's file
      List<File> dl = new ArrayList<File>();
      File e = st.beginRead("E.apk", "/storage/emulated/0/Download/E.apk", dl);
      InstallerStage.Snapshot<String> se = st.beginInstall(e);
      st.beginRead("F.apk", "content://x/F", dl);
      check("an install's snapshot keeps the file it was read from, whatever is read next", se != null && "/storage/emulated/0/Download/E.apk".equals(se.sourceRef));
      st.endInstall(se, false);
    } finally {
      del(root);
    }

    // How MainActivity uses it.
    String m = new String(Files.readAllBytes(new File(repo(), "src/com/bloatware/bingblop/MainActivity.java").toPath()), StandardCharsets.UTF_8);
    String inspect = body(m, "private JSONObject inspectInstallSourceImpl(");
    String run = body(m, "private void runInstallSelected(");
    String finish = body(m, "private void finishInstallImpl(");
    String copy = body(m, "private String copyInstallExtras(");
    check("reading a package no longer empties the whole installer folder", !m.contains("clearInstallerWorkDir") && inspect.contains("beginRead("));
    check("the install takes its snapshot before it installs anything", run.contains("beginInstall(") && run.indexOf("beginInstall(") < run.indexOf("installApksOptions("));
    check("the install hands its snapshot to the end (and to the no-privilege install)", run.contains("finishInstall(res, opts, mode, snap)") && run.contains("installNoPrivilege(files, opts, snap)"));
    check("the end copies the snapshot's data files, not the package loaded now", finish.contains("copyInstallExtras(mode, snap)") && copy.contains("snap.extras") && copy.contains("snap.zip")
        && !m.contains("installExtras") && !m.contains("installSourceZip"));
    check("the end removes only the install's own folder, and only a successful install uses it up (a failed or cancelled one keeps its files for another try)",
        finish.contains("endInstallStage(snap, res.optBoolean(\"ok\", false))") && !finish.contains("endInstallStage(snap, true)"));
    String np = body(m, "private void installNoPrivilege(");
    String fin = body(m, "private void finishInstall(");
    check("INS-3: the no-privilege install's result receiver lives on the application (a re-created window keeps the result), and its end runs even after the window's executor stopped",
        np.contains("appCtx.registerReceiver(rcv, filter") && !np.matches("(?s).*[^.]registerReceiver\\(rcv.*") && fin.contains("RejectedExecutionException"));
    check("delete after install removes only the snapshot's own source file, never a page-named path or the app's private data",
        inspect.contains("beginRead(sourceNameOf(ref), ref, stale)") && finish.contains("snap.sourceRef") && !finish.contains("optString(\"sourceRef\")") && finish.contains("!isPrivateData(new File(ref))"));

    System.out.println(fails == 0 ? "PASS InstallerStageTest: " + n + " checks" : "FAILED InstallerStageTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
