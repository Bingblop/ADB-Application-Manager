import com.bloatware.bingblop.ApkSigner;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;

public class SignMismatchTest {
  static final String KEYS = System.getProperty("keys") + "/";
  static KeyStore.PrivateKeyEntry load(String file, String alias) throws Exception {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(KEYS + file)) { ks.load(in, "password".toCharArray()); }
    return (KeyStore.PrivateKeyEntry) ks.getEntry(alias, new KeyStore.PasswordProtection("password".toCharArray()));
  }
  public static void main(String[] a) throws Exception {
    KeyStore.PrivateKeyEntry rsa = load("rsa.p12", "rsa"), ec = load("ec.p12", "ec");
    File src = new File(System.getProperty("testapk"));
    File tmp = Files.createTempDirectory("mm").toFile();
    File prepared = new File(tmp, "prep.apk");
    ApkSigner.prepare(src, prepared);
    int bad = 0;
    // RSA private key with the EC certificate (a key replaced under the same name): must refuse and leave no output
    File out = new File(tmp, "out1.apk");
    try { ApkSigner.signV2(prepared, out, rsa.getPrivateKey(), (X509Certificate) ec.getCertificate()); System.out.println("FAIL: signed with a mismatched pair"); bad++; }
    catch (IOException e) { System.out.println("PASS refused: " + e.getMessage()); if (out.exists()) { System.out.println("FAIL: output left behind"); bad++; } }
    // EC key with the RSA certificate
    File out2 = new File(tmp, "out2.apk");
    try { ApkSigner.signV2(prepared, out2, ec.getPrivateKey(), (X509Certificate) rsa.getCertificate()); System.out.println("FAIL: signed with a mismatched pair (2)"); bad++; }
    catch (IOException e) { System.out.println("PASS refused (2): " + e.getMessage()); }
    // the right pairs still sign
    File out3 = new File(tmp, "out3.apk");
    ApkSigner.signV2(prepared, out3, rsa.getPrivateKey(), (X509Certificate) rsa.getCertificate());
    File out4 = new File(tmp, "out4.apk");
    ApkSigner.signV2(prepared, out4, ec.getPrivateKey(), (X509Certificate) ec.getCertificate());
    System.out.println((out3.length() > 0 && out4.length() > 0) ? "PASS matching pairs sign" : "FAIL matching pairs");
    if (out3.length() == 0 || out4.length() == 0) bad++;
    System.out.println(bad == 0 ? "ALL PASS" : bad + " FAILED");
    System.exit(bad == 0 ? 0 : 1);
  }
}
