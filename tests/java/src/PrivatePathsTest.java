package com.bloatware.bingblop;

import java.io.File;
import java.nio.file.Files;

/** The app's own data folder is not handed out, except the parts the app fills for the user; links, "..", and look-alike folder names do not get around it. */
public class PrivatePathsTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  /** The link count as the JDK reports it on Linux (the app uses android.system.Os.stat for the same). */
  static final PrivatePaths.Links L = new PrivatePaths.Links() {
    public long count(File f) throws Exception { return ((Number) Files.getAttribute(f.toPath(), "unix:nlink")).longValue(); }
  };
  static PrivatePaths.Root[] R(File... dirs) {
    PrivatePaths.Root[] r = new PrivatePaths.Root[dirs.length];
    for (int i = 0; i < dirs.length; i++) r[i] = new PrivatePaths.Root(dirs[i]);
    return r;
  }
  static boolean B(File f, File data, PrivatePaths.Root... allowed) { return PrivatePaths.blocked(f, data, L, allowed); }
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
    PrivatePaths.Root[] ok = R(logs, morphe, new File(cache, "updates"), new File(cache, "share"));
    File c2 = touch(new File(cache, "updates/app.apk"));
    File backup = touch(new File(cache, "backup_data_123.tar"));
    File restore = touch(new File(cache, "restore_123/data.tar"));
    File rootSh = touch(new File(cache, "root_1.sh"));

    is("shared_prefs is protected", B(prefs, data, ok));
    is("the adb key is protected", B(key, data, ok));
    is("the data folder itself is protected", B(data, data, ok));
    is("an exportable cache folder is not (share)", !B(c1, data, ok));
    is("an exportable cache folder is not (updates)", !B(c2, data, ok));
    is("a full-data backup tar in the cache is protected", B(backup, data, ok));
    is("a restore work folder in the cache is protected", B(restore, data, ok));
    is("a root script in the cache is protected", B(rootSh, data, ok));
    is("a look-alike cache folder (updates2) is protected", B(touch(new File(cache, "updates2/x.apk")), data, ok));
    is("logs are not", !B(l1, data, ok));
    is("patched APKs are not", !B(m1, data, ok));
    // the Morphe folder holds the signing key next to the patched APKs: only the subfolders are allowed, as the app lists them
    PrivatePaths.Root[] okApp = PrivatePaths.exportable(new File(data, "files"), cache);      // the very list the app uses
    File mk = touch(new File(morphe, "morphe.keystore")), mj = touch(new File(morphe, "morphe_key.json")), mv = touch(new File(morphe, "vt_state.json"));
    File mp = touch(new File(morphe, "patched/abc/app-patched.apk")), mh = touch(new File(morphe, "helper/download.apk"));
    is("the Morphe signing key is protected", B(mk, data, okApp));
    is("the Morphe key info (with the password) is protected", B(mj, data, okApp));
    is("the Morphe VirusTotal state is protected", B(mv, data, okApp));
    is("a patched APK is not", !B(mp, data, okApp));
    File meta = touch(new File(morphe, "patched/abc/meta.json")), plog = touch(new File(morphe, "patched/abc/log.txt"));
    is("the run's meta.json next to a patched APK is protected", B(meta, data, okApp));
    is("the run's log.txt next to a patched APK is protected", B(plog, data, okApp));
    File storeCat = touch(new File(cache, "store/f-droid.json")), appLog = touch(new File(data, "files/logs/recording.log"));
    is("the store catalog cache is protected", B(storeCat, data, okApp));
    is("the app's own logs are protected", B(appLog, data, okApp));
    is("a Helper download of a split bundle is not", !B(touch(new File(morphe, "helper/app.apkm")), data, okApp));
    is("an unrelated file in the Helper folder is protected", B(touch(new File(morphe, "helper/notes.txt")), data, okApp));
    is("an extension check does not follow the case", !B(touch(new File(morphe, "patched/abc/APP.APK")), data, okApp));
    is("the cache's backup tar and restore folder are protected with the app's own list",
        B(backup, data, okApp) && B(restore, data, okApp) && B(rootSh, data, okApp));
    is("a picked file in the app's cache folders is not", !B(touch(new File(cache, "saf_stage/pick.apk")), data, okApp) && !B(touch(new File(cache, "cd_pick/x.bin")), data, okApp));
    is("a Helper download is not", !B(mh, data, okApp));
    is("with the whole Morphe folder allowed the key would leak (why only subfolders)", !B(mk, data, R(morphe)));
    is("a file outside the data folder is not", !B(outside, data, ok));
    is("a look-alike folder (com.example.app2) is not inside com.example.app", !B(sibling, data, ok));

    is("'..' out of the cache into shared_prefs is protected", B(new File(cache, "../shared_prefs/prefs.xml"), data, ok));
    is("'..' out of the cache into the sdcard folder is not", !B(new File(cache, "../../../sdcard/Download/app.apk"), data, ok));
    is("a non-canonical path to the cache is not", !B(new File(data, "files/../cache/share/a/app.apk"), data, ok));

    // a link inside the cache that points at the preferences
    File link = new File(cache, "share/innocent.apk");
    boolean linked;
    try { Files.createSymbolicLink(link.toPath(), prefs.toPath()); linked = true; } catch (Exception e) { linked = false; System.out.println("   (symbolic links not available here; link checks skipped)"); }
    if (linked) {
      is("a symbolic link in the cache to shared_prefs is protected", B(link, data, ok));
      File dirLink = new File(root, "sdcard/Download/shortcut");
      Files.createSymbolicLink(dirLink.toPath(), new File(data, "shared_prefs").toPath());
      is("a link outside the data folder to its shared_prefs is protected", B(new File(dirLink, "prefs.xml"), data, ok));
    }

    // a hard link made in an allowed folder to a private file: same file, allowed-looking path
    File hard = new File(cache, "share/leak.apk");
    boolean hardOk;
    try { Files.createLink(hard.toPath(), prefs.toPath()); hardOk = true; } catch (Exception e) { hardOk = false; System.out.println("   (hard links not available here; hard-link checks skipped)"); }
    if (hardOk) {
      is("a hard link in an allowed folder to shared_prefs is protected", B(hard, data, ok));
      is("the original private file is still protected", B(prefs, data, ok));
    }
    is("a file with one name in an allowed folder is not protected", !B(c1, data, ok));
    is("a link count that cannot be read counts as protected",
        PrivatePaths.blocked(c1, data, new PrivatePaths.Links() { public long count(File f) throws Exception { throw new java.io.IOException("no stat"); } }, ok));
    is("no way to read link counts counts as protected", PrivatePaths.blocked(c1, data, null, ok));
    is("a directory in an allowed folder needs no link count", !PrivatePaths.blocked(new File(cache, "share/a"), data, null, ok));

    // an allowed folder replaced by a link to a private folder is no longer an allowed folder
    File swapped = new File(cache, "swapped");
    File swapTarget = new File(data, "shared_prefs");
    touch(new File(swapTarget, "solo.xml"));       // one name only: the link count alone must not be what protects it
    boolean swapOk;
    try { Files.createSymbolicLink(swapped.toPath(), swapTarget.toPath()); swapOk = true; } catch (Exception e) { swapOk = false; }
    if (swapOk) {
      PrivatePaths.Root[] ok2 = R(logs, morphe, swapped);
      is("a file under an allowed folder that is a link to shared_prefs is protected", B(new File(swapped, "solo.xml"), data, ok2));
      is("the real shared_prefs path is protected as well", B(new File(swapTarget, "solo.xml"), data, ok2));
    }
    File outsideRoot = new File(root, "sdcard/Download");
    is("an allowed folder outside the data folder is ignored (nothing there to protect)", !B(outside, data, R(outsideRoot)));
    is("an allowed folder that is the data folder itself is ignored", B(prefs, data, R(data)));

    // deleteBackup: only a reference the app listed (as stored) counts
    java.util.List<String> listed = java.util.Arrays.asList(new File(root, "sdcard/Backups/a.adbbackup").getPath(), "content://com.android.providers.media/doc/42");
    is("a listed file reference may be deleted", PrivatePaths.listedRef(listed.get(0), listed));
    is("a listed content reference may be deleted", PrivatePaths.listedRef("content://com.android.providers.media/doc/42", listed));
    is("an unknown file reference may not", !PrivatePaths.listedRef(prefs.getPath(), listed));
    is("an unknown content reference may not", !PrivatePaths.listedRef("content://com.android.providers.media/doc/43", listed));
    is("a path that leads to a listed file may not", !PrivatePaths.listedRef(new File(root, "sdcard/Backups/../Backups/a.adbbackup").getPath(), listed));
    is("a different case or a trailing space may not", !PrivatePaths.listedRef(listed.get(0).toUpperCase(), listed) && !PrivatePaths.listedRef(listed.get(0) + " ", listed));
    is("null, empty and no list may not", !PrivatePaths.listedRef(null, listed) && !PrivatePaths.listedRef("", listed) && !PrivatePaths.listedRef(listed.get(0), null)
        && !PrivatePaths.listedRef("", java.util.Arrays.asList("")));

    is("null file or null data folder is protected", B(null, data, ok) && B(prefs, null, ok));
    is("no allowed folders: everything inside is protected", B(c1, data));

    // ---- adversarial cases (a second look, from the attacker's side) ----
    PrivatePaths.Root[] okA = PrivatePaths.exportable(new File(data, "files"), cache);
    File shared = new File(cache, "share"), upd = new File(cache, "updates");
    // a chain of links through two allowed folders ends in a private file
    File chain1 = new File(upd, "hop1"), chain2 = new File(shared, "a/hop2");
    boolean chained;
    try { chain2.getParentFile().mkdirs(); Files.createSymbolicLink(chain2.toPath(), prefs.toPath()); Files.createSymbolicLink(chain1.toPath(), chain2.toPath()); chained = true; } catch (Exception e) { chained = false; }
    if (chained) is("a chain of links through two allowed folders to a private file is protected", B(chain1, data, okA) && B(chain2, data, okA));
    // a link to a private FOLDER, and a file below it
    File dl = new File(upd, "folderlink");
    boolean dlOk;
    try { Files.createSymbolicLink(dl.toPath(), new File(data, "shared_prefs").toPath()); dlOk = true; } catch (Exception e) { dlOk = false; }
    if (dlOk) is("a file reached through a link to a private folder is protected", B(new File(dl, "prefs.xml"), data, okA) && B(dl, data, okA));
    // the same private file through other spellings
    is("a trailing slash, a dot and doubled slashes do not hide it", B(new File(prefs.getPath() + "/"), data, okA) && B(new File(prefs.getParent() + "/./" + prefs.getName()), data, okA)
        && B(new File(prefs.getParent().replace("/", "//") + "//" + prefs.getName()), data, okA));
    is("'..' written with a long detour is still protected", B(new File(upd, "x/../../shared_prefs/../shared_prefs/prefs.xml"), data, okA));
    File proc = new File("/proc/self/root" + prefs.getPath());
    if (proc.exists()) is("the same file through /proc/self/root is protected", B(proc, data, okA));
    // a link loop inside an allowed folder neither hangs nor leaks
    File loopA = new File(upd, "loopA"), loopB = new File(upd, "loopB");
    boolean loopOk;
    try { Files.createSymbolicLink(loopA.toPath(), loopB.toPath()); Files.createSymbolicLink(loopB.toPath(), loopA.toPath()); loopOk = true; } catch (Exception e) { loopOk = false; }
    if (loopOk) { boolean r; try { r = B(loopA, data, okA); } catch (Throwable t) { r = false; } is("a link loop in an allowed folder fails closed (protected), without an exception", r); }
    File dangling = new File(upd, "dangling");
    boolean dangOk;
    try { Files.createSymbolicLink(dangling.toPath(), new File(data, "shared_prefs/not-there.xml").toPath()); dangOk = true; } catch (Exception e) { dangOk = false; }
    if (dangOk) is("a link whose target is missing is protected too", B(dangling, data, okA));
    // an allowed folder that was replaced by a link to another allowed folder: its files count as the other folder's
    // an allowed folder replaced by a link to a private folder: nothing under it counts
    File swapRoot = new File(cache, "installer");
    boolean swapped2;
    try { Files.createSymbolicLink(swapRoot.toPath(), new File(data, "shared_prefs").toPath()); swapped2 = true; } catch (Exception e) { swapped2 = false; }
    if (swapped2) is("an allowed folder replaced by a link to shared_prefs protects what is behind it", B(new File(swapRoot, "prefs.xml"), data, okA));
    // a hard link to the Morphe signing key, named like an APK, in the Helper folder
    File hk = new File(morphe, "helper/looks-like.apk");
    boolean hkOk;
    try { hk.getParentFile().mkdirs(); Files.createLink(hk.toPath(), mk.toPath()); hkOk = true; } catch (Exception e) { hkOk = false; }
    if (hkOk) is("a hard link to the Morphe key named .apk in the Helper folder is protected", B(hk, data, okA));
    // a file name with line breaks or an unusual script is no different
    File odd = touch(new File(upd, "a\nb.apk"));
    is("an unusual file name in an allowed folder is not protected", !B(odd, data, okA));
    File oddPriv = touch(new File(data, "shared_prefs/a\nb.xml"));
    is("an unusual file name in a private folder is protected", B(oddPriv, data, okA));
    // a data folder given through a link (as /data/data/<pkg> is on a phone)
    File dataLink = new File(root, "data/link-to-app");
    boolean dlk;
    try { Files.createSymbolicLink(dataLink.toPath(), data.toPath()); dlk = true; } catch (Exception e) { dlk = false; }
    if (dlk) {
      is("the data folder given through a link still protects shared_prefs", B(new File(dataLink, "shared_prefs/prefs.xml"), dataLink, PrivatePaths.exportable(new File(dataLink, "files"), new File(dataLink, "cache"))));
      is("and still lets the cache folders out", !B(new File(dataLink, "cache/updates/app.apk"), dataLink, PrivatePaths.exportable(new File(dataLink, "files"), new File(dataLink, "cache"))));
      is("a path through the real folder is protected when the data folder is given as the link", B(prefs, dataLink, PrivatePaths.exportable(new File(dataLink, "files"), new File(dataLink, "cache"))));
    }
    // a relative path and an empty one
    is("an empty path means the working folder, which is not the data folder, so it is not protected (and opens nothing)", !B(new File(""), data, okA));
    is("a path with a NUL byte is protected or refused (never taken for the cache)", B(new File(upd.getPath() + "\u0000/../../shared_prefs/prefs.xml"), data, okA));

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
