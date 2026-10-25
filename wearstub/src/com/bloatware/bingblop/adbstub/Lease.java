package com.bloatware.bingblop.adbstub;

/**
 * How long the stub holds the watch without word from the phone. The phone renews the lease about once a minute while the watch is connected to it
 * (WearStub.renewCmd in the app); when the renewals stop (the app was closed or killed, the phone lost Wi-Fi, adb was restarted, reconnecting gave up)
 * the stub lets go of the screen and Wi-Fi by itself once the lease is over. Times are SystemClock.elapsedRealtime() (counts deep sleep, never goes
 * back). No Android classes: the wearstub Java suite tests it.
 */
public final class Lease {
    /** Without a renewal for this long the stub lets go. */
    public static final long MS = 5 * 60 * 1000L;

    private long renewedAt;

    public Lease(long now) {
        renewedAt = now;
    }

    /** The phone said it is still there (a renewal, or the start command sent again). */
    public synchronized void renew(long now) {
        if (now > renewedAt) renewedAt = now;
    }

    /** How long until the lease is over: 0 when it is over (let go now), at most {@link #MS}. */
    public synchronized long left(long now) {
        long l = renewedAt + MS - now;
        return l <= 0 ? 0 : Math.min(l, MS);
    }
}
