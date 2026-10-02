package com.bloatware.bingblop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Handles the inline reply from the Wi-Fi pairing notification: the user types the 6-digit pairing code
 * (optionally "port code" or "ip:port code"), and this receiver runs `adb pair` in the background and
 * updates the notification with the result - no need to open the app.
 */
public class PairReceiver extends BroadcastReceiver {

    static final String ACTION = "com.bloatware.bingblop.PAIR_REPLY";
    static final String KEY_CODE = "pair_code";
    static final String EXTRA_ENDPOINT = "endpoint";
    static final String CHANNEL = "adb_pairing";
    static final int NOTIF_ID = 7100;

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence reply = results != null ? results.getCharSequence(KEY_CODE) : null;
        final String input = reply != null ? reply.toString().trim() : "";
        final String endpointExtra = intent.getStringExtra(EXTRA_ENDPOINT);
        if (input.isEmpty()) {
            updateNotification(ctx, false, "No pairing code entered. Tap Pair and type the 6-digit code.");
            return;
        }
        updateNotification(ctx, false, "Pairing…");
        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    String endpoint = endpointExtra;
                    String code;
                    String[] toks = input.split("\\s+");
                    if (toks.length == 1) {
                        code = toks[0];
                    } else {
                        code = toks[toks.length - 1];
                        String loc = toks[0];
                        endpoint = loc.contains(":") ? loc : ("127.0.0.1:" + loc);
                    }
                    if (endpoint == null || endpoint.isEmpty()) endpoint = AdbPair.discoverPairingEndpoint(ctx);

                    if (code == null || !code.matches("\\d{6}")) {
                        updateNotification(ctx, false, "That doesn't look like a 6-digit code. Tap Pair and try again.");
                    } else if (endpoint == null || endpoint.isEmpty()) {
                        updateNotification(ctx, false, "Couldn't find the pairing port. Reply with: port code  (e.g. 37123 " + code + ")");
                    } else {
                        String out = AdbPair.pair(ctx, endpoint, code);
                        String low = out == null ? "" : out.toLowerCase();
                        boolean ok = low.contains("successfully paired");
                        if (ok) {
                            updateNotification(ctx, true, "Paired with " + endpoint + ". Open the app and connect.");
                        } else {
                            String why = out == null || out.trim().isEmpty() ? "no response from adb" : out.trim();
                            updateNotification(ctx, false, "Pairing failed: " + why);
                        }
                    }
                } catch (Exception e) {
                    updateNotification(ctx, false, "Pairing error: " + e.getMessage());
                } finally {
                    try { pending.finish(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    static void updateNotification(Context ctx, boolean ok, String text) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Wi-Fi pairing", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent open = PendingIntent.getActivity(ctx, 71, new Intent(ctx, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(ok ? "Paired over Wi-Fi" : "Wi-Fi pairing")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(ok)
                    .build();
            nm.notify(NOTIF_ID, n);
        } catch (Exception ignored) {}
    }
}
