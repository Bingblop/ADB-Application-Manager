package com.bloatware.bingblop;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Termux's official RUN_COMMAND intent: the way another app may run commands in the user's own Termux environment (its bash
 * and everything installed with pkg), without root. See https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent.
 *
 * <p>The user grants this app Termux's "Run commands in Termux environment" permission once, and sets
 * {@code allow-external-apps=true} in {@code ~/.termux/termux.properties} (Termux refuses other apps until then). Results come back
 * through a PendingIntent to {@link ResultReceiver}. The live Terminal session on top of this is {@link TermuxLink}.
 */
public final class TermuxBridge {

    private TermuxBridge() {
    }

    public static final String PKG = "com.termux";
    public static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    public static final String PREFIX = "/data/data/com.termux/files/usr";
    public static final String HOME = "/data/data/com.termux/files/home";
    public static final String BASH = PREFIX + "/bin/bash";

    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final String X_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String X_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String X_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String X_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String X_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String X_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String X_PENDING = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String EXTRA_ID = "adbmgr_termux_run";

    private static final AtomicInteger seq = new AtomicInteger(41000);
    private static final Map<Integer, TermuxLink.Callback> pending = new ConcurrentHashMap<Integer, TermuxLink.Callback>();

    /** installed, version, permission (granted to this app), and where Termux came from (Play builds may differ). */
    public static JSONObject status(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi = pm.getPackageInfo(PKG, 0);
            o.put("installed", true);
            o.put("version", pi.versionName == null ? "" : pi.versionName);
            o.put("permission", ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED);
            String installer = null;
            try {
                installer = pm.getInstallerPackageName(PKG);
            } catch (Throwable ignored) {
            }
            o.put("installer", installer == null ? "" : installer);
        } catch (PackageManager.NameNotFoundException e) {
            try {
                o.put("installed", false);
                o.put("permission", false);
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    /**
     * Hands {@code script} to Termux's bash. {@code background}: no terminal window (the result has stdout and stderr);
     * otherwise Termux opens a new session for it. Returns an id for {@link #forget}.
     */
    public static int run(Context ctx, String script, boolean background, String label, TermuxLink.Callback cb) throws IOException {
        return run(ctx, script, background, false, label, cb);
    }

    /** As above; {@code login}: bash reads the user's login profile first (PATH additions and the like), as in a Termux window. */
    public static int run(Context ctx, String script, boolean background, boolean login, String label, TermuxLink.Callback cb) throws IOException {
        int id = seq.incrementAndGet();
        Intent i = new Intent();
        i.setClassName(PKG, SERVICE);
        i.setAction(ACTION);
        i.putExtra(X_PATH, BASH);
        i.putExtra(X_ARGS, login ? new String[]{"-l", "-c", script} : new String[]{"-c", script});
        i.putExtra(X_WORKDIR, HOME);
        i.putExtra(X_BACKGROUND, background);
        if (!background) i.putExtra(X_SESSION_ACTION, "0");        // a new session, switched to, with the Termux screen opened
        if (label != null) i.putExtra(X_LABEL, label);
        if (cb != null) {
            Intent back = new Intent(ctx, ResultReceiver.class);
            back.putExtra(EXTRA_ID, id);
            int flags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            i.putExtra(X_PENDING, PendingIntent.getBroadcast(ctx, id, back, flags));
            pending.put(id, cb);
        }
        try {
            ComponentName started;
            try {
                started = ctx.startService(i);
            } catch (IllegalStateException background8) {
                // Android 8+ refuses a plain start while this app is in the background: Termux's service goes foreground itself
                started = ctx.startForegroundService(i);
            }
            if (started == null) throw new IOException("Termux is not installed");
        } catch (SecurityException se) {
            pending.remove(id);
            throw new IOException("Termux refused: this app does not have the \"Run commands in Termux environment\" permission yet");
        } catch (IOException e) {
            pending.remove(id);
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            throw new IOException("Could not reach Termux: " + e.getMessage());
        }
        return id;
    }

    public static void forget(int id) {
        pending.remove(id);
    }

    /** Runs commands as background RUN_COMMANDs: what {@link TermuxLink} uses for the Terminal session and its helpers. */
    public static TermuxLink.Launcher launcher(final Context ctx) {
        return new TermuxLink.Launcher() {
            @Override
            public int launch(String script, String label, TermuxLink.Callback cb) throws IOException {
                return run(ctx, script, true, label, cb);
            }

            @Override
            public void forget(int id) {
                TermuxBridge.forget(id);
            }
        };
    }

    /**
     * Opens {@code cmd} in a real Termux terminal window (for full-screen programs and sign-in flows that need one). The window
     * waits for Enter at the end, so what the command printed last can be read. An empty command opens a plain login shell.
     */
    public static void openInTermux(Activity a, String cmd) throws IOException {
        String c = cmd == null ? "" : cmd.trim();
        String script = c.isEmpty() ? "exec bash -l" : c + "\nprintf '\\n[finished: press Enter to close]'; read -r _";
        run(a, script, false, true, "ADB App Manager", null);
        // Android 10+ stops Termux from opening its own screen from the background unless it may draw over other apps;
        // this app is in the foreground, so it brings Termux up itself.
        try {
            Intent launch = a.getPackageManager().getLaunchIntentForPackage(PKG);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(launch);
            }
        } catch (Exception ignored) {
        }
    }

    static void deliver(Intent intent) {
        if (intent == null) return;
        TermuxLink.Callback cb = pending.remove(intent.getIntExtra(EXTRA_ID, 0));
        if (cb == null) return;
        Bundle b = intent.getBundleExtra("result");
        if (b == null) {
            cb.onResult(new TermuxLink.Result("", "", -1, 2, "Termux sent no result", false));
            return;
        }
        cb.onResult(new TermuxLink.Result(b.getString("stdout", ""), b.getString("stderr", ""), b.getInt("exitCode", -1),
                b.getInt("err", -1), b.getString("errmsg", ""), false));
    }

    /** Termux answers here (through the PendingIntent this app handed it), never another app directly: not exported. */
    public static final class ResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            deliver(intent);
        }
    }
}
