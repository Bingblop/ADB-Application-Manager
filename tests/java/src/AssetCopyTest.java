package com.bloatware.bingblop;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Copying the files the APK carries into the app's files (rish, rish_shizuku.dex, the fallback adb): a copy cut short is not kept, a new app
 * version brings its own copy (also over a read-only one), and nothing is left behind (full main review, MA1-3). extractAsset kept any non-empty
 * file for ever: a first launch ended in the middle of the copy left a broken rish_shizuku.dex until the app's data was cleared.
 */
public class AssetCopyTest {
  static int n, fails;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static AssetCopy.Source bytes(final byte[] b) {
    return new AssetCopy.Source() { public InputStream open() { return new ByteArrayInputStream(b); } };
  }

  /** An asset whose reading breaks after {@code after} bytes, as a copy does when the storage is full or the app is ended. */
  static AssetCopy.Source breaksAfter(final byte[] b, final int after) {
    return new AssetCopy.Source() { public InputStream open() {
      return new InputStream() {
        int i;
        public int read() throws IOException { if (i >= after) throw new IOException("No space left on device"); return i < b.length ? b[i++] & 0xff : -1; }
      };
    } };
  }

  static byte[] filled(int size, int seed) {
    byte[] b = new byte[size];
    for (int i = 0; i < size; i++) b[i] = (byte) (i * 31 + seed);
    return b;
  }

  static String leftovers(File dir) {
    StringBuilder s = new StringBuilder();
    File[] all = dir.listFiles();
    if (all != null) for (File f : all) if (f.getName().endsWith(".tmp")) s.append(f.getName()).append(' ');
    return s.toString();
  }

  public static void main(String[] a) throws Exception {
    File dir = Files.createTempDirectory("assetcopy").toFile();
    File dex = new File(dir, "rish_shizuku.dex");
    byte[] v1 = filled(90140, 1), v2 = filled(91000, 2);

    // a copy that breaks half way: nothing is kept that could pass for the asset, and the next start copies it whole
    check("a copy that breaks half way says so", !AssetCopy.copy(breaksAfter(v1, 16384), dex, "900"));
    check("...and leaves no file in the asset's place and no stamp", !dex.exists() && !AssetCopy.current(dex, "900"));
    check("...and no temporary file (" + leftovers(dir) + ")", leftovers(dir).isEmpty());
    check("the next start copies the whole asset", AssetCopy.copy(bytes(v1), dex, "900") && java.util.Arrays.equals(Files.readAllBytes(dex.toPath()), v1));
    check("...and it is now the current copy, with no temporary file left", AssetCopy.current(dex, "900") && leftovers(dir).isEmpty());

    // the same version again: no copy (the asset is not even opened)
    final int[] opened = { 0 };
    boolean same = AssetCopy.copy(new AssetCopy.Source() { public InputStream open() { opened[0]++; return new ByteArrayInputStream(v2); } }, dex, "900");
    check("the same app version does not copy again (the asset was not opened)", same && opened[0] == 0 && java.util.Arrays.equals(Files.readAllBytes(dex.toPath()), v1));

    // the copy the old code left behind: a non-empty, cut-short file with no stamp is replaced
    File rish = new File(dir, "rish");
    Files.write(rish.toPath(), "#!/system/bin/sh\nBASE".getBytes(StandardCharsets.UTF_8));
    byte[] script = "#!/system/bin/sh\nBASEDIR=$(dirname \"$0\")\nexec app_process ...\n".getBytes(StandardCharsets.UTF_8);
    check("a cut-short copy from before (no stamp) is replaced by the whole asset", AssetCopy.copy(bytes(script), rish, "900") && java.util.Arrays.equals(Files.readAllBytes(rish.toPath()), script));

    // a new app version: its asset replaces the old copy, also when the old copy is read-only (Android 14 wants the dex read-only)
    dex.setWritable(false, false);
    check("a new app version replaces the copy, read-only as it is", AssetCopy.copy(bytes(v2), dex, "901") && java.util.Arrays.equals(Files.readAllBytes(dex.toPath()), v2));
    check("...the new version's stamp is there and the old one is gone", AssetCopy.current(dex, "901") && !AssetCopy.stampOf(dex, "900").exists());
    check("...the other file's stamp is left alone", AssetCopy.current(rish, "900"));

    // a new version whose copy fails keeps the old copy in place (better than none) and copies again next time
    check("a new version whose copy breaks says so", !AssetCopy.copy(breaksAfter(v1, 100), dex, "902"));
    check("...and keeps the copy it had, not current for the new version", java.util.Arrays.equals(Files.readAllBytes(dex.toPath()), v2) && !AssetCopy.current(dex, "902"));
    check("...with no temporary file left (" + leftovers(dir) + ")", leftovers(dir).isEmpty());

    // an asset the APK does not carry: nothing is made
    File none = new File(dir, "uninstall_runner_900.jar");
    check("an asset that is not there makes no file", !AssetCopy.copy(new AssetCopy.Source() { public InputStream open() throws IOException { throw new java.io.FileNotFoundException("uninstall_runner.jar"); } }, none, "900") && !none.exists());

    for (File f : dir.listFiles()) { f.setWritable(true, false); f.delete(); }
    dir.delete();
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
