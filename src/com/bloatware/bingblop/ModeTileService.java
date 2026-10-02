package com.bloatware.bingblop;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: shows the working mode; tapping it switches to the next mode that is ready. */
public class ModeTileService extends TileService {

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        launch(this, QuickActions.pending(this, QuickActions.ACTION_CYCLE_MODE, 11), QuickActions.actionIntent(this, QuickActions.ACTION_CYCLE_MODE));
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Working mode");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(QuickActions.currentModeLabel(this));
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat));
        t.setState(Tile.STATE_ACTIVE);
        t.updateTile();
    }

    @SuppressWarnings("deprecation")
    static void launch(TileService s, PendingIntent pi, Intent intent) {
        if (Build.VERSION.SDK_INT >= 34) s.startActivityAndCollapse(pi);
        else s.startActivityAndCollapse(intent);
    }
}
