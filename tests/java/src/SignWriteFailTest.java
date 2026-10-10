import com.bloatware.bingblop.ApkSigner;
import java.io.*;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;

/**
 * A write that fails half way (disk full) must not leave a truncated "signed" APK behind, and the error that reaches the caller must be the write's own (not the one a second flush raises from close()). The child
 * process signs with "ulimit -f" set, so the operating system refuses the write beyond the limit (EFBIG). Needs sh, an APK and keys.
 */
public class SignWriteFailTest {
  static final String KEYS = System.getProperty("keys") + "/";

  public static void main(String[] a) throws Exception {
    if (a.length == 3 && a[0].equals("child")) { child(new File(a[1]), new File(a[2])); return; }
    File src = new File(System.getProperty("testapk"));
    File tmp = Files.createTempDirectory("wf").toFile();
    File prepared = new File(tmp, "prep.apk"), out = new File(tmp, "out.apk");
    ApkSigner.prepare(src, prepared);
    // the limit has to be below the size of the APK, or nothing fails
    long limitKb = 200;     // sh's ulimit -f counts blocks of 512 bytes (dash) or 1024 bytes (bash): 100 KB to 200 KB either way
    if (prepared.length() < 4 * 1024 * 1024L) { System.out.println("skipped: the test APK is smaller than 4 MB"); System.out.println("0 checks, 0 failed"); System.exit(0); }
    String java = System.getProperty("java.home") + "/bin/java";
    String cmd = "ulimit -f " + limitKb + " && exec '" + java + "' -cp '" + System.getProperty("java.class.path") + "' -Dkeys='" + System.getProperty("keys")
        + "' SignWriteFailTest child '" + prepared + "' '" + out + "'";
    Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes(), "UTF-8");
    int code = p.waitFor();
    System.out.print(output);
    int bad = 0;
    if (code != 0 || !output.contains("THREW")) { System.out.println("FAIL: the child did not report the write error (exit " + code + ")"); bad++; }
    // The error the caller gets must come from the failing write itself. The old code's error came from close(), which flushes again and fails again
    // with the same message; only the method names on the stack tell the two apart.
    Matcher st = Pattern.compile("STACK (\\S*)").matcher(output);
    if (!st.find()) { System.out.println("FAIL: the child reported no stack"); bad++; }
    else if (Arrays.asList(st.group(1).split(",")).contains("close")) { System.out.println("FAIL: the error that reached the caller was raised by close(): " + st.group(1)); bad++; }
    if (output.contains("out exists=true")) { System.out.println("FAIL: a truncated output was left behind"); bad++; }
    if (!output.contains("out exists=false")) { System.out.println("FAIL: no 'out exists=false' report"); bad++; }
    System.out.println(bad == 0 ? "ALL PASS" : bad + " FAILED");
    System.exit(bad == 0 ? 0 : 1);
  }

  static void child(File prepared, File out) throws Exception {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(KEYS + "rsa.p12")) { ks.load(in, "password".toCharArray()); }
    KeyStore.PrivateKeyEntry k = (KeyStore.PrivateKeyEntry) ks.getEntry("rsa", new KeyStore.PasswordProtection("password".toCharArray()));
    String threw = "NOTHING";
    try { ApkSigner.signV2(prepared, out, k.getPrivateKey(), (X509Certificate) k.getCertificate()); }
    catch (IOException e) {
      threw = "THREW " + e.getMessage();
      StringBuilder frames = new StringBuilder();
      for (StackTraceElement f : e.getStackTrace()) frames.append(f.getMethodName()).append(',');
      System.out.println("STACK " + frames);     // where the exception that reached the caller was raised
    }
    System.out.println(threw + ", out exists=" + out.exists() + (out.exists() ? " size=" + out.length() : ""));
    System.exit(threw.startsWith("THREW") ? 0 : 3);
  }
}
