package com.bloatware.bingblop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The buttons of the Stop apps notification ({@link QuickStopNotice}): Cancel, and the notification being swiped away. Not exported: only the
 * PendingIntents this app gave the notification reach it.
 */
public class QuickStopReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (QuickStopNotice.ACTION_CANCEL.equals(action)) QuickStopNotice.cancelRun(ctx);
        else if (QuickStopNotice.ACTION_DISMISSED.equals(action)) QuickStopNotice.hideRun();
    }
}
