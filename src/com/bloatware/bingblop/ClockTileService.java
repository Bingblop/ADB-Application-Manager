package com.bloatware.bingblop;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Quick Settings tile: the time with seconds as its label (24 hours or 12 hours, as the phone is set), redrawn every second only while the panel
 * is open, with the date as its subtitle (Android 10 and newer) and a clock face drawn at run time. A tap opens the alarm list. It is the
 * Quick Settings way to see seconds on any phone, without touching the clock setting.
 */
public class ClockTileService extends TileService {

    private static final int SIZE = 96;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean listening;
    private SimpleDateFormat fmt24, fmt12, fmtAmPm, fmtDate;
    private String datePattern = "";
    private long iconMinute = -1;
    private Icon icon;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!listening) return;
            render();
            handler.postDelayed(this, 1010 - System.currentTimeMillis() % 1000);        // the next second, a hair after it starts
        }
    };

    @Override
    public void onStartListening() {
        listening = true;
        handler.removeCallbacks(tick);
        render();
        handler.postDelayed(tick, 1010 - System.currentTimeMillis() % 1000);
    }

    @Override
    public void onStopListening() {
        listening = false;
        handler.removeCallbacks(tick);
    }

    @Override
    public void onDestroy() {
        listening = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override
    public void onClick() {
        Intent i = new Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ModeTileService.launch(this, PendingIntent.getActivity(this, 15, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT), i);
        } catch (RuntimeException ignored) {
            // no clock app answers
        }
    }

    private void render() {
        Tile t = getQsTile();
        if (t == null) return;
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        Locale loc = Locale.getDefault();
        boolean h24 = DateFormat.is24HourFormat(this);
        if (fmt24 == null) {
            fmt24 = new SimpleDateFormat("HH:mm:ss", loc);
            fmt12 = new SimpleDateFormat("h:mm:ss", loc);
            fmtAmPm = new SimpleDateFormat("a", loc);
        }
        SimpleDateFormat f = h24 ? fmt24 : fmt12;
        f.setTimeZone(zone);
        t.setLabel(f.format(now));
        if (Build.VERSION.SDK_INT >= 29) {
            String pattern = DateFormat.getBestDateTimePattern(loc, "EEEMMMd");
            if (fmtDate == null || !pattern.equals(datePattern)) {
                datePattern = pattern;
                fmtDate = new SimpleDateFormat(pattern, loc);
            }
            fmtDate.setTimeZone(zone);
            String date = fmtDate.format(now);
            if (h24) {
                t.setSubtitle(date);
            } else {
                fmtAmPm.setTimeZone(zone);
                t.setSubtitle(fmtAmPm.format(now) + ", " + date);
            }
        }
        long minute = (now + zone.getOffset(now)) / 60000L;
        if (icon == null || minute != iconMinute) {
            iconMinute = minute;
            icon = Icon.createWithBitmap(face(now, zone));
        }
        t.setIcon(icon);
        t.setState(Tile.STATE_ACTIVE);
        t.updateTile();
    }

    /** A clock face with both hands at the time, white on transparent (Quick Settings tints the shape). */
    private static Bitmap face(long now, TimeZone zone) {
        Calendar cal = Calendar.getInstance(zone);
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR), minute = cal.get(Calendar.MINUTE);
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(7);
        p.setStrokeCap(Paint.Cap.ROUND);
        float cx = SIZE / 2f, cy = SIZE / 2f;
        c.drawCircle(cx, cy, 40, p);
        double ma = Math.toRadians(minute * 6.0), ha = Math.toRadians((hour + minute / 60.0) * 30.0);
        c.drawLine(cx, cy, cx + (float) Math.sin(ma) * 30f, cy - (float) Math.cos(ma) * 30f, p);
        p.setStrokeWidth(8);
        c.drawLine(cx, cy, cx + (float) Math.sin(ha) * 20f, cy - (float) Math.cos(ha) * 20f, p);
        return b;
    }
}
