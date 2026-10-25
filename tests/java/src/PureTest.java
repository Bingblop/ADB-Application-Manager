import com.bloatware.bingblop.ApkScan;
import com.bloatware.bingblop.XapkInfo;
import com.bloatware.bingblop.ApkSigner;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public class PureTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  static void putEntry(ZipOutputStream z, String name, byte[] data) throws IOException {
    z.putNextEntry(new ZipEntry(name)); z.write(data); z.closeEntry();
  }

  public static void main(String[] a) throws Exception {

    // ---------- ApkSigner.isV1SignatureEntry ----------
    check("isV1SignatureEntry MANIFEST.MF", ApkSigner.isV1SignatureEntry("META-INF/MANIFEST.MF"));
    check("isV1SignatureEntry CERT.RSA", ApkSigner.isV1SignatureEntry("META-INF/CERT.RSA"));
    check("isV1SignatureEntry CERT.SF", ApkSigner.isV1SignatureEntry("META-INF/CERT.SF"));
    check("isV1SignatureEntry CERT.DSA", ApkSigner.isV1SignatureEntry("META-INF/CERT.DSA"));
    check("isV1SignatureEntry CERT.EC", ApkSigner.isV1SignatureEntry("META-INF/CERT.EC"));
    check("isV1SignatureEntry lowercase", ApkSigner.isV1SignatureEntry("meta-inf/cert.ec"));
    check("isV1SignatureEntry SIG-FOO", ApkSigner.isV1SignatureEntry("META-INF/SIG-FOO"));
    check("isV1SignatureEntry SIG-FOO.RSA", ApkSigner.isV1SignatureEntry("META-INF/SIG-FOO.RSA"));
    check("isV1SignatureEntry BADSIG (not starting with SIG-)", !ApkSigner.isV1SignatureEntry("META-INF/BADSIG"));
    check("META-INF/services/x is not a signature file", !ApkSigner.isV1SignatureEntry("META-INF/services/x.RSA"));
    check("META-INF/com/android/build.gradle is kept", !ApkSigner.isV1SignatureEntry("META-INF/com/android/build.gradle"));
    check("resources.arsc is kept", !ApkSigner.isV1SignatureEntry("resources.arsc"));
    check("META-INF starts but no slash", !ApkSigner.isV1SignatureEntry("META-INF.RSA"));
    check("META-INF/ with no name", !ApkSigner.isV1SignatureEntry("META-INF/"));
    check("META-INF/.RSA (starts with .RSA)", ApkSigner.isV1SignatureEntry("META-INF/.RSA"));

    // ---------- XapkInfo.normalizeDest ----------
    check("normalize plain obb path", "Android/obb/com.foo/main.1.com.foo.obb".equals(XapkInfo.normalizeDest("Android/obb/com.foo/main.1.com.foo.obb")));
    check("normalize leading slash + sdcard/ prefix", "Android/obb/com.foo/x.obb".equals(XapkInfo.normalizeDest("/sdcard/Android/obb/com.foo/x.obb")));
    check("normalize storage/emulated/0 prefix + case", "Android/obb/com.foo/x.obb".equals(XapkInfo.normalizeDest("storage/emulated/0/ANDROID/OBB/com.foo/x.obb")));
    check("normalize backslashes", "Android/data/com.foo/f.bin".equals(XapkInfo.normalizeDest("Android\\data\\com.foo\\f.bin")));
    check("reject traversal ..", XapkInfo.normalizeDest("Android/obb/com.foo/../../etc/x") == null);
    check("reject non-Android root", XapkInfo.normalizeDest("Download/com.foo/x.obb") == null);
    check("reject Android/media", XapkInfo.normalizeDest("Android/media/com.foo/x") == null);
    check("reject too short", XapkInfo.normalizeDest("Android/obb/com.foo") == null);
    check("reject empty segment", XapkInfo.normalizeDest("Android/obb//x.obb") == null);
    check("belongsTo right pkg", XapkInfo.belongsTo("Android/obb/com.foo/x.obb", "com.foo"));
    check("belongsTo wrong pkg", !XapkInfo.belongsTo("Android/obb/com.bar/x.obb", "com.foo"));

    // ---------- XapkInfo.inspect on synthetic XAPK ----------
    File x = File.createTempFile("tst", ".xapk");
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(x))) {
      putEntry(z, "icon.png", new byte[]{1,2,3});
      putEntry(z, "com.foo.apk", new byte[]{9});
      putEntry(z, "config.arm64_v8a.apk", new byte[]{9,9});
      byte[] big = new byte[5000]; Arrays.fill(big, (byte)'o');
      putEntry(z, "Android/obb/com.foo/main.12.com.foo.obb", big);
      putEntry(z, "Android/obb/com.other/main.1.com.other.obb", big);            // wrong package -> dropped by forPackage
      putEntry(z, "Android/obb/com.foo/../../evil.obb", big);                     // traversal -> never a candidate
      putEntry(z, "data_blob.bin", big);                                          // only mapped via manifest expansions
      String mf = "{\"xapk_version\":2,\"package_name\":\"com.foo\",\"split_apks\":[{\"file\":\"com.foo.apk\",\"id\":\"base\"}],"
        + "\"expansions\":[{\"file\":\"data_blob.bin\",\"install_location\":\"EXTERNAL_STORAGE\",\"install_path\":\"/Android/data/com.foo/files/data_blob.bin\"}]}";
      putEntry(z, "manifest.json", mf.getBytes("UTF-8"));
    }
    XapkInfo.Info info = XapkInfo.inspect(x);
    check("inspect: manifest parsed", info.manifest != null && info.manifest.optInt("xapk_version") == 2);
    check("inspect: isXapk", info.isXapk());
    List<XapkInfo.Extra> mine = info.forPackage("com.foo");
    Set<String> dests = new TreeSet<>(); for (XapkInfo.Extra e : mine) dests.add(e.dest);
    check("inspect: com.foo gets the obb + manifest-mapped data file only: " + dests,
      dests.equals(new TreeSet<>(Arrays.asList("Android/obb/com.foo/main.12.com.foo.obb", "Android/data/com.foo/files/data_blob.bin"))));
    check("inspect: traversal entry never offered", info.candidates.stream().noneMatch(e -> e.entry.contains("..")));
    long obbSize = mine.stream().filter(e -> e.dest.endsWith(".obb")).findFirst().get().size;
    check("inspect: uncompressed size known (5000)", obbSize == 5000);
    check("inspect: forPackage(null) empty", info.forPackage(null).isEmpty());

    // plain APKS (no manifest, no data) is not an XAPK
    File s = File.createTempFile("tst", ".apks");
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(s))) { putEntry(z, "base.apk", new byte[]{1}); putEntry(z, "info.json", "{}".getBytes()); }
    XapkInfo.Info plain = XapkInfo.inspect(s);
    check("plain apks: not an XAPK", !plain.isXapk() && plain.candidates.isEmpty());
    // a non-zip file must not throw
    File junk = File.createTempFile("tst", ".bin"); Files.write(junk.toPath(), "not a zip".getBytes());
    check("non-zip: no crash, no extras", XapkInfo.inspect(junk).candidates.isEmpty());
    // a manifest.json whose central-directory size (what ZipEntry.getSize() reports) says 1000 bytes but that inflates to 2 MB: the bytes
    // actually read count, so it is dropped (a bigger one ran the app out of memory and the Installer never answered); the OBB scan goes on
    File liar = File.createTempFile("tst", ".xapk");
    byte[] padded = new byte[2 * 1024 * 1024];
    Arrays.fill(padded, (byte) ' ');
    byte[] head = "{\"xapk_version\":2}".getBytes("UTF-8");
    System.arraycopy(head, 0, padded, 0, head.length);
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(liar))) {
      putEntry(z, "com.foo.apk", new byte[]{9});
      putEntry(z, "Android/obb/com.foo/main.1.com.foo.obb", new byte[]{1, 2, 3});
      putEntry(z, "manifest.json", padded);
    }
    byte[] raw = Files.readAllBytes(liar.toPath());
    byte[] mfName = "manifest.json".getBytes("UTF-8");
    int patched = 0;
    for (int i = 0; i + 46 + mfName.length <= raw.length; i++) {
      if (raw[i] != 'P' || raw[i + 1] != 'K' || raw[i + 2] != 1 || raw[i + 3] != 2) continue;     // central-directory record
      int nameLen = (raw[i + 28] & 0xff) | (raw[i + 29] & 0xff) << 8;
      if (nameLen != mfName.length || !Arrays.equals(Arrays.copyOfRange(raw, i + 46, i + 46 + nameLen), mfName)) continue;
      raw[i + 24] = (byte) 0xe8; raw[i + 25] = 0x03; raw[i + 26] = 0; raw[i + 27] = 0;            // uncompressed size: 1000
      patched++;
    }
    Files.write(liar.toPath(), raw);
    check("lying manifest.json: the archive was patched to declare 1000 bytes", patched == 1);
    XapkInfo.Info lied = XapkInfo.inspect(liar);
    check("lying manifest.json: not read past the limit (dropped, not parsed)", lied.manifest == null);
    check("lying manifest.json: the OBB file is still found", lied.forPackage("com.foo").size() == 1 && lied.forPackage("com.foo").get(0).dest.equals("Android/obb/com.foo/main.1.com.foo.obb"));
    check("humanBytes", "1.5 KB".equals(XapkInfo.humanBytes(1536)) && "2.00 GB".equals(XapkInfo.humanBytes(2L*1073741824)));

    // ---------- ApkScan ----------
    check("kindOf apk/apks/apkm/xapk case-insens", "apk".equals(ApkScan.kindOf("A.APK")) && "apks".equals(ApkScan.kindOf("b.Apks"))
      && "apkm".equals(ApkScan.kindOf("c.apkm")) && "xapk".equals(ApkScan.kindOf("d.xapk")));
    check("kindOf rejects others / bare ext", ApkScan.kindOf("x.zip") == null && ApkScan.kindOf(".apk") == null && ApkScan.kindOf("apk") == null && ApkScan.kindOf(null) == null);

    Path root = Files.createTempDirectory("scan");
    Files.createDirectories(root.resolve("Download"));
    Files.createDirectories(root.resolve("a/b/c/d"));
    Files.createDirectories(root.resolve(".trashed-1"));
    Files.write(root.resolve("Download/app.apk"), new byte[100]);
    Files.write(root.resolve("Download/game.XAPK"), new byte[300]);
    Files.write(root.resolve("a/b/c/d/deep.apks"), new byte[50]);
    Files.write(root.resolve("a/bundle.apkm"), new byte[70]);
    Files.write(root.resolve("a/empty.apk"), new byte[0]);              // empty placeholder: skipped
    Files.write(root.resolve("a/notes.txt"), new byte[10]);             // not a package
    Files.write(root.resolve(".trashed-1/old.apk"), new byte[20]);      // trash: skipped
    Files.createSymbolicLink(root.resolve("a/loop"), root);              // symlink loop: not followed
    ApkScan.Limits lim = new ApkScan.Limits(); lim.deadlineMs = System.currentTimeMillis() + 10000;
    List<ApkScan.Entry> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
    ApkScan.walk(root.toFile(), 0, out, seen, lim);
    Set<String> names = new TreeSet<>(); for (ApkScan.Entry e : out) names.add(e.name);
    check("walk finds exactly the 4 package files: " + names, names.equals(new TreeSet<>(Arrays.asList("app.apk", "game.XAPK", "deep.apks", "bundle.apkm"))));
    check("walk: not truncated", !lim.hitLimit);
    ApkScan.Entry xe = out.stream().filter(e -> e.name.equals("game.XAPK")).findFirst().get();
    check("walk: kind + size + mtime recorded", "xapk".equals(xe.kind) && xe.size == 300 && xe.mtime > 0 && !xe.shell);

    ApkScan.Limits lim2 = new ApkScan.Limits(); lim2.deadlineMs = System.currentTimeMillis() + 10000; lim2.maxResults = 2;
    List<ApkScan.Entry> out2 = new ArrayList<>(); ApkScan.walk(root.toFile(), 0, out2, new HashSet<>(), lim2);
    check("walk honors maxResults and flags truncation", out2.size() <= 2 && lim2.hitLimit);
    ApkScan.Limits lim3 = new ApkScan.Limits(); lim3.deadlineMs = System.currentTimeMillis() + 10000; lim3.maxDepth = 1;
    List<ApkScan.Entry> out3 = new ArrayList<>(); ApkScan.walk(root.toFile(), 0, out3, new HashSet<>(), lim3);
    Set<String> n3 = new TreeSet<>(); for (ApkScan.Entry e : out3) n3.add(e.name);
    check("walk honors maxDepth (no deep.apks): " + n3, !n3.contains("deep.apks") && n3.contains("app.apk"));
    ApkScan.Limits lim4 = new ApkScan.Limits(); lim4.deadlineMs = System.currentTimeMillis() - 1;
    List<ApkScan.Entry> out4 = new ArrayList<>(); ApkScan.walk(root.toFile(), 0, out4, new HashSet<>(), lim4);
    check("walk stops at an expired deadline", out4.isEmpty() && lim4.hitLimit);

    // sort: newest first
    ApkScan.Entry o = new ApkScan.Entry(); o.name = "old.apk"; o.mtime = 1000;
    ApkScan.Entry n = new ApkScan.Entry(); n.name = "new.apk"; n.mtime = 9000;
    ApkScan.Entry m = new ApkScan.Entry(); m.name = "mid.apk"; m.mtime = 5000;
    List<ApkScan.Entry> srt = new ArrayList<>(Arrays.asList(o, n, m)); ApkScan.sort(srt);
    check("sort newest first", srt.get(0) == n && srt.get(1) == m && srt.get(2) == o);

    // shell output parsers
    String find = "/storage/emulated/0/Android/data/org.telegram.messenger/files/Telegram/Telegram Documents/App v2.apk\n"
      + "\n/storage/emulated/0/Android/obb/x/readme.txt\nrelative/path.apk\n/storage/emulated/0/Android/data/a/b.XAPK\n";
    List<String> fp = ApkScan.parseFindOutput(find, 100);
    check("parseFindOutput keeps only absolute package paths (spaces ok): " + fp.size(), fp.size() == 2 && fp.get(0).endsWith("App v2.apk") && fp.get(1).endsWith("b.XAPK"));
    check("parseFindOutput honors max", ApkScan.parseFindOutput(find, 1).size() == 1);
    Map<String,long[]> st = ApkScan.parseStatOutput("1234|1700000000|/storage/emulated/0/Android/data/a/b.apk\ngarbage\n99|5|/x/with|pipe.apk\nabc|1|/bad\n");
    check("parseStatOutput: normal line", st.containsKey("/storage/emulated/0/Android/data/a/b.apk") && st.get("/storage/emulated/0/Android/data/a/b.apk")[0] == 1234 && st.get("/storage/emulated/0/Android/data/a/b.apk")[1] == 1700000000L);
    check("parseStatOutput: path containing '|' preserved, junk ignored", st.containsKey("/x/with|pipe.apk") && !st.containsKey("/bad") && st.size() == 2);
    JSONObject j = ApkScan.toJson(xe);
    check("toJson fields", "game.XAPK".equals(j.optString("name")) && "xapk".equals(j.optString("kind")) && j.optLong("size") == 300);

    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
