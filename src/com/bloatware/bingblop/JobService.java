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
    private static volatile Runnable cancelHook;
    private static volatile String title = "Working…";
    private static volatile String text = "";
    private static volatile int pct = -1;
    private static volatile boolean running;

    /** Starts the notification. {@code onCancel} runs when the person taps Cancel in it. */
    public static void begin(Context c, String jobTitle, Runnable onCancel) {
        title = jobTitle; text = ""; pct = -1; cancelHook = onCancel;
        try {
            Intent i = new Intent(c, JobService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException e) {
            // the system refused a background start: the job still runs, only without the notification
        }
    }

    public static void progress(Context c, String t, int percent) {
        text = t; pct = percent;
        if (!running) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(ID, build(c)); } catch (RuntimeException ignored) {}
    }

    public static void end(Context c, String doneText) {
        cancelHook = null;
        try { c.stopService(new Intent(c, JobService.class)); } catch (RuntimeException ignored) {}
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
            Runnable r = cancelHook;
            if (r != null) try { r.run(); } catch (RuntimeException ignored) {}
            text = "Stopping…";
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && running) try { nm.notify(ID, build(this)); } catch (RuntimeException ignored) {}
            return START_NOT_STICKY;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "File jobs", NotificationManager.IMPORTANCE_LOW));
        Notification n = build(this);
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(ID, n);
        running = true;
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() { running = false; super.onDestroy(); }

    @Override public IBinder onBind(Intent intent) { return null; }
}
