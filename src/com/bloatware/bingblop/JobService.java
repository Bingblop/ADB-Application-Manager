package com.bloatware.bingblop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps a long file job (a big copy, an extraction) alive when the app is left, and shows its progress with a Cancel button in a notification.
 * The work itself runs in the app's own threads; this only holds the process and the notification. {@link #begin} starts it, {@link #progress}
 * updates the text and bar, {@link #end} stops it and leaves a short "done" note.
 */
public class JobService extends Service {
    private static final String CHANNEL = "jobs", DONE_CHANNEL = "jobs_done";
    private static final int ID = 7201, DONE_ID = 7202;
    private static final String ACTION_CANCEL = "com.bloatware.bingblop.JOB_CANCEL";
    private static final java.util.List<Runnable> cancelHooks = new java.util.ArrayList<Runnable>();
    private static String title = "Working…";
    private static String text = "";
    private static int pct = -1;
    private static int active;                        // jobs that have begun and not ended: the notification stays until the last one is done
    private static volatile boolean running;          // the service has called startForeground
    private static boolean stopRequested;             // the last job ended before the service had started: it stops itself as soon as it has

    /** Starts (or joins) the notification. {@code onCancel} runs when the person taps Cancel in it. */
    public static void begin(Context c, String jobTitle, Runnable onCancel) {
        synchronized (JobService.class) {
            active++;
            title = jobTitle; text = ""; pct = -1; stopRequested = false;
            if (onCancel != null) cancelHooks.add(onCancel);
        }
        try {
            Intent i = new Intent(c, JobService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException e) {
            // the system refused a background start: the job still runs, only without the notification
        }
    }

    public static void progress(Context c, String t, int percent) {
        synchronized (JobService.class) {
            if (active <= 0 || !running) return;
            text = t; pct = percent;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) try { nm.notify(ID, build(c)); } catch (RuntimeException ignored) {}
        }
    }

    /** One job is done. The last one stops the service (after it has started, if it has not yet) and leaves a short "done" note. */
    public static void end(Context c, Runnable onCancel, String doneText) {
        boolean last;
        synchronized (JobService.class) {
            if (onCancel != null) cancelHooks.remove(onCancel);
            active = Math.max(0, active - 1);
            last = active == 0;
            if (last) { text = ""; stopRequested = !running; }
        }
        if (last && running) {
            try { c.stopService(new Intent(c, JobService.class)); } catch (RuntimeException ignored) {}
        }
        if (doneText == null || doneText.isEmpty()) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(DONE_CHANNEL, "File jobs finished", NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, DONE_CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(title).setContentText(doneText).setAutoCancel(true);
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(DONE_ID, b.build());
        } catch (RuntimeException ignored) {}
    }

    private static Notification build(Context c) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).setContentText(text.isEmpty() ? "Working…" : text)
                .setStyle(new Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true);
        if (pct >= 0) b.setProgress(100, pct, false); else b.setProgress(0, 0, true);
        Intent cancel = new Intent(c, JobService.class).setAction(ACTION_CANCEL);
        b.addAction(new Notification.Action.Builder(null, "Cancel", PendingIntent.getService(c, 1, cancel, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return b.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            java.util.List<Runnable> hooks;
            synchronized (JobService.class) { hooks = new java.util.ArrayList<Runnable>(cancelHooks); text = "Stopping…"; }
            for (Runnable r : hooks) try { r.run(); } catch (RuntimeException ignored) {}
            if (!running) { stopSelf(); return START_NOT_STICKY; }                  // nothing runs: this was only the Cancel of a notification that outlived its job
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) try { nm.notify(ID, build(this)); } catch (RuntimeException ignored) {}
            return START_NOT_STICKY;
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "File jobs", NotificationManager.IMPORTANCE_LOW));
            Notification n;
            synchronized (JobService.class) { n = build(this); }
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
        } catch (RuntimeException e) {
            // not allowed to run in the foreground right now (a restriction of the phone): the job goes on without the notification
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean stop;
        synchronized (JobService.class) { running = true; stop = stopRequested; stopRequested = false; }
        if (stop) stopSelf();                                                         // the job was over before the service got going
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { synchronized (JobService.class) { running = false; } super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return null; }
}
