import com.bloatware.bingblop.RishShell;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Regression tests for the Rish review findings, run against a real mksh (Android's /system/bin/sh) with toybox applets
 * on the PATH (ps, grep, kill, printf ... like Android has), not just dash.
 */
public class RishReviewTest {
  static int fails = 0;
  static void check(String name, boolean ok) { check(name, ok, null); }
  static void check(String name, boolean ok, String detail) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) { fails++; if (detail != null) System.out.println("     detail: " + detail); } }

  static final String ENV = System.getProperty("rishenv");   // folder with bin/mksh and tb/<toybox applets>, made by run.js
  static final RishShell.Spawner SPAWNER = new RishShell.Spawner() {
    public Process spawn(String[] argv) throws Exception {
      String[] a = argv.clone();
      if (a[0].equals("sh")) a[0] = ENV + "/bin/mksh";
      ProcessBuilder pb = new ProcessBuilder(a);
      pb.directory(new File("/"));
      pb.environment().put("PATH", ENV + "/tb:/usr/bin:/bin");
      return pb.start();
    }
  };
  static class Collect implements RishShell.Sink {
    final StringBuilder sb = new StringBuilder();
    public synchronized void onOutput(String t) { sb.append(t); }
    public synchronized String text() { return sb.toString(); }
  }
  static String vis(String s) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) { if (c == '\n') b.append("\\n"); else if (c < 32) b.append(String.format("\\x%02x", (int) c)); else b.append(c); } return b.toString(); }

  /** processes whose command line is exactly "sleep N" */
  static int count(String n) {
    int c = 0;
    File[] fs = new File("/proc").listFiles();
    if (fs == null) return 0;
    for (File f : fs) {
      if (!f.getName().matches("[0-9]+")) continue;
      try {
        byte[] b = Files.readAllBytes(new File(f, "cmdline").toPath());
        String s = new String(b).replace('\0', ' ').trim();
        if (s.equals("sleep " + n) || s.endsWith("/sleep " + n)) c++;
      } catch (Exception e) { }
    }
    return c;
  }
  static void killAll(String n) {
    File[] fs = new File("/proc").listFiles();
    if (fs == null) return;
    for (File f : fs) {
      if (!f.getName().matches("[0-9]+")) continue;
      try {
        byte[] b = Files.readAllBytes(new File(f, "cmdline").toPath());
        String s = new String(b).replace('\0', ' ').trim();
        if (s.equals("sleep " + n) || s.endsWith("/sleep " + n)) new ProcessBuilder("/bin/kill", "-9", f.getName()).start().waitFor();
      } catch (Exception e) { }
    }
  }
  static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }
  // A shell may print its completion marker before its asynchronous child has
  // exec'd sleep. Wait for the observable process, with a hard startup deadline.
  static boolean awaitCount(String n, int expected, long timeoutMs) {
    long deadline = System.nanoTime() + timeoutMs * 1000000L;
    do {
      if (count(n) == expected) return true;
      sleepMs(20);
    } while (System.nanoTime() < deadline);
    return count(n) == expected;
  }
  static RishShell.Result runStop(final RishShell shell, String cmd, long stopAfterMs, long timeout, RishShell.Sink sink) throws Exception {
    if (stopAfterMs >= 0) new Thread(() -> { sleepMs(stopAfterMs); shell.stop(); }).start();
    return shell.run(cmd, timeout, sink);
  }

  public static void main(String[] args) throws Exception {
    for (String n : new String[]{"7771", "7772", "7773", "7774"}) killAll(n);

    // ---- 1. a shell that ends itself on a mistake is started again, in the same folder ----
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      check("mksh: starts, uid and prompt read", s.isAlive() && s.uid() >= 0 && s.prompt().contains(":/ "));
      s.run("cd /tmp; export KEEP=1", 5000, null);
      Collect c = new Collect();
      RishShell.Result r = s.run(". /no/such/file_xyz.sh", 10000, c);
      check("sourcing a missing file (mksh quits on it): revived, not exited", r.revived && !r.exited && s.isAlive(), "revived=" + r.revived + " exited=" + r.exited + " out=" + vis(c.text()));
      check("…in the same folder", "/tmp".equals(r.cwd) && "/tmp".equals(s.cwd()));
      c = new Collect(); s.run("pwd; echo KEEP=[$KEEP]", 5000, c);
      check("…it works again (exports from before are gone, as said)", c.text().equals("/tmp\nKEEP=[]\n"), vis(c.text()));
      for (String bad : new String[]{"export my-var=1", "exec /no/such/binary_xyz", "echo $((1/0))", "readonly RO=1; RO=2", "shift 9"}) {
        r = s.run(bad, 10000, null);
        check("a mistake like '" + bad + "' never leaves the session dead", s.isAlive() && !r.exited, "revived=" + r.revived + " exited=" + r.exited);
      }
      r = s.run("exit 3", 10000, null);
      check("a typed `exit 3` really ends the session (not revived) with its status", r.exited && !r.revived && r.exit == 3 && !s.isAlive(), "exited=" + r.exited + " revived=" + r.revived + " exit=" + r.exit);
      s.start();
      r = s.run("echo bye; exit", 10000, null);
      check("`echo bye; exit` ends it too", r.exited && !r.revived);
      s.start();
      r = s.run("( exit 5 )", 10000, null);
      check("a subshell exit doesn't end the session", !r.exited && r.exit == 5 && s.isAlive());
      s.close();
    }

    // ---- 2. STOP: gentle first, never kills background jobs of an unrelated command, reaches grandchildren ----
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      s.run("sleep 7771 &", 5000, null);
      check("background job started", awaitCount("7771", 1, 2000), "found: " + count("7771"));
      long t0 = System.currentTimeMillis();
      RishShell.Result r = runStop(s, "sleep 30", 300, 60000, new Collect());
      long took = System.currentTimeMillis() - t0;
      sleepMs(200);
      check("STOP ends the foreground job within a second or so (SIGINT)", r.stopped && !r.restarted && took < 2500, "took " + took + " restarted=" + r.restarted);
      check("…and the earlier background job survives", count("7771") == 1);
      check("…shell state is intact", s.isAlive() && s.cwd().equals("/"));
      s.run("export RISH_T=bar; RISH_V=baz", 5000, null);
      runStop(s, "sleep 30", 300, 60000, new Collect());
      Collect cv = new Collect(); s.run("echo [$RISH_T] [$RISH_V]", 5000, cv);
      check("…variables and exports survive a stop", cv.text().equals("[bar] [baz]\n"), vis(cv.text()));
      s.close();
      sleepMs(300);
      check("closing the shell ends the background job too", count("7771") == 0, "left: " + count("7771"));
      killAll("7771");
    }
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      Collect c = new Collect();
      long t0 = System.currentTimeMillis();
      RishShell.Result r = runStop(s, "sh -c 'sleep 7772; echo done-gc'", 500, 60000, c);
      long took = System.currentTimeMillis() - t0;
      sleepMs(300);
      check("STOP on `sh -c '…'` ends it and its grandchild (no orphan)", r.stopped && count("7772") == 0 && !c.text().contains("done-gc"), "took " + took + " alive=" + count("7772") + " restarted=" + r.restarted);
      check("…without replacing the shell", !r.restarted && s.isAlive());
      s.close(); killAll("7772");
    }
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      s.run("cd /tmp", 5000, null);
      long t0 = System.currentTimeMillis();
      RishShell.Result r = runStop(s, "sh -c 'trap \"\" INT TERM; sleep 7774; echo survived'", 300, 60000, new Collect());
      long took = System.currentTimeMillis() - t0;
      sleepMs(300);
      check("a command that ignores INT and TERM is ended by KILL in the end", r.stopped && count("7774") == 0, "took " + took + " alive=" + count("7774"));
      check("…the shell survives that, still in its folder", s.isAlive() && "/tmp".equals(s.cwd()) && !r.restarted);
      s.close(); killAll("7774");
    }
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      s.run("cd /tmp", 5000, null);
      long t0 = System.currentTimeMillis();
      RishShell.Result r = runStop(s, "while true; do sleep 1; done", 400, 60000, new Collect());
      long took = System.currentTimeMillis() - t0;
      check("a loop in the shell itself ends via a restart in the same folder (the documented limit)", r.stopped && s.isAlive() && "/tmp".equals(s.cwd()) && took < 9000, "took " + took + " restarted=" + r.restarted + " cwd=" + s.cwd());
      s.close();
    }

    // ---- 3. closing ends a running command and can't be undone by a restart in flight ----
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      final RishShell sf = s;
      new Thread(() -> { sleepMs(500); sf.close(); }).start();
      long t0 = System.currentTimeMillis();
      RishShell.Result r = s.run("sleep 7773", 60000, new Collect());
      long took = System.currentTimeMillis() - t0;
      sleepMs(500);
      check("close() during a foreground command: run returns exited", r.exited && took < 3500, "took " + took);
      check("…and the foreground child is gone, not orphaned", count("7773") == 0, "alive=" + count("7773"));
      killAll("7773");
    }
    {
      final AtomicInteger spawns = new AtomicInteger();
      final AtomicReference<Process> last = new AtomicReference<Process>();
      RishShell.Spawner slow = argv -> {
        if (argv.length == 1 && spawns.incrementAndGet() == 2) sleepMs(900);       // the restart's spawn is slow
        Process p = SPAWNER.spawn(argv);
        if (argv.length == 1) last.set(p);
        return p;
      };
      final RishShell s = new RishShell(slow);
      s.start();
      new Thread(() -> { sleepMs(5000); s.close(); }).start();      // lands while the replacement shell is being spawned
      long t0 = System.currentTimeMillis();
      RishShell.Result r = null;
      boolean threw = false;
      try {
        r = s.run("while true; do sleep 0.1; done", 200, null);
      } catch (IOException e) { threw = true; }
      sleepMs(300);
      Process p = last.get();
      boolean osAlive;
      try { p.exitValue(); osAlive = false; } catch (IllegalThreadStateException e) { osAlive = true; }
      check("close() while the shell is being replaced doesn't bring it back", !s.isAlive() && !osAlive, "alive=" + s.isAlive() + " os=" + osAlive + " threw=" + threw + " r=" + (r == null ? null : r.restarted));
      if (osAlive) p.destroyForcibly();
    }

    // ---- 4. a shell that starts but never answers makes start() fail, not spin ----
    {
      final AtomicInteger spawns = new AtomicInteger();
      RishShell.Spawner mute = argv -> {
        if (argv.length == 1) { spawns.incrementAndGet(); return new ProcessBuilder("/bin/sleep", "60").start(); }
        return SPAWNER.spawn(argv);
      };
      RishShell s = new RishShell(mute, 800);
      long t0 = System.currentTimeMillis();
      String err = null;
      try { s.start(); } catch (Exception e) { err = e.getMessage(); }
      long took = System.currentTimeMillis() - t0;
      check("a silent shell: start() gives up with an error", err != null, String.valueOf(err));
      check("…once, in bounded time, without respawning in a loop", spawns.get() == 1 && took < 12000, "spawns=" + spawns.get() + " took=" + took);
      check("…and leaves nothing running", !s.isAlive());
    }

    // ---- 5. the trailer can't be hidden by what a command does ----
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      Collect c = new Collect();
      long t0 = System.currentTimeMillis();
      RishShell.Result r = s.run("PATH=/nonexistent; echo path-gone", 8000, c);
      check("PATH=/nonexistent: the command returns (printf is called by full path)", c.text().equals("path-gone\n") && !r.stopped && !r.restarted && System.currentTimeMillis() - t0 < 4000, vis(c.text()) + " stopped=" + r.stopped + " restarted=" + r.restarted);
      s.run("exec >/dev/null", 8000, c);
      t0 = System.currentTimeMillis();
      c = new Collect();
      r = s.run("echo this-goes-to-null", 8000, c);
      check("exec >/dev/null: later commands still return promptly (trailer is on fd 3)", !r.stopped && !r.restarted && System.currentTimeMillis() - t0 < 4000, "stopped=" + r.stopped + " restarted=" + r.restarted);
      s.close();
    }
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      Collect c = new Collect();
      RishShell.Result r = s.run("mkdir -p /tmp/rish_nl_test && cd \"/tmp/rish_nl_test\" && mkdir -p \"a\nb\" && cd \"a\nb\" && pwd", 8000, c);
      check("a newline inside the folder name: cwd is exact", "/tmp/rish_nl_test/a\nb".equals(r.cwd) && "/tmp/rish_nl_test/a\nb".equals(s.cwd()), vis(r.cwd));
      check("…and nothing leaks out as stray output after the command's own output", c.text().equals("/tmp/rish_nl_test/a\nb\n"), vis(c.text()));
      s.run("cd /; rm -rf /tmp/rish_nl_test", 8000, null);
      s.close();
    }
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      Collect c = new Collect();
      s.run("printf 'a\\036b\\n'", 5000, c);
      check("output containing the record separator itself comes back untouched", c.text().equals("a\u001eb\n"), vis(c.text()));
      s.close();
    }

    // ---- 6. start(cwd) ----
    {
      RishShell s = new RishShell(SPAWNER);
      s.start("/tmp");
      check("start(cwd) opens the shell in that folder", "/tmp".equals(s.cwd()));
      s.close();
      s.start();
      check("a plain start() after close() works and starts at /", s.isAlive() && "/".equals(s.cwd()));
      s.close();
    }
    for (String n : new String[]{"7771", "7772", "7773", "7774"}) killAll(n);
    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
