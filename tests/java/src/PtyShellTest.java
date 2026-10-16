package com.bloatware.bingblop;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    /** The pid of a running process whose command line is exactly "sleep N" (mark), or -1 (a zombie does not count). */
    static int pidOf(String mark) throws Exception {
        File[] ps = new File("/proc").listFiles();
        if (ps == null) return -1;
        for (File f : ps) {
            if (!f.getName().matches("[0-9]+")) continue;
            try {
                String cmd = new String(java.nio.file.Files.readAllBytes(new File(f, "cmdline").toPath()), StandardCharsets.UTF_8);
                if (!cmd.equals(mark.replace(' ', '\0') + "\0")) continue;
                String stat = new String(java.nio.file.Files.readAllBytes(new File(f, "stat").toPath()), StandardCharsets.UTF_8);
                if (stat.substring(stat.lastIndexOf(')') + 2).startsWith("Z")) continue;
                return Integer.parseInt(f.getName());
            } catch (java.io.IOException gone) {
            }
        }
        return -1;
    }

    static void type(Rec r, String s) throws Exception { byte[] b = s.getBytes(StandardCharsets.UTF_8); r.sh.write(b, 0, b.length); }

    /**
     * A process as Shizuku hands it over (ShizukuRemoteProcess): while the program runs, exitValue() throws IllegalArgumentException (the server's
     * IllegalThreadStateException after the binder), and once its server has died, exitValue() and waitFor() throw a RuntimeException.
     */
    static final class RemoteLike extends Process {
        final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        final CountDownLatch ended = new CountDownLatch(1);
        volatile boolean serverDied;
        final OutputStream in = new OutputStream() {
            public void write(int b) { synchronized (stdin) { stdin.write(b); } }
            public void write(byte[] b, int off, int len) { synchronized (stdin) { stdin.write(b, off, len); } }
        };
        final InputStream out = new InputStream() {
            public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 0xff; }
            public int read(byte[] b, int off, int len) throws IOException {
                try { ended.await(); } catch (InterruptedException e) { throw new IOException(e); }
                return -1;
            }
        };
        public OutputStream getOutputStream() { return in; }
        public InputStream getInputStream() { return out; }
        public InputStream getErrorStream() { return new java.io.ByteArrayInputStream(new byte[0]); }
        public int waitFor() throws InterruptedException {
            ended.await();
            if (serverDied) throw new RuntimeException(new IOException("the Shizuku server died"));
            return 0;
        }
        public int exitValue() {
            if (serverDied) throw new RuntimeException(new IOException("the Shizuku server died"));
            if (ended.getCount() > 0) throw new IllegalArgumentException("process hasn't exited");
            return 0;
        }
        public void destroy() { ended.countDown(); }
        byte[] written() { synchronized (stdin) { return stdin.toByteArray(); } }
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        // ---- the scripts (pure text) ----
        List<String> c = PtyShell.command("/lib/libptyexec.so", 30, 100, Arrays.asList("/system/bin/sh", "-l"));
        is("command line: helper, rows, cols, --, program", c.equals(Arrays.asList("/lib/libptyexec.so", "30", "100", "--", "/system/bin/sh", "-l")), String.valueOf(c));
        is("a size of 0 becomes 1", PtyShell.command("h", 0, -4, Arrays.asList("x")).subList(1, 3).equals(Arrays.asList("1", "1")));
        String sc = PtyShell.launchScript("/data/app/x y/lib/libptyexec.so", "/data/local/tmp/.cp", 24, 80, "/system/bin/sh");
        is("script: tries the helper where it is, copies it when it cannot run, then execs it with the size and the program",
                sc.contains("H='/data/app/x y/lib/libptyexec.so'") && sc.contains("1 1 -- /system/bin/true </dev/null >/dev/null") && sc.contains("cp -f \"$H\" \"$C\"") && sc.contains("C=/data/local/tmp/.cp")
                && sc.trim().endsWith("exec \"$P\" 24 80 -- /system/bin/sh"), sc);
        is("script without a copy path has no copy step", !PtyShell.launchScript("/h", "", 24, 80, "sh").contains("cp -f"));
        String ts = TermuxLink.ptyScript("/lib/libptyexec.so", 40, 120, "/data/data/com.termux/files/usr/bin/bash", 4321, "tok123", "");
        is("termux script: connects back with the token, then the helper runs bash with the connection as its terminal",
                ts.contains("exec 3<>/dev/tcp/127.0.0.1/4321") && ts.contains("ADBMGR %s") && ts.contains("tok123") && ts.contains("TERM=xterm-256color")
                && ts.contains("-- '/data/data/com.termux/files/usr/bin/bash' -l 0<&3 1>&3 2>&3 3>&-") && ts.contains("40 120") && ts.contains("adbmgr-ptyexec"), ts);

        // ---- a Shizuku process (SYS-1): no helper needed ----
        // The writer thread asks exitValue() as soon as it starts. A Shizuku process answers "still running" with an IllegalArgumentException, which
        // used to escape that thread (on Android an uncaught exception on any thread ends the app) and left a terminal that refused every key.
        final List<String> uncaught = new ArrayList<String>();
        Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread th, Throwable ex) { synchronized (uncaught) { uncaught.add(th.getName() + ": " + ex); } }
        });
        RemoteLike rp = new RemoteLike();
        Rec rr = new Rec();
        rr.sh = new PtyShell(rp, rr);
        Thread.sleep(700);                                   // longer than one wait of the writer: it has asked exitValue() at least twice
        boolean rwOk = true, rzOk = true;
        try { type(rr, "ls\r"); } catch (IOException ex) { rwOk = false; }
        try { rr.sh.resize(30, 100); } catch (IOException ex) { rzOk = false; }
        long rw0 = System.currentTimeMillis();
        byte[] keys = { 'D', 0, 3, 'l', 's', '\r' }, size = { 'R', 0, 30, 0, 100 };
        while (!(contains(rp.written(), keys) && contains(rp.written(), size)) && System.currentTimeMillis() - rw0 < 2000) Thread.sleep(20);
        synchronized (uncaught) { is("Shizuku process: its \"still running\" answer ends no thread", uncaught.isEmpty(), String.valueOf(uncaught)); }
        is("Shizuku process: keys and a resize are taken", rwOk && rzOk, "write " + rwOk + ", resize " + rzOk);
        is("Shizuku process: they reach the program, framed", contains(rp.written(), keys) && contains(rp.written(), size), Arrays.toString(rp.written()));
        rp.destroy();
        is("Shizuku process: its end is reported with its status", rr.done.await(3, TimeUnit.SECONDS) && rr.code == 0, "code " + rr.code);
        // the Shizuku server dies while the terminal is open: waitFor() and exitValue() throw a RuntimeException
        RemoteLike dp = new RemoteLike();
        Rec dr = new Rec();
        dr.sh = new PtyShell(dp, dr);
        Thread.sleep(200);
        dp.serverDied = true;
        dp.destroy();
        is("Shizuku server gone: the end is reported, status unknown (-1)", dr.done.await(3, TimeUnit.SECONDS) && dr.code == -1, "code " + dr.code);
        boolean drRefused = false;
        try { type(dr, "x"); } catch (IOException ex) { drRefused = true; }
        is("Shizuku server gone: later input is refused", drRefused);
        Thread.sleep(700);
        synchronized (uncaught) { is("Shizuku server gone: no thread dies of it", uncaught.isEmpty(), String.valueOf(uncaught)); }
        Thread.setDefaultUncaughtExceptionHandler(oldHandler);

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

        // ---- the shell ends while a job it left in the background still has the terminal (SYS-2) ----
        // The job keeps the terminal open, so its output never ends: the helper used to wait for that, and the page showed an open, dead terminal.
        String jmark = "sleep " + (40000 + new java.util.Random().nextInt(20000));
        Rec j = start(h, 24, 80, "/bin/bash", "--norc", "-i");
        is("a shell for a background job", j.waitFor("bash-", 3000), j.text());
        type(j, jmark + " &\n");
        long j0 = System.currentTimeMillis();
        int jPid = -1;
        while (jPid < 0 && System.currentTimeMillis() - j0 < 3000) { jPid = pidOf(jmark); if (jPid < 0) Thread.sleep(20); }
        type(j, "exit 3\n");
        long j1 = System.currentTimeMillis();
        boolean jEnded = j.done.await(3, TimeUnit.SECONDS);
        long jMs = System.currentTimeMillis() - j1;
        is("the shell exits while its background job runs: the end is reported at once, with the shell's status",
                jPid > 0 && pidOf(jmark) == jPid && jEnded && j.code == 3 && jMs < 1500, "job " + jPid + ", ended " + jEnded + " after " + jMs + " ms, code " + j.code);
        if (!jEnded) j.sh.close();
        if (pidOf(jmark) > 0) new ProcessBuilder("kill", "-9", String.valueOf(pidOf(jmark))).start().waitFor();
        // the helper holds SIGCHLD back to notice the end: the program must not inherit that (a shell would not see its own jobs end)
        Rec sb = start(h, 24, 80, "/bin/sh", "-c", "grep SigBlk /proc/self/status");
        java.util.regex.Matcher sbm = java.util.regex.Pattern.compile("SigBlk:\\s*([0-9a-f]+)").matcher(sb.done.await(4, TimeUnit.SECONDS) ? sb.text() : "");
        is("the program starts with SIGCHLD not blocked", sbm.find() && (Long.parseUnsignedLong(sbm.group(1), 16) & (1L << 16)) == 0, sb.text());

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

        // ---- a program that ignores SIGHUP is killed after a grace period (C-015) ----
        // close() hangs up (the helper sends SIGHUP and, 500 ms later, SIGKILL to the program's process group) and destroys the helper after 800 ms; the
        // helper used to wait for the program forever, so a program that ignores SIGHUP survived the closed terminal.
        String mark = "sleep " + (40000 + new java.util.Random().nextInt(20000));       // an unusual duration: no other process has this command line
        Rec ig = start(h, 24, 80, "/bin/sh", "-c", "trap '' HUP; exec " + mark);
        long ig0 = System.currentTimeMillis();
        int igPid = -1;
        while (igPid < 0 && System.currentTimeMillis() - ig0 < 3000) { igPid = pidOf(mark); if (igPid < 0) Thread.sleep(20); }
        is("a program that ignores SIGHUP is running", igPid > 0, mark);
        ig.sh.close();
        long ig1 = System.currentTimeMillis();
        while (pidOf(mark) > 0 && System.currentTimeMillis() - ig1 < 2000) Thread.sleep(20);
        boolean igGone = pidOf(mark) < 0;
        is("it is gone within 2 s of close()", igGone, igGone ? "" : "still running: pid " + pidOf(mark));
        is("and the end is reported as a kill (137)", ig.done.await(2, TimeUnit.SECONDS) && ig.code == 137, "code " + ig.code);
        if (!igGone) new ProcessBuilder("kill", "-9", String.valueOf(pidOf(mark))).start().waitFor();

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

        // ---- close() never waits for a blocked write ----
        // A big paste (newline-terminated lines) into a program that does not read its terminal: the pty's input buffer fills, the helper blocks
        // writing to it, the pipe to the helper fills and the writer thread blocks inside the stream's lock. close() used to close that stream on the
        // caller's thread, before starting the killer thread: it never returned (an ANR when the app closed its sessions) and the helper, the shell
        // and the program outlived the app.
        String bmark = "sleep " + (40000 + new java.util.Random().nextInt(20000));
        ProcessBuilder bpb = new ProcessBuilder(PtyShell.command(h, 24, 80, Arrays.asList("/bin/sh", "-c", "echo ready; " + bmark)));
        bpb.redirectErrorStream(true);
        final Process bproc = bpb.start();
        Rec b = new Rec();
        b.sh = new PtyShell(bproc, b);
        int bPid = -1;
        long bs = System.currentTimeMillis();
        while (bPid < 0 && System.currentTimeMillis() - bs < 3000) { bPid = pidOf(bmark); if (bPid < 0) Thread.sleep(20); }
        is("a program that takes no input is running on the terminal", b.waitFor("ready", 3000) && bPid > 0, b.text());
        byte[] bline = ("x".repeat(71) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] bchunk = new byte[bline.length * 1000];
        for (int i = 0; i < 1000; i++) System.arraycopy(bline, 0, bchunk, i * bline.length, bline.length);
        for (int i = 0; i < 4; i++) b.sh.write(bchunk, 0, bchunk.length);        // 288 KB of typed lines: the pty takes a few KB, the pipe 64 KB, the writer blocks on the rest
        Thread.sleep(1500);
        final PtyShell bsh = b.sh;
        final CountDownLatch bclosed = new CountDownLatch(1);
        final long bt0 = System.currentTimeMillis();
        Thread bct = new Thread(new Runnable() { public void run() { bsh.close(); bclosed.countDown(); } }, "closer");
        bct.setDaemon(true);
        bct.start();
        boolean bReturned = bclosed.await(2, TimeUnit.SECONDS);
        is("close() returns at once although the writer is blocked in a write the program never takes", bReturned, bReturned ? "" : "close() still running after " + (System.currentTimeMillis() - bt0) + " ms");
        is("the helper is gone within a moment of close()", b.done.await(4, TimeUnit.SECONDS) && !bproc.isAlive(), "code " + b.code + " alive " + bproc.isAlive());
        long bt1 = System.currentTimeMillis();
        while (pidOf(bmark) > 0 && System.currentTimeMillis() - bt1 < 3000) Thread.sleep(20);
        boolean bGone = pidOf(bmark) < 0;
        is("and so is the program on its terminal", bGone, bGone ? "" : "still running: pid " + pidOf(bmark));
        if (!bGone) new ProcessBuilder("kill", "-9", String.valueOf(pidOf(bmark))).start().waitFor();
        if (!bReturned) bproc.destroyForcibly();

        System.out.println(fails == 0 ? "ALL PASS (" + n + " checks)" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
