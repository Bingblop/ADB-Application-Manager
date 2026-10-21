package com.bloatware.bingblop;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which Android build family (a profile) builds the phone's theme colours, so the page can compute what that build's SystemUI writes,
 * with that build's resource names, and refuse every build it cannot compute (findings T-049, T-050). No Android classes: MainActivity
 * looks each name of {@link #PROBES} up with getIdentifier(name, "color", "android") and each name of {@link #BOOL_PROBES} with
 * getIdentifier(name, "bool", "android") and passes the names found here with the SDK level; tested on a computer
 * (tests/java/src/MonetProfileTest.java). getSystemPalette reports the answer to the page as "profile" and "profileReason".
 *
 * <p>One UI is decided like any other build, by its SDK level and its names (the owner's decision, PR 13): Samsung's builds keep Android's
 * Monet colours, which Google apps and other apps that use them read. That Samsung's own apps may keep Samsung's palette is inferred from how
 * overlays work, not measured on a phone; {@link #oneUi} only tells the page whether to show its note about it.
 *
 * <p>The probes are colour names of the framework's resources (frameworks/base core/res/res/values/colors.xml) that exist on some
 * releases only, read in AOSP's branch of each release:
 * <ul>
 * <li>system_on_theme_app_ring_light: android15-release only (15 QPR1 renamed it system_theme_notif_light);</li>
 * <li>system_theme_notif_light: android15-qpr1-release and every later branch read (15 QPR2, 16.0, 16 QPR1, 16 QPR2, 17);</li>
 * <li>system_inverse_surface_light: android15-qpr2-release, android16-release and later;</li>
 * <li>system_primary_dim_light: android16-qpr1-release, android16-qpr2-release and android17-release;</li>
 * <li>system_semantic_red_container_high_light: android16-qpr2-release only (android17-release does not have it);</li>
 * <li>system_primary_fixed_light: android17-release only (android16-qpr2-release and every earlier branch have the unsuffixed
 * system_primary_fixed only; 17 adds the _light and _dark names and makes the unsuffixed one an alias of _light in values/ and of _dark in
 * values-night/).</li>
 * </ul>
 * None of them is in android14-release. They are plain {@code <color>} entries (no feature flag on the entry), so a build of that branch
 * has them. That a phone maker's build keeps them, and that getIdentifier finds a name the SDK does not publish, was not checked on a
 * phone.
 *
 * <p>No colour name tells Android 14.0 from its quarterly updates (QPR3 adds system_error_* only), so SDK 34 is told apart by a framework
 * boolean (frameworks/base core/res/res/values/config.xml):
 * <ul>
 * <li>config_repairModeSupported: android14-qpr1-release (line 6591), android14-qpr2-release (6810) and android14-qpr3-release (6907), a
 * plain {@code <bool>} entry and a java-symbol (Android 14 has no per-entry resource flag); not anywhere in android14-release's core/res.
 * On the 19 Android 14 branch heads read it is there exactly where SystemUI's ThemeOverlayController imports libmonet (qpr1, qpr2, qpr3,
 * qpr3-d1, d2 and their -sN branches) and on none of the 14.0 lines (release, s1, s2, security, platform, gsi, d1); of the 71
 * android-14.0.0_rN tags of frameworks/base, the 55 that have it all import libmonet there and the 16 without it do not.</li>
 * </ul>
 * An SDK 34 phone without it (or whose lookup throws) stays {@link #P14}, as every SDK 34 phone was before.
 *
 * <p>Fail closed: a build that matches no row below gets {@link #UNKNOWN}, and the page writes no root colours there.
 */
public final class MonetProfile {
    private MonetProfile() {}

    // ---- profiles the page can compute (reason REASON_OK)
    /** Android 12, 12L and 13 (SDK 31-33): no colour roles; the page offers Pure black only there, as before this class. */
    public static final String P12 = "P12";
    /** Android 14.0 (SDK 34 without config_repairModeSupported): android14-release's own SystemUI colour maths. */
    public static final String P14 = "P14";
    /**
     * Android 14 QPR1, QPR2 and QPR3 (SDK 34 with config_repairModeSupported): {@link #P14}'s palettes, shades, names and layout (the same
     * 165 entries), with the 44 roles and 12 fixed colours from the libmonet these branches build (external/libmonet android14-qpr1, qpr2
     * and qpr3-release are the same tree), which the page computes.
     */
    public static final String P14Q = "P14Q";
    /**
     * Android 15.0 (SDK 35 with system_on_theme_app_ring_light, without system_theme_notif_light): 44 roles, the same names and layout as
     * {@link #P15Q1} (palettes and fixed colours unsuffixed), with libmonet android15-release's own role maths, which the page computes.
     */
    public static final String P15 = "P15";
    /** Android 15 QPR1 (SDK 35 with system_theme_notif_light, without system_inverse_surface_light): 44 roles. */
    public static final String P15Q1 = "P15Q1";
    /** Android 15 QPR2 (SDK 35 with system_inverse_surface_light): 50 roles. */
    public static final String P15Q2 = "P15Q2";
    /** Android 16.0 (SDK 36 with system_inverse_surface_light, without system_primary_dim_light): 50 roles. */
    public static final String P16 = "P16";
    /**
     * Android 16 QPR1 (SDK 36 with system_primary_dim_light, without system_semantic_red_container_high_light): spec 2025, 55 roles, and
     * SystemUI writes every palette shade and role as {@code _light} and {@code _dark} (libmonet android16-qpr1-release).
     */
    public static final String P16Q1 = "P16Q1";
    /** Android 16 QPR2 (SDK 36 with system_semantic_red_container_high_light): as {@link #P16Q1}, with QPR2's own role changes (libmonet android16-qpr2-release). */
    public static final String P16Q2 = "P16Q2";
    /**
     * Android 17 (SDK 37 with system_primary_fixed_light): spec 2026, 55 roles, and SystemUI writes every palette shade, role and fixed colour
     * as {@code _light} and {@code _dark} (libmonet android17-release).
     */
    public static final String P17 = "P17";

    /**
     * Whether SystemUI on this profile writes the palette shades as {@code system_<row>_<shade>_light} and {@code _dark} (each from its own
     * mode's scheme) and leaves the unsuffixed {@code system_<row>_<shade>} names as aliases of them (Android 16 QPR1, QPR2 and 17: core/res
     * values/colors_dynamic.xml and values-night/colors_dynamic.xml). The overlay this app writes there takes the suffixed names
     * (OverlayRules.fabricatePlanProblem refuses an unsuffixed palette name) and is looked up by them (OverlayRules.lookupEntries).
     */
    public static boolean suffixedPalettes(String profile) {
        return P16Q1.equals(profile) || P16Q2.equals(profile) || P17.equals(profile);
    }

    /**
     * Whether SystemUI on this profile writes the 12 fixed colours as {@code system_primary_fixed_light} and {@code _dark} (and so on), each
     * from its own mode's scheme, and the unsuffixed {@code system_primary_fixed} is an alias of the {@code _light} name in core/res
     * values/colors.xml and of the {@code _dark} name in values-night/colors.xml (Android 17; values-night/colors_dynamic.xml also points the
     * dark materialColor*Fixed at {@code _dark}; TOC assignColorsToOverlay has no fixed branch any more), so an overlay on the unsuffixed
     * name would show one colour over both of SystemUI's. There the overlay this app writes takes the suffixed fixed names (OverlayRules.fabricatePlanProblem
     * refuses an unsuffixed one).
     */
    public static boolean suffixedFixed(String profile) {
        return P17.equals(profile);
    }

    /** Anything else: an SDK level this app does not know, or an SDK level whose names match no release. */
    public static final String UNKNOWN = "unknown";

    // ---- the reason keys ("profileReason" in getSystemPalette)
    /** The page can compute this profile. */
    public static final String REASON_OK = "ok";
    /** A known Android release whose colours the app cannot compute yet (no profile gives it now; the page still handles the key). */
    public static final String REASON_NOT_YET = "notYet";
    /** No release matched (or the names could not be read). */
    public static final String REASON_UNKNOWN = "unknown";

    // ---- the probe names (colour resources of package "android")
    public static final String PROBE_INVERSE_SURFACE = "system_inverse_surface_light";
    public static final String PROBE_THEME_NOTIF = "system_theme_notif_light";
    public static final String PROBE_ON_THEME_APP_RING = "system_on_theme_app_ring_light";
    public static final String PROBE_PRIMARY_DIM = "system_primary_dim_light";
    public static final String PROBE_SEMANTIC_RED = "system_semantic_red_container_high_light";
    public static final String PROBE_PRIMARY_FIXED = "system_primary_fixed_light";

    /** The Android 14 quarterly-update probe: a boolean resource of package "android" (not a colour). */
    public static final String PROBE_REPAIR_MODE = "config_repairModeSupported";

    /**
     * Every colour name {@link #of} looks at: MainActivity checks each with getIdentifier(name, "color", "android") and passes the ones that
     * exist.
     */
    public static final List<String> PROBES = Collections.unmodifiableList(Arrays.asList(
            PROBE_INVERSE_SURFACE, PROBE_THEME_NOTIF, PROBE_ON_THEME_APP_RING, PROBE_PRIMARY_DIM, PROBE_SEMANTIC_RED, PROBE_PRIMARY_FIXED));

    /**
     * Every boolean name {@link #of} looks at: MainActivity checks each with getIdentifier(name, "bool", "android") and passes the ones that
     * exist in the same set as the colour names.
     */
    public static final List<String> BOOL_PROBES = Collections.unmodifiableList(Arrays.asList(PROBE_REPAIR_MODE));

    /** A profile id and why it is (or is not) usable. */
    public static final class Result {
        public final String profile;
        public final String reason;

        Result(String profile, String reason) {
            this.profile = profile;
            this.reason = reason;
        }

        /** True when the page may write root colours for this profile. */
        public boolean ok() { return REASON_OK.equals(reason); }

        @Override
        public String toString() { return profile + "/" + reason; }
    }

    private static Result ok(String p) { return new Result(p, REASON_OK); }
    private static Result unknown() { return new Result(UNKNOWN, REASON_UNKNOWN); }

    /**
     * The decision. {@code sdk} is Build.VERSION.SDK_INT, {@code present} the names of {@link #PROBES} and {@link #BOOL_PROBES} the phone
     * has (null when they could not be read: then nothing is known). A One UI phone is decided by the same rule as any other.
     * The order of the tests is part of the rule: per SDK level the newest release first. On 34 the quarterly-update
     * name gives {@link #P14Q}; without it, or when the names could not be read, the answer stays {@link #P14} (the answer every SDK 34 phone
     * had before). On 37 the Android 17 name must be there: a 37 build without it, or whose names could not be read, is unknown (fail
     * closed), and so is every SDK level after 37.
     */
    public static Result of(int sdk, Set<String> present) {
        if (sdk >= 31 && sdk <= 33) return ok(P12);
        if (sdk == 34) return present != null && present.contains(PROBE_REPAIR_MODE) ? ok(P14Q) : ok(P14);
        if (present == null) return unknown();
        if (sdk == 35) {
            if (present.contains(PROBE_INVERSE_SURFACE)) return ok(P15Q2);
            if (present.contains(PROBE_THEME_NOTIF)) return ok(P15Q1);
            if (present.contains(PROBE_ON_THEME_APP_RING)) return ok(P15);
            return unknown();
        }
        if (sdk == 36) {
            if (present.contains(PROBE_SEMANTIC_RED)) return ok(P16Q2);
            if (present.contains(PROBE_PRIMARY_DIM)) return ok(P16Q1);
            if (present.contains(PROBE_INVERSE_SURFACE)) return ok(P16);
            return unknown();
        }
        if (sdk == 37) {
            if (present.contains(PROBE_PRIMARY_FIXED)) return ok(P17);
            return unknown();
        }
        return unknown();                                            // below Android 12 (no Material You) and every SDK level after 37
    }

    /** The One UI value {@link #oneUi} gives a Samsung phone whose version could not be read: not blank, so the page counts it as One UI. */
    public static final String ONE_UI_UNREAD = "?";

    /**
     * The One UI value getSystemPalette reports as "oneUi" (the page shows its One UI note on the root theme card when it is not blank; it
     * no longer decides the profile). {@code samsung}: Build.MANUFACTURER or Build.BRAND is samsung; {@code version}: the One UI version
     * read ("6.1.1"; "" or null when none was); {@code propertyUnset}: getprop ro.build.version.oneui ran and printed nothing (the property
     * is not set; false when getprop failed, timed out or printed something that is not a version). A version read is the answer. A Samsung
     * phone without one gives "" only when the property is not set (a Samsung phone running another build of Android); when it could not be
     * read, {@link #ONE_UI_UNREAD}, which counts as One UI (the note shows; MainActivity does not keep that answer, so the next call reads
     * again). Any other phone: "".
     */
    public static String oneUi(boolean samsung, String version, boolean propertyUnset) {
        if (!samsung) return "";
        if (version != null && !version.trim().isEmpty()) return version.trim();
        return propertyUnset ? "" : ONE_UI_UNREAD;
    }

    /**
     * The contrast getSystemPalette reports: UiModeManager.getContrast() widened to a double, which is the value SystemUI hands its
     * colour maths (a float passed where a double is taken), so the page computes at the same number. NaN or an infinity counts as 0
     * (unavailable); a finite value outside [-1, 1] is held at the nearest end (the range the API documents).
     */
    public static double contrast(float raw) {
        if (Float.isNaN(raw) || Float.isInfinite(raw)) return 0;
        if (raw < -1f) return -1;
        if (raw > 1f) return 1;
        return raw;
    }

    /**
     * Whether the lock-screen wallpaper is the one set most recently, so its colours are the ones SystemUI seeds the theme from: SystemUI's
     * ThemeOverlayController.getLatestWallpaperType takes the lock wallpaper when its WallpaperManager id is higher than the home
     * (system) wallpaper's, else the home one (android14-release). getWallpaperId answers a negative number for a kind that is not set
     * (no separate lock wallpaper), which is never higher than a set home wallpaper's id.
     */
    public static boolean lockWallpaperIsLatest(int lockId, int systemId) {
        return lockId > systemId;
    }
}
