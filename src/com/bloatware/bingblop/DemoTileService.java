package com.bloatware.bingblop;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile: shows whether Demo Mode is on, and a tap turns it on or off (through the working mode, like the Mode tile: the tap goes
 * to {@link QuickActionActivity} with the action {@link QuickActions#ACTION_DEMO_TOGGLE}). Android keeps no readable flag for demo mode that
 * this app may look at, so the state is the one the app saved itself, in the preferences file "sysui_tuner" under "demo_on"; the
 * page and the quick action write it with {@link #setOn}. System UI ends demo mode by itself when it restarts, so after that the tile may say
 * "On" until it is tapped.
 */
public class DemoTileService extends TileService {

    static final String PREFS = "sysui_tuner";
    static final String KEY_ON = "demo_on";

    /** The saved state: true when this app last turned demo mode on. */
    public static boolean isOn(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Saves the state and asks Android to redraw the tile if it is on the panel. */
    public static void setOn(Context c, boolean on) {
        Context app = c.getApplicationContext() != null ? c.getApplicationContext() : c;
        try {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
        } catch (RuntimeException ignored) {
            // the tile then shows the old state until the next tap
        }
        try {
            TileService.requestListeningState(app, new ComponentName(app, DemoTileService.class));
        } catch (Throwable ignored) {
            // the tile is not added, or not switched on
        }
    }

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        boolean on = isOn(this);
        t.setLabel("Demo mode");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(on ? "On" : "Off");
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat));
        t.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        ModeTileService.launch(this, QuickActions.pending(this, QuickActions.ACTION_DEMO_TOGGLE, 13), QuickActions.actionIntent(this, QuickActions.ACTION_DEMO_TOGGLE));
    }
}
