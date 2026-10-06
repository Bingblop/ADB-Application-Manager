package com.bloatware.bingblop;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Icon;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.SparseArray;

/**
 * Quick Settings tile: the battery level as its label ("87%"), a battery glyph drawn at run time (so no drawable files are needed), the charging
 * state as its subtitle (Android 10 and newer), and a tap that opens the battery usage screen. It listens to the battery broadcast only while the
 * panel is open.
 */
public class BatteryTileService extends TileService {

    private static final int SIZE = 96;
    private static final SparseArray<Icon> ICONS = new SparseArray<Icon>();

    private boolean registered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent battery) {
            render(battery);
        }
    };

    @Override
    public void onStartListening() {
        Intent sticky = null;
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            sticky = Build.VERSION.SDK_INT >= 33 ? registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) : registerReceiver(receiver, f);
            registered = true;
        } catch (RuntimeException ignored) {
            // no live updates; the tile still shows what it can read now
        }
        render(sticky);
    }

    @Override
    public void onStopListening() {
        stop();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        if (!registered) return;
        registered = false;
        try {
            unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    @Override
    public void onClick() {
        Intent main = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ModeTileService.launch(this, pending(main, 14), main);
        } catch (RuntimeException e) {
            Intent alt = new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ModeTileService.launch(this, pending(alt, 14), alt);
            } catch (RuntimeException ignored) {
                // this phone has neither screen
            }
        }
    }

    private PendingIntent pending(Intent i, int code) {
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void render(Intent battery) {
        Tile t = getQsTile();
        if (t == null) return;
        int level = -1, scale = 100, status = BatteryManager.BATTERY_STATUS_UNKNOWN, plugged = 0;
        if (battery != null) {
            level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
            plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        }
        if (level < 0) {
            BatteryManager bm = getSystemService(BatteryManager.class);
            if (bm != null) level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        int pct = percent(level, scale);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        t.setLabel(pct < 0 ? "Battery" : pct + "%");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(statusText(status, plugged));
        t.setIcon(icon(pct < 0 ? 0 : pct, charging));
        t.setState(Tile.STATE_ACTIVE);
        t.updateTile();
    }

    /** The level as 0 to 100, or -1 when it is not known. */
    static int percent(int level, int scale) {
        if (level < 0) return -1;
        int p = scale > 0 ? Math.round(level * 100f / scale) : level;
        return Math.max(0, Math.min(100, p));
    }

    static String statusText(int status, int plugged) {
        String source = (plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0 ? " (AC)" : (plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0 ? " (USB)"
                : (plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 ? " (wireless)" : "";
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) return "Charging" + source;
        if (status == BatteryManager.BATTERY_STATUS_FULL) return "Full" + source;
        if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) return "On battery";
        if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) return "Not charging" + source;
        return "";
    }

    /** One of 22 glyphs (a level in steps of 10, with or without the charging bolt), drawn once and kept. */
    static synchronized Icon icon(int pct, boolean charging) {
        int step = Math.max(0, Math.min(100, Math.round(pct / 10f) * 10));
        int key = step * 2 + (charging ? 1 : 0);
        Icon i = ICONS.get(key);
        if (i == null) {
            i = Icon.createWithBitmap(glyph(step, charging));
            ICONS.put(key, i);
        }
        return i;
    }

    /** White on transparent: Quick Settings tints the shape itself. The bolt is cut out of the fill. */
    private static Bitmap glyph(int step, boolean charging) {
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(38, 5, 58, 17), 4, 4, p);                  // the terminal
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        c.drawRoundRect(new RectF(25, 17, 71, 90), 8, 8, p);                 // the body
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(90);
        c.drawRoundRect(new RectF(31, 23, 65, 84), 3, 3, p);                 // the empty part
        p.setAlpha(255);
        if (step > 0) {
            float h = Math.max(6f, 61f * step / 100f);
            c.drawRect(new RectF(31, 84 - h, 65, 84), p);                    // the charge
        }
        if (charging) {
            Path bolt = new Path();
            bolt.moveTo(52, 28);
            bolt.lineTo(37, 56);
            bolt.lineTo(46, 56);
            bolt.lineTo(43, 80);
            bolt.lineTo(59, 49);
            bolt.lineTo(50, 49);
            bolt.close();
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            c.drawPath(bolt, p);
            p.setXfermode(null);
        }
        return b;
    }
}
