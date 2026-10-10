package com.bloatware.bingblop;

import java.io.File;
import java.nio.file.Files;

/** The app's own data folder is not handed out, except the parts the app fills for the user; links, "..", and look-alike folder names do not get around it. */
public class PrivatePathsTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static File touch(File f) throws Exception { f.getParentFile().mkdirs(); Files.write(f.toPath(), new byte[]{1}); return f; }

  public static void main(String[] a) throws Exception {
    File root = Files.createTempDirectory("privpaths").toFile();
    File data = new File(root, "data/com.example.app");
    File cache = new File(data, "cache"), logs = new File(data, "files/logs"), morphe = new File(data, "files/morphe");
    File prefs = touch(new File(data, "shared_prefs/prefs.xml"));
    File key = touch(new File(data, "files/adb_home/.android/adbkey"));
    File c1 = touch(new File(cache, "share/a/app.apk"));
    File l1 = touch(new File(logs, "log.txt"));
    File m1 = touch(new File(morphe, "patched.apk"));
    File outside = touch(new File(root, "sdcard/Download/app.apk"));
    File sibling = touch(new File(root, "data/com.example.app2/shared_prefs/x.xml"));
    File[] ok = { cache, logs, morphe };

    is("shared_prefs is protected", PrivatePaths.blocked(prefs, data, ok));
    is("the adb key is protected", PrivatePaths.blocked(key, data, ok));
    is("the data folder itself is protected", PrivatePaths.blocked(data, data, ok));
    is("the cache is not", !PrivatePaths.blocked(c1, data, ok));
    is("logs are not", !PrivatePaths.blocked(l1, data, ok));
    is("patched APKs are not", !PrivatePaths.blocked(m1, data, ok));
    is("a file outside the data folder is not", !PrivatePaths.blocked(outside, data, ok));
    is("a look-alike folder (com.example.app2) is not inside com.example.app", !PrivatePaths.blocked(sibling, data, ok));

    is("'..' out of the cache into shared_prefs is protected", PrivatePaths.blocked(new File(cache, "../shared_prefs/prefs.xml"), data, ok));
    is("'..' out of the cache into the sdcard folder is not", !PrivatePaths.blocked(new File(cache, "../../../sdcard/Download/app.apk"), data, ok));
    is("a non-canonical path to the cache is not", !PrivatePaths.blocked(new File(data, "files/../cache/share/a/app.apk"), data, ok));

    // a link inside the cache that points at the preferences
    File link = new File(cache, "share/innocent.apk");
    boolean linked;
    try { Files.createSymbolicLink(link.toPath(), prefs.toPath()); linked = true; } catch (Exception e) { linked = false; System.out.println("   (symbolic links not available here; link checks skipped)"); }
    if (linked) {
      is("a symbolic link in the cache to shared_prefs is protected", PrivatePaths.blocked(link, data, ok));
      File dirLink = new File(root, "sdcard/Download/shortcut");
      Files.createSymbolicLink(dirLink.toPath(), new File(data, "shared_prefs").toPath());
      is("a link outside the data folder to its shared_prefs is protected", PrivatePaths.blocked(new File(dirLink, "prefs.xml"), data, ok));
    }

    is("null file or null data folder is protected", PrivatePaths.blocked(null, data, ok) && PrivatePaths.blocked(prefs, null, ok));
    is("no allowed folders: everything inside is protected", PrivatePaths.blocked(c1, data));

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
