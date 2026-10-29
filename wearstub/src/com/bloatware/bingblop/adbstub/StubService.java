package com.bloatware.bingblop.adbstub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.net.Inet4Address;

/**
 * What the stub is for, held while it runs: the screen on (a bright screen wake lock), Wi-Fi on and at full power (a Wi-Fi lock, and a request for a
 * Wi-Fi network: on Wear OS that is what stops the watch from turning Wi-Fi off and going back to the phone's Bluetooth), shown as a foreground
 * service so the watch does not end it. Everything is let go in {@link #release} (Stop, the phone's Disconnect, the end of the {@link Lease} when the
 * phone stops renewing it) and, should that be skipped, when the process ends: the system drops the locks and the network request of a process that is
 * gone, so am force-stop also gives the watch back its normal Wi-Fi.
 */
public final class StubService extends Service {
    static final String ACTION_STOP = "com.bloatware.bingblop.adbstub.STOP";
    /** The phone's renewal of the lease (am broadcast over adb, to the same receiver as Stop). */
    static final String ACTION_RENEW = "com.bloatware.bingblop.adbstub.RENEW";
    static final String EXTRA_PHONE = "phone";
    /** The adb port the phone reached the watch on (version 3 and later), for the status screen. */
    static final String EXTRA_PORT = "port";
    private static final String CHANNEL = "stub";
    private static final int NOTE_ID = 1;

    /** Read by the status screen. */
    static volatile boolean running;
    static volatile long since;
    static volatile String phone = "";
    static volatile boolean wifiUp;
    static volatile String ip = "";
    static volatile int port;

    private static volatile StubService instance;

    private PowerManager.WakeLock screen;
    private WifiManager.WifiLock wifiLock, lowLatency;
    private ConnectivityManager.NetworkCallback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Lease lease;
    /** At the end of the lease: let go, unless the phone renewed it meanwhile (then wait for the new end). */
    private final Runnable leaseCheck = new Runnable() {
        @Override
        public void run() {
            Lease l = lease;
            if (l == null || !running) return;
            long left = l.left(SystemClock.elapsedRealtime());
            if (left <= 0) stopNow(StubService.this);
            else handler.postDelayed(this, left);
        }
    };

    /** Stops the stub from anywhere in the app (the Stop receiver, the status screen). */
    static void stopNow(Context c) {
        StubService s = instance;
        if (s != null) s.release();
        StubActivity.finishIfOpen();
    }

    /** The phone is still there (the Renew broadcast): the stub keeps holding for another {@link Lease#MS}. Nothing when it is not running. */
    static void renewNow() {
        StubService s = instance;
        if (s != null) s.renew();
    }

    private void renew() {
        Lease l = lease;
        if (running && l != null) l.renew(SystemClock.elapsedRealtime());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopNow(this);
            return START_NOT_STICKY;
        }
        if (intent != null && intent.hasExtra(EXTRA_PHONE)) {
            // a start from the phone says which phone and which port (0: the phone has none to tell, e.g. a Bluetooth link), so a port from an earlier start does not stay
            phone = clean(intent.getStringExtra(EXTRA_PHONE));
            int p = intent.getIntExtra(EXTRA_PORT, 0);
            port = p >= 1 && p <= 65535 ? p : 0;
        }
        startForeground(NOTE_ID, note());
        renew();
        hold();
        return START_STICKY;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\p{Cntrl}]", " ").trim();
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    @SuppressWarnings("deprecation")
    private void hold() {
        if (running) return;
        running = true;
        since = System.currentTimeMillis();
        lease = new Lease(SystemClock.elapsedRealtime());
        handler.removeCallbacks(leaseCheck);
        handler.postDelayed(leaseCheck, Lease.MS);
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            screen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "adbstub:screen");
            screen.setReferenceCounted(false);
            screen.acquire();
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "adbstub:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            if (Build.VERSION.SDK_INT >= 29) {
                lowLatency = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "adbstub:wifi-ll");
                lowLatency.setReferenceCounted(false);
                lowLatency.acquire();
            }
        } catch (Exception ignored) {}
        try {
            final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    wifiUp = true;
                    ip = ipOf(cm.getLinkProperties(n));
                }

                @Override
                public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                    ip = ipOf(lp);
                }

                @Override
                public void onLost(Network n) {
                    wifiUp = false;
                    ip = "";
                }
            };
            cm.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback);
        } catch (Exception ignored) {}
    }

    private static String ipOf(LinkProperties lp) {
        if (lp == null) return "";
        for (LinkAddress a : lp.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return "";
    }

    /** Lets go of everything and ends the service. Safe to call twice. */
    void release() {
        handler.removeCallbacks(leaseCheck);
        lease = null;
        try { if (screen != null && screen.isHeld()) screen.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
        try { if (lowLatency != null && lowLatency.isHeld()) lowLatency.release(); } catch (Exception ignored) {}
        try {
            if (callback != null) ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(callback);
        } catch (Exception ignored) {}
        screen = null;
        wifiLock = null;
        lowLatency = null;
        callback = null;
        running = false;
        wifiUp = false;
        ip = "";
        try { stopForeground(true); } catch (Exception ignored) {}
        stopSelf();
    }

    @Override
    public void onDestroy() {
        release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    private Notification note() {
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, StubActivity.class), immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, StubService.class).setAction(ACTION_STOP), immutable | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "ADB Toolbox", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(R.drawable.ic_stub)
                .setContentTitle("ADB Toolbox")
                .setContentText("Screen on, Wi-Fi held for ADB")
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }
}
