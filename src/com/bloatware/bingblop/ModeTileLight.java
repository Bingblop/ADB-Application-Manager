package com.bloatware.bingblop;

/**
 * The Working mode tile's light: lit only while a privileged backend (ADB TCP, Wireless Debugging, Shizuku or Root) is really usable, as
 * MainActivity.probeWorkingMode last found it. Automatic is a wish and Read-Only has no power, so neither lights the tile.
 * No Android classes: tested on a computer (tests/java/src/ModeTileLightTest.java). The tile runs without the activity, so MainActivity
 * keeps the probe's two inputs in the app's preferences ({@link #PREF_MODE}, {@link #PREF_AVAILABLE}) and the tile reads them there.
 */
public final class ModeTileLight {
    private ModeTileLight() {}

    /** The resolved working mode the last probe found ("adb_tcp", "shizuku", "unprivileged" ...), written by MainActivity after every probe. */
    public static final String PREF_MODE = "mode_tile_mode";
    /** Whether that mode was available (connected, authorized, or su granted) at the time. */
    public static final String PREF_AVAILABLE = "mode_tile_available";

    /** The backends that give the app its power. Null-safe; "auto" and "unprivileged" are not backends. */
    public static boolean privileged(String mode) {
        return "adb_tcp".equals(mode) || "adb_wireless".equals(mode) || "shizuku".equals(mode) || "root".equals(mode);
    }

    /** True when the tile should be lit: the probe resolved to a privileged backend AND found it available. */
    public static boolean lit(String resolvedMode, boolean available) {
        return available && privileged(resolvedMode);
    }
}
