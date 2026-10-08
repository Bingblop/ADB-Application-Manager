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
import android.os.PowerManager;

/**
 * Keeps the Helper's downloads (the ones the in-app browser started, see {@link HelperDownloads}) going when the app is left or the screen goes off.
 * The downloading itself runs in the manager's own threads; this holds the process (a foreground service of type dataSync), the CPU (a partial wake lock
 * that is given back as soon as nothing is going on) and the notification: how many files, how far, how fast, how long is left, with Pause all and Cancel.
 * {@link #sync} is called whenever a job changes: it starts the service when the first job runs and stops it, leaving a short note of how it went, when the last
 * one is over. A job that is paused stays paused (and resumable): nothing is lost when the system ends the service.
 */
public class DownloadService extends Service {
    private static final String CHANNEL = "helper_dl", DONE_CHANNEL = "helper_dl_done";
    private static final int ID = 7401, DONE_ID = 7402;
    private static final String ACTION_PAUSE = "com.bloatware.bingblop.HD_PAUSE_ALL", ACTION_CANCEL = "com.bloatware.bingblop.HD_CANCEL_ALL";
    private static final long NOTIFY_EVERY_MS = 900, WAKE_MS = 10 * 60 * 1000L, WAKE_RENEW_MS = 8 * 60 * 1000L;

    private static final DownloadNotice notice = new DownloadNotice();
    private static volatile HelperDownloads manager;
    private static volatile boolean running;            // the service has called startForeground
    private static boolean startAsked;                  // startForegroundService has been called and the service has not stopped since
    private static boolean stopRequested;               // everything ended before the service had started: it stops itself as soon as it has
    private static long lastNotify, lastRenew;
    private static DownloadNotice.Snapshot last = new DownloadNotice.Snapshot();
    private static PowerManager.WakeLock wake;

    /**
     * Called when a job changed ({@code stateChanged}: it went from one state to another; progress calls come many times a second and are thinned out here).
     * {@code enabled} false is the person's choice of no background service: nothing is started and a running service is stopped.
     */
    public static void sync(Context c, HelperDownloads dl, boolean stateChanged, boolean enabled) {
        long now = System.currentTimeMillis();
        boolean start = false, stop = false, notifyNow = false;
        DownloadNotice.Snapshot s;
        String doneText = null;
        boolean failure = false;
        synchronized (DownloadService.class) {
            manager = dl;
            if (!stateChanged && now - lastNotify < NOTIFY_EVERY_MS) return;
            s = notice.update(dl.list(), now);
            last = s;
            if (enabled && s.active()) {
                if (!startAsked) { startAsked = true; stopRequested = false; start = true; }
                if (running) notifyNow = true;
                lastNotify = now;
                renewWake(c, now);
            } else if (startAsked || running) {
                stop = true;
                startAsked = false;
                stopRequested = !running;
                doneText = enabled ? notice.summary() : null;
                failure = notice.hadFailure();
                notice.reset();
                releaseWake();
            } else {
                // no service was running (it was refused, or it is switched off): a note still says how the downloads went when they are over
                if (enabled && !s.active()) { doneText = notice.summary(); failure = notice.hadFailure(); }
                if (!s.active() || !enabled) notice.reset();
            }
        }
        if (start) {
            try {
                Intent i = new Intent(c, DownloadService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
            } catch (RuntimeException e) {
                // the system refused a background start: the downloads still run, only without the notification
                synchronized (DownloadService.class) { startAsked = false; }
            }
        }
        if (notifyNow) post(c, s);
        if (stop && running) {
            try { c.stopService(new Intent(c, DownloadService.class)); } catch (RuntimeException ignored) {}
        }
        if (doneText != null) doneNote(c, doneText, failure);
    }

    private static void renewWake(Context c, long now) {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbmanager:helper-downloads");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld() || now - lastRenew > WAKE_RENEW_MS) { wake.acquire(WAKE_MS); lastRenew = now; }
        } catch (RuntimeException ignored) {}
    }

    private static void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (RuntimeException ignored) {}
    }

    private static void post(Context c, DownloadNotice.Snapshot s) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) try { nm.notify(ID, build(c, s)); } catch (RuntimeException ignored) {}
    }

    private static Notification build(Context c, DownloadNotice.Snapshot s) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(s.title.isEmpty() ? "Downloading" : s.title)
                .setContentText(s.text.isEmpty() ? "Starting…" : s.text).setStyle(new Notification.BigTextStyle().bigText(s.text))
                .setOngoing(true).setOnlyAlertOnce(true);
        if (s.percent >= 0) b.setProgress(100, s.percent, false); else b.setProgress(0, 0, true);
        Intent pause = new Intent(c, DownloadService.class).setAction(ACTION_PAUSE);
        Intent cancel = new Intent(c, DownloadService.class).setAction(ACTION_CANCEL);
        int fl = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        b.addAction(new Notification.Action.Builder(null, "Pause all", PendingIntent.getService(c, 11, pause, fl)).build());
        b.addAction(new Notification.Action.Builder(null, "Cancel", PendingIntent.getService(c, 12, cancel, fl)).build());
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setContentIntent(PendingIntent.getActivity(c, 0, open, fl));
        return b.build();
    }

    private static void doneNote(Context c, String text, boolean failure) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(DONE_CHANNEL, "Helper downloads finished", NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, DONE_CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(failure ? android.R.drawable.stat_notify_error : android.R.drawable.stat_sys_download_done).setContentTitle("Morphe Helper").setContentText(text).setAutoCancel(true);
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            nm.notify(DONE_ID, b.build());
        } catch (RuntimeException ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_PAUSE.equals(action) || ACTION_CANCEL.equals(action)) {
            HelperDownloads dl = manager;
            if (dl == null) { if (!running) stopSelf(); return START_NOT_STICKY; }        // the notification outlived the process that had the downloads
            if (ACTION_PAUSE.equals(action)) dl.pauseAll(); else dl.cancelAll();
            return START_NOT_STICKY;                                                       // the jobs report their change: sync stops the service when the last one is over
        }
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Helper downloads", NotificationManager.IMPORTANCE_LOW));
            Notification n;
            synchronized (DownloadService.class) { n = build(this, last); }
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
        } catch (RuntimeException e) {
            // not allowed to run in the foreground right now (a restriction of the phone): the downloads go on without the notification
            synchronized (DownloadService.class) { startAsked = false; }
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean stop;
        synchronized (DownloadService.class) { running = true; stop = stopRequested; stopRequested = false; }
        if (stop) stopSelf();                                                              // everything was over before the service got going
        return START_NOT_STICKY;
    }

    /** Android 15 ends a data-sync service after six hours: what is going is paused (and can be resumed), not lost. */
    public void onTimeout(int startId, int fgsType) {
        HelperDownloads dl = manager;
        if (dl != null) dl.pauseAll();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        synchronized (DownloadService.class) { running = false; startAsked = false; releaseWake(); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
