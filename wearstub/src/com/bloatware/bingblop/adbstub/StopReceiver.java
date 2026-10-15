package com.bloatware.bingblop.adbstub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The phone's Disconnect (am broadcast over adb; only the shell and the system may send it, see the manifest): stop and close. */
public final class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && StubService.ACTION_STOP.equals(intent.getAction())) StubService.stopNow(context);
    }
}
