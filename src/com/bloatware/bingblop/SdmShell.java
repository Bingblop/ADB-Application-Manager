package com.bloatware.bingblop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * {@link Sdm.Shell} over the app's working mode (ADB over TCP, Wireless debugging, Shizuku or Root), which the host app provides as a
 * {@link ModeRunner}. This class turns "start this script in the current mode" into the two calls the tools need: a short {@link #run} that
 * returns the whole output and a {@link #stream} that hands the output over line by line while the command runs, with a timeout and a kill on
 * cancel. Output is read as the process produces it, never collected first (a scan prints hundreds of thousands of lines).
 *
 * <p>What each mode really offers, and so what {@link ModeRunner#open} has to do (all of it is already in MainActivity):
 * <ul>
 * <li>ADB over TCP / Wireless debugging: {@code buildAdbProcess("-s", target, "shell", script)} with {@code redirectErrorStream(true)}; the old
 *     adb protocol loses the exit code, so callers end a script with a sentinel line ({@code echo __SDM_END__}) instead of trusting it.</li>
 * <li>Shizuku: {@code Shizuku.newProcess(new String[]{"sh", "-c", "exec 2>&1; " + script}, null, null)} (what {@code shizukuPipe} does).</li>
 * <li>Root: {@code new ProcessBuilder("su", "-c", script)} with {@code redirectErrorStream(true)}.</li>
 * <li>Standard (no privilege): there is no shell; {@link #uid()} is -1 and every call throws an IOException.</li>
 * </ul>
 * The script travels as one argument, so it is limited to about 100 KB (the callers batch below that). The process' stdin is closed right after
 * the start, so a command that reads stdin ends instead of waiting.
 *
 * <p>Ported from SD Maid SE by darken (d4rken-org/sdmaid-se), GPL-3.0, https://github.com/d4rken-org/sdmaid-se; this class is the port's own
 * (SD Maid has its own root and ADB gateways). Pure Java, no android.* classes.
 */
public final class SdmShell implements Sdm.Shell {

    /** What the host (MainActivity) implements: which mode is working now and how to start a script in it. */
    public interface ModeRunner {
        /** The working mode now: "adb_tcp", "adb_wireless", "shizuku", "root" or "standard" (no privilege). Asked before every command, so it must be cheap. */
        String mode();
        /**
         * Starts {@code script} (an {@code sh} command line, may hold quotes, pipes, newlines) in the current mode and returns the process at once, without
         * reading from it. The returned process' standard output carries the output of the script with standard error merged into it. Throws an
         * IOException when it cannot start (not connected, no permission).
         */
        Process open(String script) throws IOException;
    }

    public static final int MAX_SCRIPT_BYTES = 100000;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long NEGATIVE_TTL_MS = 5000;
    private static final int MAX_RUN_CHARS = 32 * 1024 * 1024;

    private final ModeRunner runner;
    private final Object cacheLock = new Object();
    private String cachedMode = "";
    private int cachedUid = -1;
    private long cachedAt = 0;

    public SdmShell(ModeRunner runner) {
        this.runner = runner;
    }

    public static boolean isPrivilegedMode(String mode) {
        return "adb_tcp".equals(mode) || "adb_wireless".equals(mode) || "shizuku".equals(mode) || "root".equals(mode);
    }

    public String mode() {
        String m = runner.mode();
        return m == null ? "standard" : m;
    }

    public boolean privileged() {
        return isPrivilegedMode(mode());
    }

    /** 0 for root (also a Shizuku server started as root), 2000 for the shell user (ADB, Shizuku), -1 when there is no shell. Asked once per mode. */
    @Override
    public int uid() {
        String mode = mode();
        if (!isPrivilegedMode(mode)) return -1;
        long now = System.currentTimeMillis();
        synchronized (cacheLock) {
            if (mode.equals(cachedMode) && (cachedUid >= 0 || now - cachedAt < NEGATIVE_TTL_MS)) return cachedUid;
        }
        int uid = -1;
        try {
            String out = run("id -u 2>/dev/null", 20000);
            for (String l : out.split("\n")) {
                l = l.trim();
                if (l.matches("\\d{1,10}")) { uid = Integer.parseInt(l); break; }
            }
        } catch (IOException e) {
            uid = -1;
        }
        synchronized (cacheLock) {
            cachedMode = mode; cachedUid = uid; cachedAt = System.currentTimeMillis();
        }
        return uid;
    }

    /** Forgets the remembered uid (after the user changed the working mode). */
    public void forget() {
        synchronized (cacheLock) { cachedMode = ""; cachedUid = -1; cachedAt = 0; }
    }

    @Override
    public String run(String script, int timeoutMs) throws IOException {
        final StringBuilder sb = new StringBuilder();
        stream(script, timeoutMs, new Sdm.LineSink() {
            @Override public void line(String line) {
                if (sb.length() > MAX_RUN_CHARS) return;
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
        }, null);
        if (sb.length() > MAX_RUN_CHARS) throw new IOException("The output is too long");
        return sb.toString();
    }

    @Override
    public void stream(String script, int timeoutMs, final Sdm.LineSink sink, final Sdm.Cancel cancel) throws IOException {
        if (script == null) throw new IOException("No script");
        if (script.getBytes(UTF8).length > MAX_SCRIPT_BYTES) throw new IOException("The script is too long for one command");
        if (!isPrivilegedMode(mode())) throw new IOException("This needs ADB, Shizuku or Root. Set up a working mode first.");
        if (cancel != null && cancel.cancelled()) return;
        final Process p = runner.open(script);
        if (p == null) throw new IOException("The shell did not start");
        try { p.getOutputStream().close(); } catch (IOException ignored) {}
        final long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        final Object sinkLock = new Object();
        final boolean[] abandoned = { false };
        final Throwable[] failure = { null };
        final CountDownLatch done = new CountDownLatch(1);
        // The output is read on a thread of its own, so a cancel or a timeout does not have to wait for a silent process (or for a child of it that still
        // holds the pipe): the process is killed and the reader is left behind; it never calls the sink again.
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF8), 1 << 16);
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (sinkLock) {
                            if (abandoned[0]) return;
                            sink.line(line);
                        }
                    }
                } catch (Throwable t) {
                    synchronized (sinkLock) { if (!abandoned[0]) failure[0] = t; }
                } finally {
                    if (r != null) try { r.close(); } catch (IOException ignored) {}
                    done.countDown();
                }
            }
        }, "sdm-shell-read");
        reader.setDaemon(true);
        reader.start();
        boolean timedOut = false, cancelled = false;
        try {
            while (!done.await(50, TimeUnit.MILLISECONDS)) {
                if (cancel != null && cancel.cancelled()) { cancelled = true; break; }
                if (System.currentTimeMillis() > deadline) { timedOut = true; break; }
            }
        } catch (InterruptedException e) {
            cancelled = true;
            Thread.currentThread().interrupt();
        }
        if (timedOut || cancelled) {
            synchronized (sinkLock) { abandoned[0] = true; }
        }
        kill(p);
        Throwable f;
        synchronized (sinkLock) { f = failure[0]; }
        if (f != null) {
            if (f instanceof IOException) throw (IOException) f;
            if (f instanceof RuntimeException) throw (RuntimeException) f;
            if (f instanceof Error) throw (Error) f;
            throw new IOException(f);
        }
        if (timedOut) throw new IOException("Timed out after " + timeoutMs + " ms");
    }

    private static void kill(Process p) {
        try { p.destroy(); } catch (RuntimeException ignored) {}
        try { p.destroyForcibly(); } catch (RuntimeException ignored) {}
    }

    // ---------------------------------------------------------------------------------------------------------- running apps

    private static final Pattern APP_USER = Pattern.compile("u\\d+_a\\d+");
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");

    /** The packages that have a process now, read from {@code ps} output (any layout where the first column is the user and the last the process name). */
    public static Set<String> parseRunning(String ps) {
        Set<String> out = new HashSet<String>();
        if (ps == null) return out;
        for (String line : ps.split("\n")) {
            String[] t = line.trim().split("\\s+");
            if (t.length < 2 || !APP_USER.matcher(t[0]).matches()) continue;
            String name = t[t.length - 1];
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (PKG.matcher(name).matches()) out.add(name);
        }
        return out;
    }

    /** The packages with a running process, through the shell; empty when it cannot be told (no shell, ps failed). */
    public static Set<String> runningPackages(Sdm.Shell shell) {
        try {
            if (shell == null || shell.uid() < 0) return new HashSet<String>();
            return parseRunning(shell.run("ps -A -o USER,NAME 2>/dev/null || ps -A 2>/dev/null", 20000));
        } catch (IOException e) {
            return new HashSet<String>();
        }
    }
}
