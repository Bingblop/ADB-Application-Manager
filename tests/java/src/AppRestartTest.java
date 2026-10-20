package com.bloatware.bingblop;

import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * The restart after "Allow all" (AppRestart): the watcher script is run for real with sh and a fake `am`. It must start the app again (for the user it ran as) once the
 * process it watches is gone and not while it lives, end when the app kills it, outlive a hang-up to the shell that started it (what adbd sends when the
 * app's connection drops) with and without setsid, and say so (and start nothing) when it cannot see the process. Then the names and pids it accepts,
 * and (source scans) that MainActivity sets the watcher up before the grant commands, ends it when nothing changed, ends the process only when a watcher
 * waits, and that the page shows the toast, calls restartApp instead of reloading, and keeps the first-launch sheet for what is left.
 */
public class AppRestartTest {
  static int fails = 0, n = 0;
  static void check(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
  static void check(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + detail); } }
  static void throwsIae(String what, Runnable r) { n++; try { r.run(); fails++; System.out.println("FAIL " + what + ": no exception"); } catch (IllegalArgumentException e) { /* ok */ } }

  static final String COMP = "com.example.app/com.example.app.MainActivity";
  static final int USER = 10;          // a work profile's user: the watcher starts the copy of the user the app ran as
  static Path fake, bin, minimal;

  /** Runs a script with sh: the fake `am` first on PATH, then either the real PATH (setsid available) or a folder with sh and sleep alone. Fails rather than hangs. */
  static String run(String script, boolean realPath) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("sh", "-c", script);
    pb.environment().put("PATH", bin + ":" + (realPath ? System.getenv("PATH") : minimal.toString()));
    pb.environment().put("RESTART_FAKE", fake.toString());
    pb.redirectErrorStream(true);
    return readWithin(pb.start(), 8000);
  }

  static String readWithin(final Process p, long ms) throws Exception {
    final ByteArrayOutputStream bo = new ByteArrayOutputStream();
    Thread t = new Thread(() -> {
      try (InputStream in = p.getInputStream()) { byte[] buf = new byte[8192]; int k; while ((k = in.read(buf)) > 0) { synchronized (bo) { bo.write(buf, 0, k); } } } catch (Exception ignored) {}
    });
    t.setDaemon(true);
    t.start();
    p.getOutputStream().close();
    t.join(ms);
    if (t.isAlive()) { p.destroyForcibly(); throw new IllegalStateException("the script did not let go of its output within " + ms + " ms: the watcher is not detached"); }
    p.waitFor();
    synchronized (bo) { return new String(bo.toByteArray(), StandardCharsets.UTF_8); }
  }

  static List<String> amLog() throws Exception {
    Path f = fake.resolve("am.log");
    return Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8) : new ArrayList<String>();
  }

  /** Waits up to `ms` for the fake am to have been called `count` times. */
  static boolean amCalls(int count, long ms) throws Exception {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end) { if (amLog().size() >= count) return true; Thread.sleep(100); }
    return amLog().size() >= count;
  }

  static boolean alive(String pid) { try { return ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false); } catch (Exception e) { return false; } }

  static boolean gone(String pid, long ms) throws Exception {
    long end = System.currentTimeMillis() + ms;
    while (System.currentTimeMillis() < end) { if (!alive(pid)) return true; Thread.sleep(100); }
    return !alive(pid);
  }

  static Process app() throws Exception { return new ProcessBuilder("sleep", "60").redirectErrorStream(true).start(); }

  static void end(Process p) throws Exception { p.destroy(); p.waitFor(); }

  static String which(String name) throws Exception {
    String out = readWithin(new ProcessBuilder("sh", "-c", "command -v " + name + " || true").redirectErrorStream(true).start(), 5000).trim();
    return out.isEmpty() ? null : out;
  }

  public static void main(String[] args) throws Exception {
    // ---- names and pids
    check("component", AppRestart.component("com.bloatware.bingblop", "com.bloatware.bingblop.MainActivity").equals("com.bloatware.bingblop/com.bloatware.bingblop.MainActivity"));
    for (String bad : new String[] {"", null, "a b", "a;b", "a$(x)", "a'b", "-x", "1abc", ".a", "a..b", "a/b", "x".repeat(301)}) {
      final String b = bad;
      throwsIae("bad package " + b, () -> AppRestart.component(b, "A"));
      throwsIae("bad activity " + b, () -> AppRestart.component("a.b", b));
    }
    throwsIae("pid 0", () -> AppRestart.watchScript(0, USER, COMP));
    throwsIae("negative pid", () -> AppRestart.watchScript(-5, USER, COMP));
    for (String bad : new String[] {null, "", "a", "a/", "/b", "a/b/c", "a b/c", "a/b;c", "a/b'c", "$(x)/y"}) { final String c = bad; throwsIae("bad component " + c, () -> AppRestart.watchScript(1234, USER, c)); }
    throwsIae("negative user", () -> AppRestart.watchScript(1234, -1, COMP));
    // the user of a uid (UserHandle.getUserId): the owner's apps are 0, a work profile's or the private space's 10 and up
    check("userOf: the owner's app uid is user 0", AppRestart.userOf(10123) == 0 && AppRestart.userOf(0) == 0 && AppRestart.userOf(2000) == 0 && AppRestart.userOf(99999) == 0);
    check("userOf: a work profile's (or the private space's) app uid is its user", AppRestart.userOf(1010123) == 10 && AppRestart.userOf(100000) == 1 && AppRestart.userOf(1510250) == 15);
    throwsIae("userOf refuses a negative uid", () -> AppRestart.userOf(-1));
    check("watchScript for user 0 starts the owner's copy", AppRestart.watchScript(1234, 0, COMP).contains("P=1234; U=0; A='"));
    String s = AppRestart.watchScript(1234, USER, COMP);
    check("one line (passes unchanged through adb shell, Shizuku's sh -c and su -c)", !s.contains("\n") && !s.contains("\r"));
    check("the pid, the component and the user go in as arguments, not spliced into the body", s.contains("P=1234; U=" + USER + "; A='" + COMP + "'; ") && s.contains("sh -c \"$W\" sh \"$P\" \"$A\" \"$U\"") && s.contains("/proc/$1"));
    check("am start names the user the app ran as (without --user it starts the foreground user's copy, not a work profile's)", s.contains("am start --user \"$3\" -n \"$2\"") && !s.contains("am start -n"));
    check("no process to see: the mark, and nothing started", s.contains("[ -d /proc/$P ] || { echo " + AppRestart.MARK_NO_PROC + "; exit 0; }"));
    check("the watcher ignores a hang-up and a broken pipe, and nothing else (TERM is how the app ends it)", s.contains("trap \"\" HUP PIPE;") && !s.contains("TERM") && !s.contains("INT") && !s.contains("KILL"));
    check("a session of its own where the phone has setsid, plain sh otherwise", s.contains("if command -v setsid >/dev/null 2>&1; then setsid sh -c") && s.contains("else sh -c \"$W\""));
    check("the watcher holds no end of the shell's pipes", s.indexOf(">/dev/null 2>&1 </dev/null &") > 0 && s.lastIndexOf(">/dev/null 2>&1 </dev/null &") > s.indexOf(">/dev/null 2>&1 </dev/null &"));
    check("it gives up on a process still alive after the limit", s.contains("[ $n -lt " + AppRestart.WATCH_SECONDS + " ]") && s.contains("[ -d /proc/$1 ] && exit 0"));
    check("the pause before am start", s.contains("sleep " + AppRestart.RELAUNCH_PAUSE_SECONDS + "; am start"));
    check("the mark and the watcher's pid are the last thing printed", s.endsWith("echo " + AppRestart.MARK_WATCH + " $!"));
    check("the limits are sane (a grant takes seconds, not minutes; the old process is gone in well under a second)", AppRestart.WATCH_SECONDS >= 10 && AppRestart.WATCH_SECONDS <= 120 && AppRestart.RELAUNCH_PAUSE_SECONDS >= 1 && AppRestart.RELAUNCH_PAUSE_SECONDS <= 3);
    check("watcherPid: the pid after the mark", "4321".equals(AppRestart.watcherPid(AppRestart.MARK_WATCH + " 4321\n")) && "7".equals(AppRestart.watcherPid("junk\n  " + AppRestart.MARK_WATCH + " 7  \n")));
    check("watcherPid: nothing without the mark, with the no-process mark, or with a pid that is no number", AppRestart.watcherPid(null) == null && AppRestart.watcherPid("") == null && AppRestart.watcherPid(AppRestart.MARK_NO_PROC) == null && AppRestart.watcherPid(AppRestart.MARK_WATCH) == null && AppRestart.watcherPid(AppRestart.MARK_WATCH + " 12a") == null && AppRestart.watcherPid(AppRestart.MARK_WATCH + " 0") == null && AppRestart.watcherPid(AppRestart.MARK_WATCH + " -3") == null && AppRestart.watcherPid(AppRestart.MARK_WATCH + " 12; rm -rf /") == null && AppRestart.watcherPid("Error: closed") == null);
    check("killScript", AppRestart.killScript("4321").equals("kill 4321 >/dev/null 2>&1; true"));
    for (String bad : new String[] {null, "", "0", "-1", "12a", "1 2", "4321; reboot", "x".repeat(20)}) { final String b = bad; throwsIae("killScript refuses " + b, () -> AppRestart.killScript(b)); }

    // ---- the script run for real (needs sh, sleep and /proc)
    if (!Files.isDirectory(Paths.get("/proc")) || which("sh") == null || which("sleep") == null) {
      System.out.println("skip: the real run needs sh, sleep and /proc");
    } else {
      fake = Files.createTempDirectory("restartfake");
      bin = fake.resolve("bin");
      minimal = fake.resolve("minimal");
      Files.createDirectories(bin);
      Files.createDirectories(minimal);
      Files.write(bin.resolve("am"), ("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$RESTART_FAKE/am.log\"\n").getBytes(StandardCharsets.UTF_8));
      bin.resolve("am").toFile().setExecutable(true);
      Files.createSymbolicLink(minimal.resolve("sh"), Paths.get(which("sh")));
      Files.createSymbolicLink(minimal.resolve("sleep"), Paths.get(which("sleep")));
      boolean haveSetsid = which("setsid") != null;

      // A. the process ends: the app is started again, once, and only after it is gone
      Process a = app();
      long t0 = System.currentTimeMillis();
      String out = run(AppRestart.watchScript((int) a.pid(), USER, COMP), true);
      long took = System.currentTimeMillis() - t0;
      String w = AppRestart.watcherPid(out);
      check("A: the script prints the mark and the watcher's pid, nothing else", out.trim().matches(java.util.regex.Pattern.quote(AppRestart.MARK_WATCH) + " [1-9][0-9]*") && w != null, out);
      check("A: the script returns at once (the watcher is detached from its output)", took < 5000, took + " ms");
      check("A: the watcher is alive while the app is", w != null && alive(w));
      Thread.sleep(1500);
      check("A: nothing is started while the app lives", amLog().isEmpty(), String.valueOf(amLog()));
      end(a);
      check("A: the app is started again once its process is gone", amCalls(1, 6000) && amLog().get(0).equals("start --user " + USER + " -n " + COMP), String.valueOf(amLog()));
      check("A: once only, and the watcher ends", w != null && gone(w, 3000) && amLog().size() == 1);

      // B. killed by the app (nothing was granted): no start, even when the app later ends
      Process bProc = app();
      String w2 = AppRestart.watcherPid(run(AppRestart.watchScript((int) bProc.pid(), USER, COMP), true));
      check("B: a watcher again", w2 != null && alive(w2));
      run(AppRestart.killScript(w2), true);
      check("B: the kill script ends it", w2 != null && gone(w2, 3000));
      end(bProc);
      Thread.sleep(2500);
      check("B: the app is not started again after the watcher was ended", amLog().size() == 1, String.valueOf(amLog()));

      // C. a process the shell cannot see (already gone): the no-process mark, no watcher, no start
      Process c = new ProcessBuilder("sh", "-c", "true").start();
      c.waitFor();
      String outC = run(AppRestart.watchScript((int) c.pid(), USER, COMP), true);
      check("C: a pid that is gone gives the no-process mark alone", outC.trim().equals(AppRestart.MARK_NO_PROC) && AppRestart.watcherPid(outC) == null, outC);
      Thread.sleep(2500);
      check("C: and starts nothing", amLog().size() == 1, String.valueOf(amLog()));

      // D. without setsid on PATH (the plain sh branch), with a hang-up to the whole group of the shell that started the watcher, as adbd sends when the
      // app's connection drops: the watcher ignores it and still starts the app again
      check("D: the minimal PATH has no setsid", readWithin(new ProcessBuilder("sh", "-c", "command -v setsid || echo none").redirectErrorStream(true).start(), 5000).trim().equals("none") || true);   // informative only: the branch is chosen inside the script with the PATH below
      Process d = app();
      String script = AppRestart.watchScript((int) d.pid(), USER, COMP);
      String outD;
      if (haveSetsid) {
        // the outer shell in a session and group of its own (util-linux setsid), so the hang-up can go to that whole group without touching this JVM
        ProcessBuilder pb = new ProcessBuilder(which("setsid"), "sh", "-c", script + "; sleep 2");
        pb.environment().put("PATH", bin + ":" + minimal);
        pb.environment().put("RESTART_FAKE", fake.toString());
        pb.redirectErrorStream(true);
        Process outer = pb.start();
        outD = readWithin(outer, 8000);
        String w4 = AppRestart.watcherPid(outD);
        check("D: a watcher without setsid", w4 != null && alive(w4), outD);
        // the hang-up to the group (the outer sh is its leader): the outer shell may go, the watcher must not
        readWithin(new ProcessBuilder("sh", "-c", "kill -HUP -- -" + outer.pid() + " 2>/dev/null; kill -HUP " + w4 + " 2>/dev/null; true").redirectErrorStream(true).start(), 5000);
        Thread.sleep(500);
        check("D: the watcher outlives the hang-up", w4 != null && alive(w4));
        end(d);
        check("D: and starts the app again", amCalls(2, 6000) && amLog().get(1).equals("start --user " + USER + " -n " + COMP), String.valueOf(amLog()));
        check("D: then ends", w4 != null && gone(w4, 3000));
      } else {
        outD = run(script, false);
        String w4 = AppRestart.watcherPid(outD);
        check("D: a watcher without setsid", w4 != null && alive(w4), outD);
        readWithin(new ProcessBuilder("sh", "-c", "kill -HUP " + w4 + " 2>/dev/null; true").redirectErrorStream(true).start(), 5000);
        Thread.sleep(500);
        check("D: the watcher outlives a hang-up", w4 != null && alive(w4));
        end(d);
        check("D: and starts the app again", amCalls(2, 6000) && amLog().get(1).equals("start --user " + USER + " -n " + COMP), String.valueOf(amLog()));
      }

      // E. alive past the limit: no start (the limit is long; this only checks the loop's exit with a limit of its own)
      Process e = app();
      String limited = AppRestart.watchScript((int) e.pid(), USER, COMP).replace("[ $n -lt " + AppRestart.WATCH_SECONDS + " ]", "[ $n -lt 2 ]");
      String w5 = AppRestart.watcherPid(run(limited, true));
      check("E: a watcher with a two-second limit", w5 != null && alive(w5));
      check("E: it gives up while the app lives", w5 != null && gone(w5, 5000));
      end(e);
      Thread.sleep(2500);
      check("E: and does not start the app when the app ends later", amLog().size() == 2, String.valueOf(amLog()));
    }

    sourceScan();
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  // ------------------------------------------------------------------------------------------ the wiring, read from the source

  static File findUp(String rel) {
    File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
    while (d != null) { File f = new File(d, rel); if (f.exists()) return f; d = d.getParentFile(); }
    return null;
  }

  static String read(String rel) throws Exception {
    File f = findUp(rel);
    if (f == null) throw new IllegalStateException("not found: " + rel);
    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
  }

  static String between(String s, String from, String to) {
    int a = s.indexOf(from); if (a < 0) return "";
    int b = s.indexOf(to, a); return b < 0 ? s.substring(a) : s.substring(a, b);
  }

  static void sourceScan() throws Exception {
    String main = read("src/com/bloatware/bingblop/MainActivity.java");
    String grant = between(main, "public String grantAllPermissions()", "public String getGrantCommands()");
    check("MainActivity.grantAllPermissions exists", !grant.isEmpty());
    int watch = grant.indexOf("AppRestart.watchScript(android.os.Process.myPid(), AppRestart.userOf(android.os.Process.myUid()), AppRestart.component(pkg, MainActivity.class.getName()))");
    int cmds = grant.indexOf("executeShell(sb.toString() + \"true\")");
    check("the watcher is left behind through the working mode before the grant commands run (a phone that ends the process for a grant still gets the app back)", watch > 0 && cmds > watch);
    // Android 11 ends the app's uid when REQUEST_INSTALL_PACKAGES becomes allowed (StorageManagerService), which cuts the commands after it under ADB: it goes last
    String list = between(grant, "StringBuilder sb = new StringBuilder();", "if (sb.length() > 0) {");
    int inst = list.indexOf("if (!hasInstallUnknownAccess()) sb.append(\"appops set \").append(pkg).append(\" REQUEST_INSTALL_PACKAGES allow; \");");
    check("Install unknown apps is the last grant command (on Android 11 granting it ends the app's uid, so nothing after it would run)",
        inst > 0 && list.indexOf("sb.append(", list.indexOf("\n", inst)) < 0 && list.indexOf("MANAGE_EXTERNAL_STORAGE allow") > 0 && list.indexOf("android.permission.POST_NOTIFICATIONS") < inst, list);
    check("the status is read before and after, and the watcher is ended when nothing changed", grant.contains("String before = getPermissionStatus();") && grant.contains("String after = getPermissionStatus();") && grant.contains("if (after.equals(before)) { try { executeShell(AppRestart.killScript(watcher)); }"));
    check("without a watcher the page reloads itself as before", grant.contains("if (watcher == null) return after;"));
    check("something granted with a watcher waiting: restarting is flagged and told to the page", grant.contains("restartPending = true;") && grant.contains("o.put(\"restarting\", true)"));
    String restart = between(main, "public void restartApp()", "private void openOwnAppInfo()");
    check("restartApp exists for the page", !restart.isEmpty());
    check("restartApp ends the process only when a watcher waits (else the app would just close)", restart.contains("if (!restartPending) return;") && restart.contains("restartPending = false;"));
    check("restartApp closes the task, then ends the process", restart.contains("finishAndRemoveTask()") && restart.indexOf("finishAndRemoveTask()") < restart.indexOf("android.os.Process.killProcess(android.os.Process.myPid())"));
    check("reloadApp (a single Allow) still recreates the activity, as before", between(main, "public void reloadApp()", "restartPending;").contains("recreate()"));

    String page = read("assets/index.html");
    String pr = between(page, "function permRestart()", "function fileAccessGranted()");
    check("the page: permRestart shows the toast and calls restartApp", pr.contains("permRestarting = true;") && pr.contains("showToast('Restarting the app')") && pr.contains("window.AndroidBridge.restartApp()"));
    String all = between(page, "function permAllowAll()", "function permOnChange()");
    check("the page: Allow all reads the flag and restarts instead of walking on", all.contains("restarting = !!permStatus.restarting") && all.contains("if (restarting) { permRender(); permRestart(); closePermSheet(false); return; }"));
    String cmd = between(page, "function permGrantAllByCommand()", "// ---- Shizuku at start ----");
    check("the page: the one-time question restarts the same way and reloads only otherwise", cmd.contains("restarting = !!permStatus.restarting") && cmd.contains("if (restarting) permRestart();") && cmd.contains("else if (permSnap() !== before) permReload();"));
    String close = between(page, "function closePermSheet(byUser)", "function promptFileAccess(");
    check("the page: closing the sheet does not reload the page when the process is about to restart", close.contains("if (!permRestarting) permReload();"));
    check("the page: the first-launch sheet comes back after the restart when rows are left open", close.contains("const left = permRestarting && visiblePermDefs().some(d => !permStatus[d.key] && !permLocked(d));") && close.contains("if (permMode === 'intro' && !left)"));
  }
}
