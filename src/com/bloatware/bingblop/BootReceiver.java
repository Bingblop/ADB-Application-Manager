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
        firewallAtBoot(ctx, p, goAsync());
        String fingerprint = Build.FINGERPRINT == null ? "" : Build.FINGERPRINT;
        // "app_fp" is written by MainActivity on every normal launch (see onCreate); it is the only
        // baseline that can exist before this receiver has ever recorded one of its own, so if the OTA
        // reboot is the very first reboot since the user turned profile watching on, that value - not
        // a missing "nothing to compare" - is what "before the update" means here.
        String last = p.getString("receiver_fp", p.getString("app_fp", null));
        p.edit().putString("receiver_fp", fingerprint).apply();
        String watched = p.getString("watched_profile", "");
        if (last == null || last.equals(fingerprint) || watched.isEmpty()) return;
        notifyUpdated(ctx, watched);
    }

    /**
     * Android Firewall (v7.21.0), "Apply on boot": a reboot empties every netfilter table, so the script the app wrote at the last apply
     * (files/firewall/boot.sh, with its own delay in front) is run again through su. Only su: Shizuku is rarely up this early. The script is
     * the app's own file (not a page string), and nothing else is run.
     */
    static void firewallAtBoot(Context ctx, SharedPreferences p, final PendingResult pending) {
        if (!p.getBoolean("fw_boot", false) || !new java.io.File(ctx.getFilesDir(), FirewallRules.BOOT_SCRIPT).isFile()) {
            if (pending != null) { try { pending.finish(); } catch (Throwable ignored) {} }
            return;
        }
        final java.io.File f = new java.io.File(ctx.getFilesDir(), FirewallRules.BOOT_SCRIPT);
        // The script sleeps its delay before the first iptables line, far longer than a receiver may hold a broadcast, so it is started in
        // the background of su's shell with no pipe to this process (an output pipe would break it when this process is gone) and the
        // broadcast is finished as soon as su has taken it: the script outlives this receiver on its own.
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String cmd = "( sh " + BackupScripts.quote(f.getAbsolutePath()) + " </dev/null >/dev/null 2>&1 & )";
                    ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
                    pb.redirectErrorStream(true);
                    pb.redirectOutput(ProcessBuilder.Redirect.to(new java.io.File("/dev/null")));
                    Process proc = pb.start();
                    proc.getOutputStream().close();
                    proc.waitFor();
                } catch (Throwable ignored) {
                } finally {
                    if (pending != null) { try { pending.finish(); } catch (Throwable ignored) {} }
                }
            }
        }, "firewall-boot");
        t.setDaemon(true);
        t.start();
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
