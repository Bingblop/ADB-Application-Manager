import com.bloatware.bingblop.RishShell;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class RishTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  static class Collect implements RishShell.Sink {
    final StringBuilder sb = new StringBuilder();
    int calls = 0;
    public synchronized void onOutput(String t) { sb.append(t); calls++; }
    public synchronized String text() { return sb.toString(); }
  }

  static RishShell.Spawner local = new RishShell.Spawner() {
    public Process spawn(String[] argv) throws Exception {
      ProcessBuilder pb = new ProcessBuilder(argv);
      pb.directory(new File("/"));
      return pb.start();
    }
  };

  static RishShell.Result run(RishShell sh, String cmd, Collect c) throws Exception {
    return sh.run(cmd, 20000, c);
  }

  public static void main(String[] args) throws Exception {
    RishShell sh = new RishShell(local);
    sh.start();
    check("alive after start", sh.isAlive());
    check("pid read", sh.pid() > 1);
    String realUid = new BufferedReader(new InputStreamReader(new ProcessBuilder("id", "-u").start().getInputStream())).readLine().trim();
    check("uid read (" + sh.uid() + ")", String.valueOf(sh.uid()).equals(realUid));
    check("initial cwd is /", "/".equals(sh.cwd()));
    check("prompt has cwd and sign", sh.prompt().endsWith(":/ " + (sh.uid() == 0 ? "#" : "$")));

    Collect c = new Collect();
    RishShell.Result r = run(sh, "echo hi", c);
    check("echo output exact", "hi\n".equals(c.text()));
    check("echo exit 0", r.exit == 0 && !r.exited && !r.stopped);

    c = new Collect();
    r = run(sh, "printf nonl", c);
    check("no trailing newline preserved", "nonl".equals(c.text()));

    c = new Collect();
    r = run(sh, "cd /tmp", c);
    check("cd prints nothing", c.text().isEmpty());
    check("cwd follows cd", "/tmp".equals(r.cwd) && "/tmp".equals(sh.cwd()));
    check("prompt follows cd", sh.prompt().contains(":/tmp "));
    c = new Collect();
    run(sh, "pwd", c);
    check("pwd after cd (state persists)", "/tmp\n".equals(c.text()));

    c = new Collect();
    run(sh, "export RISH_T=bar; RISH_V=baz", c);
    c = new Collect();
    run(sh, "echo $RISH_T $RISH_V", c);
    check("exports and variables persist", "bar baz\n".equals(c.text()));

    c = new Collect();
    r = run(sh, "false", c);
    check("exit status 1", r.exit == 1);
    c = new Collect();
    r = run(sh, "(exit 7)", c);
    check("exit status 7", r.exit == 7);

    c = new Collect();
    r = run(sh, "ls /definitely/not/here", c);
    check("stderr merged into output", c.text().length() > 0 && r.exit != 0);

    // Syntax errors and unbalanced quotes must not swallow the framing
    c = new Collect();
    r = run(sh, "echo 'unterminated", c);
    check("unbalanced quote returns", r.exit != 0 && !r.exited && c.text().length() > 0);
    c = new Collect();
    r = run(sh, "echo \"also open", c);
    check("unbalanced dquote returns", r.exit != 0 && !r.exited);
    c = new Collect();
    r = run(sh, "if then fi ((", c);
    check("garbage syntax returns", r.exit != 0 && !r.exited);
    c = new Collect();
    r = run(sh, "echo still alive", c);
    check("shell usable after syntax errors", "still alive\n".equals(c.text()) && r.exit == 0);

    // stdin isolation: cat must not eat the next frame
    c = new Collect();
    r = run(sh, "cat", c);
    check("cat reads /dev/null and returns", r.exit == 0 && c.text().isEmpty());
    c = new Collect();
    r = run(sh, "read x; echo got=[$x]", c);
    check("read does not consume framing", c.text().startsWith("got=[") && !r.exited);

    // Quotes, backslashes, unicode and special chars survive the quoting
    c = new Collect();
    run(sh, "echo \"it's a \\\"test\\\" \\\\ $HOME_NOT_SET café ✓\"", c);
    check("quoting + unicode round trip", c.text().equals("it's a \"test\" \\  café ✓\n"));

    // A forged trailer in the output must not end the command early
    c = new Collect();
    r = run(sh, "printf '\\036@@RISH:fake:0|/\\n'; echo after", c);
    check("forged trailer ignored", c.text().endsWith("after\n") && c.text().contains("@@RISH:fake"));

    // Large output, incremental delivery
    c = new Collect();
    r = run(sh, "seq 1 200000", c);
    String[] lines = c.text().split("\n");
    check("large output complete (" + lines.length + " lines)", lines.length == 200000 && "200000".equals(lines[lines.length - 1]) && "1".equals(lines[0]));
    check("large output delivered in several chunks (" + c.calls + ")", c.calls > 5);

    // Multi-byte characters across read boundaries
    c = new Collect();
    r = run(sh, "i=0; while [ $i -lt 20000 ]; do printf '\\303\\251\\342\\234\\223'; i=$((i+1)); done", c);
    String t = c.text();
    check("utf-8 stream intact (" + t.length() + " chars)", t.length() == 40000 && t.indexOf('�') < 0);

    // Stop a long command
    final RishShell shf = sh;
    c = new Collect();
    new Thread(() -> { try { Thread.sleep(400); } catch (Exception e) {} shf.stop(); }).start();
    long t0 = System.currentTimeMillis();
    r = sh.run("sleep 30", 60000, c);
    long dt = System.currentTimeMillis() - t0;
    check("stop ends a sleeping command quickly (" + dt + " ms)", dt < 4000 && r.stopped && !r.timedOut && !r.restarted);
    check("shell alive and cwd kept after stop", sh.isAlive() && "/tmp".equals(sh.cwd()));
    c = new Collect();
    run(sh, "echo $RISH_T", c);
    check("variables survive a stop", "bar\n".equals(c.text()));

    // Stop a pipeline
    c = new Collect();
    new Thread(() -> { try { Thread.sleep(400); } catch (Exception e) {} shf.stop(); }).start();
    t0 = System.currentTimeMillis();
    r = sh.run("yes | head -c 100000000 | cat > /dev/null; sleep 30 | cat", 60000, c);
    dt = System.currentTimeMillis() - t0;
    check("stop ends a pipeline (" + dt + " ms)", dt < 5000 && r.stopped);

    // Timeout
    c = new Collect();
    t0 = System.currentTimeMillis();
    r = sh.run("sleep 30", 600, c);
    dt = System.currentTimeMillis() - t0;
    check("timeout stops the command (" + dt + " ms)", dt < 4000 && r.timedOut && r.stopped);
    check("shell alive after timeout", sh.isAlive());

    // A command that ignores TERM gets KILLed
    c = new Collect();
    new Thread(() -> { try { Thread.sleep(300); } catch (Exception e) {} shf.stop(); }).start();
    t0 = System.currentTimeMillis();
    r = sh.run("sh -c 'trap \"\" TERM; sleep 30'", 60000, c);
    dt = System.currentTimeMillis() - t0;
    check("TERM-ignoring command is killed (" + dt + " ms)", dt < 7000 && r.stopped && !r.restarted);
    check("shell alive after kill", sh.isAlive());

    // Background job output arrives on the idle sink
    final Collect idle = new Collect();
    sh.setIdleSink(idle);
    c = new Collect();
    run(sh, "(sleep 0.4; echo late) &", c);
    Thread.sleep(900);
    check("background output goes to the idle sink", idle.text().contains("late"));

    // exit ends the shell
    c = new Collect();
    r = sh.run("exit 3", 10000, c);
    check("exit reports exited (" + r.exit + ")", r.exited && !sh.isAlive());
    boolean threw = false;
    try { sh.run("echo x", 5000, new Collect()); } catch (IOException e) { threw = true; }
    check("run on a dead shell throws", threw);
    sh.start();
    c = new Collect();
    run(sh, "echo again", c);
    check("start() brings a fresh shell back", "again\n".equals(c.text()) && sh.isAlive());

    // restart keeps the working directory (wedged shell)
    run(sh, "cd /usr", new Collect());
    sh.close();
    check("close ends the shell", !sh.isAlive());

    // Spawner failure surfaces
    RishShell bad = new RishShell(new RishShell.Spawner() { public Process spawn(String[] a) throws Exception { throw new IOException("no shizuku"); } });
    boolean failed = false;
    try { bad.start(); } catch (Exception e) { failed = e.getMessage().contains("no shizuku"); }
    check("spawn failure propagates", failed);

    // quote()
    check("quote plain", "'abc'".equals(RishShell.quote("abc")));
    check("quote single quotes", "'it'\\''s'".equals(RishShell.quote("it's")));

    // A login profile that prints a banner (Termux's ~/.bash_profile with neofetch, a "welcome" line) before the identity probe answers:
    // the pid and uid are still read from the marked answer, so STOP can still end what a command started.
    RishShell chatty = new RishShell(new RishShell.Spawner() {
      public Process spawn(String[] a) throws Exception {
        if (a.length > 1) return local.spawn(a);           // the helpers (ps, kill) STOP uses
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "echo 'Welcome to this phone|0|fake|/x'; printf 'banner without a newline 1|2|3'; exec sh");
        pb.directory(new File("/"));
        return pb.start();
      }
    });
    chatty.start();
    check("a chatty profile: pid read (" + chatty.pid() + ")", chatty.pid() > 1);
    check("a chatty profile: uid read (" + chatty.uid() + ")", String.valueOf(chatty.uid()).equals(realUid));
    final RishShell chattyF = chatty;
    long ct0 = System.currentTimeMillis();
    new Thread(() -> { try { Thread.sleep(400); } catch (Exception e) {} chattyF.stop(); }).start();
    RishShell.Result cr = chatty.run("sleep 30", 20000, new Collect());
    long cdt = System.currentTimeMillis() - ct0;
    check("a chatty profile: stop still ends a sleeping command (" + cdt + " ms, stopped " + cr.stopped + ", restarted " + cr.restarted + ")", cdt < 4000 && cr.stopped && !cr.restarted);
    Collect cc0 = new Collect();
    chatty.run("echo after", 10000, cc0);
    check("a chatty profile: the shell still works after", "after\n".equals(cc0.text()));
    chatty.close();

    // Concurrent runs serialise (second waits for first)
    final RishShell s2 = new RishShell(local);
    s2.start();
    final AtomicInteger order = new AtomicInteger();
    final int[] seen = new int[2];
    Thread a1 = new Thread(() -> { try { Collect cc = new Collect(); s2.run("sleep 0.5; echo A", 10000, cc); seen[0] = order.incrementAndGet(); } catch (Exception e) { seen[0] = -1; } });
    Thread a2 = new Thread(() -> { try { Thread.sleep(100); Collect cc = new Collect(); s2.run("echo B", 10000, cc); seen[1] = order.incrementAndGet(); } catch (Exception e) { seen[1] = -1; } });
    a1.start(); a2.start(); a1.join(); a2.join();
    check("concurrent runs serialise", seen[0] == 1 && seen[1] == 2);
    s2.close();

    System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILED"));
    System.exit(fails == 0 ? 0 : 1);
  }
}
