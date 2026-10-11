import com.bloatware.bingblop.AppExtras;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

// What the app menu's Features, Configurations, Signatures and Libraries tabs are made from: the words for the numbers, the libraries of a manifest,
// a signing certificate against keytool's own reading, the signature schemes of an APK (v1 files, the v2 / v3 / v3.1 blocks of the APK Signing Block), the
// native libraries inside it.
public class AppExtrasTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  static String run(String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true);
    Process p = pb.start();
    ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n; InputStream in = p.getInputStream();
    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
    p.waitFor();
    return new String(bo.toByteArray(), "UTF-8");
  }

  static byte[] zip(Map<String, byte[]> files) throws Exception {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    ZipOutputStream z = new ZipOutputStream(bo);
    for (Map.Entry<String, byte[]> e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
    z.close();
    return bo.toByteArray();
  }

  static void le(ByteArrayOutputStream o, long v, int bytes) { for (int i = 0; i < bytes; i++) o.write((int) (v >> (8 * i)) & 0xFF); }

  // the zip with an APK Signing Block holding these (id, value) pairs put in front of its central directory
  static byte[] withSigBlock(byte[] zip, long[] ids) throws Exception {
    int eocd = -1;
    for (int i = zip.length - 22; i >= 0; i--) if (zip[i] == 0x50 && zip[i + 1] == 0x4b && zip[i + 2] == 5 && zip[i + 3] == 6) { eocd = i; break; }
    long cd = (zip[eocd + 16] & 0xFFL) | (zip[eocd + 17] & 0xFFL) << 8 | (zip[eocd + 18] & 0xFFL) << 16 | (zip[eocd + 19] & 0xFFL) << 24;
    ByteArrayOutputStream pairs = new ByteArrayOutputStream();
    for (long id : ids) { byte[] val = new byte[10]; le(pairs, 4 + val.length, 8); le(pairs, id, 4); pairs.write(val); }
    long size = pairs.size() + 24;
    ByteArrayOutputStream block = new ByteArrayOutputStream();
    le(block, size, 8); block.write(pairs.toByteArray()); le(block, size, 8); block.write("APK Sig Block 42".getBytes("ISO-8859-1"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(zip, 0, (int) cd); out.write(block.toByteArray()); out.write(zip, (int) cd, zip.length - (int) cd);
    byte[] r = out.toByteArray();
    long ncd = cd + block.size();
    int e2 = eocd + block.size();
    for (int i = 0; i < 4; i++) r[e2 + 16 + i] = (byte) (ncd >> (8 * i));
    return r;
  }

  static File write(File dir, String name, byte[] b) throws Exception { File f = new File(dir, name); Files.write(f.toPath(), b); return f; }

  public static void main(String[] args) throws Exception {
    check("One UI 9.0", AppExtras.oneUi(90000).equals("9.0"));
    check("One UI 6.1.1", AppExtras.oneUi(60101).equals("6.1.1"));
    check("One UI 4.5", AppExtras.oneUi(40500).equals("4.5"));
    check("One UI 8.0 / 7.0", AppExtras.oneUi(80000).equals("8.0") && AppExtras.oneUi(70000).equals("7.0"));
    check("no One UI for 0, small and absurd numbers", AppExtras.oneUi(0).isEmpty() && AppExtras.oneUi(-5).isEmpty() && AppExtras.oneUi(999).isEmpty() && AppExtras.oneUi(5000000).isEmpty());

    // numbers to words
    check("OpenGL ES 0x00030002 is 3.2", AppExtras.glEs(0x00030002).equals("3.2"));
    check("OpenGL ES 0x00020000 is 2.0", AppExtras.glEs(0x00020000).equals("2.0"));
    check("OpenGL ES 0 says nothing", AppExtras.glEs(0).isEmpty());
    check("touch screen words", AppExtras.touchScreen(3).equals("Finger") && AppExtras.touchScreen(2).equals("Stylus") && AppExtras.touchScreen(1).equals("No touch screen") && AppExtras.touchScreen(0).equals("Any"));
    check("keyboard words", AppExtras.keyboardType(2).equals("QWERTY") && AppExtras.keyboardType(3).equals("12-key") && AppExtras.keyboardType(1).equals("No keyboard"));
    check("navigation words", AppExtras.navigation(2).equals("D-pad") && AppExtras.navigation(3).equals("Trackball") && AppExtras.navigation(4).equals("Wheel") && AppExtras.navigation(1).equals("No navigation"));
    check("input feature flags", AppExtras.inputFeatures(0).equals("None") && AppExtras.inputFeatures(1).equals("Five-way navigation") && AppExtras.inputFeatures(3).equals("Five-way navigation, Hardware keyboard"));

    // libraries named in a manifest
    String xml = "<manifest>\n<application>\n <uses-library android:name=\"org.apache.http.legacy\" android:required=\"false\" />\n <uses-library android:name=\"android.test.base\" />\n"
        + " <uses-static-library android:name=\"com.google.android.trichromelibrary\" android:version=\"5\" android:certDigest=\"AA\" />\n <uses-native-library android:name=\"libOpenCL.so\" android:required=\"false\" />\n"
        + " <uses-library android:name=\"android.test.base\" />\n</application></manifest>";
    JSONArray libs = AppExtras.manifestLibraries(xml);
    check("manifest libraries: four, the repeat is dropped", libs.length() == 4);
    check("a library without required is required; required=false is optional", libs.getJSONObject(1).getBoolean("required") && !libs.getJSONObject(0).getBoolean("required"));
    check("kinds: library, static, native", libs.getJSONObject(0).getString("kind").equals("library") && libs.getJSONObject(2).getString("kind").equals("static") && libs.getJSONObject(3).getString("kind").equals("native"));
    check("a static library has its version", libs.getJSONObject(2).getString("version").equals("5"));
    check("no manifest, no libraries", AppExtras.manifestLibraries(null).length() == 0 && AppExtras.manifestLibraries("").length() == 0);

    File tmp = Files.createTempDirectory("extras").toFile();

    // APK signature schemes
    Map<String, byte[]> base = new LinkedHashMap<String, byte[]>();
    base.put("AndroidManifest.xml", new byte[]{1, 2, 3});
    base.put("classes.dex", new byte[200]);
    byte[] plain = zip(base);
    check("an unsigned zip has no scheme", AppExtras.sigSchemes(write(tmp, "plain.apk", plain)).isEmpty());
    check("v2 and v3 blocks are found", AppExtras.sigSchemes(write(tmp, "v23.apk", withSigBlock(plain, new long[]{0x7109871aL, 0xf05368c0L}))).toString().equals("[v2, v3]"));
    check("a v3.1 block is found", AppExtras.sigSchemes(write(tmp, "v31.apk", withSigBlock(plain, new long[]{0xf05368c0L, 0x1b93ad61L}))).toString().equals("[v3, v3.1]"));
    check("an unknown block id is ignored", AppExtras.sigSchemes(write(tmp, "other.apk", withSigBlock(plain, new long[]{0x42726577L}))).isEmpty());
    Map<String, byte[]> v1 = new LinkedHashMap<String, byte[]>(base);
    v1.put("META-INF/CERT.SF", new byte[]{1}); v1.put("META-INF/CERT.RSA", new byte[]{2}); v1.put("META-INF/MANIFEST.MF", new byte[]{3});
    check("v1 is the JAR signature files", AppExtras.sigSchemes(write(tmp, "v1.apk", zip(v1))).toString().equals("[v1]"));
    check("v1 with v2", AppExtras.sigSchemes(write(tmp, "v12.apk", withSigBlock(zip(v1), new long[]{0x7109871aL}))).toString().equals("[v1, v2]"));
    check("a .SF without its signature block file is not v1", AppExtras.sigSchemes(write(tmp, "sf.apk", zip(new LinkedHashMap<String, byte[]>() {{ put("META-INF/CERT.SF", new byte[]{1}); put("a", new byte[]{2}); }}))).isEmpty());
    check("something that is not a zip says nothing and does not throw", AppExtras.sigSchemes(write(tmp, "junk.apk", "not a zip at all".getBytes())).isEmpty());

    // native libraries
    Map<String, byte[]> nl = new LinkedHashMap<String, byte[]>(base);
    nl.put("lib/arm64-v8a/libfoo.so", new byte[1000]); nl.put("lib/arm64-v8a/libbar.so", new byte[20]); nl.put("lib/x86_64/libfoo.so", new byte[900]);
    nl.put("lib/arm64-v8a/notes.txt", new byte[1]); nl.put("lib/deep/er/libz.so", new byte[1]); nl.put("assets/libx.so", new byte[1]);
    JSONArray nlibs = AppExtras.nativeLibs(write(tmp, "native.apk", zip(nl)));
    check("native libraries: the .so files of lib/<abi>/ only", nlibs.length() == 3);
    JSONObject first = nlibs.getJSONObject(0);
    check("a native library has name, abi and size", first.getString("name").equals("libfoo.so") && first.getString("abi").equals("arm64-v8a") && first.getLong("size") == 1000);

    // a certificate, against keytool
    String ks = new File(tmp, "k.p12").getAbsolutePath();
    boolean haveKeytool = true;
    try {
      String gen = run("keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=Extras Test, O=Test Org, C=US", "-keystore", ks, "-storetype", "PKCS12", "-storepass", "password", "-keypass", "password");
      haveKeytool = new File(ks).exists();
      if (!haveKeytool) System.out.println("   keytool said: " + gen);
    } catch (Exception e) { haveKeytool = false; }
    if (!haveKeytool) {
      System.out.println("SKIP certificate checks: no keytool");
    } else {
      File der = new File(tmp, "c.der");
      run("keytool", "-exportcert", "-alias", "t", "-keystore", ks, "-storetype", "PKCS12", "-storepass", "password", "-file", der.getAbsolutePath());
      String listing = run("keytool", "-list", "-v", "-alias", "t", "-keystore", ks, "-storetype", "PKCS12", "-storepass", "password");
      JSONObject c = AppExtras.certInfo(Files.readAllBytes(der.toPath()));
      check("certificate: subject and issuer", c.getString("subject").contains("CN=Extras Test") && c.getString("subject").contains("O=Test Org") && c.getBoolean("selfSigned"));
      check("certificate: SHA-256 equals keytool's", listing.contains("SHA256: " + c.getString("sha256")));
      check("certificate: SHA-1 equals keytool's", listing.contains("SHA1: " + c.getString("sha1")));
      check("certificate: MD5 is 16 colon-separated bytes", c.getString("md5").matches("([0-9A-F]{2}:){15}[0-9A-F]{2}"));
      check("certificate: key is RSA, 2048 bits", c.getString("keyAlg").equals("RSA") && c.getInt("keyBits") == 2048);
      check("certificate: signature algorithm and version", c.getString("sigAlg").toUpperCase().contains("RSA") && c.getInt("version") == 3);
      check("certificate: ten years of validity", Math.abs((c.getLong("notAfter") - c.getLong("notBefore")) / 86400000L - 3650) <= 1);
      String serial = c.getString("serial");
      check("certificate: serial equals keytool's", listing.toUpperCase().contains("SERIAL NUMBER: " + serial));
    }
    JSONObject bad = AppExtras.certInfo(new byte[]{1, 2, 3});
    check("something that is not a certificate gives an error, not a throw", bad.has("error") && !bad.has("sha256"));

    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
