package com.bloatware.bingblop;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;

/**
 * The part of the Termux connection that needs no Android classes (unit-tested off-device against a real bash).
 *
 * <p>Another app cannot start Termux's bash itself: it lives in Termux's private folder, and Android does not let one app run
 * another app's programs. The official way in is Termux's RUN_COMMAND intent, which runs a command as Termux but only hands the
 * output back once the command has ended. For a live terminal this class turns ONE such command into a persistent session: the app
 * listens on a random loopback port, the command it asks Termux to run connects back with bash's own {@code /dev/tcp}, proves it is
 * the command the app started by sending a one-time token as its first line, and then becomes a long-lived bash that reads commands
 * from that connection. To {@link RishShell} the connection looks like an ordinary process, so the Terminal gets the same framing,
 * streaming output, STOP and cd / export persistence as with its other shells.
 *
 * <p>Any app on the phone can open a loopback connection, so the listener takes one connection that knows the token and nothing
 * else, and it closes as soon as that connection arrived (or the attempt is given up).
 */
public final class TermuxLink {

    private TermuxLink() {
    }

    /** What a finished RUN_COMMAND reported. {@code err} is Termux's own error code: -1 means the command itself ran. */
    public static final class Result {
        public final String stdout;
        public final String stderr;
        public final int exitCode;
        public final int err;
        public final String errmsg;
        public final boolean timedOut;

        public Result(String stdout, String stderr, int exitCode, int err, String errmsg, boolean timedOut) {
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
            this.err = err;
            this.errmsg = errmsg == null ? "" : errmsg;
            this.timedOut = timedOut;
        }

        /** The command ran (whatever its exit status): Termux neither refused nor lost it. */
        public boolean ran() {
            return err == -1 && !timedOut;
        }
    }

    public interface Callback {
        void onResult(Result r);
    }

    /** Starts a bash script in Termux (RUN_COMMAND in the app, a plain local bash in the tests). The result comes when it ended. */
    public interface Launcher {
        /** Returns an id for {@link #forget}. Throws when the command could not even be handed over (no permission, no Termux). */
        int launch(String script, String label, Callback cb) throws IOException;

        /** The result of {@code id} is no longer wanted. */
        void forget(int id);
    }

    /** The first line the bridge sends, followed by the token. */
    static final String HELLO = "ADBMGR ";

    /**
     * The script Termux runs for a session: connect back, say the token, then become {@code bash -s} on that connection.
     * {@code login}: read the user's Termux login profile (~/.bash_profile or ~/.profile), so PATH and the like match what they
     * get in Termux itself.
     */
    public static String bridgeScript(String bash, int port, String token, String workdir, boolean login) {
        StringBuilder s = new StringBuilder();
        s.append("exec 3<>/dev/tcp/127.0.0.1/").append(port).append(" || exit 97\n");
        s.append("printf '").append(HELLO).append("%s\\n' '").append(token).append("' >&3\n");
        if (workdir != null && !workdir.trim().isEmpty()) {
            s.append("cd ").append(RishShell.quote(workdir.trim())).append(" 2>/dev/null || cd \"$HOME\" 2>/dev/null\n");
        } else {
            s.append("cd \"$HOME\" 2>/dev/null\n");
        }
        s.append("exec ").append(RishShell.quote(bash)).append(login ? " -l" : "").append(" -s 0<&3 1>&3 2>&3 3>&-\n");
        return s.toString();
    }

    /**
     * The script of a real-terminal session: the same connection back to the app as {@link #bridgeScript}, but what is on the other end is the pty helper
     * (libptyexec.so, run from this app's library folder: Termux may execute it) with bash on a terminal, not a bash that reads commands.
     * The connection carries the helper's framing in and the terminal's bytes out.
     */
    public static String ptyScript(String helper, int rows, int cols, String bash, int port, String token, String workdir) {
        StringBuilder s = new StringBuilder();
        s.append("exec 3<>/dev/tcp/127.0.0.1/").append(port).append(" || exit 97\n");
        s.append("printf '").append(HELLO).append("%s\\n' '").append(token).append("' >&3\n");
        if (workdir != null && !workdir.trim().isEmpty()) {
            s.append("cd ").append(RishShell.quote(workdir.trim())).append(" 2>/dev/null || cd \"$HOME\" 2>/dev/null\n");
        } else {
            s.append("cd \"$HOME\" 2>/dev/null\n");
        }
        s.append("export TERM=xterm-256color COLORTERM=truecolor LANG=en_US.UTF-8\n");
        // the helper runs bash on a terminal; its input and output are the connection (a copy of the helper in Termux's own folder if Termux may not run it where it is)
        s.append(PtyShell.launchScript(helper, "\"$HOME/.cache/adbmgr-ptyexec\"", rows, cols, RishShell.quote(bash) + " -l 0<&3 1>&3 2>&3 3>&-"));
        return s.toString();
    }

    /** A real-terminal session in Termux: {@link #openSession} with {@link #ptyScript} as the command Termux runs. */
    public static Process openPtySession(Launcher launcher, String helper, int rows, int cols, String bash, String workdir, long timeoutMs) throws IOException {
        return connect(launcher, timeoutMs, new ScriptMaker() {
            private String h = helper;
            @Override
            public String make(int port, String token) {
                return ptyScript(h, rows, cols, bash, port, token, workdir);
            }
        });
    }

