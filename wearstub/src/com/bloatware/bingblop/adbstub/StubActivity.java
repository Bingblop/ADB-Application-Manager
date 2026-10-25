package com.bloatware.bingblop.adbstub;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Date;

/** The one screen of the stub: the state of the connection, read again every second. Opening it starts the service that holds the screen and Wi-Fi. */
public final class StubActivity extends Activity {
    private static WeakReference<StubActivity> open = new WeakReference<StubActivity>(null);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state, lines;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    static void finishIfOpen() {
        final StubActivity a = open.get();
        if (a != null) a.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                a.finishAndRemoveTask();
            }
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        open = new WeakReference<StubActivity>(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        int pad = dp(22);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(pad, dp(28), pad, dp(36));
        TextView title = text(13, Color.parseColor("#B0B0B0"));
        title.setText("ADB Stub");
        state = text(20, Color.WHITE);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        lines = text(13, Color.parseColor("#E0E0E0"));
        lines.setLineSpacing(0, 1.25f);
        col.addView(title);
        col.addView(state);
        col.addView(lines);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.setFillViewport(true);
        sv.addView(col);
        setContentView(sv);
        startStub(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        startStub(intent);
    }

    private void startStub(Intent from) {
        Intent i = new Intent(this, StubService.class);
        if (from != null && from.hasExtra(StubService.EXTRA_PHONE)) i.putExtra(StubService.EXTRA_PHONE, from.getStringExtra(StubService.EXTRA_PHONE));
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        if (open.get() == this) open.clear();
        super.onDestroy();
    }

    private void render() {
        boolean on = StubService.running;
        boolean wifi = StubService.wifiUp;
        if (!on) {
            state.setText("Starting…");
            state.setTextColor(Color.parseColor("#B0B0B0"));
        } else if (wifi) {
            state.setText("Connected");
            state.setTextColor(Color.parseColor("#4CD07D"));
        } else {
            state.setText("Waiting for Wi-Fi");
            state.setTextColor(Color.parseColor("#FFB74D"));
        }
        StringBuilder b = new StringBuilder();
        if (!StubService.phone.isEmpty()) b.append("Phone: ").append(StubService.phone).append('\n');
        b.append("Wi-Fi: ").append(wifi ? "connected" + (StubService.ip.isEmpty() ? "" : " · " + StubService.ip) : "not connected").append('\n');
        String wd = wirelessDebugging();
        if (wd != null) b.append("Wireless debugging: ").append(wd).append('\n');
        b.append("Screen: ").append(on ? "kept on" : "normal").append('\n');
        if (on && StubService.since > 0) b.append("Since ").append(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(StubService.since))).append('\n');
        b.append("\nDisconnect on the phone to stop.");
        lines.setText(b.toString());
    }

    /** "on" / "off" from the system setting (Android 11 and later), or null when the watch has no such setting. */
    private String wirelessDebugging() {
        if (Build.VERSION.SDK_INT < 30) return null;
        try {
            return Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1 ? "on" : "off";
        } catch (Exception e) {
            return null;
        }
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
