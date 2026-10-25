import com.bloatware.bingblop.DeviceLink;
import com.bloatware.bingblop.SplitPick;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DeviceLinkTest {
  static int n, fails;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static File zip(File dir, String name, String... entries) throws Exception {
    File f = new File(dir, name);
    ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f));
    for (String e : entries) { z.putNextEntry(new ZipEntry(e)); z.write(("data of " + e).getBytes("UTF-8")); z.closeEntry(); }
    z.close();
    return f;
  }

  static class Fake implements DeviceLink.Adb {
    final List<List<String>> calls = new ArrayList<List<String>>();
    String installAnswer = "Performing Streamed Install\nSuccess";
    public String run(List<String> args, int timeoutMs) {
      calls.add(new ArrayList<String>(args));
      if (args.get(0).startsWith("install")) return installAnswer;
      if (args.get(0).equals("push")) return "1 file pushed, 0 skipped.";
      return "";
    }
    String names(int call) { StringBuilder sb = new StringBuilder(); for (String s : calls.get(call)) sb.append(new File(s).getName()).append(' '); return sb.toString().trim(); }
  }

  static JSONArray items(JSONObject... o) { JSONArray a = new JSONArray(); for (JSONObject x : o) a.put(x); return a; }

  public static void main(String[] args) throws Exception {
    File tmp = Files.createTempDirectory("dl").toFile();
    // #190: every file an install would read, so the bridge can refuse the app's private ones first
    check("localFiles names each item's archive or its paths", DeviceLink.localFiles(new org.json.JSONArray("[{\"name\":\"a\",\"archive\":\"/x/a.apks\"},{\"name\":\"b\",\"paths\":[\"/x/b1.apk\",\"/x/b2.apk\"]}]")).equals(java.util.Arrays.asList("/x/a.apks", "/x/b1.apk", "/x/b2.apk")));
    // old picks: a new pick clears folders over an hour old, but not one whose file is still in the Send queue or being pushed (#189)
    {
      File root = Files.createTempDirectory("cdpick").toFile();
      long now = System.currentTimeMillis(), hour = 3600000L;
      File queued = new File(root, "a"), stale = new File(root, "b"), fresh = new File(root, "c");
      for (File d : new File[]{queued, stale, fresh}) { d.mkdirs(); new FileOutputStream(new File(d, "x.apk")).close(); }
      queued.setLastModified(now - 2 * hour); stale.setLastModified(now - 2 * hour); fresh.setLastModified(now - 60000L);
      List<File> gone = DeviceLink.clearOldPicks(root, now, hour, Arrays.asList(new File(queued, "x.apk").getAbsolutePath()));
      check("old picks: the folder of a file still in the queue stays, even after two hours", new File(queued, "x.apk").isFile());
      check("old picks: an old folder nobody uses goes", !stale.exists() && gone.size() == 1);
      check("old picks: a folder from a minute ago stays", new File(fresh, "x.apk").isFile());
      check("old picks: no folder and no list are fine", DeviceLink.clearOldPicks(new File(root, "none"), now, hour, null).isEmpty());
    }
    // serials and verdicts
    check("serials: an IP:port, a USB serial and an IPv6 address are fine", DeviceLink.validSerial("192.168.1.20:5555") && DeviceLink.validSerial("R5CT1234ABC") && DeviceLink.validSerial("[fe80::1]:5555") && DeviceLink.validSerial("adb-R5CT-xyz._adb-tls-connect._tcp"));
    check("serials: option-looking, spaced, shell or empty ones are refused", !DeviceLink.validSerial("-s") && !DeviceLink.validSerial("a b") && !DeviceLink.validSerial("a;b") && !DeviceLink.validSerial("") && !DeviceLink.validSerial(null) && !DeviceLink.validSerial("$(x)"));
    check("an install is done on Success only", DeviceLink.installed("Performing Streamed Install\nSuccess") && !DeviceLink.installed("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]") && !DeviceLink.installed("adb: failed to install x.apk: Failure [X]") && !DeviceLink.installed("") && !DeviceLink.installed(null) && !DeviceLink.installed("Success\n[Process timed out after 5ms]") && !DeviceLink.installed("error: device offline"));

    // the splits a device needs
    List<String> names = Arrays.asList("base.apk", "split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.hdpi.apk", "split_config.xxhdpi.apk", "split_config.en.apk", "split_config.de.apk", "split_config.fr.apk", "split_feature_video.apk");
    List<String> watch = SplitPick.pick(names, Arrays.asList("armeabi-v7a", "armeabi"), 280, "de");
    check("a 32-bit watch (280 dpi, German) gets the base, the feature, the v7a CPU split, hdpi (nearest to 280), German and English only: " + watch,
        watch.equals(Arrays.asList("base.apk", "split_config.armeabi_v7a.apk", "split_config.hdpi.apk", "split_config.en.apk", "split_config.de.apk", "split_feature_video.apk")) || (watch.size() == 6 && watch.get(0).equals("base.apk") && watch.containsAll(Arrays.asList("split_config.armeabi_v7a.apk", "split_config.hdpi.apk", "split_config.en.apk", "split_config.de.apk", "split_feature_video.apk"))));
    List<String> phone = SplitPick.pick(names, Arrays.asList("arm64-v8a", "armeabi-v7a"), 480, "en");
    check("a 64-bit phone (480 dpi, English) gets arm64, xxhdpi and English: " + phone, phone.contains("split_config.arm64_v8a.apk") && !phone.contains("split_config.armeabi_v7a.apk") && phone.contains("split_config.xxhdpi.apk") && !phone.contains("split_config.hdpi.apk") && phone.contains("split_config.en.apk") && !phone.contains("split_config.de.apk"));
    check("a CPU nobody has a split for keeps every CPU split; unknown density keeps every density", SplitPick.pick(names, Arrays.asList("x86_64"), 0, "").containsAll(Arrays.asList("split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.hdpi.apk", "split_config.xxhdpi.apk")));
    check("the density tie goes to the larger one (xhdpi 320 and hdpi 240, device 280)", SplitPick.pick(Arrays.asList("base.apk", "config.hdpi.apk", "config.xhdpi.apk"), null, 280, "").contains("config.xhdpi.apk"));
    check("a package with no config splits is returned as it is", SplitPick.pick(Arrays.asList("app.apk"), null, 0, "").equals(Arrays.asList("app.apk")));
    // a feature module with native code and its own drawables has CPU and density splits of its own: one of each per module, in any order
    List<String> base = Arrays.asList("base.apk", "split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.hdpi.apk", "split_config.xxhdpi.apk", "split_config.en.apk");
    List<String> feature = Arrays.asList("split_feature_camera.apk", "split_feature_camera.config.arm64_v8a.apk", "split_feature_camera.config.armeabi_v7a.apk", "split_feature_camera.config.hdpi.apk", "split_feature_camera.config.xxhdpi.apk", "split_feature_camera.config.en.apk");
    List<String> want = Arrays.asList("base.apk", "split_feature_camera.apk", "split_config.arm64_v8a.apk", "split_feature_camera.config.arm64_v8a.apk", "split_config.xxhdpi.apk", "split_feature_camera.config.xxhdpi.apk", "split_config.en.apk", "split_feature_camera.config.en.apk");
    for (boolean featureFirst : new boolean[] {false, true}) {
      List<String> all = new ArrayList<>(featureFirst ? feature : base);
      all.addAll(featureFirst ? base : feature);
      List<String> got = SplitPick.pick(all, Arrays.asList("arm64-v8a", "armeabi-v7a"), 480, "en");
      check("a feature's own CPU and density splits are kept with the base's (" + (featureFirst ? "feature" : "base") + " listed first): " + got,
          got.size() == want.size() && got.containsAll(want) && got.get(0).equals("base.apk"));
    }
    List<String> xapkNames = SplitPick.pick(Arrays.asList("com.example.apk", "config.arm64_v8a.apk", "config.armeabi_v7a.apk", "config.xxhdpi.apk", "config.mdpi.apk", "dynamic_ar.apk", "dynamic_ar.config.arm64_v8a.apk", "dynamic_ar.config.armeabi_v7a.apk", "dynamic_ar.config.xxhdpi.apk", "dynamic_ar.config.mdpi.apk"), Arrays.asList("arm64-v8a", "armeabi-v7a"), 480, "en");
    check("XAPK names: the feature's CPU and density splits are kept too: " + xapkNames, xapkNames.containsAll(Arrays.asList("config.arm64_v8a.apk", "config.xxhdpi.apk", "dynamic_ar.config.arm64_v8a.apk", "dynamic_ar.config.xxhdpi.apk")) && xapkNames.size() == 6);
    List<String> mixed = SplitPick.pick(Arrays.asList("base.apk", "split_config.arm64_v8a.apk", "config.armeabi_v7a.apk", "base.config.x86_64.apk"), Arrays.asList("arm64-v8a", "armeabi-v7a"), 480, "en");
    check("split_config.*, config.* and base.config.* all configure the base: one CPU split for it: " + mixed, mixed.equals(Arrays.asList("base.apk", "split_config.arm64_v8a.apk")));

    JSONObject profile = new JSONObject().put("abis", new JSONArray(Arrays.asList("armeabi-v7a", "armeabi"))).put("dpi", 280).put("lang", "de");

    // one APK
    Fake f = new Fake();
    JSONObject r = DeviceLink.install(tmp, items(new JSONObject().put("name", "A").put("paths", new JSONArray(Arrays.asList("/x/a.apk")))), new JSONObject(), profile, f, null);
    check("one APK: adb install -r, and the answer is ok", r.optBoolean("ok") && f.calls.size() == 1 && f.calls.get(0).equals(Arrays.asList("install", "-r", "/x/a.apk")));
    // splits of an app on this phone
    f = new Fake();
    r = DeviceLink.install(tmp, items(new JSONObject().put("name", "B").put("paths", new JSONArray(Arrays.asList("/d/base.apk", "/d/split_a.apk")))), new JSONObject().put("downgrade", true).put("grantAll", true).put("testOk", true), profile, f, null);
    check("two files of one app: install-multiple with the flags in order", f.calls.get(0).equals(Arrays.asList("install-multiple", "-r", "-d", "-g", "-t", "/d/base.apk", "/d/split_a.apk")));
    f = new Fake();
    DeviceLink.install(tmp, items(new JSONObject().put("name", "B").put("paths", new JSONArray(Arrays.asList("/d/base.apk")))), new JSONObject().put("reinstall", false), profile, f, null);
    check("reinstall off leaves out -r", f.calls.get(0).equals(Arrays.asList("install", "/d/base.apk")));

    // an APKS / APKM with splits
    File apks = zip(tmp, "app.apks", "base.apk", "splits/split_config.arm64_v8a.apk", "splits/split_config.armeabi_v7a.apk", "splits/split_config.hdpi.apk", "splits/split_config.xxhdpi.apk", "splits/split_config.en.apk", "splits/split_config.de.apk", "splits/split_config.fr.apk", "toc.pb");
    f = new Fake();
    List<String> steps = new ArrayList<String>();
    r = DeviceLink.install(tmp, items(new JSONObject().put("name", "App").put("archive", apks.getAbsolutePath())), new JSONObject(), profile, f, (i, t, nm, line) -> steps.add(i + "/" + t + " " + line));
    String used = f.names(0);
    check("an APKS: install-multiple with the base and the splits this watch needs (" + used + ")", r.optBoolean("ok") && f.calls.get(0).get(0).equals("install-multiple") && used.contains("base.apk") && used.contains("split_config.armeabi_v7a.apk") && used.contains("split_config.hdpi.apk") && used.contains("split_config.de.apk") && used.contains("split_config.en.apk") && !used.contains("arm64") && !used.contains("xxhdpi") && !used.contains("fr.apk") && !used.contains("toc.pb"));
    check("progress is reported (reading, installing)", steps.size() >= 2 && steps.get(0).contains("Reading") && steps.get(1).contains("Installing"));
    check("the staging folder is gone afterwards", tmp.listFiles((d, nm) -> nm.startsWith("cd")).length == 0);

    // an XAPK with an OBB
    File xapk = zip(tmp, "game.xapk", "manifest.json", "com.x.game.apk", "Android/obb/com.x.game/main.1.com.x.game.obb", "icon.png");
    f = new Fake();
    r = DeviceLink.install(tmp, items(new JSONObject().put("name", "Game").put("archive", xapk.getAbsolutePath())), new JSONObject(), profile, f, null);
    check("an XAPK: install, then the OBB goes to /sdcard/Android/obb/<package>/ (" + f.calls + ")", r.optBoolean("ok") && f.calls.size() == 3 && f.calls.get(0).get(0).equals("install") && f.calls.get(1).get(0).equals("shell") && f.calls.get(1).get(3).equals("'/sdcard/Android/obb/com.x.game'")
        && f.calls.get(2).get(0).equals("push") && f.calls.get(2).get(2).equals("/sdcard/Android/obb/com.x.game/main.1.com.x.game.obb"));

    // a failed install pushes nothing and says why
    f = new Fake();
    f.installAnswer = "Performing Streamed Install\nadb: failed to install x.apk: Failure [INSTALL_FAILED_OLDER_SDK]";
    r = DeviceLink.install(tmp, items(new JSONObject().put("name", "Game").put("archive", xapk.getAbsolutePath())), new JSONObject(), profile, f, null);
    check("a failed install: not ok, the reason is kept, nothing is pushed", !r.optBoolean("ok") && f.calls.size() == 1 && r.getJSONArray("results").getJSONObject(0).getString("out").contains("INSTALL_FAILED_OLDER_SDK"));

    // not a package, and one bad item does not stop the next
    File junk = new File(tmp, "notes.zip"); Files.write(junk.toPath(), "x".getBytes());
    File noApk = zip(tmp, "empty.apks", "readme.txt");
    f = new Fake();
    r = DeviceLink.install(tmp, items(new JSONObject().put("name", "Bad").put("archive", junk.getAbsolutePath()), new JSONObject().put("name", "Empty").put("archive", noApk.getAbsolutePath()), new JSONObject().put("name", "Good").put("paths", new JSONArray(Arrays.asList("/x/g.apk")))), new JSONObject(), profile, f, null);
    JSONArray rs = r.getJSONArray("results");
    check("a bad file and a package without an APK fail on their own; the next item is still installed", !r.optBoolean("ok") && rs.length() == 3 && !rs.getJSONObject(0).optBoolean("ok") && !rs.getJSONObject(1).optBoolean("ok") && rs.getJSONObject(1).getString("out").contains("no APK") && rs.getJSONObject(2).optBoolean("ok") && f.calls.size() == 1);
    check("an entry that climbs out of the folder (..) is not taken", !DeviceLink.class.getName().isEmpty() && SplitPick.pick(Arrays.asList("a.apk"), null, 0, "").size() == 1);

    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