    interface ScriptMaker {
        String make(int port, String token);
    }

    /** A spawner for {@link RishShell}: the session itself is a connected bash, helpers (ps, kill) are one-off commands. */
    public static RishShell.Spawner spawner(final Launcher launcher, final String bash, final String workdir, final boolean login,
                                            final long connectTimeoutMs) {
        return new RishShell.Spawner() {
            @Override
            public Process spawn(String[] argv) throws Exception {
                if (argv.length >= 3 && "-c".equals(argv[1])) return runCommand(launcher, argv[2]);
                return openSession(launcher, bash, workdir, login, connectTimeoutMs);
            }
        };
    }

    /** Starts a session and waits (at most {@code timeoutMs}) for it to connect back. */
    public static Process openSession(final Launcher launcher, final String bash, final String workdir, final boolean login, long timeoutMs) throws IOException {
        return connect(launcher, timeoutMs, new ScriptMaker() {
            @Override
            public String make(int port, String token) {
                return bridgeScript(bash, port, token, workdir, login);
            }
        });
    }

    /** How many accepted connections may wait for their hello at once; when more come, the oldest is closed. */
    static final int MAX_PENDING_HELLOS = 32;
    /** How long a connection has to send its hello line. */
    static final long HELLO_WAIT_MS = 3000;

    /** A connection that has not sent its hello line yet. */
    private static final class Candidate {
        final Socket socket;
        final long deadline = System.nanoTime() + HELLO_WAIT_MS * 1000000L;
        final byte[] buf = new byte[128];
        int n;

        Candidate(Socket socket) {
            this.socket = socket;
        }
    }

    /**
     * Reads what the connection has sent so far, without blocking. 1: it sent the right hello line; -1: refuse it (wrong or too long line, closed,
     * or out of time); 0: wait for more.
     */
    private static int helloStep(Candidate c, byte[] want) {
        try {
            if (System.nanoTime() > c.deadline) return -1;
            InputStream in = c.socket.getInputStream();
            // one byte at a time, so that nothing after the end of the line is taken from the stream
            while (in.available() > 0) {
                int ch = in.read();
                if (ch < 0) return -1;
                if (ch == '\n') {
                    boolean ok = c.n == want.length && MessageDigest.isEqual(want, Arrays.copyOf(c.buf, c.n));
                    if (ok) c.socket.setSoTimeout(0);
                    return ok ? 1 : -1;
                }
                if (c.n >= c.buf.length) return -1;
                c.buf[c.n++] = (byte) ch;
            }
            return 0;
        } catch (IOException e) {
            return -1;
        }
    }

