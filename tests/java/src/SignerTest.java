import com.bloatware.bingblop.ApkSigner;
import com.bloatware.bingblop.ZipTool;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

public class SignerTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
  static final String APKSIGNER = System.getProperty("apksigner");
  static final String KEYS = System.getProperty("keys") + "/";

  static String shOut(String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd); pb.redirectErrorStream(true);
    Process p = pb.start();
    ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n; InputStream in = p.getInputStream();
    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
    p.waitFor();
    StringBuilder sb = new StringBuilder();
    for (String line : new String(bo.toByteArray(), "UTF-8").split("\n")) if (!line.startsWith("Picked up ")) sb.append(line).append('\n');
    return sb.toString();
  }
  static String verify(File apk) throws Exception {
    return shOut(APKSIGNER, "verify", "--verbose", "--print-certs", "--min-sdk-version", "26", apk.getAbsolutePath());
  }

  static KeyStore.PrivateKeyEntry load(String file, String alias) throws Exception {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(KEYS + file)) { ks.load(in, "password".toCharArray()); }
    return (KeyStore.PrivateKeyEntry) ks.getEntry(alias, new KeyStore.PasswordProtection("password".toCharArray()));
  }

  static void signWith(File in, File out, KeyStore.PrivateKeyEntry k) throws Exception {
    File prepared = File.createTempFile("prep", ".apk");
    try {
      ApkSigner.prepare(in, prepared);
      ApkSigner.signV2(prepared, out, k.getPrivateKey(), (X509Certificate) k.getCertificate());
    } finally { prepared.delete(); }
  }

  static boolean verified(File apk, KeyStore.PrivateKeyEntry k, String label) throws Exception {
    String v = verify(apk);
    String sha = ApkSigner.certSha256((X509Certificate) k.getCertificate());
    boolean ok = v.startsWith("Verifies") && v.contains("Verified using v2 scheme (APK Signature Scheme v2): true")
        && v.contains("Number of signers: 1") && v.toLowerCase().contains("certificate sha-256 digest: " + sha);
    if (!ok) System.out.println("   --- apksigner said (" + label + "):\n" + v);
    return ok;
  }

  public static void main(String[] args) throws Exception {
    KeyStore.PrivateKeyEntry rsa = load("rsa.p12", "rsa");
    KeyStore.PrivateKeyEntry ec = load("ec.p12", "ec");
    File src56 = new File(System.getProperty("testapk"));
    File tmp = Files.createTempDirectory("signt").toFile();

    // 1. a real, previously signed APK re-signed with an RSA key
    File o1 = new File(tmp, "rsa.apk");
    signWith(src56, o1, rsa);
    check("RSA re-sign of real APK verifies with apksigner (v2, cert matches)", verified(o1, rsa, "rsa"));
    ZipTool.Archive a1 = ZipTool.open(o1);
    boolean anyV1 = false; for (ZipTool.Entry e : a1.entries) if (ApkSigner.isV1SignatureEntry(e.name)) anyV1 = true;
    check("no v1 signature entries remain", !anyV1);
    check("v1 scheme not claimed", !verify(o1).contains("(JAR signing): true"));

    // 2. EC key
    File o2 = new File(tmp, "ec.apk");
    signWith(src56, o2, ec);
    check("EC re-sign verifies with apksigner", verified(o2, ec, "ec"));

    // 3. signing is repeatable and replaces an old signature (signed APK -> prepare -> sign with another key)
    File o3 = new File(tmp, "resign.apk");
    signWith(o1, o3, ec);
    check("re-signing a signed APK with a different key verifies", verified(o3, ec, "resign"));
    check("old signer is gone", !verify(o3).toLowerCase().contains(ApkSigner.certSha256((X509Certificate) rsa.getCertificate())));

    // 4. an APK edited in the archive editor (replace + add + delete) then signed
    File edited = new File(tmp, "edited.apk");
    ZipTool.Archive a = ZipTool.open(o1);
    List<ZipTool.Edit> edits = new ArrayList<ZipTool.Edit>();
    edits.add(ZipTool.Edit.add("assets/hello.txt", "hello world".getBytes("UTF-8")));
    edits.add(ZipTool.Edit.delete("assets/index.html"));
    ZipTool.rewrite(a, edited, edits, true, null);
    String broken = verify(edited);
    check("apksigner rejects the edited (unsigned-after-edit) APK", !broken.startsWith("Verifies"));
    File o4 = new File(tmp, "edited_signed.apk");
    signWith(edited, o4, rsa);
    check("edited APK signed in-app verifies", verified(o4, rsa, "edited"));
    ZipTool.Archive a4 = ZipTool.open(o4);
    check("edit result has the added file and lacks the deleted one", a4.find("assets/hello.txt") != null && a4.find("assets/index.html") == null);

    // 5. multi-chunk (> 1 MB sections, with odd sizes)
    for (int size : new int[]{1, 1024 * 1024 - 1, 1024 * 1024, 1024 * 1024 + 1, 3 * 1024 * 1024 + 17, 12 * 1024 * 1024 + 5}) {
      File big = new File(tmp, "big" + size + ".apk");
      byte[] rnd = new byte[size]; new Random(size).nextBytes(rnd);
      List<ZipTool.Edit> ed = new ArrayList<ZipTool.Edit>();
      ed.add(ZipTool.Edit.add("assets/big.bin", rnd));
      ZipTool.rewrite(ZipTool.open(src56), big, ed, true, null);
      File bo = new File(tmp, "big" + size + "_signed.apk");
      signWith(big, bo, size % 2 == 0 ? rsa : ec);
      check("large entry (" + size + " bytes) signed apk verifies", verified(bo, size % 2 == 0 ? rsa : ec, "big" + size));
      big.delete(); bo.delete();
    }

    // 6. tamper detection: flip a byte in a stored/compressed entry -> apksigner must reject
    byte[] raw = Files.readAllBytes(o1.toPath());
    ZipTool.Archive ta = ZipTool.open(o1);
    ZipTool.Entry victim = null; for (ZipTool.Entry e : ta.entries) if (!e.dir && e.csize > 64) { victim = e; break; }
    File tampered = new File(tmp, "tampered.apk");
    byte[] t = raw.clone();
    // local header is 30 + nameLen + extraLen; just flip a byte in the middle of the entry data
    int lhoOff = (int) victim.lho;
    int nameLen = (t[lhoOff + 26] & 0xff) | ((t[lhoOff + 27] & 0xff) << 8);
    int extraLen = (t[lhoOff + 28] & 0xff) | ((t[lhoOff + 29] & 0xff) << 8);
    int dataOff = lhoOff + 30 + nameLen + extraLen;
    t[dataOff + (int) (victim.csize / 2)] ^= 0x55;
    Files.write(tampered.toPath(), t);
    check("tampered APK is rejected by apksigner", !verify(tampered).startsWith("Verifies"));

    // 7. refuses an APK that already has a signing block
    boolean threw = false;
    try { ApkSigner.signV2(o1, new File(tmp, "x.apk"), rsa.getPrivateKey(), (X509Certificate) rsa.getCertificate()); } catch (IOException e) { threw = true; }
    check("signV2 refuses input that already has a signing block", threw);

    // 8. v1 entry name matcher
    check("isV1SignatureEntry MANIFEST.MF", ApkSigner.isV1SignatureEntry("META-INF/MANIFEST.MF"));
    check("isV1SignatureEntry CERT.RSA", ApkSigner.isV1SignatureEntry("META-INF/CERT.RSA"));
    check("isV1SignatureEntry CERT.SF", ApkSigner.isV1SignatureEntry("META-INF/CERT.SF"));
    check("isV1SignatureEntry lowercase", ApkSigner.isV1SignatureEntry("meta-inf/cert.ec"));
    check("isV1SignatureEntry SIG-FOO", ApkSigner.isV1SignatureEntry("META-INF/SIG-FOO"));
    check("META-INF/services/x is not a signature file", !ApkSigner.isV1SignatureEntry("META-INF/services/x.RSA"));
    check("META-INF/com/android/build.gradle is kept", !ApkSigner.isV1SignatureEntry("META-INF/com/android/build.gradle"));
    check("resources.arsc is kept", !ApkSigner.isV1SignatureEntry("resources.arsc"));

    // 9. matches()
    check("matches(pub, cert) true for the pair", ApkSigner.matches(rsa.getCertificate().getPublicKey(), (X509Certificate) rsa.getCertificate()));
    check("matches(pub, cert) false for a different pair", !ApkSigner.matches(ec.getCertificate().getPublicKey(), (X509Certificate) rsa.getCertificate()));

    // 10. cert hash helper matches keytool
    String kt = shOut("keytool", "-list", "-v", "-keystore", KEYS + "rsa.p12", "-storepass", "password", "-alias", "rsa");
    String sha = ApkSigner.certSha256((X509Certificate) rsa.getCertificate()).toUpperCase();
    String shaColon = sha.replaceAll("(..)(?!$)", "$1:");
    check("certSha256 equals keytool's SHA256", kt.contains(shaColon));

    for (File f : tmp.listFiles()) f.delete(); tmp.delete();
    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
