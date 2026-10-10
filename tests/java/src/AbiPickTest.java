import com.bloatware.bingblop.AbiPick;

import java.util.Arrays;
import java.util.List;

public class AbiPickTest {
  static int n, fails;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  public static void main(String[] a) {
    List<String> three = Arrays.asList("SHA256SUMS.txt", "ADB_Application_Manager_Pro-v7.10.0-arm64-v8a.apk", "ADB_Application_Manager_Pro-v7.10.0-armeabi-v7a.apk", "ADB_Application_Manager_Pro-v7.10.0-universal.apk");
    check("a 64-bit phone gets the arm64 file", AbiPick.pickApk(three, new String[]{"arm64-v8a", "armeabi-v7a", "armeabi"}) == 1);
    check("a 32-bit phone gets the v7a file", AbiPick.pickApk(three, new String[]{"armeabi-v7a", "armeabi"}) == 2);
    check("an ABI with no file of its own (x86) gets the universal one", AbiPick.pickApk(three, new String[]{"x86_64", "x86"}) == 3);
    check("no ABIs given gets the universal one", AbiPick.pickApk(three, null) == 3);
    check("the order of the files does not matter", AbiPick.pickApk(Arrays.asList("b-universal.apk", "b-armeabi-v7a.apk", "b-arm64-v8a.apk"), new String[]{"arm64-v8a"}) == 2);
    check("a release with one untagged apk (the older ones) gives it to every phone", AbiPick.pickApk(Arrays.asList("SHA256SUMS.txt", "App-v7.9.22.apk"), new String[]{"armeabi-v7a"}) == 1);
    check("a release without an apk gives -1", AbiPick.pickApk(Arrays.asList("notes.txt"), new String[]{"arm64-v8a"}) == -1);
    check("a 64-bit phone with only a 32-bit and a universal file gets the first of its ABIs that has one (arm64 has none, v7a does)",
        AbiPick.pickApk(Arrays.asList("x-armeabi-v7a.apk", "x-universal.apk"), new String[]{"arm64-v8a", "armeabi-v7a"}) == 0);
    check("an .APK in capitals counts, a .txt named like an ABI does not", AbiPick.pickApk(Arrays.asList("arm64-v8a.txt", "App-universal.APK"), new String[]{"arm64-v8a"}) == 1);
    check("is64: arm64 first is 64-bit, v7a first is not, nothing is not", AbiPick.is64(new String[]{"arm64-v8a", "armeabi-v7a"}) && !AbiPick.is64(new String[]{"armeabi-v7a"}) && !AbiPick.is64(null) && !AbiPick.is64(new String[0]));
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
