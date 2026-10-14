package com.bloatware.bingblop;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Starting Shizuku through adb: the command, run for real through sh against fake data folders, and the reading of its output. */
public class ShizukuStartTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }

  static String run(String cmd) throws Exception {
    Process p = new ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out;
  }

  public static void main(String[] x) throws Exception {
    is("the Shizuku package is valid", ShizukuStart.validPackage("moe.shizuku.privileged.api"));
    is("the Shizuku+ package is valid", ShizukuStart.validPackage("af.shizuku.plus.api"));
    String[] bad = { null, "", "shizuku", "a.b;rm -rf /", "a.b c", "a.b'c", "../x.y", "a..b", ".a.b", "a.b.", "1a.b", "a.b$(id)", "a.b\nc", "a.b`id`" };
    for (String b : bad) is("not a package: " + b, !ShizukuStart.validPackage(b) && ShizukuStart.command(b) == null && ShizukuStart.scriptPaths(b).isEmpty());

    List<String> paths = ShizukuStart.scriptPaths("moe.shizuku.privileged.api");
    is("the script is looked for in the app's own data folder", paths.size() == 2 && paths.get(0).equals("/sdcard/Android/data/moe.shizuku.privileged.api/start.sh")
        && paths.get(1).equals("/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"));

    String cmd = ShizukuStart.command("moe.shizuku.privileged.api");
    is("the command names both paths, quoted", cmd.contains("'/sdcard/Android/data/moe.shizuku.privileged.api/start.sh'") && cmd.contains("'/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh'"));
    is("the command is valid sh", run("sh -n -c " + RishShell.quote(cmd) + " && echo ok").trim().equals("ok"));

    // run the command for real: the paths in it point into a fake phone
    File root = Files.createTempDirectory("shz").toFile();
    File data = new File(root, "Android/data/moe.shizuku.privileged.api");
    String fake = cmd.replace("/sdcard", root.getPath()).replace("/storage/emulated/0", root.getPath() + "/none");

    String none = run(fake);
    ShizukuStart.Result r0 = ShizukuStart.parse(none);
    is("no script: NO_START_SCRIPT, nothing run", r0.noScript && r0.script.isEmpty() && r0.exit == -1 && r0.text.isEmpty());

    data.mkdirs();
    Files.write(new File(data, "start.sh").toPath(), "echo starting shizuku\necho 'warning: x' >&2\nexit 0\n".getBytes());
    ShizukuStart.Result r1 = ShizukuStart.parse(run(fake));
    is("script found and run: path, exit 0, both streams in the text", !r1.noScript && r1.script.endsWith("/start.sh") && r1.exit == 0
        && r1.text.contains("starting shizuku") && r1.text.contains("warning: x"));

    Files.write(new File(data, "start.sh").toPath(), "echo boom\nexit 3\n".getBytes());
    ShizukuStart.Result r2 = ShizukuStart.parse(run(fake));
    is("a failing script keeps its exit status and text", r2.exit == 3 && r2.text.equals("boom"));

    Files.write(new File(data, "start.sh").toPath(), "echo EXIT:0\necho NO_START_SCRIPT\n".getBytes());
    ShizukuStart.Result r3 = ShizukuStart.parse(run(fake));
    is("a script that prints the marker words is still read as one that ran", r3.script.endsWith("/start.sh") && !r3.noScript);

    // reading text
    is("null and empty text", ShizukuStart.parse(null).exit == -1 && ShizukuStart.parse("").text.isEmpty());
    is("CRLF and blank lines are dropped", ShizukuStart.parse("SCRIPT:/a/start.sh\r\n\r\nhello\r\nEXIT:0\r\n").text.equals("hello"));
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 2000; i++) big.append("line ").append(i).append('\n');
    ShizukuStart.Result rb = ShizukuStart.parse(big.toString());
    is("a long output is cut to its end", rb.text.length() <= 4000 && rb.text.endsWith("line 1999"));

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
