package com.bloatware.bingblop;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;

import java.util.function.Consumer;

/**
 * Switches this app's own Quick Settings tiles on and off (the System UI Tuner's "Manage QS tiles"): a tile that is off is not
 * offered in the phone's tile editor. Needs no permission; it only changes this app's own components.
 */
public final class TileSwitch {

    private TileSwitch() {}

    /** Whether the tile service is switched on. A service nobody has switched either way follows the manifest ({@code android:enabled}). */
    public static boolean isEnabled(Context c, Class<?> tile) {
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName cn = new ComponentName(c, tile);
            int s = pm.getComponentEnabledSetting(cn);
            if (s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true;
            if (s != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) return false;       // disabled, disabled by the user, disabled until used
            ServiceInfo si = pm.getServiceInfo(cn, 0);
            return si.enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** Switches the tile service on or off without stopping the app. True when the change was accepted. */
    public static boolean setEnabled(Context c, Class<?> tile, boolean enabled) {
        try {
            c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, tile),
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Asks Android's own "Add tile?" dialog to put the tile in the Quick Settings panel (Android 13 and newer; the tile must be switched on first). False below that, or when the request could not be made. */
    public static boolean requestAdd(Activity a, Class<?> tile, String label) {
        return requestAdd(a, tile, label, null);
    }

    /**
     * The same, and {@code result} (may be null) gets the outcome on the main thread: StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED (2),
     * ..._ALREADY_ADDED (1), ..._NOT_ADDED (0), ..._DIALOG_DISMISSED (3), or an error code of 1000 or more (the app was not in the foreground, a request is
     * already open, the component is not an enabled tile service ...).
     */
    public static boolean requestAdd(Activity a, Class<?> tile, String label, final Consumer<Integer> result) {
        if (Build.VERSION.SDK_INT < 33 || a == null || tile == null) return false;
        try {
            StatusBarManager sbm = a.getSystemService(StatusBarManager.class);
            if (sbm == null) return false;
            ComponentName cn = new ComponentName(a, tile);
            CharSequence name = label == null || label.trim().isEmpty() ? cn.getShortClassName() : label;
            sbm.requestAddTileService(cn, name, Icon.createWithResource(a, R.drawable.ic_stat), a.getMainExecutor(), new Consumer<Integer>() {
                @Override
                public void accept(Integer code) {
                    if (result != null) result.accept(code);
                }
            });
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
