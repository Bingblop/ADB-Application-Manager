package com.bloatware.bingblop;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * A persistent shell session behind the Terminal tab's "Rish mode": one long-lived {@code sh}, started
 * through Shizuku so it runs as the shell user, that is fed commands over stdin. Because it is the same
 * shell process every time, {@code cd}, {@code export} and shell variables persist between commands,
 * like in {@code adb shell} or the real rish.
 *
 * <p>Framing: every command goes out as ONE stdin line,
 * <pre>  { sh -n -c '&lt;command&gt;' &amp;&amp; command eval '&lt;command&gt;'; } &lt;/dev/null 2&gt;&amp;1; printf '\036@@RISH:&lt;nonce&gt;:%s|%s\n' "$?" "$PWD"</pre>
 * The command text is single-quoted here, so an unbalanced quote or a syntax error in what the user typed
 * can never swallow the framing line (a failed {@code sh -n} syntax check skips the eval, which in some shells
 * would otherwise end the session), and {@code </dev/null} stops a command such as {@code cat} from reading
 * the next frame. The trailer starts with the ASCII record separator, so it needs no leading newline and the
 * command's output comes back byte-for-byte. Output is handed to a {@link Sink} as it arrives.
 *
 * <p>Pure Java with no Android classes: unit-tested off-device against a plain /bin/sh.
 */
public final class RishShell {

    /** Starts a process. argv[0] is "sh" for the session itself and for the short-lived stop helper. */
    public interface Spawner {
        Process spawn(String[] argv) throws Exception;
    }

    /**
     * Receives output as it arrives, never an empty string. Called on the reader thread while the shell's
     * state lock is held, so it must be quick and must not call back into the shell.
     */
    public interface Sink {
        void onOutput(String text);
    }

    public static final class Result {
        /** Exit status of the command, -1 if unknown. */
        public final int exit;
        /** Working directory after the command ran. */
        public final String cwd;
        /** The shell itself ended (for example the user typed {@code exit}). */
        public final boolean exited;
        /** The command was ended early by {@link #stop()} or by the timeout. */
        public final boolean stopped;
        public final boolean timedOut;
        /** The command ignored the stop signal, so the shell was replaced (the working directory was kept). */
        public final boolean restarted;

        Result(int exit, String cwd, boolean exited, boolean stopped, boolean timedOut, boolean restarted) {
            this.exit = exit;
            this.cwd = cwd;
            this.exited = exited;
            this.stopped = stopped;
            this.timedOut = timedOut;
            this.restarted = restarted;
        }
    }

    private static final String RS = "\u001e";
    private static final long PROBE_TIMEOUT_MS = 15000;

    private final Spawner spawner;
    private final String nonce;
    private final String marker;          // RS + "@@RISH:" + nonce + ":" - what a command's trailer starts with
    private final Object lock = new Object();     // guards everything below
    private final Object runLock = new Object();  // one command (or start) at a time

    private Process proc;
    private OutputStream stdin;
    private boolean alive;
    private int generation;
    private int shellPid = -1;
    private int uid = -1;
    private String host = "";
    private String cwd = "/";
    private int exitCode = -1;
    private long lastFeedAt;
    private final StringBuilder buf = new StringBuilder();
    private Pending current;
    private Sink idleSink;

    private static final class Pending {
        final Sink sink;
        boolean done;
        int exit = -1;
        volatile boolean stopRequested;

        Pending(Sink sink) {
            this.sink = sink;
        }
    }

