package com.bloatware.bingblop;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A persistent shell session behind the Terminal tab's "Rish mode": one long-lived {@code sh}, started
 * through Shizuku so it runs as the shell user, that is fed commands over stdin. Because it is the same
 * shell process every time, {@code cd}, {@code export} and shell variables persist between commands,
 * like in {@code adb shell} or the real rish.
 *
 * <p>Framing: every command goes out as ONE stdin line,
 * <pre>  { sh -n -c '&lt;command&gt;' &amp;&amp; command eval '&lt;command&gt;'; } &lt;/dev/null 2&gt;&amp;1; /path/to/printf '\036@@RISH:&lt;nonce&gt;:%s|%s\036' "$?" "$PWD" &gt;&amp;3</pre>
 * The command text is single-quoted here, so an unbalanced quote or a syntax error in what the user typed
 * can never swallow the framing line (a failed {@code sh -n} syntax check skips the eval, which in some shells
 * would otherwise end the session), and {@code </dev/null} stops a command such as {@code cat} from reading
 * the next frame. The trailer starts and ends with the ASCII record separator, so it needs no newline (and a
 * newline inside the working directory's name can't break it) and the command's output comes back
 * byte-for-byte. It goes out on descriptor 3, a copy of the original stdout made at start, and printf is called by
 * its full path, so a command that redirects stdout ({@code exec >/dev/null}) or changes PATH can't hide it.
 * Output is handed to a {@link Sink} as it arrives.
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
        /**
         * The shell ended on its own while running something that was not {@code exit} (some shells quit on a failed
         * special builtin such as {@code . missing.sh} or {@code export a-b=1}) and was started again in the same
         * folder; variables and exports from before are gone.
         */
        public final boolean revived;

        Result(int exit, String cwd, boolean exited, boolean stopped, boolean timedOut, boolean restarted, boolean revived) {
            this.exit = exit;
            this.cwd = cwd;
            this.exited = exited;
            this.stopped = stopped;
            this.timedOut = timedOut;
            this.restarted = restarted;
            this.revived = revived;
        }
    }

    private static final String RS = "\u001e";
    private static final long PROBE_TIMEOUT_MS = 15000;
    /** The user typed `exit` somewhere in the command (as a word), so the shell ending is what they asked for. */
    private static final Pattern TYPED_EXIT = Pattern.compile("(?:^|[\\s;&|({`])exit(?:$|[\\s;&|)}`])");
    private static final long STAGE_MS = 1200;      // INT, then TERM, then KILL, this far apart

    private final Spawner spawner;
    private final long probeTimeoutMs;
    private final String syntaxShell;     // what checks a command's syntax before it runs: the session's own kind of shell
    private final String nonce;
    private final String marker;          // RS + "@@RISH:" + nonce + ":" - what a command's trailer starts with
    private final Object lock = new Object();     // guards everything below
    private final Object runLock = new Object();  // one command (or start) at a time

    private Process proc;
    private OutputStream stdin;
    private boolean alive;
    private boolean closed;               // close() was called: a restart that is in flight must not bring the shell back
    private String printfCmd = "printf";  // the full path once the shell has told us where printf lives
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
        this(spawner, PROBE_TIMEOUT_MS);
    }

    /** {@code probeTimeoutMs}: how long a freshly started shell gets to answer before start() gives up. */
    public RishShell(Spawner spawner, long probeTimeoutMs) {
        this(spawner, probeTimeoutMs, "sh");
    }

    /**
     * {@code syntaxShell}: the shell that syntax-checks each command first ({@code <syntaxShell> -n -c '<command>'}). It has to
     * be the same kind of shell as the session: a bash session (Termux) checked by a plain sh would refuse bash syntax such as
     * arrays or {@code [[ ]]}.
     */
    public RishShell(Spawner spawner, long probeTimeoutMs, String syntaxShell) {
        this.spawner = spawner;
        this.probeTimeoutMs = probeTimeoutMs;
        this.syntaxShell = syntaxShell == null || !syntaxShell.matches("[A-Za-z0-9_./+-]+") ? "sh" : syntaxShell;
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
        start(null);
    }

    /** As {@link #start()}, in {@code cwd} when that folder can be entered. */
    public void start(String cwd) throws Exception {
        synchronized (runLock) {
            synchronized (lock) {
                closed = false;
            }
            startLocked(cwd);
        }
    }

    /** Ends the shell and anything it started. A running command returns with {@code exited} set. */
    public void close() {
        synchronized (lock) {
            closed = true;          // set together with the process swap below, so a restart in flight can't win
        }
        closeProcess(true);
    }

    private void startLocked(String restoreCwd) throws Exception {
        closeProcess(true);
        Process p = spawner.spawn(new String[]{"sh"});
        int gen;
        boolean wasClosed;
        synchronized (lock) {
            wasClosed = closed;
            gen = ++generation;
            if (!wasClosed) {
                proc = p;
                stdin = p.getOutputStream();
                alive = true;
                buf.setLength(0);
                current = null;
                exitCode = -1;
                printfCmd = "printf";
            }
        }
        if (wasClosed) {
            killQuietly(p);
            throw new IOException("the shell was closed");
        }
        startReader(p.getInputStream(), gen, true);
        startReader(p.getErrorStream(), gen, false);
        startWaiter(p, gen);
        // Fold stderr into stdout for the shell and everything it starts (one pipe to read, no deadlock), and keep
        // a copy of stdout on descriptor 3 for the trailers, which a command's own redirections can't touch.
        write("exec 2>&1\nexec 3>&1\n");

        final StringBuilder probe = new StringBuilder();
        Result r;
        try {
            r = runLocked("printf '%s|%s|%s|%s' \"$$\" \"$(id -u)\" \"$(getprop ro.product.device 2>/dev/null)\" \"$(command -v printf)\"",
                    probeTimeoutMs, new Sink() {
                        @Override
                        public void onOutput(String text) {
                            probe.append(text);
                        }
                    }, false);
        } catch (IOException e) {
            closeProcess(true);
            throw e;
        }
        if (r.exited || r.stopped || r.timedOut) {
            closeProcess(true);
            throw new IOException(r.exited ? "the shell ended right after it started" : "the shell started but did not answer");
        }
        String[] f = probe.toString().split("\\|", -1);
        synchronized (lock) {
            if (f.length >= 1) shellPid = parseInt(f[0], -1);
            if (f.length >= 2) uid = parseInt(f[1], -1);
            if (f.length >= 3) host = f[2].trim();
            // an external printf is looked up through PATH, which a command can change: remember where it is
            if (f.length >= 4 && f[3].trim().startsWith("/") && f[3].trim().matches("[A-Za-z0-9_./+-]+")) printfCmd = f[3].trim();
        }
        if (restoreCwd != null && !restoreCwd.isEmpty() && !"/".equals(restoreCwd)) {
            runLocked("cd " + quote(restoreCwd), 5000, null, false);
        }
    }

    private void closeProcess(boolean kill) {
        Process p;
        OutputStream o;
        int pid;
        synchronized (lock) {
            generation++;
            p = proc;
            o = stdin;
            pid = shellPid;
            proc = null;
            stdin = null;
            alive = false;
            shellPid = -1;
            lock.notifyAll();
        }
        // A shell that is waiting on a foreground job defers SIGTERM, so end its children first.
        if (kill && pid > 0 && p != null) signalTree(pid, "KILL", true);
        if (o != null) {
            try {
                o.close();
            } catch (IOException ignored) {
            }
        }
        if (p != null) killQuietly(p);
    }

    private static void killQuietly(Process p) {
        try {
            p.destroyForcibly();
        } catch (Throwable ignored) {
        }
        try {
            p.destroy();
        } catch (Throwable ignored) {
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
            return runLocked(cmd, timeoutMs, sink, true);
        }
    }

    /** {@code allowRestart} false (while starting): a shell that doesn't answer is an error, not something to replace. */
    private Result runLocked(String cmd, long timeoutMs, Sink sink, boolean allowRestart) throws IOException {
        final Pending p = new Pending(sink);
        final String pf;
        synchronized (lock) {
            if (!alive) throw new IOException("the shell is not running");
            current = p;
            pf = printfCmd;
        }
        try {
            // `sh -n` rejects bad syntax first and `command eval` keeps the shell alive even if some shell
            // treats an eval error as fatal (eval is a POSIX special builtin, `command` lifts that).
            String q = quote(cmd);
            write("{ " + syntaxShell + " -n -c " + q + " && command eval " + q + "; } </dev/null 2>&1; " + pf + " '\\036@@RISH:" + nonce
                    + ":%s|%s\\036' \"$?\" \"${PWD:-$(pwd)}\" >&3\n");
        } catch (IOException e) {
            synchronized (lock) {
                current = null;
            }
            throw e;
        }

        final long start = System.nanoTime();
        int stage = 0;               // 0 running, 1 SIGINT sent, 2 SIGTERM sent, 3 SIGKILL sent
        long nextStageAt = 0;
        boolean timedOut = false;
        boolean interrupted = false;
        while (true) {
            synchronized (lock) {
                if (p.done || !alive) break;
                try {
                    lock.wait(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    interrupted = true;
                    break;
                }
                if (p.done || !alive) break;
            }
            long elapsed = (System.nanoTime() - start) / 1000000L;
            // Stop gently first: SIGINT ends a foreground job (like ^C) but background jobs (`&`) ignore it, so
            // they survive; only if that isn't enough does it escalate to SIGTERM and SIGKILL on the whole tree.
            if (stage == 0 && (p.stopRequested || (timeoutMs > 0 && elapsed >= timeoutMs))) {
                timedOut = !p.stopRequested;
                stage = 1;
                nextStageAt = elapsed + STAGE_MS;
                signalShellTree("INT");
            } else if (stage == 1 && elapsed >= nextStageAt) {
                stage = 2;
                nextStageAt = elapsed + STAGE_MS;
                signalShellTree("TERM");
            } else if (stage == 2 && elapsed >= nextStageAt) {
                stage = 3;
                nextStageAt = elapsed + STAGE_MS + 300;
                signalShellTree("KILL");
            } else if (stage == 3 && elapsed >= nextStageAt) {
                break;               // the shell itself is wedged
            }
        }

        if (interrupted) {
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
            return new Result(exit, cwdNow, false, stage > 0, timedOut, false, false);
        }
        if (!shellAlive) {
            int code = exitCode();
            if (code < 0) {
                code = exitValueQuietly(dead);
                synchronized (lock) {
                    exitCode = code;
                }
            }
            boolean wasClosed;
            synchronized (lock) {
                wasClosed = closed;
            }
            if (allowRestart && !wasClosed && stage == 0 && !TYPED_EXIT.matcher(cmd).find()) {
                // It ended on something that wasn't `exit`: start another one in the same folder instead of dropping the session.
                try {
                    startLocked(cwdNow);
                    return new Result(code, cwd(), false, false, false, false, true);
                } catch (Exception ignored) {
                    // could not: report the plain exit below
                }
            }
            return new Result(code, cwdNow, true, stage > 0, timedOut, false, false);
        }
        if (!allowRestart) throw new IOException("the shell stopped responding");
        // Wedged: replace the shell, keeping the working directory.
        try {
            startLocked(cwdNow);
        } catch (Exception e) {
            throw new IOException("could not restart the shell: " + e.getMessage());
        }
        return new Result(-1, cwd(), false, true, timedOut, true, false);
    }

    /** Asks the running command to end: SIGINT, then SIGTERM, then SIGKILL (each {@value #STAGE_MS} ms apart) if it ignores that. */
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

    /** Signals everything the shell started (the running command and its own children) without touching the shell itself. */
    private void signalShellTree(String sig) {
        int pid;
        synchronized (lock) {
            pid = shellPid;
        }
        if (pid > 0) signalTree(pid, sig, false);
    }

    /**
     * Sends {@code sig} to every descendant of {@code root}. The tree is read from the process table (ps, with /proc as a
     * second source) and signalled in one {@code kill}, so a script's grandchildren are not left running when the script
     * itself is stopped. {@code wait} blocks until the kill has been sent.
     */
    private void signalTree(int root, String sig, boolean wait) {
        try {
            Set<Integer> kids = descendants(root);
            if (kids.isEmpty()) return;
            StringBuilder cmd = new StringBuilder("kill -").append(sig);
            for (int k : kids) cmd.append(' ').append(k);
            cmd.append(" 2>/dev/null");
            Process h = spawner.spawn(new String[]{"sh", "-c", cmd.toString()});
            if (wait) {
                drainSync(h, 2000);
            } else {
                drainAsync(h);
            }
        } catch (Exception ignored) {
        }
    }

    private static final Pattern PS_ROW = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s*$");
    private static final Pattern PROC_ROW = Pattern.compile("^/proc/(\\d+)/status:PPid:\\s*(\\d+)\\s*$");

    private Set<Integer> descendants(int root) throws Exception {
        String table = runHelper("ps -A -o PID,PPID 2>/dev/null; grep -H '^PPid:' /proc/[0-9]*/status 2>/dev/null", 2500);
        Map<Integer, List<Integer>> children = new HashMap<Integer, List<Integer>>();
        for (String line : table.split("\n")) {
            Matcher m = PS_ROW.matcher(line);
            if (!m.matches()) m = PROC_ROW.matcher(line.trim());
            if (!m.matches()) continue;
            int pid = parseInt(m.group(1), -1), ppid = parseInt(m.group(2), -1);
            if (pid <= 0 || ppid <= 0) continue;
            List<Integer> l = children.get(ppid);
            if (l == null) {
                l = new ArrayList<Integer>();
                children.put(ppid, l);
            }
            l.add(pid);
        }
        Set<Integer> out = new LinkedHashSet<Integer>();
        List<Integer> todo = new ArrayList<Integer>();
        todo.add(root);
        while (!todo.isEmpty() && out.size() < 2000) {
            int at = todo.remove(todo.size() - 1);
            List<Integer> l = children.get(at);
            if (l == null) continue;
            for (int c : l) if (c != root && out.add(c)) todo.add(c);
        }
        return out;
    }

    /** Runs a short shell script and returns what it printed (empty on any problem), waiting at most {@code timeoutMs}. */
    private String runHelper(String script, long timeoutMs) {
        final StringBuilder sb = new StringBuilder();
        final Process h;
        try {
            h = spawner.spawn(new String[]{"sh", "-c", script});
        } catch (Exception e) {
            return "";
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        h.getOutputStream().close();
                    } catch (IOException ignored) {
                    }
                    Reader r = new InputStreamReader(h.getInputStream(), StandardCharsets.UTF_8);
                    char[] c = new char[4096];
                    int n;
                    while ((n = r.read(c)) != -1) {
                        synchronized (sb) {
                            if (sb.length() < (1 << 20)) sb.append(c, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(h);
                }
            }
        }, "rish-helper-read");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) killQuietly(h);
        synchronized (sb) {
            return sb.toString();
        }
    }

    /** Waits (at most {@code timeoutMs}) for a short helper to finish, discarding its output. */
    private static void drainSync(final Process p, long timeoutMs) {
        Thread t = drainThread(p);
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Process p) {
        try {
            p.getOutputStream().close();
        } catch (Throwable ignored) {
        }
        try {
            p.getInputStream().close();
        } catch (Throwable ignored) {
        }
        try {
            p.getErrorStream().close();
        } catch (Throwable ignored) {
        }
        try {
            p.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void drainAsync(final Process p) {
        drainThread(p);
    }

    private static Thread drainThread(final Process p) {
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
                    closeQuietly(p);
                }
            }
        }, "rish-helper");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static int exitValueQuietly(Process p) {
        if (p == null) return -1;
        for (int i = 0; i < 10; i++) {
            try {
                return p.exitValue();
            } catch (RuntimeException notYet) {
                // a remote process reports "still running" as a different exception: wait and ask again
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
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
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
            lastFeedAt = System.nanoTime() / 1000000L;
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
                int eol = buf.indexOf(RS, i + marker.length());
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
                long exitedAt = System.nanoTime() / 1000000L;
                while (true) {
                    synchronized (lock) {
                        if (gen != generation || !alive) return;
                        long now = System.nanoTime() / 1000000L;
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
