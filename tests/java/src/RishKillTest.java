import com.bloatware.bingblop.RishShell;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * A hung command is killed: RishShell.run ends a command that never finishes (timeout or stop()), within a bounded time, and the process tree
 * it started (a `sleep` child, a grandchild, one that ignores INT and TERM) is dead afterwards. Real processes: a real mksh with toybox applets on the
 * PATH (what run.js builds as rishenv, prerequisite rish). Every `sleep` has its own number, drawn at random per run, so the suite finds (and kills) exactly its own processes in /proc.
 * A helper that waits for a death kills the survivor itself, so a failing run leaves no `sleep` behind.
 */
public class RishKillTest {
  static int fails = 0, n = 0;
  static void is(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + (detail == null ? "" : ": " + detail)); } }

  static final String ENV = System.getProperty("rishenv");   // folder with bin/mksh and tb/<toybox applets>, made by run.js
  /** The numbers of the sleeps this run starts, drawn per run: only a process that sleeps exactly this long is looked for or killed, so nothing else on the host can match. */
  static final String[] NUMS = new String[8];
  static {
    long base = 10000000L + new java.security.SecureRandom().nextInt(80000000);
    for (int i = 0; i < NUMS.length; i++) NUMS[i] = String.valueOf(base + i * 7919L);
  }
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

  /** pids of the processes whose command line is `sleep N` (no zombies: their cmdline is empty) */
  static List<String> pids(String num) {
    List<String> out = new ArrayList<String>();
    File[] fs = new File("/proc").listFiles();
    if (fs == null) return out;
    for (File f : fs) {
      if (!f.getName().matches("[0-9]+")) continue;
      try {
        String s = new String(Files.readAllBytes(new File(f, "cmdline").toPath())).replace('\0', ' ').trim();
        if (s.equals("sleep " + num) || s.endsWith("/sleep " + num)) out.add(f.getName());
      } catch (Exception e) { }
    }
    return out;
  }
  static void kill9(String pid) { try { new ProcessBuilder(ENV + "/tb/kill", "-9", pid).redirectErrorStream(true).start().waitFor(); } catch (Exception e) { } }
  static void killAll() { for (String n : NUMS) for (String p : pids(n)) kill9(p); }
  static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { } }
  static boolean pidAlive(String pid) { return new File("/proc/" + pid + "/cmdline").exists() && !zombie(pid); }
  static boolean zombie(String pid) {
    try { return new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath())).replaceAll("^.*\\) ", "").startsWith("Z"); } catch (Exception e) { return true; }
  }

  /** The process with this command line is there (the command has really started it) within timeoutMs. */
  static boolean started(String num, long timeoutMs) {
    long end = System.nanoTime() + timeoutMs * 1000000L;
    do { if (!pids(num).isEmpty()) return true; sleepMs(20); } while (System.nanoTime() < end);
    return false;
  }
  /** True when no `sleep num` is left within timeoutMs. A survivor is killed here, so a failing run leaves nothing behind. */
  static boolean gone(String num, long timeoutMs) {
    long end = System.nanoTime() + timeoutMs * 1000000L;
    do { if (pids(num).isEmpty()) return true; sleepMs(20); } while (System.nanoTime() < end);
    for (String p : pids(num)) kill9(p);
    return false;
  }
  /** True when that pid is dead within timeoutMs; a survivor is killed. */
  static boolean pidGone(String pid, long timeoutMs) {
    long end = System.nanoTime() + timeoutMs * 1000000L;
    do { if (!pidAlive(pid)) return true; sleepMs(20); } while (System.nanoTime() < end);
    kill9(pid);
    return false;
  }
  static RishShell.Result runStop(final RishShell shell, String cmd, final long stopAfterMs, long timeout, RishShell.Sink sink) throws Exception {
    new Thread(new Runnable() { public void run() { sleepMs(stopAfterMs); shell.stop(); } }).start();
    return shell.run(cmd, timeout, sink);
  }

  public static void main(String[] args) throws Exception {
    try {
      killAll();
      run();
    } finally {
      killAll();
    }
    System.out.println(n + " checks, " + fails + " failed");
    if (fails > 0) System.exit(1);
  }

  static void run() throws Exception {
    // 1. timeout: a command that never ends and prints nothing is ended soon after the deadline, with its child
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      s.run("cd /tmp; export KEPT=yes", 5000, null);
      long t0 = System.currentTimeMillis();
      RishShell.Result r = s.run("sleep " + NUMS[0], 700, new Collect());
      long took = System.currentTimeMillis() - t0;
      is("timeout: the call reports stopped and timedOut", r.stopped && r.timedOut && !r.exited, "stopped=" + r.stopped + " timedOut=" + r.timedOut + " exited=" + r.exited);
      is("timeout: it returns soon after the deadline, not when the sleep would end", took >= 650 && took < 5000, took + " ms");
      is("timeout: the sleep child is dead", gone(NUMS[0], 3000), "sleep " + NUMS[0] + " still alive");
      is("timeout: the shell itself was not replaced and is still in its folder", !r.restarted && s.isAlive() && "/tmp".equals(s.cwd()), "restarted=" + r.restarted + " cwd=" + s.cwd());
      // the contract of a stop: the same shell goes on, with its variables
      Collect c = new Collect();
      RishShell.Result r2 = s.run("echo [$KEPT] $PWD", 5000, c);
      is("after a timeout the session is reusable, variables and folder kept", "[yes] /tmp\n".equals(c.text()) && r2.exit == 0 && !r2.stopped, c.text());
      s.close();
    }

    // 2. stop(): same, ended by the caller, and a grandchild (sh -c 'sleep ...; ...') goes too
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      Collect c = new Collect();
      long t0 = System.currentTimeMillis();
      RishShell.Result r = runStop(s, "sh -c 'sh -c \"sleep " + NUMS[1] + "\"; echo after-stop'", 500, 60000, c);
      long took = System.currentTimeMillis() - t0;
      is("stop: reported as stopped, not as a timeout", r.stopped && !r.timedOut && !r.exited, "stopped=" + r.stopped + " timedOut=" + r.timedOut);
      is("stop: it returns soon after the stop, long before the 60 s timeout", took >= 450 && took < 5000, took + " ms");
      is("stop: the grandchild sleep (two shells down) is dead", gone(NUMS[1], 3000), "sleep " + NUMS[1] + " still alive");
      is("stop: the rest of the script did not run", !c.text().contains("after-stop"), c.text());
      is("stop: the shell stays the same one", !r.restarted && s.isAlive(), "restarted=" + r.restarted);
      s.close();
    }

    // 3. output before the hang is delivered, and nothing arrives after the call returned
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      Collect c = new Collect();
      RishShell.Result r = s.run("echo one; echo two; sleep " + NUMS[2], 900, c);
      is("output then hang: both lines arrived, in order, before the call ended", c.text().startsWith("one\ntwo\n"), c.text());
      is("output then hang: the call timed out", r.timedOut && r.stopped, "timedOut=" + r.timedOut);
      is("output then hang: the sleep is dead", gone(NUMS[2], 3000), "sleep " + NUMS[2] + " still alive");
      String seen = c.text();
      sleepMs(300);
      is("output then hang: the sink is silent after the call returned", seen.equals(c.text()), seen + " -> " + c.text());
      s.close();
    }

    // 4. a command that ignores INT and TERM is ended by KILL in the end (stages are 1.2 s apart), still inside a bound
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      long t0 = System.currentTimeMillis();
      RishShell.Result r = s.run("sh -c 'trap \"\" INT TERM; sleep " + NUMS[3] + "; echo survived'", 500, new Collect());
      long took = System.currentTimeMillis() - t0;
      is("ignores INT and TERM: it is still ended, by the timeout", r.stopped && r.timedOut, "stopped=" + r.stopped + " timedOut=" + r.timedOut);
      is("ignores INT and TERM: it took the INT, TERM and KILL stages, and no more", took >= 2500 && took < 7000, took + " ms");
      is("ignores INT and TERM: the sleep is dead", gone(NUMS[3], 3000), "sleep " + NUMS[3] + " still alive");
      is("ignores INT and TERM: the shell is the same one", !r.restarted && s.isAlive(), "restarted=" + r.restarted);
      s.close();
    }

    // 5. a loop in the shell itself has no child to signal: the shell is replaced (the documented limit); the old shell must be dead, the folder kept
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      s.run("cd /tmp", 5000, null);
      String oldPid = String.valueOf(s.pid());
      long t0 = System.currentTimeMillis();
      RishShell.Result r = s.run("while :; do :; done", 500, new Collect());
      long took = System.currentTimeMillis() - t0;
      is("busy loop in the shell: it returns in bounded time, flagged restarted", r.stopped && r.restarted && took < 9000, "stopped=" + r.stopped + " restarted=" + r.restarted + " " + took + " ms");
      is("busy loop in the shell: the old shell process is dead", pidGone(oldPid, 3000), "pid " + oldPid + " still alive");
      is("busy loop in the shell: a new shell took over, in the same folder", s.isAlive() && s.pid() > 0 && !oldPid.equals(String.valueOf(s.pid())) && "/tmp".equals(s.cwd()), "pid=" + s.pid() + " cwd=" + s.cwd());
      Collect c = new Collect();
      s.run("echo back", 5000, c);
      is("busy loop in the shell: the new shell answers", "back\n".equals(c.text()), c.text());
      s.close();
    }

    // 6. close() ends a running command and its child too
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      final RishShell sf = s;
      new Thread(new Runnable() { public void run() { started(NUMS[4], 5000); sf.close(); } }).start();
      long t0 = System.currentTimeMillis();
      RishShell.Result r = s.run("sleep " + NUMS[4], 60000, new Collect());
      long took = System.currentTimeMillis() - t0;
      is("close(): the running call returns, exited, soon", r.exited && took < 5000, "exited=" + r.exited + " " + took + " ms");
      is("close(): the sleep child is dead", gone(NUMS[4], 3000), "sleep " + NUMS[4] + " still alive");
    }

    // 7. a command that finishes by itself is left alone, also when it runs for a while below the timeout
    {
      RishShell s = new RishShell(SPAWNER); s.start();
      Collect c = new Collect();
      long t0 = System.currentTimeMillis();
      RishShell.Result r = s.run("echo start; sleep 1; echo end", 8000, c);
      long took = System.currentTimeMillis() - t0;
      is("a normal command: its whole output", "start\nend\n".equals(c.text()), c.text());
      is("a normal command: status 0, not stopped, not timed out, not restarted", r.exit == 0 && !r.stopped && !r.timedOut && !r.restarted && !r.exited && !r.revived, "exit=" + r.exit + " stopped=" + r.stopped);
      is("a normal command: it ended by itself, well before the timeout", took >= 900 && took < 4000, took + " ms");
      // a stop() with nothing running does nothing to the next command
      s.stop();
      c = new Collect();
      r = s.run("sleep 1; echo fine", 8000, c);
      is("a stop() with nothing running does not hit the next command", "fine\n".equals(c.text()) && r.exit == 0 && !r.stopped, c.text() + " stopped=" + r.stopped);
      // a background job of an earlier command is not part of a later command's timeout
      s.run("sleep " + NUMS[5] + " &", 5000, null);
      is("setup: the background job runs", started(NUMS[5], 3000), "no sleep " + NUMS[5]);
      r = s.run("sleep " + NUMS[6], 500, new Collect());
      is("a timeout of a later command leaves the earlier background job alone", gone(NUMS[6], 3000) && !pids(NUMS[5]).isEmpty(), NUMS[5] + " alive=" + pids(NUMS[5]).size());
      s.close();
      is("close(): the background job is gone too", gone(NUMS[5], 3000), "sleep " + NUMS[5] + " still alive");
    }
  }
}
