package com.bloatware.bingblop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

/**
 * The patcher of the Morphe Patcher tab. It runs in a process of its own (android:process=":morphe", so the big heap a patch needs never
 * weighs on the app, and a patch bundle that crashes takes only this process down), as a foreground service with a notification and Cancel, so
 * a long patch goes on when the app is left. It runs the Morphe engine (engine/, dexed into this APK) and writes everything the engine says
 * to an events file that the app tails (see {@link MorpheEvents}); the file also is the log shown under "Patched APKs".
 *
 * Intent extras: cmd ("list" | "patch"), job (path of the job json), events (path of the events file), title.
 * The process ends itself when the job is done, to give the memory back.
 */
public class MorpheService extends Service {
    public static final String ACTION_RUN = "com.bloatware.bingblop.MORPHE_RUN";
    public static final String ACTION_CANCEL = "com.bloatware.bingblop.MORPHE_CANCEL";
    private static final String CHANNEL = "morphe", DONE_CHANNEL = "morphe_done";
    private static final int ID = 7301, DONE_ID = 7302;
    private static final String ENGINE_CLASS = "com.bloatware.bingblop.morphe.EngineMain";

    private volatile String title = "Morphe";
    private volatile String text = "";
    private volatile int percent = -1;
    private volatile File eventsFile;
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile String cmdName = "";

    /** Starts one engine command. Returns false when the system refused to start the service. */
    public static boolean start(Context c, String cmd, File job, File events, String title) {
        try {
            Intent i = new Intent(c, MorpheService.class).setAction(ACTION_RUN)
                    .putExtra("cmd", cmd).putExtra("job", job.getAbsolutePath()).putExtra("events", events.getAbsolutePath()).putExtra("title", title);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void cancel(Context c) {
        try { c.startService(new Intent(c, MorpheService.class).setAction(ACTION_CANCEL)); } catch (RuntimeException ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            File ev = eventsFile;
            if (ev != null && running) {
                append(ev, "LOG WARN Cancelled.\nRESULT {\"success\":false,\"cancelled\":true,\"error\":\"Cancelled\"}\n");
            }
            // The engine cannot be stopped from outside politely; ending the process is what Cancel means here
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); } }, 300);
            return START_NOT_STICKY;
        }
        if (!ACTION_RUN.equals(intent.getAction()) || running) { if (!running) stopSelf(); return START_NOT_STICKY; }
        final String cmd = intent.getStringExtra("cmd");
        cmdName = cmd == null ? "" : cmd;
        final File job = new File(intent.getStringExtra("job"));
        final File events = new File(intent.getStringExtra("events"));
        title = intent.getStringExtra("title") == null ? "Morphe" : intent.getStringExtra("title");
        eventsFile = events;
        running = true;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Morphe Patcher", NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, build());
        } catch (RuntimeException e) {
            // not allowed to run in the foreground right now: the job still runs, only without the notification
        }
        Thread worker = new Thread(null, new Runnable() { @Override public void run() { work(cmd, job, events); } }, "morphe-engine", 32L * 1024 * 1024);
        worker.start();
        return START_NOT_STICKY;
    }

    private void work(String cmd, File job, File events) {
        PrintStream sink = null;
        int code = 1;
        try {
            // A fresh file for a patch; a list is read once and its file is removed by the app
            OutputStream fos = new FileOutputStream(events, false);
            sink = new PrintStream(new Tee(fos), true, "UTF-8");
            Class<?> engine = Class.forName(ENGINE_CLASS);
            Method run = engine.getMethod("runCommand", String.class, String.class, PrintStream.class);
            Object r = run.invoke(null, cmd, job.getAbsolutePath(), sink);
            code = r instanceof Integer ? (Integer) r : 1;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            String msg = c instanceof ClassNotFoundException
                    ? "This build of the app does not contain the Morphe engine (engine/dist/morphe-engine-dex.zip was missing when it was built)."
                    : c.toString();
            if (sink == null) { try { sink = new PrintStream(new FileOutputStream(events, true), true, "UTF-8"); } catch (IOException ignored) {} }
            if (sink != null) sink.println("RESULT {\"success\":false,\"error\":" + org.json.JSONObject.quote(msg) + "}");
        } finally {
            if (sink != null) sink.close();
            running = false;
            if (!cancelled) {
                notifyDone(code == 0);
                stopForeground(true);
            }
            // give the heap back: this process has nothing else to do
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { android.os.Process.killProcess(android.os.Process.myPid()); } }, 1500);
        }
    }

    /** Passes the bytes on to the file and keeps the notification's text current from the lines that go by. */
    private final class Tee extends OutputStream {
        private final OutputStream out;
        private final StringBuilder line = new StringBuilder();
        private long lastNotify;

        Tee(OutputStream out) { this.out = out; }

        @Override public void write(int b) throws IOException { out.write(b); feed((byte) b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); for (int i = off; i < off + len; i++) feed(b[i]); }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }

        private void feed(byte b) {
            if (b == '\n') { handle(line.toString()); line.setLength(0); }
            else if (line.length() < 400) line.append((char) (b & 0xff));
        }

        private void handle(String l) {
            String t = null;
            if (l.startsWith("STEP ")) t = l.substring(5);
            else if (l.startsWith("PATCH ")) t = l.substring(6);
            if (t == null) return;
            text = t.replace("OK ", "Applied: ").replace("FAIL ", "Failed: ");
            long now = System.currentTimeMillis();
            if (now - lastNotify < 400) return;
            lastNotify = now;
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) try { nm.notify(ID, build()); } catch (RuntimeException ignored) {}
        }
    }

    private static void append(File f, String s) {
        try {
            FileOutputStream o = new FileOutputStream(f, true);
            try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
        } catch (IOException ignored) {}
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text.isEmpty() ? "Working…" : text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true).setProgress(0, 0, true);
        Intent cancel = new Intent(this, MorpheService.class).setAction(ACTION_CANCEL);
        b.addAction(new Notification.Action.Builder(null, "Cancel", PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    private void notifyDone(boolean ok) {
        if (!"patch".equals(cmdName)) return;                                                          // a patch list is not worth a note
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(DONE_CHANNEL, "Morphe Patcher finished", NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, DONE_CHANNEL) : new Notification.Builder(this);
            b.setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                    .setContentTitle(title).setContentText(ok ? "Patched. Open the app to install it." : "Patching failed. Open the app for the log.").setAutoCancel(true);
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(DONE_ID, b.build());
        } catch (RuntimeException ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
