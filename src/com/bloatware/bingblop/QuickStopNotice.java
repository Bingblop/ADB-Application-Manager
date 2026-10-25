package com.bloatware.bingblop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/**
 * The notification of a Stop apps run ({@link QuickStopRun}): "Stopping apps", how far it is ("3 of 12 · Chrome") with a progress bar, a Cancel button, and,
 * on a tap on its body, this app open on Saved Applications with the sheet of the quick list (the run goes on). On Android 16 and later, where the phone
 * lets this app post them, it asks to be a Live Update (a chip in the status bar that says "3/12"); anywhere else, and when that fails, it is the same
 * notification without that. It is cleared when the run ends.
 *
 * Posting and clearing hold the run's monitor, and a run that was cancelled or is over is never posted again: a Cancel that comes while an update is
 * being posted cannot leave a notification behind.
 */
final class QuickStopNotice {
    static final String CHANNEL = "quick_stop";
    static final int ID = 7501;
    static final String ACTION_CANCEL = "com.bloatware.bingblop.QUICK_STOP_CANCEL";
    static final String ACTION_DISMISSED = "com.bloatware.bingblop.QUICK_STOP_DISMISSED";
    private static final int REQ_CANCEL = 7511, REQ_OPEN = 7512, REQ_DISMISS = 7513;
    /** A notification whose process died goes away by itself after this (every update starts the time again). */
    private static final long TIMEOUT_MS = 3 * 60 * 1000L;

    private QuickStopNotice() {}

    /** Posts or updates the notification; nothing when the run was cancelled, is over, or its notification was swiped away. */
    static void show(Context c, QuickStopRun run) {
        try {
            Context app = c.getApplicationContext();
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            synchronized (run) {
                if (!run.open()) return;
                // named after the tile; the same CHANNEL id, so a channel made under the old name (Stop apps) is renamed in place
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kill Apps", NotificationManager.IMPORTANCE_LOW));
                nm.notify(ID, build(app, nm, run));
            }
        } catch (Throwable ignored) {}               // a notification that cannot be shown (no permission, a restriction) never stops the run
    }

    /** The run is over: the notification goes, and no update can bring it back. */
    static void clear(Context c, QuickStopRun run) {
        synchronized (run) {
            run.close();
            dismiss(c);
        }
    }

    /** Cancel on the notification: the run that is going stops after the app it is on, and the notification goes at once. */
    static void cancelRun(Context c) {
        QuickStopRun r = QuickStopRun.active();
        if (r == null) { dismiss(c); return; }           // a notification that outlived its run: only the notification goes
        synchronized (r) {
            r.cancel();
            dismiss(c);
        }
    }

    /** The notification was swiped away: the run goes on without showing it again. */
    static void hideRun() {
        QuickStopRun r = QuickStopRun.active();
        if (r != null) r.hide();
    }

    /** Takes the notification away (also one whose run is gone). */
    static void dismiss(Context c) {
        try {
            NotificationManager nm = (NotificationManager) c.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------------------------------ what it looks like

    private static Notification build(Context c, NotificationManager nm, QuickStopRun run) throws Exception {
        if (canPost(nm)) {
            try { return builder(c, run, true).build(); } catch (Throwable notAsLiveUpdate) { /* the plain one below */ }
        }
        return builder(c, run, false).build();
    }

    private static Notification.Builder builder(Context c, QuickStopRun run, boolean live) throws Exception {
        // not auto-cancel: a tap opens the app and must not clear the notification of a run that goes on
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Stopping apps")
                .setContentText(run.progressText())
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(100, run.percent(), false)
                .setTimeoutAfter(TIMEOUT_MS);
        if (!run.listName().isEmpty()) b.setSubText(run.listName());
        int flags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent cancel = new Intent(c, QuickStopReceiver.class).setAction(ACTION_CANCEL);
        b.addAction(new Notification.Action.Builder(null, "Cancel", PendingIntent.getBroadcast(c, REQ_CANCEL, cancel, flags)).build());
        // a tap on the body: the app on Saved Applications with the sheet of the quick list (MainActivity.takeQuickOpen -> the page's onQuickStopOpen)
        Intent open = new Intent(c, MainActivity.class).putExtra(QuickActions.EXTRA_OPEN_QUICK_LIST, run.listId())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setContentIntent(PendingIntent.getActivity(c, REQ_OPEN, open, flags));
        b.setDeleteIntent(PendingIntent.getBroadcast(c, REQ_DISMISS, new Intent(c, QuickStopReceiver.class).setAction(ACTION_DISMISSED), flags));
        if (live) promote(b, run);
        return b;
    }

    /**
     * Android 16's Live Update: asks for it, gives the chip its text ("3/12") and the bar the style made for it. Reflection, because this app is built
     * against Android 14's SDK; if any of it is missing the caller builds the plain notification.
     */
    private static void promote(Notification.Builder b, QuickStopRun run) throws Exception {
        call(b, "setRequestPromotedOngoing", Boolean.TRUE);
        call(b, "setShortCriticalText", run.shortText());
        Class<?> style = Class.forName("android.app.Notification$ProgressStyle");
        Class<?> segment = Class.forName("android.app.Notification$ProgressStyle$Segment");
        Object st = style.getConstructor().newInstance();
        call(st, "addProgressSegment", segment.getConstructor(int.class).newInstance(100));
        call(st, "setProgress", Integer.valueOf(run.percent()));
        b.setStyle((Notification.Style) st);
    }

    private static Object call(Object target, String name, Object arg) throws Exception {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!name.equals(m.getName()) || p.length != 1) continue;
            boolean fits = p[0].isInstance(arg) || (arg instanceof Boolean && p[0] == boolean.class) || (arg instanceof Integer && p[0] == int.class);
            if (fits) return m.invoke(target, arg);
        }
        throw new NoSuchMethodException(name);
    }

    // ------------------------------------------------------------------------------------------ Live Updates, the permission

    /** True on a phone that has Live Updates (Android 16 and later); the permissions sheet shows its row only there. */
    static boolean liveSupported() {
        return Build.VERSION.SDK_INT >= 36;
    }

    /** True when this app may post Live Updates now (the permission is declared in the manifest and the person has not turned them off for this app). */
    static boolean liveAllowed(Context c) {
        try {
            return canPost((NotificationManager) c.getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean canPost(NotificationManager nm) {
        if (!liveSupported() || nm == null) return false;
        try {
            return Boolean.TRUE.equals(NotificationManager.class.getMethod("canPostPromotedNotifications").invoke(nm));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The system page where the person turns this app's Live Updates on, or null when this phone has none. The action names are read from Settings by
     * name (they are Android 16's), with the strings as a second chance.
     */
    static Intent liveSettingsIntent(Context c) {
        String[] fields = {"ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS", "ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS"};
        String[] literals = {"android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS", "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"};
        for (int i = 0; i < fields.length; i++) {
            String action = literals[i];
            try { action = (String) Settings.class.getField(fields[i]).get(null); } catch (Throwable ignored) {}
            Intent in = new Intent(action).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { if (in.resolveActivity(c.getPackageManager()) != null) return in; } catch (Throwable ignored) {}
        }
        return null;
    }
}
