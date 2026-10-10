package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A hung process is killed: SdmShell.stream / run start a real sh through a ModeRunner, and a timeout or a cancel must end the call within a bounded time,
 * kill the shell, and never call the sink again afterwards. (ShellOutcomeTest only covers the text a timeout leaves; this one starts processes.)
 * Needs sh and sleep; without them the test says so and passes.
 */
public class SdmShellKillTest {
    static int fails = 0, n = 0;
    static void is(String what, boolean ok, String d) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + d); } }

    /** Starts scripts with sh -c, standard error merged, and remembers the process it started last. */
    static class Runner implements SdmShell.ModeRunner {
        volatile Process last;
        public String mode() { return "root"; }
        public Process open(String script) throws IOException {
            Process p = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start();
            last = p;
            return p;
        }
    }

    static boolean dead(Process p) throws Exception { return p.waitFor(5, TimeUnit.SECONDS); }

    public static void main(String[] args) throws Exception {
        if (!new File("/bin/sh").exists()) {
            System.out.println("skipped: sh is not available");
            System.out.println("0 checks, 0 failed");
            return;
        }
        final Runner r = new Runner();
        final SdmShell sh = new SdmShell(r);
        if (!sh.privileged()) { System.out.println("FAIL setup: mode root is not treated as privileged"); System.exit(1); }

        // 1. a process that prints nothing and never ends: the timeout ends the call and kills it
        long t0 = System.currentTimeMillis();
        String msg = null;
        try { sh.run("exec sleep 300", 600); } catch (IOException e) { msg = e.getMessage(); }
        long took = System.currentTimeMillis() - t0;
        is("silent hang: the call ends with a timeout message", msg != null && msg.startsWith("Timed out after 600 ms"), String.valueOf(msg));
        is("silent hang: it ends soon after the deadline, not when the process would", took >= 550 && took < 5000, took + " ms");
        is("silent hang: the process is dead", dead(r.last), "still alive after 5 s");

        // 2. cancel: the call returns without an error and the process is killed
        final long start2 = System.currentTimeMillis();
        t0 = System.currentTimeMillis();
        Exception err = null;
        try {
            sh.stream("exec sleep 300", 60000, new Sdm.LineSink() { public void line(String l) { } }, new Sdm.Cancel() {
                public boolean cancelled() { return System.currentTimeMillis() - start2 > 300; }
            });
        } catch (Exception e) { err = e; }
        took = System.currentTimeMillis() - t0;
        is("cancel: no exception", err == null, String.valueOf(err));
        is("cancel: the call returns soon after the cancel", took >= 250 && took < 5000, took + " ms");
        is("cancel: the process is dead", dead(r.last), "still alive after 5 s");

        // 3. output, then a hang: the lines before the hang arrive, the call times out, and the sink is not called after the call returned
        final List<String> got = Collections.synchronizedList(new ArrayList<String>());
        msg = null;
        try {
            sh.stream("echo one; echo two; exec sleep 300", 900, new Sdm.LineSink() { public void line(String l) { got.add(l); } }, null);
        } catch (IOException e) { msg = e.getMessage(); }
        is("output then hang: both lines arrived before the timeout", got.size() == 2 && "one".equals(got.get(0)) && "two".equals(got.get(1)), got.toString());
        is("output then hang: the call times out", msg != null && msg.startsWith("Timed out after 900 ms"), String.valueOf(msg));
        is("output then hang: the process is dead", dead(r.last), "still alive after 5 s");
        int seen = got.size();
        Thread.sleep(300);
        is("output then hang: the sink is silent after the call returned", got.size() == seen, got.toString());

        // 4. a child that keeps the pipe open (the shell is killed, the child holds stdout): the call still returns at the deadline
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        t0 = System.currentTimeMillis();
        msg = null;
        try {
            sh.stream("sleep 300 & echo $!; wait", 800, new Sdm.LineSink() { public void line(String l) { lines.add(l); } }, null);
        } catch (IOException e) { msg = e.getMessage(); }
        took = System.currentTimeMillis() - t0;
        is("pipe held by a child: the call returns at the deadline", took >= 750 && took < 5000, took + " ms");
        is("pipe held by a child: it reports the timeout", msg != null && msg.startsWith("Timed out after 800 ms"), String.valueOf(msg));
        is("pipe held by a child: the shell itself is dead", dead(r.last), "still alive after 5 s");
        // tidy up: the shell's own child (sleep) is not part of what the wrapper promises to kill, so end it here
        if (!lines.isEmpty() && lines.get(0).matches("\\d{1,10}")) {
            new ProcessBuilder("kill", lines.get(0)).redirectErrorStream(true).start().waitFor();
        }

        // 5. a command that finishes by itself is not touched: its whole output, and it exits with 0
        String out = sh.run("echo hello; echo world", 5000);
        is("a normal command: its output", "hello\nworld".equals(out), out);
        is("a normal command: it ended by itself with status 0", r.last.waitFor(5, TimeUnit.SECONDS) && r.last.exitValue() == 0, "exit " + r.last.exitValue());

        System.out.println(n + " checks, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
