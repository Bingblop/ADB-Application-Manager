package com.bloatware.bingblop;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The Terminal's Termux session (TermuxLink + RishShell in bash mode), run against this machine's bash: a stand-in launcher plays
 * Termux's RUN_COMMAND (it runs the script with a local bash and reports stdout / stderr / exit at the end), so the real bridge
 * script, the token check, the loopback connection, the framing, STOP (helpers through the launcher) and the end of a session
 * are all exercised for real.
 */
public class TermuxLinkTest {
  static int fails = 0;
  static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }

  static class Collect implements RishShell.Sink {
    final StringBuilder sb = new StringBuilder();
    public synchronized void onOutput(String t) { sb.append(t); }
    public synchronized String text() { return sb.toString(); }
  }

  /** Plays Termux: runs each script with a local bash, answers when it ended (like the PendingIntent result). */
  static class LocalTermux implements TermuxLink.Launcher {
    final AtomicInteger launches = new AtomicInteger();
    final AtomicInteger helpers = new AtomicInteger();
    final Set<Integer> forgotten = Collections.synchronizedSet(new HashSet<Integer>());
    final List<String> scripts = Collections.synchronizedList(new ArrayList<String>());
    public int launch(final String script, String label, final TermuxLink.Callback cb) throws IOException {
      final int id = launches.incrementAndGet();
      scripts.add(script);
      if (label != null && label.contains("helper")) helpers.incrementAndGet();
      final Process p = new ProcessBuilder("bash", "-c", script).start();
      Thread t = new Thread(new Runnable() { public void run() {
        try {
          final ByteArrayOutputStream err = new ByteArrayOutputStream();
          Thread et = new Thread(new Runnable() { public void run() { try { copy(p.getErrorStream(), err); } catch (IOException ignored) {} } });
          et.start();
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          copy(p.getInputStream(), out);
          int code = p.waitFor();
          et.join();
          if (cb != null && !forgotten.contains(id)) cb.onResult(new TermuxLink.Result(out.toString("UTF-8"), err.toString("UTF-8"), code, -1, "", false));
        } catch (Exception e) {
          if (cb != null) cb.onResult(new TermuxLink.Result("", "", -1, 2, "launcher: " + e, false));
        }
      } });
      t.setDaemon(true);
      t.start();
      return id;
    }
    public void forget(int id) { forgotten.add(id); }
  }

  static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] b = new byte[8192]; int n;
    while ((n = in.read(b)) != -1) out.write(b, 0, n);
  }

  static RishShell.Result run(RishShell sh, String cmd, Collect c) throws Exception { return sh.run(cmd, 20000, c); }

  public static void main(String[] args) throws Exception {
    String bash = "/bin/bash";
    if (!new File(bash).canExecute()) bash = "bash";

    // ---------------------------------------------------------------- the bridge script
    String s = TermuxLink.bridgeScript("/data/data/com.termux/files/usr/bin/bash", 40123, "ab12", "/sdcard/My Projects", true);
    check("script connects back to the given loopback port with bash's /dev/tcp", s.contains("exec 3<>/dev/tcp/127.0.0.1/40123"));
    check("script says the token as its first line", s.contains("printf 'ADBMGR %s\\n' 'ab12' >&3"));
    check("script cds into the folder, quoted (spaces), falling back to HOME", s.contains("cd '/sdcard/My Projects' 2>/dev/null || cd \"$HOME\""));
    check("script becomes a login bash reading the connection", s.contains("exec '/data/data/com.termux/files/usr/bin/bash' -l -s 0<&3 1>&3 2>&3 3>&-"));
    check("without the profile option: no -l", TermuxLink.bridgeScript("/b/bash", 1, "t", "", false).contains("exec '/b/bash' -s 0<&3"));
    String odd = TermuxLink.bridgeScript("/b/bash", 1, "t", "/tmp/it's here", false);
    check("a quote in the folder name cannot break out of the script", odd.contains("cd '/tmp/it'\\''s here'"));

    // ---------------------------------------------------------------- the token check
    check("tokens are 32 hex characters and differ each time", TermuxLink.randomHex(16).matches("[0-9a-f]{32}") && !TermuxLink.randomHex(16).equals(TermuxLink.randomHex(16)));
    check("hello accepts the right token", helloWith("ADBMGR goodtoken\n", "goodtoken"));
    check("hello refuses a wrong token", !helloWith("ADBMGR badtoken\n", "goodtoken"));
    check("hello refuses a token that is only a prefix of the line", !helloWith("ADBMGR goodtoken-and-more\n", "goodtoken"));
    check("hello refuses a connection that closes without a line", !helloWith("ADBMGR goodtoken", "goodtoken"));
    char[] longLine = new char[300]; Arrays.fill(longLine, 'x');
    check("hello refuses an endless first line", !helloWith(new String(longLine) + "\n", "goodtoken"));
    long t0 = System.currentTimeMillis();
    check("hello gives up on a silent connection (3 s)", !helloWith(null, "goodtoken") && System.currentTimeMillis() - t0 < 6000);

    // ---------------------------------------------------------------- a real session
    LocalTermux termux = new LocalTermux();
    RishShell sh = new RishShell(TermuxLink.spawner(termux, bash, "/tmp", true, 15000), 15000, "bash");
    sh.start();
    check("session starts through the launcher and connects back", sh.isAlive() && termux.launches.get() >= 1);
    check("the session script is the bridge script", termux.scripts.get(0).contains("/dev/tcp/127.0.0.1/") && termux.scripts.get(0).contains("ADBMGR"));
    check("pid and uid are read", sh.pid() > 1 && sh.uid() >= 0);
    check("it starts in the requested folder", "/tmp".equals(sh.cwd()));

    Collect c = new Collect();
    RishShell.Result r = run(sh, "echo hi", c);
    check("echo comes back exactly", "hi\n".equals(c.text()) && r.exit == 0);

    c = new Collect();
    r = run(sh, "arr=(alpha beta gamma); echo \"${arr[2]} ${#arr[@]}\"", c);
    check("bash syntax (arrays) passes the bash syntax check and runs", "gamma 3\n".equals(c.text()) && r.exit == 0);

    c = new Collect();
    r = run(sh, "[[ -d /tmp && 3 -gt 2 ]] && echo yes", c);
    check("[[ ]] works", "yes\n".equals(c.text()));

    c = new Collect();
    r = run(sh, "echo $((6 * 7)) ; printf '%s' \"$BASH_VERSION\" | head -c 1", c);
    check("it really is bash", c.text().startsWith("42\n") && c.text().length() == 4);

    c = new Collect();
    r = run(sh, "if then fi", c);
    check("a syntax error is reported, the session survives", r.exit != 0 && !r.exited && sh.isAlive());

    run(sh, "cd / && export TL_VAR=kept", new Collect());
    c = new Collect();
    r = run(sh, "echo \"$PWD $TL_VAR\"", c);
    check("cd and export persist between commands", "/ kept\n".equals(c.text()) && "/".equals(r.cwd));

    c = new Collect();
    r = run(sh, "false", c);
    check("exit status comes through", r.exit == 1);

    c = new Collect();
    r = run(sh, "for i in 1 2 3; do echo line$i; done; echo err >&2", c);
    check("stdout and stderr both arrive, in order", "line1\nline2\nline3\nerr\n".equals(c.text()));

    c = new Collect();
    r = run(sh, "head -c 300000 /dev/zero | tr '\\0' 'a'; echo", c);
    check("a big output (300 KB) arrives whole", c.text().length() == 300001);

    c = new Collect();
    r = run(sh, "printf 'caf\\303\\251 \\342\\234\\223\\n'", c);
    check("UTF-8 survives the connection", "café ✓\n".equals(c.text()));

    // STOP: the helpers (ps, kill) go through the launcher too, as Termux helpers would
    final RishShell fsh = sh;
    final Collect sc = new Collect();
    final RishShell.Result[] sr = new RishShell.Result[1];
    Thread st = new Thread(new Runnable() { public void run() { try { sr[0] = fsh.run("echo started; sleep 30 && echo never", 60000, sc); } catch (Exception e) {} } });
    st.start();
    long w0 = System.currentTimeMillis();
    while (!sc.text().contains("started") && System.currentTimeMillis() - w0 < 5000) Thread.sleep(20);
    int helpersBefore = termux.helpers.get();
    long s0 = System.currentTimeMillis();
    sh.stop();
    st.join(15000);
    check("STOP ends a running command", sr[0] != null && sr[0].stopped && !sc.text().contains("never"));
    check("STOP is quick (" + (System.currentTimeMillis() - s0) + " ms)", System.currentTimeMillis() - s0 < 8000);
    check("STOP used helper commands through the launcher", termux.helpers.get() > helpersBefore);
    c = new Collect();
    r = run(sh, "echo after", c);
    check("the session is usable after STOP", "after\n".equals(c.text()) && sh.isAlive());

    // in a ; chain the next program still starts (as in any script); STOP keeps ending each one until the command is over
    final Collect sc2 = new Collect();
    final RishShell.Result[] sr2 = new RishShell.Result[1];
    Thread st2 = new Thread(new Runnable() { public void run() { try { sr2[0] = fsh.run("echo go; sleep 30; sleep 30; echo next", 60000, sc2); } catch (Exception e) {} } });
    st2.start();
    w0 = System.currentTimeMillis();
    while (!sc2.text().contains("go") && System.currentTimeMillis() - w0 < 5000) Thread.sleep(20);
    s0 = System.currentTimeMillis();
    sh.stop();
    st2.join(20000);
    check("STOP on a ; chain ends every program in it, one after the other (" + (System.currentTimeMillis() - s0) + " ms)",
        sr2[0] != null && sr2[0].stopped && sc2.text().contains("next") && System.currentTimeMillis() - s0 < 10000 && sh.isAlive());

    // the session ending: `exit` ends bash, Termux reports the result
    c = new Collect();
    r = run(sh, "exit 5", c);
    check("typing exit ends the session (with its status)", r.exited && !sh.isAlive());

    // closing from the app side
    LocalTermux termux2 = new LocalTermux();
    RishShell sh2 = new RishShell(TermuxLink.spawner(termux2, bash, "", false, 15000), 15000, "bash");
    sh2.start();
    check("a second session (no profile, HOME)", sh2.isAlive());
    sh2.close();
    Thread.sleep(300);
    check("close ends it", !sh2.isAlive());

    // ---------------------------------------------------------------- failures
    TermuxLink.Launcher refusing = new TermuxLink.Launcher() {
      public int launch(String script, String label, TermuxLink.Callback cb) {
        cb.onResult(new TermuxLink.Result("", "", -1, 2, "Termux: allow-external-apps property is not set to \"true\"", false));
        return 1;
      }
      public void forget(int id) {}
    };
    try {
      TermuxLink.openSession(refusing, bash, "", false, 5000);
      check("Termux refusing is an error", false);
    } catch (IOException e) {
      check("Termux refusing (allow-external-apps) fails fast with the setting named", e.getMessage().contains("allow-external-apps=true"));
    }
    TermuxLink.Launcher throwing = new TermuxLink.Launcher() {
      public int launch(String script, String label, TermuxLink.Callback cb) throws IOException { throw new IOException("Termux refused: this app does not have the permission"); }
      public void forget(int id) {}
    };
    try {
      TermuxLink.openSession(throwing, bash, "", false, 5000);
      check("no permission is an error", false);
    } catch (IOException e) {
      check("no permission: the launcher's reason comes through", e.getMessage().contains("permission"));
    }
    TermuxLink.Launcher silent = new TermuxLink.Launcher() {
      public int launch(String script, String label, TermuxLink.Callback cb) { return 1; }
      public void forget(int id) {}
    };
    t0 = System.currentTimeMillis();
    try {
      TermuxLink.openSession(silent, bash, "", false, 1500);
      check("silence is an error", false);
    } catch (IOException e) {
      check("Termux never connecting back times out with a message", e.getMessage().contains("did not connect back") && System.currentTimeMillis() - t0 < 4000);
    }
    TermuxLink.Launcher brokenBash = new LocalTermux() {
      public int launch(String script, String label, TermuxLink.Callback cb) throws IOException {
        return super.launch("echo 'bash: /dev/tcp/127.0.0.1/1: Connection refused' >&2; exit 97", label, cb);
      }
    };
    try {
      TermuxLink.openSession(brokenBash, bash, "", false, 5000);
      check("a script that cannot connect is an error", false);
    } catch (IOException e) {
      check("a script that cannot connect back says so", e.getMessage().contains("could not connect back"));
    }

    // an impostor connecting first with a wrong token is ignored; the real session still opens
    final LocalTermux slow = new LocalTermux() {
      public int launch(final String script, final String label, final TermuxLink.Callback cb) throws IOException {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("/dev/tcp/127\\.0\\.0\\.1/(\\d+)").matcher(script);
        if (m.find()) {
          try (Socket imp = new Socket("127.0.0.1", Integer.parseInt(m.group(1)))) {
            imp.getOutputStream().write("ADBMGR 00000000000000000000000000000000\n".getBytes(StandardCharsets.US_ASCII));
            imp.getOutputStream().flush();
            Thread.sleep(200);
          } catch (InterruptedException ignored) {}
        }
        return super.launch(script, label, cb);
      }
    };
    RishShell sh3 = new RishShell(TermuxLink.spawner(slow, bash, "", false, 15000), 15000, "bash");
    sh3.start();
    c = new Collect();
    run(sh3, "echo real", c);
    check("an impostor with a wrong token is turned away; the real bridge gets in", "real\n".equals(c.text()));
    sh3.close();

    // silent peers (any app on the phone can open this loopback port) must not keep the real bridge waiting: six threads open a silent connection
    // every 50 ms for as long as the session is being opened
    final java.util.concurrent.atomic.AtomicBoolean stopFlood = new java.util.concurrent.atomic.AtomicBoolean();
    final List<Socket> flood = Collections.synchronizedList(new ArrayList<Socket>());
    final LocalTermux flooded = new LocalTermux() {
      public int launch(final String script, final String label, final TermuxLink.Callback cb) throws IOException {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("/dev/tcp/127\\.0\\.0\\.1/(\\d+)").matcher(script);
        if (m.find()) {
          final int port = Integer.parseInt(m.group(1));
          for (int i = 0; i < 6; i++) {
            Thread ft = new Thread(new Runnable() { public void run() {
              while (!stopFlood.get()) {
                try { flood.add(new Socket("127.0.0.1", port)); Thread.sleep(50); } catch (Exception e) { return; }
              }
            } });
            ft.setDaemon(true);
            ft.start();
          }
          try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return super.launch(script, label, cb);
      }
    };
    // the number of threads checking a hello at once is counted all the while
    final int[] peakHello = { 0 };
    Thread sampler = new Thread(new Runnable() { public void run() {
      while (!stopFlood.get()) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if ("termux-hello".equals(t.getName()) && t.isAlive()) n++;
        if (n > peakHello[0]) peakHello[0] = n;
        try { Thread.sleep(5); } catch (InterruptedException e) { return; }
      }
    } });
    sampler.setDaemon(true);
    sampler.start();
    long tf = System.currentTimeMillis();
    String floodErr = null;
    Process fp = null;
    try { fp = TermuxLink.openSession(flooded, bash, "", false, 15000); } catch (IOException e) { floodErr = e.getMessage(); }
    long floodMs = System.currentTimeMillis() - tf;
    stopFlood.set(true);
    check("silent connections do not keep the real bridge out (opened in " + floodMs + " ms, error " + floodErr + ")", fp != null && floodMs < 8000);
    check("no more than " + TermuxLink.MAX_PENDING_HELLOS + " hello threads at once, however many peers connect (peak " + peakHello[0] + " with " + flood.size() + " connections)", peakHello[0] <= TermuxLink.MAX_PENDING_HELLOS && flood.size() > TermuxLink.MAX_PENDING_HELLOS);
    if (fp != null) fp.destroy();
    for (Socket fs : new ArrayList<Socket>(flood)) { try { fs.close(); } catch (IOException ignored) {} }

    // ---------------------------------------------------------------- one-off helper commands
    LocalTermux termux4 = new LocalTermux();
    Process hp = TermuxLink.runCommand(termux4, "echo out; echo err >&2; exit 3");
    String hout = new String(readAll(hp.getInputStream()), StandardCharsets.UTF_8);
    check("a helper's output is stdout then stderr", "out\nerr\n".equals(hout));
    check("a helper's exit status", hp.waitFor() == 3 && hp.exitValue() == 3);
    Process never = TermuxLink.runCommand(silent, "true");
    boolean running = false;
    try { never.exitValue(); } catch (IllegalThreadStateException e) { running = true; }
    check("a helper with no answer yet is still running", running);
    never.destroy();
    check("destroying it ends the wait (-1) and its output (EOF)", never.waitFor() == -1 && never.getInputStream().read() == -1);

    System.out.println(fails == 0 ? "ALL PASS" : "FAILURES: " + fails);
    System.exit(fails == 0 ? 0 : 1);
  }

  static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); copy(in, o); return o.toByteArray(); }

  /** Connects to a fresh listener, sends {@code line} (null: nothing), and returns what hello() decided. */
  static boolean helloWith(final String line, String token) throws Exception {
    try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      final Socket client = new Socket("127.0.0.1", ss.getLocalPort());
      Socket server = ss.accept();
      if (line != null) {
        client.getOutputStream().write(line.getBytes(StandardCharsets.US_ASCII));
        client.getOutputStream().flush();
        if (!line.endsWith("\n")) client.shutdownOutput();
      }
      boolean ok = TermuxLink.hello(server, token);
      client.close();
      server.close();
      return ok;
    }
  }
}
