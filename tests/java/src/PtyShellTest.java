package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The real terminal: PtyShell and the helper it runs (native/pty/ptyexec.c, built for this computer in native/pty/x86_64/ptyexec): a program on a pseudo-terminal
 * is a tty of the asked size, follows resizes, takes Ctrl-C as a key, returns its exit status, hangs up when the app side closes, is not flooded
 * (the output waits for the page), and the scripts that start the helper on the phone / in Termux are shaped as designed.
 */
public class PtyShellTest {
    static int n = 0, fails = 0;
    static void is(String what, boolean ok) { n++; if (!ok) { fails++; System.out.println("FAIL " + what); } }
    static void is(String what, boolean ok, String detail) { n++; if (!ok) { fails++; System.out.println("FAIL " + what + ": " + detail); } }

    static final class Rec implements PtyShell.Listener {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final CountDownLatch done = new CountDownLatch(1);
        volatile int code = Integer.MIN_VALUE;
        PtyShell sh;
        boolean autoAck = true;
        public void onData(byte[] d, int len) { synchronized (out) { out.write(d, 0, len); } if (autoAck && sh != null) sh.ack(len); }
        public void onExit(int c) { code = c; done.countDown(); }
        String text() { synchronized (out) { return new String(out.toByteArray(), StandardCharsets.UTF_8); } }
        boolean waitFor(String s, long ms) throws Exception { long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (text().contains(s)) return true; Thread.sleep(20); } return false; }
    }

    static Rec start(String helper, int rows, int cols, String... prog) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(PtyShell.command(helper, rows, cols, Arrays.asList(prog)));
        pb.environment().put("TERM", "xterm-256color");
        pb.redirectErrorStream(true);
        Rec r = new Rec();
        r.sh = new PtyShell(pb.start(), r);
        return r;
    }

    static void type(Rec r, String s) throws Exception { byte[] b = s.getBytes(StandardCharsets.UTF_8); r.sh.write(b, 0, b.length); }

    public static void main(String[] args) throws Exception {
        // ---- the scripts (pure text) ----
        List<String> c = PtyShell.command("/lib/libptyexec.so", 30, 100, Arrays.asList("/system/bin/sh", "-l"));
        is("command line: helper, rows, cols, --, program", c.equals(Arrays.asList("/lib/libptyexec.so", "30", "100", "--", "/system/bin/sh", "-l")), String.valueOf(c));
        is("a size of 0 becomes 1", PtyShell.command("h", 0, -4, Arrays.asList("x")).subList(1, 3).equals(Arrays.asList("1", "1")));
        String sc = PtyShell.launchScript("/data/app/x y/lib/libptyexec.so", "/data/local/tmp/.cp", 24, 80, "/system/bin/sh");
        is("script: tries the helper where it is, copies it when it cannot run, then execs it with the size and the program",
                sc.contains("H='/data/app/x y/lib/libptyexec.so'") && sc.contains("1 1 -- /system/bin/true") && sc.contains("cp -f \"$H\" \"$C\"") && sc.contains("C=/data/local/tmp/.cp")
                && sc.trim().endsWith("exec \"$P\" 24 80 -- /system/bin/sh"), sc);
        is("script without a copy path has no copy step", !PtyShell.launchScript("/h", "", 24, 80, "sh").contains("cp -f"));
        String ts = TermuxLink.ptyScript("/lib/libptyexec.so", 40, 120, "/data/data/com.termux/files/usr/bin/bash", 4321, "tok123", "");
        is("termux script: connects back with the token, then the helper runs bash with the connection as its terminal",
                ts.contains("exec 3<>/dev/tcp/127.0.0.1/4321") && ts.contains("ADBMGR %s") && ts.contains("tok123") && ts.contains("TERM=xterm-256color")
                && ts.contains("-- '/data/data/com.termux/files/usr/bin/bash' -l 0<&3 1>&3 2>&3 3>&-") && ts.contains("40 120") && ts.contains("adbmgr-ptyexec"), ts);

        File helper = new File(System.getProperty("ptyexec", "native/pty/x86_64/ptyexec"));
        if (!helper.canExecute() || !new File("/dev/ptmx").exists()) {
            System.out.println("skip the terminal itself (no native/pty/x86_64/ptyexec: run native/pty/build.sh, or no /dev/ptmx)");
            System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
            System.exit(fails == 0 ? 0 : 1);
        }
        String h = helper.getAbsolutePath();

        // ---- a shell on a terminal ----
        Rec r = start(h, 30, 100, "/bin/bash", "--norc", "-i");
        is("the shell starts and shows its prompt", r.waitFor("bash-", 3000), r.text());
        type(r, "stty size; tty\n");
        is("it is a terminal of 30 rows and 100 columns", r.waitFor("30 100", 3000) && r.text().contains("/dev/pts/"), r.text());
        r.sh.resize(41, 123);
        type(r, "stty size\n");
        is("a resize reaches the program", r.waitFor("41 123", 3000), r.text());
        type(r, "sleep 30\n");
        Thread.sleep(300);
        type(r, "\u0003");
        type(r, "echo still-here-$((6*7))\n");
        is("Ctrl-C (a key) stops the running command, the shell lives on", r.waitFor("still-here-42", 3000), r.text());
        type(r, "printf 'caf\\xc3\\xa9 \\xe2\\x82\\xac\\n'\n");
        is("UTF-8 comes out whole", r.waitFor("café €", 3000), r.text());
        byte[] big = ("echo " + "y".repeat(9000) + "\n").getBytes(StandardCharsets.UTF_8);
        r.sh.write(big, 0, big.length);
        is("a long line (many frames) arrives whole", r.waitFor("y".repeat(9000), 4000));
        type(r, "exit 9\n");
        is("the exit status of the program is reported", r.done.await(4, TimeUnit.SECONDS) && r.code == 9, "code " + r.code);

        // ---- other endings ----
        Rec q = start(h, 24, 80, "/bin/sh", "-c", "echo bye; exit 3");
        is("a short program: output and status 3", q.done.await(4, TimeUnit.SECONDS) && q.code == 3 && q.text().contains("bye"), q.text() + " " + q.code);
        Rec m = start(h, 24, 80, "/no/such/program");
        is("a program that does not exist: the helper says so, status 127", m.done.await(4, TimeUnit.SECONDS) && m.code == 127 && m.text().contains("cannot run"), m.text() + " " + m.code);
        Rec k = start(h, 24, 80, "/bin/sh", "-c", "kill -9 $$");
        is("a killed program: 128 + the signal", k.done.await(4, TimeUnit.SECONDS) && k.code == 137, "code " + k.code);
        Rec z = start(h, 24, 80, "/bin/sleep", "60");
        Thread.sleep(200);
        long t0 = System.currentTimeMillis();
        z.sh.close();
        is("closing hangs up: the program ends within a second or two", z.done.await(4, TimeUnit.SECONDS) && System.currentTimeMillis() - t0 < 3500 && z.code == 129, "code " + z.code);

        // ---- flow control: output waits for the page ----
        Rec f = new Rec();
        f.autoAck = false;
        ProcessBuilder pb = new ProcessBuilder(PtyShell.command(h, 24, 80, Arrays.asList("/bin/sh", "-c", "yes aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
        pb.redirectErrorStream(true);
        f.sh = new PtyShell(pb.start(), f);
        Thread.sleep(1500);
        int got = f.text().length();
        is("without acknowledgements the output stops near the limit (a flood cannot run ahead of the screen)", got > 100 * 1024 && got < PtyShell.MAX_UNACKED + 64 * 1024, "got " + got);
        f.sh.ack(got);
        Thread.sleep(600);
        is("after the page acknowledges, more arrives", f.text().length() > got + 1000, f.text().length() + " vs " + got);
        f.sh.close();

        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
