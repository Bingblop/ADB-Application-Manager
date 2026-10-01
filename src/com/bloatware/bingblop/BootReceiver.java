package com.bloatware.bingblop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * After a reboot, notices a system update (the build fingerprint changed) and, if a profile is being
 * watched, reminds the user to check whether its apps came back. It never changes any app by itself:
 * re-applying a profile is a reviewed, one-tap step inside the app.
 */
public class BootReceiver extends BroadcastReceiver {

    static final String CHANNEL = "profile_watch";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SharedPreferences p = QuickActions.prefs(ctx);
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        String last = p.getString("receiver_fp", null);
        p.edit().putString("receiver_fp", fingerprint).apply();
        String watched = p.getString("watched_profile", "");
        if (last == null || last.equals(fingerprint) || watched.isEmpty()) return;
        notifyUpdated(ctx, watched);
    }

    static void notifyUpdated(Context ctx, String profileName) {
        // Android 13+ needs the notification permission; without it the in-app banner still tells the user
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Profile watch", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(ctx, 31, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Your phone was updated")
                .setContentText("Open the app to check whether apps from \"" + profileName + "\" came back.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(7001, n);
    }
}
