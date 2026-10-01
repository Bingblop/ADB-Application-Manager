package com.bloatware.bingblop;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget: current mode, a button to switch it, and a button to force-stop the quick list. */
public class QuickWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context));
        }
    }

    static RemoteViews build(Context ctx) {
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_quick);
        v.setTextViewText(R.id.widget_mode, "Mode: " + QuickActions.currentModeLabel(ctx));
        v.setTextViewText(R.id.widget_list, "Quick list: " + QuickActions.quickListSummary(ctx));
        v.setOnClickPendingIntent(R.id.widget_btn_mode, QuickActions.pending(ctx, QuickActions.ACTION_CYCLE_MODE, 21));
        v.setOnClickPendingIntent(R.id.widget_btn_stop, QuickActions.pending(ctx, QuickActions.ACTION_STOP_LIST, 22));
        Intent open = new Intent(ctx, MainActivity.class);
        v.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(ctx, 23, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return v;
    }

    /** Redraws every placed widget (after a mode change or when the quick list changes). */
    static void refreshAll(Context ctx) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(ctx);
            int[] ids = m.getAppWidgetIds(new ComponentName(ctx, QuickWidgetProvider.class));
            for (int id : ids) m.updateAppWidget(id, build(ctx));
        } catch (Exception ignored) {}
    }
}
