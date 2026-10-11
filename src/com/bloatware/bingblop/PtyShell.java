package com.bloatware.bingblop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * A program on a real terminal (a pseudo-terminal), for the full-screen Terminal: vim, nano, top, htop, less, ssh and everything else that needs a terminal.
 * Java cannot open a pty itself, so the program is run by libptyexec.so (native/pty/ptyexec.c, a small static helper): it creates the pty, starts
 * the program on it and relays the bytes over its own stdin and stdout. This class speaks the helper's framing (keys and window sizes in, raw
 * terminal bytes out) and keeps the output from running ahead of the screen (the page tells how much it has shown: {@link #ack}).
 * No Android classes here: it is tested on a computer with the helper built for it.
 */
public final class PtyShell {

    public interface Listener {
        /** Terminal output, as bytes (the page decodes them: a character may be split between two calls). */
        void onData(byte[] data, int n);

        /** The program ended (or the helper did): its exit status (128 + signal when it was killed), -1 when unknown. */
        void onExit(int code);
    }

    /** Output that was sent to the page and not yet reported shown: past this the reader waits (a runaway program cannot drown the page). */
    static final int MAX_UNACKED = 256 * 1024;
    private static final int FRAME = 4096;
    /** Input that was handed over and not yet written to the helper: past this {@link #write} refuses (it never waits for the program to take its input). */
    static final int MAX_QUEUED = 1024 * 1024;

    private final Process process;
    private final OutputStream in;
    private final Object flow = new Object();
    private final Object queueLock = new Object();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>();     // frames for the helper, in order
    private long queued;
    private long unacked;
    private volatile boolean closed;
    private boolean failed;      // the writer ended: nothing more can be sent (guarded by queueLock)

    /** The helper's command line: {@code helper ROWS COLS -- program args...}. */
    public static List<String> command(String helper, int rows, int cols, List<String> program) {
        List<String> c = new ArrayList<String>();
        c.add(helper);
        c.add(String.valueOf(Math.max(1, rows)));
        c.add(String.valueOf(Math.max(1, cols)));
        c.add("--");
        c.addAll(program);
        return c;
    }

    /**
     * The shell script that starts the helper: try it where it is (this app's library folder); a program that may not run files from there (the phone's
     * shell user on some phones, a newer Termux) gets a copy in {@code copyTo} (a path in the shell's own syntax, e.g. {@code /data/local/tmp/.x} or
     * {@code "$HOME/.cache/x"}; empty: no copy). {@code program}: the command that runs on the terminal, already quoted for sh.
     */
    public static String launchScript(String helper, String copyTo, int rows, int cols, String program) {
        String h = RishShell.quote(helper);
        StringBuilder s = new StringBuilder();
        s.append("H=").append(h).append("; P=\"$H\"\n");
        if (copyTo != null && !copyTo.isEmpty()) {
            s.append("if ! \"$H\" 1 1 -- /system/bin/true >/dev/null 2>&1; then\n");
            s.append("  C=").append(copyTo).append("\n");
            s.append("  mkdir -p \"$(dirname \"$C\")\" 2>/dev/null\n");
            s.append("  if cp -f \"$H\" \"$C\" 2>/dev/null && chmod 755 \"$C\" 2>/dev/null; then P=\"$C\"; fi\n");
            s.append("fi\n");
        }
        s.append("exec \"$P\" ").append(Math.max(1, rows)).append(' ').append(Math.max(1, cols)).append(" -- ").append(program).append('\n');
        return s.toString();
    }

    /** {@code process} is the helper (or something that ends up running it: su, adb, a Termux connection). */
    public PtyShell(Process process, final Listener listener) {
        this.process = process;
        this.in = process.getOutputStream();
        final InputStream out = process.getInputStream();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[16384];
                try {
                    int n;
                    while ((n = out.read(buf)) > 0) {
                        synchronized (flow) {
                            unacked += n;
                        }
                        listener.onData(buf, n);
                        synchronized (flow) {
                            while (unacked > MAX_UNACKED && !closed) {
                                try {
                                    flow.wait(500);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
                int code = -1;
                try {
                    code = PtyShell.this.process.waitFor();
                } catch (InterruptedException ignored) {
                }
                // nothing can be sent to a program that ended: the writer stops, and later input is refused
                synchronized (queueLock) {
                    failed = true;
                    queue.clear();
                    queued = 0;
                    queueLock.notifyAll();
                }
                listener.onExit(code);
            }
        }, "pty-reader");
        t.setDaemon(true);
        t.start();
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                writeLoop();
            }
        }, "pty-writer");
        w.setDaemon(true);
        w.start();
    }

    /** Writes the queued frames to the helper, one after the other; this thread, not the caller of {@link #write}, waits when the helper takes no input. */
    private void writeLoop() {
        try {
            while (true) {
                byte[] f;
                synchronized (queueLock) {
                    while (queue.isEmpty()) {
                        if (closed || failed || ended()) return;
                        queueLock.wait(500);
                    }
                    f = queue.peekFirst();
                }
                in.write(f);
                in.flush();
                synchronized (queueLock) {
                    queue.pollFirst();
                    queued -= f.length;
                    queueLock.notifyAll();
                }
            }
        } catch (IOException e) {
            // the helper is gone: what is still queued has nowhere to go
        } catch (InterruptedException ignored) {
        } finally {
            synchronized (queueLock) {
                failed = true;
                queue.clear();
                queued = 0;
            }
        }
    }

    /** Whether the program (the helper) is gone: seen here, not from the reader, which may still be waiting for the page to acknowledge output. */
    private boolean ended() {
        try {
            process.exitValue();
            return true;
        } catch (IllegalThreadStateException running) {
            return false;
        }
    }

    /** The page showed {@code n} more bytes. */
    public void ack(long n) {
        synchronized (flow) {
            unacked = Math.max(0, unacked - n);
            flow.notifyAll();
        }
    }

    /**
     * Keys (already as the terminal wants them: UTF-8, escape sequences for the arrows and so on). They are queued and written by the writer thread, so this
     * returns at once even when the program takes no input: a caller that waited here would also hold up the acknowledgement that frees its output. When
     * {@link #MAX_QUEUED} bytes are already waiting, or the helper is gone, the input is refused with an IOException (all of it or none).
     */
    public void write(byte[] data, int off, int len) throws IOException {
        if (len <= 0) return;
        // refused before anything is copied: the size on the wire is the data plus three bytes for every frame
        long framed = (long) len + 3L * ((len + FRAME - 1) / FRAME);
        if (framed > MAX_QUEUED) throw new IOException("the terminal is not taking input fast enough");
        List<byte[]> frames = new ArrayList<byte[]>();
        long total = 0;
        while (len > 0) {
            int n = Math.min(len, FRAME);
            byte[] f = new byte[3 + n];
            f[0] = 'D';
            f[1] = (byte) (n >> 8);
            f[2] = (byte) n;
            System.arraycopy(data, off, f, 3, n);
            frames.add(f);
            total += f.length;
            off += n;
            len -= n;
        }
        enqueue(frames, total);
    }

    public void resize(int rows, int cols) throws IOException {
        rows = Math.max(1, Math.min(rows, 1000));
        cols = Math.max(1, Math.min(cols, 1000));
        List<byte[]> frames = new ArrayList<byte[]>();
        frames.add(new byte[]{'R', (byte) (rows >> 8), (byte) rows, (byte) (cols >> 8), (byte) cols});
        enqueue(frames, 5);
    }

    private void enqueue(List<byte[]> frames, long total) throws IOException {
        synchronized (queueLock) {
            if (closed || failed || ended()) throw new IOException("the terminal is closed");
            if (queued + total > MAX_QUEUED) throw new IOException("the terminal is not taking input fast enough");
            for (byte[] f : frames) queue.addLast(f);
            queued += total;
            queueLock.notifyAll();
        }
    }

    /** Hangs up (the helper sends the program SIGHUP when its input ends), and ends the process if it is still there a moment later. */
    public void close() {
        closed = true;
        synchronized (flow) {
            flow.notifyAll();
        }
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
        final Process p = process;
        Thread killer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                }
                p.destroy();
            }
        }, "pty-killer");
        killer.setDaemon(true);
        killer.start();
    }
}
