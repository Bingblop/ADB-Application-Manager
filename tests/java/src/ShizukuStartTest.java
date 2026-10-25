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

  /** The first number group of the first match, or -1. */
  static int num(String s, String regex) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
    return m.find() ? Integer.parseInt(m.group(1)) : -1;
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

    // T-008: the script is not stopped after the 8 s of an ordinary adb shell command (stopping the adb client mid-run may take the start down with it)
    is("the start script may run well over 8 s, and is still bounded", ShizukuStart.SCRIPT_TIMEOUT_MS >= 15000 && ShizukuStart.SCRIPT_TIMEOUT_MS <= 30000);
    File main = null;
    for (File d = new File(System.getProperty("user.dir")).getAbsoluteFile(); d != null && main == null; d = d.getParentFile()) {
      File f = new File(d, "src/com/bloatware/bingblop/MainActivity.java");
      if (f.isFile()) main = f;
    }
    if (main == null) System.out.println("SKIP MainActivity.java not found");
    else {
      String src = new String(Files.readAllBytes(main.toPath()), "UTF-8");
      int at = src.indexOf("private JSONObject startShizukuNow()");
      int end = at < 0 ? -1 : src.indexOf("\n    private ", at + 10);
      String start = at >= 0 && end > at ? src.substring(at, end) : "";
      is("startShizukuNow was found", !start.isEmpty());
      is("startShizukuNow runs the script with SCRIPT_TIMEOUT_MS, not runAdbShell's 8 s", start.contains("runAdbShell(target, cmd, ShizukuStart.SCRIPT_TIMEOUT_MS)")
          && !start.contains("runAdbShell(target, cmd)"));
      int rs = src.indexOf("private String runAdbShell(String target, String cmd, int timeoutMs)");
      int re = rs < 0 ? -1 : src.indexOf("\n    }\n", rs);
      String run = rs >= 0 && re > rs ? src.substring(rs, re) : "";
      int uses = 0;
      for (int i = run.indexOf("\"shell\", cmd), timeoutMs)"); i >= 0; i = run.indexOf("\"shell\", cmd), timeoutMs)", i + 1)) uses++;
      is("runAdbShell's timeout applies to the run and to the run after a reconnect", uses == 2 && !run.contains("cmd), 8000)"));
      is("the plain runAdbShell keeps 8 s for every other command", src.contains("return runAdbShell(target, cmd, 8000);"));

      // T-012: the first-launch sheet waits for a start it offered for longer than startShizukuNow can take, or a slow start opens the sheet first and
      // the Shizuku authorize dialog then comes over it. The bound is read from MainActivity: adb devices, a reconnect, the script, the binder poll.
      int devices = num(src, "buildAdbProcess\\(\"devices\"\\), (\\d+)\\)");
      int reconnect = num(run, "buildAdbProcess\\(\"connect\", target\\), (\\d+)\\)");
      int polls = num(start, "i < (\\d+) && !activityDestroyed");
      int pause = num(start, "Thread\\.sleep\\((\\d+)\\)");
      is("the parts of startShizukuNow's bound were found (" + devices + ", " + reconnect + ", " + polls + " x " + pause + ")", devices > 0 && reconnect > 0 && polls > 0 && pause > 0);
      int bound = devices + reconnect + ShizukuStart.SCRIPT_TIMEOUT_MS + polls * pause;
      File page = new File(main.getParentFile().getParentFile().getParentFile().getParentFile().getParentFile(), "assets/index.html");
      int wait = page.isFile() ? num(new String(Files.readAllBytes(page.toPath()), "UTF-8"), "const SZ_START_WAIT_MS = (\\d+);") : -1;
      is("the page's SZ_START_WAIT_MS (" + wait + " ms) is longer than startShizukuNow's bound (" + bound + " ms)", wait > bound);
    }

    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }
}