    public RishShell(Spawner spawner) {
        this.spawner = spawner;
        byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b & 0xff));
        this.nonce = hex.toString();
        this.marker = RS + "@@RISH:" + nonce + ":";
    }

    /** Where output that arrives while no command is running (a background job, say) goes. */
    public void setIdleSink(Sink sink) {
        synchronized (lock) {
            idleSink = sink;
        }
    }

    public boolean isAlive() {
        synchronized (lock) {
            return alive;
        }
    }

    public int uid() {
        synchronized (lock) {
            return uid;
        }
    }

    public int pid() {
        synchronized (lock) {
            return shellPid;
        }
    }

    public String host() {
        synchronized (lock) {
            return host;
        }
    }

    public String cwd() {
        synchronized (lock) {
            return cwd;
        }
    }

    /** The shell's own exit status once it has ended, -1 while it runs or if it could not be read. */
    public int exitCode() {
        synchronized (lock) {
            return exitCode;
        }
    }

    /** A prompt like {@code husky:/sdcard $}, or {@code #} for uid 0. */
    public String prompt() {
        synchronized (lock) {
            String h = host == null || host.isEmpty() ? "android" : host;
            return h + ":" + cwd + (uid == 0 ? " #" : " $");
        }
    }

    /** Single-quotes a string for a POSIX shell. */
    public static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    /** Starts the shell and reads its identity. Call again after the shell ended to start a fresh one. */
    public void start() throws Exception {
        synchronized (runLock) {
            startLocked(null);
        }
    }

    /** Ends the shell. A running command returns with {@code exited} set. */
    public void close() {
        closeProcess();
    }

    private void startLocked(String restoreCwd) throws Exception {
        closeProcess();
        Process p = spawner.spawn(new String[]{"sh"});
        int gen;
        synchronized (lock) {
            gen = ++generation;
            proc = p;
            stdin = p.getOutputStream();
            alive = true;
            buf.setLength(0);
            current = null;
            exitCode = -1;
        }
        startReader(p.getInputStream(), gen, true);
        startReader(p.getErrorStream(), gen, false);
        startWaiter(p, gen);
        // Fold stderr into stdout for the shell and everything it starts (one pipe to read, no deadlock).
        write("exec 2>&1\n");

        final StringBuilder probe = new StringBuilder();
        Result r = runLocked("printf '%s|%s|%s' \"$$\" \"$(id -u)\" \"$(getprop ro.product.device 2>/dev/null)\"",
                PROBE_TIMEOUT_MS, new Sink() {
                    @Override
                    public void onOutput(String text) {
                        probe.append(text);
                    }
                });
        if (r.exited) throw new IOException("the shell ended right after it started");
        String[] f = probe.toString().split("\\|", -1);
        synchronized (lock) {
            if (f.length >= 1) shellPid = parseInt(f[0], -1);
            if (f.length >= 2) uid = parseInt(f[1], -1);
            if (f.length >= 3) host = f[2].trim();
        }
        if (restoreCwd != null && !restoreCwd.isEmpty() && !"/".equals(restoreCwd)) {
            runLocked("cd " + quote(restoreCwd), 5000, null);
        }
    }

    private void closeProcess() {
        Process p;
        OutputStream o;
        synchronized (lock) {
            generation++;
            p = proc;
            o = stdin;
            proc = null;
            stdin = null;
            alive = false;
            shellPid = -1;
            lock.notifyAll();
        }
        if (o != null) {
            try {
                o.close();
            } catch (IOException ignored) {
            }
        }
        if (p != null) {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Running commands
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs one command and blocks until it finishes, is stopped, or the timeout (ms, 0 = none) passes.
     * Output goes to {@code sink} as it arrives. A command that ignores the stop signal gets the shell
     * replaced, keeping the working directory.
     */
    public Result run(String cmd, long timeoutMs, Sink sink) throws IOException {
        synchronized (runLock) {
            return runLocked(cmd, timeoutMs, sink);
        }
    }

    private Result runLocked(String cmd, long timeoutMs, Sink sink) throws IOException {
        final Pending p = new Pending(sink);
        synchronized (lock) {
            if (!alive) throw new IOException("the shell is not running");
            current = p;
        }
        try {
            // `sh -n` rejects bad syntax first and `command eval` keeps the shell alive even if some shell
            // treats an eval error as fatal (eval is a POSIX special builtin, `command` lifts that).
            String q = quote(cmd);
            write("{ sh -n -c " + q + " && command eval " + q + "; } </dev/null 2>&1; printf '\\036@@RISH:" + nonce
                    + ":%s|%s\\n' \"$?\" \"${PWD:-$(pwd)}\"\n");
        } catch (IOException e) {
            synchronized (lock) {
                current = null;
            }
            throw e;
        }

        long start = System.currentTimeMillis();
        int stage = 0;               // 0 running, 1 SIGTERM sent, 2 SIGKILL sent
        long nextStage = 0;
        boolean timedOut = false;
        while (true) {
            synchronized (lock) {
                if (p.done || !alive) break;
                try {
                    lock.wait(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    stage = 3;
                    break;
                }
                if (p.done || !alive) break;
            }
            long now = System.currentTimeMillis();
            if (stage == 0 && (p.stopRequested || (timeoutMs > 0 && now - start >= timeoutMs))) {
                timedOut = !p.stopRequested;
                stage = 1;
                nextStage = now + 2000;
                signalChildren("TERM");
            } else if (stage == 1 && now >= nextStage) {
                stage = 2;
                nextStage = now + 2000;
                signalChildren("KILL");
            } else if (stage == 2 && now >= nextStage) {
                break;               // the shell itself is wedged
            }
        }

        if (stage == 3) {
            synchronized (lock) {
                current = null;
            }
            throw new IOException("interrupted");
        }
        boolean done;
        boolean shellAlive;
        int exit;
        String cwdNow;
        Process dead;
        synchronized (lock) {
            done = p.done;
            shellAlive = alive;
            exit = p.exit;
            cwdNow = cwd;
            dead = proc;
            current = null;
        }
        if (done) {
            return new Result(exit, cwdNow, false, stage > 0, timedOut, false);
        }
        if (!shellAlive) {
            int code = exitCode();
            if (code < 0) {
                code = exitValueQuietly(dead);
                synchronized (lock) {
                    exitCode = code;
                }
            }
            return new Result(code, cwdNow, true, stage > 0, timedOut, false);
        }
        // Wedged: replace the shell, keeping the working directory.
        try {
            startLocked(cwdNow);
        } catch (Exception e) {
            throw new IOException("could not restart the shell: " + e.getMessage());
        }
        return new Result(-1, cwd(), false, true, timedOut, true);
    }

    /** Asks the running command to end: SIGTERM, then SIGKILL after two seconds if it ignores that. */
    public void stop() {
        synchronized (lock) {
            if (current != null) {
                current.stopRequested = true;
                lock.notifyAll();
            }
        }
    }

    private void write(String s) throws IOException {
        OutputStream o;
        synchronized (lock) {
            o = stdin;
        }
        if (o == null) throw new IOException("the shell is not running");
        o.write(s.getBytes(StandardCharsets.UTF_8));
        o.flush();
    }

    /** Signals the shell's children (the running command) without touching the shell itself. */
    private void signalChildren(String sig) {
        int pid;
        synchronized (lock) {
            pid = shellPid;
        }
        if (pid <= 0) return;
        // pkill where toybox has it; otherwise find the children through /proc.
        String script = "pkill -" + sig + " -P " + pid + " 2>/dev/null; "
                + "for f in $(grep -l \"^PPid:[[:space:]]*" + pid + "\\$\" /proc/[0-9]*/status 2>/dev/null); do "
                + "p=${f#/proc/}; kill -" + sig + " ${p%/status} 2>/dev/null; done";
        try {
            drainAsync(spawner.spawn(new String[]{"sh", "-c", script}));
        } catch (Exception ignored) {
        }
    }

    private static void drainAsync(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        p.getOutputStream().close();
                    } catch (IOException ignored) {
                    }
                    byte[] b = new byte[1024];
                    InputStream in = p.getInputStream();
                    while (in.read(b) != -1) {
                        // discard
                    }
                    p.waitFor();
                } catch (Exception ignored) {
                } finally {
                    try {
                        p.destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, "rish-helper");
        t.setDaemon(true);
        t.start();
    }

    private static int exitValueQuietly(Process p) {
        if (p == null) return -1;
        for (int i = 0; i < 10; i++) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException notYet) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            } catch (Throwable t) {
                return -1;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Reading and parsing the shell's output
    // ---------------------------------------------------------------------------------------------

    private void startReader(final InputStream in, final int gen, final boolean primary) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
                    char[] c = new char[8192];
                    int n;
                    while ((n = r.read(c)) != -1) {
                        if (n > 0) feed(new String(c, 0, n), gen);
                    }
                } catch (IOException ignored) {
                } finally {
                    if (primary) onEof(gen);
                }
            }
        }, primary ? "rish-out" : "rish-err");
        t.setDaemon(true);
        t.start();
    }

    private void feed(String s, int gen) {
        synchronized (lock) {
            if (gen != generation) return;
            lastFeedAt = System.currentTimeMillis();
            buf.append(s);
            while (true) {
                int i = buf.indexOf(marker);
                if (i < 0) {
                    // Everything is plain output except a tail that might be the start of a trailer.
                    int n = buf.length() - partialMarkerTail();
                    if (n > 0) {
                        deliver(buf.substring(0, n));
                        buf.delete(0, n);
                    }
                    return;
                }
                int eol = buf.indexOf("\n", i + marker.length());
                if (eol < 0) {
                    // The trailer has started but its status line is incomplete: wait for the rest.
                    if (i > 0) {
                        deliver(buf.substring(0, i));
                        buf.delete(0, i);
                    }
                    return;
                }
                if (i > 0) deliver(buf.substring(0, i));
                String status = buf.substring(i + marker.length(), eol);
                buf.delete(0, eol + 1);
                int bar = status.indexOf('|');
                int code = parseInt(bar < 0 ? status : status.substring(0, bar), -1);
                if (bar >= 0 && bar + 1 < status.length()) cwd = status.substring(bar + 1);
                if (current != null && !current.done) {
                    current.done = true;
                    current.exit = code;
                    lock.notifyAll();
                }
            }
        }
    }

    /** Length of the longest tail of buf that is a proper prefix of the marker (0 almost always). */
    private int partialMarkerTail() {
        int from = Math.max(0, buf.length() - (marker.length() - 1));
        int idx = buf.indexOf(RS, from);
        while (idx >= 0) {
            int tail = buf.length() - idx;
            boolean prefix = true;
            for (int k = 0; k < tail; k++) {
                if (buf.charAt(idx + k) != marker.charAt(k)) {
                    prefix = false;
                    break;
                }
            }
            if (prefix) return tail;
            idx = buf.indexOf(RS, idx + 1);
        }
        return 0;
    }

    private void deliver(String text) {
        if (text.isEmpty()) return;
        Sink target = current != null && !current.done ? current.sink : idleSink;
        if (target != null) target.onOutput(text);
    }

    private void onEof(int gen) {
        synchronized (lock) {
            if (gen != generation) return;
            endLocked();
        }
    }

    private void endLocked() {
        alive = false;
        if (buf.length() > 0) {
            deliver(buf.toString());     // a partial trailer at EOF is just text
            buf.setLength(0);
        }
        lock.notifyAll();
    }

    /**
     * The shell can be gone while a background job it left behind still holds the output pipe open (so the
     * reader never sees EOF). Watch the process itself: once it has exited and the output has been quiet for
     * a moment, the session is over.
     */
    private void startWaiter(final Process p, final int gen) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                int code;
                try {
                    code = p.waitFor();
                } catch (Throwable e) {
                    return;              // the reader's EOF covers this case
                }
                long exitedAt = System.currentTimeMillis();
                while (true) {
                    synchronized (lock) {
                        if (gen != generation || !alive) return;
                        long now = System.currentTimeMillis();
                        if (now - lastFeedAt >= 200 || now - exitedAt >= 3000) {
                            exitCode = code;
                            endLocked();
                            return;
                        }
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }, "rish-wait");
        t.setDaemon(true);
        t.start();
    }
}
