package com.bloatware.bingblop;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * build.sh stops when javac fails (review SYS-2). javac still writes the classes that compiled, MainActivity among them, when another
 * file has errors; a build that went on would dex and sign an APK with classes missing. The real build.sh is run in a scratch folder
 * with a tiny source tree and stand-ins for aapt2 and d8, so only the Java step is real.
 *
 * Whether javac writes MainActivity.class before it meets the error depends on the order of the files on its command line (it
 * generates class by class and stops generating at the first error). build.sh lists src/ before the generated gen/, so the broken
 * class is put where the stand-in aapt2 writes R.java: then MainActivity.class is always written, as it is on a phone build whose
 * broken file happens to come late.
 *
 * build.sh also stops when the 64-bit adb (assets/libadb.so) is missing for an arm64 or universal APK (finding BLD-2): it used to
 * skip the file in silence and sign an APK on which no adb mode can start, while the 32-bit branch already stopped.
 */
public class BuildScriptTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + (detail == null ? "" : "\n---\n" + detail + "\n---")); } }

  static File repo() {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    for (int i = 0; d != null && i < 8; i++, d = d.getParentFile()) if (new File(d, "build.sh").isFile() && new File(d, "AndroidManifest.xml").isFile()) return d;
    return null;
  }

  static boolean onPath(String tool) {
    String p = System.getenv("PATH");
    if (p != null) for (String d : p.split(File.pathSeparator)) if (new File(d, tool).canExecute()) return true;
    return false;
  }

  static void write(File f, String text) throws Exception {
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
  }

  static void jar(File f) throws Exception {
    f.getParentFile().mkdirs();
    try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
      z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
      z.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
      z.closeEntry();
    }
  }

  static void exe(File f, String text) throws Exception {
    write(f, text);
    f.setExecutable(true);
  }

  static int status;

  /** Runs a copy of build.sh in a fresh folder: src/ holds MainActivity, the generated folder a class with the given body; the output. */
  static String build(File repo, File dir, String generated) throws Exception { return build(repo, dir, generated, true, null); }

  /** The same, with or without a stand-in assets/libadb.so, for the given ABI (null: build.sh's default). */
  static String build(File repo, File dir, String generated, boolean withAdb, String abi) throws Exception {
    deleteTree(dir);
    dir.mkdirs();
    Files.copy(new File(repo, "build.sh").toPath(), new File(dir, "build.sh").toPath());
    write(new File(dir, "AndroidManifest.xml"), "<manifest/>\n");
    new File(dir, "res").mkdirs();
    new File(dir, "assets").mkdirs();
    if (withAdb) write(new File(dir, "assets/libadb.so"), "stand-in adb\n");
    jar(new File(dir, "libs/empty.jar"));
    jar(new File(dir, "android.jar"));       // no java.lang inside: build.sh then compiles with --release 8
    write(new File(dir, "src/com/bloatware/bingblop/MainActivity.java"), "package com.bloatware.bingblop;\npublic class MainActivity { int f() { return 1; } }\n");
    write(new File(dir, "genfiles/com/bloatware/bingblop/Late.java"), "package com.bloatware.bingblop;\nclass Late { " + generated + " }\n");
    File stubs = new File(dir, "stubs");
    exe(new File(stubs, "aapt2"), "#!/bin/sh\n# stand-in: makes the files build.sh expects; link copies genfiles/ to the --java folder (where R.java goes)\n"
        + "while [ $# -gt 0 ]; do case \"$1\" in -o) : > \"$2\";; --java) mkdir -p \"$2\" && cp -R " + new File(dir, "genfiles").getPath() + "/. \"$2\"/;; esac; shift; done\nexit 0\n");
    exe(new File(stubs, "d8"), "#!/bin/sh\necho 'stand-in d8 ran'\nexit 0\n");
    ProcessBuilder pb = new ProcessBuilder("bash", new File(dir, "build.sh").getPath()).directory(dir).redirectErrorStream(true);
    pb.environment().put("AAPT2", new File(stubs, "aapt2").getPath());
    pb.environment().put("D8", new File(stubs, "d8").getPath());
    pb.environment().put("ANDROID_JAR", new File(dir, "android.jar").getPath());
    pb.environment().remove("BOOTCLASSPATH");
    pb.environment().remove("D8_JAR");
    pb.environment().put("KEYSTORE", new File(dir, "throwaway.keystore").getPath());
    if (abi == null) pb.environment().remove("ABI"); else pb.environment().put("ABI", abi);
    Process p = pb.start();
    byte[] out = p.getInputStream().readAllBytes();
    if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); status = -1; }
    else status = p.exitValue();
    return new String(out, StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*m", "");
  }

  static void deleteTree(File f) {
    File[] c = f.listFiles();
    if (c != null) for (File x : c) deleteTree(x);
    f.delete();
  }

  public static void main(String[] args) throws Exception {
    File repo = repo();
    if (repo == null) { System.out.println("SKIP build.sh not found"); System.out.println("PASS BuildScriptTest: 0 checks"); return; }
    for (String tool : new String[] {"bash", "javac", "python3"}) {
      if (!onPath(tool)) { System.out.println("SKIP needs " + tool + " on PATH"); System.out.println("PASS BuildScriptTest: 0 checks"); return; }
    }
    File work = Files.createTempDirectory("buildsh").toFile();
    try {
      String bad = build(repo, new File(work, "bad"), "static int g() { int x = \"s\"; return x; }");
      boolean mainBuilt = new File(work, "bad/obj/classes/com/bloatware/bingblop/MainActivity.class").isFile();
      check("a compile error in a class other than MainActivity stops the build (non-zero exit)", status != 0 && status != -1, "exit " + status + "\n" + bad);
      check("... although javac did write MainActivity.class (the old class check alone would have passed)", mainBuilt, bad);
      check("... with the reason in words", bad.contains("Java compilation failed"), bad);
      check("... and javac's own error is shown (file, line and message)", bad.contains("Late.java:2: error: incompatible types"), bad);
      check("... and nothing after the Java step ran (no dex, no APK)", !bad.contains("Converting to Dalvik") && !bad.contains("stand-in d8 ran"), bad);

      String good = build(repo, new File(work, "good"), "static int g() { return 1; }");
      check("sources that compile go on to the dex step", good.contains("Converting to Dalvik") && good.contains("stand-in d8 ran") && !good.contains("Java compilation failed"), "exit " + status + "\n" + good);
      check("javac's [options] warnings are not shown", !good.contains("warning: [options]") && !bad.contains("warning: [options]"), good);

      for (String abi : new String[] {null, "universal"}) {
        String what = "without assets/libadb.so the " + (abi == null ? "default (arm64-v8a)" : abi) + " build";
        String noAdb = build(repo, new File(work, "noadb-" + abi), "static int g() { return 1; }", false, abi);
        check(what + " stops (non-zero exit)", status != 0 && status != -1, "exit " + status + "\n" + noAdb);
        check(what + " names the missing file", noAdb.contains("assets/libadb.so is missing"), noAdb);
        check(what + " stops before anything is compiled or signed", !noAdb.contains("Compiling Java sources") && !noAdb.contains("Built and signed"), noAdb);
      }
    } finally {
      deleteTree(work);
    }

    System.out.println(fails == 0 ? "PASS BuildScriptTest: " + n + " checks" : "FAILED BuildScriptTest: " + fails + " of " + n);
    if (fails != 0) System.exit(1);
  }
}
