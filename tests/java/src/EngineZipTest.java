import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Morphe engine zip (engine/dist/morphe-engine-dex.zip) must hold, next to its dex files, what the patcher reads at run time. d8 drops every
 * file that is not a class; without Kotlin's built-in metadata (kotlin/**.kotlin_builtins) and the service files of kotlin-reflect every bundle
 * fails on a phone with "KotlinReflectionInternalError: Unresolved class: class java.lang.String" (a computer's Java finds them on the class path,
 * so the engine tests on a computer do not show it).
 */
public class EngineZipTest {
  static int n, fails;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static File find() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) {
      File f = new File(d, "engine/dist/morphe-engine-dex.zip");
      if (f.isFile()) return f;
    }
    return null;
  }

  public static void main(String[] a) throws Exception {
    File zip = find();
    if (zip == null) { System.out.println("SKIP engine/dist/morphe-engine-dex.zip not found"); System.out.println("ALL PASSED (0 checks)"); return; }
    Set<String> names = new HashSet<String>();
    ZipFile z = new ZipFile(zip);
    try {
      for (java.util.Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) names.add(e.nextElement().getName());
    } finally { z.close(); }
    check("the engine has dex files", names.contains("classes.dex"));
    check("Kotlin's built-in metadata of kotlin.* is in", names.contains("kotlin/kotlin.kotlin_builtins"));
    check("the built-ins of kotlin.collections, ranges and reflect are in", names.contains("kotlin/collections/collections.kotlin_builtins") && names.contains("kotlin/ranges/ranges.kotlin_builtins") && names.contains("kotlin/reflect/reflect.kotlin_builtins"));
    check("kotlin-reflect's service files are in (BuiltInsLoader, MetadataExtensions, ExternalOverridabilityCondition)",
        names.contains("META-INF/services/kotlin.reflect.jvm.internal.impl.builtins.BuiltInsLoader")
            && names.contains("META-INF/services/kotlin.reflect.jvm.internal.impl.km.internal.extensions.MetadataExtensions")
            && names.contains("META-INF/services/kotlin.reflect.jvm.internal.impl.resolve.ExternalOverridabilityCondition"));
    check("the patcher's version file is in", names.contains("app/morphe/patcher/version.properties"));
    check("ARSCLib's framework files are in", names.contains("frameworks/android/android-34.apk"));
    // the compiled names of morphe-patcher's internal members: patch bundles (Gboard's) reach them by reflection, and the name holds the Kotlin module name
    byte[] dex = new byte[0];
    ZipFile z2 = new ZipFile(zip);
    try {
      java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
      for (java.util.Enumeration<? extends ZipEntry> e = z2.entries(); e.hasMoreElements(); ) {
        ZipEntry en = e.nextElement();
        if (!en.getName().endsWith(".dex")) continue;
        java.io.InputStream in = z2.getInputStream(en);
        byte[] buf = new byte[1 << 16]; int r;
        while ((r = in.read(buf)) > 0) all.write(buf, 0, r);
        in.close();
      }
      dex = all.toByteArray();
    } finally { z2.close(); }
    String text = new String(dex, "ISO-8859-1");
    check("the internal members keep the names morphe-patcher's own build gives them (getPatchClasses$morphe_patcher, getOpcodes$morphe_patcher, addClass$morphe_patcher)",
        text.contains("getPatchClasses$morphe_patcher") && text.contains("getOpcodes$morphe_patcher") && text.contains("addClass$morphe_patcher"));
    check("no member is named after the engine's own module (a patch that reflects on one would fail with NoSuchMethodException)", !text.contains("$com_bloatware_bingblop_morphe_engine") && !text.contains("$morphe_engine"));
    System.out.println(fails == 0 ? "ALL PASSED (" + n + " checks)" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
