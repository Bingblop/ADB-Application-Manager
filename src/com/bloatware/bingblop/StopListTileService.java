package com.bloatware.bingblop;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: force-stops every app in the quick list (chosen under Saved Applications). */
public class StopListTileService extends TileService {

    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setLabel("Stop apps");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(QuickActions.quickListSummary(this));
        t.setIcon(Icon.createWithResource(this, R.drawable.ic_stat));
        t.setState(QuickActions.quickList(this) != null ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }

    @Override
    public void onClick() {
        ModeTileService.launch(this, QuickActions.pending(this, QuickActions.ACTION_STOP_LIST, 12), QuickActions.actionIntent(this, QuickActions.ACTION_STOP_LIST));
    }
}
