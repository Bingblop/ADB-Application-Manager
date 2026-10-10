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

        // ---- a program that ended: its writer thread ends too, and later input is refused ----
        Rec e = start(h, 24, 80, "/bin/sh", "-c", "exit 0");
        is("a program that ends on its own", e.done.await(4, TimeUnit.SECONDS));
        Thread.sleep(1200);
        int writers = 0;
        for (Thread th : Thread.getAllStackTraces().keySet()) if ("pty-writer".equals(th.getName()) && th.isAlive()) writers++;
        is("no writer thread is left behind by sessions that ended", writers == 0, "writers " + writers);
        boolean refusedAfterEnd = false;
        try { type(e, "x"); } catch (java.io.IOException ex) { refusedAfterEnd = true; }
        is("input for a program that ended is refused", refusedAfterEnd);

        // the helper is killed (its connection dropped) while the reader still waits for the page to acknowledge output: the writer must end anyway
        Rec g = new Rec();
        g.autoAck = false;
        ProcessBuilder pb3 = new ProcessBuilder(PtyShell.command(h, 24, 80, Arrays.asList("/bin/sh", "-c", "yes aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
        pb3.redirectErrorStream(true);
        Process helperProc = pb3.start();
        g.sh = new PtyShell(helperProc, g);
        Thread.sleep(1500);                              // the reader is at the limit and waits for the page
        helperProc.destroyForcibly();
        Thread.sleep(1500);
        is("the helper is gone although the page never acknowledged (the reader still waits, no end reported yet)", g.done.getCount() == 1 && g.text().length() > 100 * 1024, "got " + g.text().length());
        int writers2 = 0;
        for (Thread th : Thread.getAllStackTraces().keySet()) if ("pty-writer".equals(th.getName()) && th.isAlive()) writers2++;
        is("its writer thread has ended all the same", writers2 == 0, "writers " + writers2);
        boolean refusedFinite = false;
        try { type(g, "x"); } catch (java.io.IOException ex) { refusedFinite = true; }
        is("and input for it is refused", refusedFinite);
        g.sh.ack(g.text().length());
        g.sh.ack(1 << 20);
        is("when the page catches up, the end is reported", g.done.await(4, TimeUnit.SECONDS), "code " + g.code);
        // one paste larger than the queue is refused before a copy of it is made
        final byte[] huge = new byte[8 * 1024 * 1024];
        Rec hq = start(h, 24, 80, "/bin/sleep", "20");
        long m0 = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        boolean refusedHuge = false;
        try { hq.sh.write(huge, 0, huge.length); } catch (java.io.IOException ex) { refusedHuge = true; }
        is("a paste over the limit is refused", refusedHuge);
        hq.sh.close();

        // ---- input never blocks the caller (C-014) ----
        // The page's calls reach the app one after another. A program that takes no input and floods its output (the page not yet acknowledging) used to
        // block a paste in the pipe to the helper, and with it every later call (the acknowledgement that would have freed the output): a deadlock.
        Rec d = new Rec();
        d.autoAck = false;
        ProcessBuilder pb2 = new ProcessBuilder(PtyShell.command(h, 24, 80, Arrays.asList("/bin/sh", "-c", "yes aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")));
        pb2.redirectErrorStream(true);
        d.sh = new PtyShell(pb2.start(), d);
        Thread.sleep(1500);                              // the reader waits for acknowledgements; the helper's output is blocked; so is its input
        final int dGot = d.text().length();
        final byte[] paste = new byte[100 * 1024];
        Arrays.fill(paste, (byte) 'a');
        final CountDownLatch pasted = new CountDownLatch(1);
        final PtyShell dsh = d.sh;
        Thread pt = new Thread(new Runnable() { public void run() { try { dsh.write(paste, 0, paste.length); } catch (java.io.IOException e) { } pasted.countDown(); } });
        pt.setDaemon(true);
        pt.start();
        is("a 100 KB paste returns at once although the program takes no input", pasted.await(2, TimeUnit.SECONDS));
        final CountDownLatch resized = new CountDownLatch(1);
        Thread rt = new Thread(new Runnable() { public void run() { try { dsh.resize(30, 90); } catch (java.io.IOException e) { } resized.countDown(); } });
        rt.setDaemon(true);
        rt.start();
        is("and so does a resize behind it", resized.await(2, TimeUnit.SECONDS));
        d.sh.ack(dGot);
        Thread.sleep(600);
        is("the acknowledgement frees the output again", d.text().length() > dGot + 1000, d.text().length() + " vs " + dGot);
        // far more than the program will ever take: refused with an error, never waiting
        final int[] refused = { 0 };
        final CountDownLatch flooded = new CountDownLatch(1);
        Thread ft = new Thread(new Runnable() { public void run() {
            for (int i = 0; i < 60; i++) { try { dsh.write(paste, 0, paste.length); } catch (java.io.IOException e) { refused[0]++; } }
            flooded.countDown(); } });
        ft.setDaemon(true);
        ft.start();
        is("6 MB of input in a row never blocks the caller", flooded.await(5, TimeUnit.SECONDS));
        is("what the queue cannot hold is refused with an error", refused[0] > 0, "refused " + refused[0]);
        d.sh.close();
        // the queue keeps the order of keys and resizes, and a normal shell still gets every byte
        Rec o = start(h, 30, 100, "/bin/bash", "--norc", "-i");
        is("a shell prompt after the change", o.waitFor("bash-", 3000), o.text());
        for (int i = 0; i < 20; i++) type(o, "echo seq-" + i + "\n");
        is("many small writes arrive in order", o.waitFor("seq-19", 3000), o.text());
        int a0 = o.text().indexOf("seq-0\r\n"), a19 = o.text().lastIndexOf("seq-19\r\n");
        is("the first line comes before the last", a0 >= 0 && a19 > a0, o.text());
        o.sh.close();

        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
