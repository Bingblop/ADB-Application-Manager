package com.bloatware.bingblop.adbstub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The phone's calls over adb (am broadcast; only the shell and the system may send them, see the manifest): Stop (its Disconnect) stops and closes,
 * Renew keeps the stub holding for another lease (see {@link Lease}).
 */
public final class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (StubService.ACTION_STOP.equals(intent.getAction())) StubService.stopNow(context);
        else if (StubService.ACTION_RENEW.equals(intent.getAction())) {
            if (intent.hasExtra(StubService.EXTRA_PORT)) StubService.updatePort(intent.getIntExtra(StubService.EXTRA_PORT, 0));
            StubService.renewNow();
        }
    }
}