    private static Process connect(Launcher launcher, long timeoutMs, ScriptMaker maker) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        final SessionProcess proc = new SessionProcess(launcher);
        // Any app on the phone can open this loopback port. A peer that connects and says nothing used to hold the accepting thread for 3 s, so a few
        // such peers kept Termux's real connection waiting until the timeout. The hellos are therefore read here without blocking, all connections
        // in turn: no thread per connection, at most MAX_PENDING_HELLOS sockets held (the oldest is closed when more come), and a connection that
        // sends its hello is looked at within one short wait however many silent ones are queued.
        final LinkedList<Candidate> waiting = new LinkedList<Candidate>();
        Socket sock = null;
        try {
            server.setSoTimeout(20);
            String token = randomHex(16);
            final byte[] want = (HELLO + token).getBytes(StandardCharsets.US_ASCII);
            String script = maker.make(server.getLocalPort(), token);
            proc.launchId = launcher.launch(script, "ADB App Manager terminal", new Callback() {
                @Override
                public void onResult(Result r) {
                    proc.ended(r);
                }
            });
            long deadline = System.nanoTime() + timeoutMs * 1000000L;
            while (sock == null) {
                Result early = proc.result();
                if (early != null) throw new IOException(failureText(early));
                if (System.nanoTime() > deadline) {
                    throw new IOException("Termux did not connect back within " + Math.max(1, timeoutMs / 1000) + " s");
                }
                try {
                    // everything that is already waiting in the backlog, then one short wait for the next
                    waiting.add(new Candidate(server.accept()));
                    while (waiting.size() > MAX_PENDING_HELLOS) closeQuietly(waiting.removeFirst().socket);
                } catch (SocketTimeoutException again) {
                    // nothing new; look at the connections already waiting
                }
                for (Iterator<Candidate> it = waiting.iterator(); it.hasNext() && sock == null; ) {
                    Candidate c = it.next();
                    int r = helloStep(c, want);
                    if (r == 0) continue;
                    it.remove();
                    if (r > 0) sock = c.socket;
                    else closeQuietly(c.socket);
                }
            }
            proc.attach(sock);
        } catch (IOException e) {
            closeQuietly(sock);
            proc.destroy();
            throw e;
        } finally {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            for (Candidate c : waiting) closeQuietly(c.socket);
        }
        return proc;
    }

    /** A one-off command whose output (stdout then stderr) is readable once it ended. */
    public static Process runCommand(Launcher launcher, String script) throws IOException {
        final CommandProcess p = new CommandProcess(launcher);
        p.id = launcher.launch(script, "ADB App Manager helper", new Callback() {
            @Override
            public void onResult(Result r) {
                p.finish(r);
            }
        });
        return p;
    }

    /** What to tell the user when Termux refused or lost a command. */
    public static String failureText(Result r) {
        if (r.timedOut) return "Termux did not answer";
        String msg = r.errmsg.trim();
        if (!msg.isEmpty()) {
            if (msg.contains("allow-external-apps")) {
                return "Termux does not accept commands from other apps yet: set allow-external-apps=true in ~/.termux/termux.properties ("
                        + firstLine(msg) + ")";
            }
            return "Termux: " + firstLine(msg);
        }
        if (!r.ran()) return "Termux could not run the command (error " + r.err + ")";
        String err = r.stderr.trim();
        if (r.exitCode == 97 || err.contains("/dev/tcp")) return "bash in Termux could not connect back to this app" + (err.isEmpty() ? "" : ": " + firstLine(err));
        return "the Termux shell ended right away (exit " + r.exitCode + ")" + (err.isEmpty() ? "" : ": " + firstLine(err));
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String l = nl < 0 ? s : s.substring(0, nl);
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }

    /** Reads the first line of a new connection (3 s at most) and checks the token, in constant time. */
    static boolean hello(Socket s, String token) {
        try {
            s.setSoTimeout(3000);
            InputStream in = s.getInputStream();
            byte[] buf = new byte[128];
            int n = 0;
            while (n < buf.length) {
                int c = in.read();
                if (c < 0) return false;
                if (c == '\n') break;
                buf[n++] = (byte) c;
            }
            if (n >= buf.length) return false;
            byte[] want = (HELLO + token).getBytes(StandardCharsets.US_ASCII);
            byte[] got = new byte[n];
            System.arraycopy(buf, 0, got, 0, n);
            boolean ok = MessageDigest.isEqual(want, got);
            if (ok) s.setSoTimeout(0);
            return ok;
        } catch (IOException e) {
            return false;
        }
    }

    static String randomHex(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static final OutputStream DISCARD = new OutputStream() {
        @Override
        public void write(int b) {
        }

        @Override
        public void write(byte[] b, int off, int len) {
        }
    };

    /** The long-lived bash, seen through its connection. It ends when Termux reports the command finished, or on destroy(). */
    static final class SessionProcess extends Process {
        private final Launcher launcher;
        volatile int launchId = -1;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile Result result;
        private volatile Socket socket;
        private volatile InputStream in = new ByteArrayInputStream(new byte[0]);
        private volatile OutputStream out = DISCARD;

        SessionProcess(Launcher launcher) {
            this.launcher = launcher;
        }

        void attach(Socket s) throws IOException {
            in = s.getInputStream();
            out = s.getOutputStream();
            socket = s;
        }

        void ended(Result r) {
            result = r;
            done.countDown();
        }

        Result result() {
            return result;
        }

        @Override
        public OutputStream getOutputStream() {
            return out;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            done.await();
            return exitValue();
        }

        @Override
        public int exitValue() {
            if (done.getCount() > 0) throw new IllegalThreadStateException("still running");
            Result r = result;
            return r != null && r.ran() ? r.exitCode : -1;
        }

        @Override
        public void destroy() {
            closeQuietly(socket);
            if (launchId >= 0) launcher.forget(launchId);
            done.countDown();
        }
    }

    /** Blocks readers until the command has ended, then serves its output. */
    static final class LatchedInput extends InputStream {
        private final CountDownLatch ready = new CountDownLatch(1);
        private volatile byte[] data = new byte[0];
        private int pos;

        void set(byte[] d) {
            if (ready.getCount() == 0) return;
            data = d == null ? new byte[0] : d;
            ready.countDown();
        }

        private void await() throws IOException {
            try {
                ready.await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        @Override
        public synchronized int read() throws IOException {
            await();
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) throws IOException {
            await();
            if (len == 0) return 0;
            if (pos >= data.length) return -1;
            int n = Math.min(len, data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() {
            set(new byte[0]);
        }
    }

    static final class CommandProcess extends Process {
        private final Launcher launcher;
        volatile int id = -1;
        private final LatchedInput in = new LatchedInput();
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile int exit = -1;

        CommandProcess(Launcher launcher) {
            this.launcher = launcher;
        }

        void finish(Result r) {
            exit = r.ran() ? r.exitCode : -1;
            in.set((r.stdout + r.stderr).getBytes(StandardCharsets.UTF_8));
            done.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return DISCARD;
        }

        @Override
        public InputStream getInputStream() {
            return in;
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            done.await();
            return exit;
        }

        @Override
        public int exitValue() {
            if (done.getCount() > 0) throw new IllegalThreadStateException("still running");
            return exit;
        }

        @Override
        public void destroy() {
            if (id >= 0) launcher.forget(id);
            in.set(new byte[0]);
            done.countDown();
        }
    }
}
