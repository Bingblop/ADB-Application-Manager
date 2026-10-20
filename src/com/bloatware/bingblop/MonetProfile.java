package com.bloatware.bingblop;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Which Android build family (a profile) builds the phone's theme colours, so the page can compute what that build's SystemUI writes,
 * with that build's resource names, and refuse every build it cannot compute (finding T-049). No Android classes: MainActivity looks each
 * name of {@link #PROBES} up with getIdentifier(name, "color", "android"), reads the One UI version and passes both here with the SDK
 * level; tested on a computer (tests/java/src/MonetProfileTest.java). getSystemPalette reports the answer to the page as "profile" and
 * "profileReason".
 *
 * <p>The probes are colour names of the framework's resources (frameworks/base core/res/res/values/colors.xml) that exist on some
 * releases only, read in AOSP's branch of each release:
 * <ul>
 * <li>system_on_theme_app_ring_light: android15-release only (15 QPR1 renamed it system_theme_notif_light);</li>
 * <li>system_theme_notif_light: android15-qpr1-release and every later branch read (15 QPR2, 16.0, 16 QPR1, 16 QPR2);</li>
 * <li>system_inverse_surface_light: android15-qpr2-release, android16-release and later;</li>
 * <li>system_primary_dim_light: android16-qpr1-release and android16-qpr2-release;</li>
 * <li>system_semantic_red_container_high_light: android16-qpr2-release only.</li>
 * </ul>
 * None of them is in android14-release. They are plain {@code <color>} entries (no feature flag on the entry), so a build of that branch
 * has them. That a phone maker's build keeps them, and that getIdentifier finds a name the SDK does not publish, was not checked on a
 * phone.
 *
 * <p>Fail closed: a build that matches no row below gets {@link #UNKNOWN}, and the page writes no root colours there.
 */
public final class MonetProfile {
    private MonetProfile() {}

    // ---- profiles the page can compute (reason REASON_OK)
    /** Android 12, 12L and 13 (SDK 31-33): no colour roles; the page offers Pure black only there, as before this class. */
    public static final String P12 = "P12";
    /** Android 14 (SDK 34). */
    public static final String P14 = "P14";
    /** Android 15 QPR1 (SDK 35 with system_theme_notif_light, without system_inverse_surface_light): 44 roles. */
    public static final String P15Q1 = "P15Q1";
    /** Android 15 QPR2 (SDK 35 with system_inverse_surface_light): 50 roles. */
    public static final String P15Q2 = "P15Q2";
    /** Android 16.0 (SDK 36 with system_inverse_surface_light, without system_primary_dim_light): 50 roles. */
    public static final String P16 = "P16";

    // ---- profiles recognised but not computed yet (reason REASON_NOT_YET)
    /** Android 15.0 (SDK 35 with system_on_theme_app_ring_light): older role maths. */
    public static final String P15 = "P15";
    /** Android 16 QPR1 (SDK 36 with system_primary_dim_light, without system_semantic_red_container_high_light): spec 2025. */
    public static final String P16Q1 = "P16Q1";
    /** Android 16 QPR2 (SDK 36 with system_semantic_red_container_high_light): spec 2025, its own changes. */
    public static final String P16Q2 = "P16Q2";
    /** Android 17 (SDK 37): spec 2026, fixed colours in both modes. */
    public static final String P17 = "P17";

    /** Anything else: One UI, an SDK level this app does not know, or an SDK level whose names match no release. */
    public static final String UNKNOWN = "unknown";

    // ---- the reason keys ("profileReason" in getSystemPalette)
    /** The page can compute this profile. */
    public static final String REASON_OK = "ok";
    /** A known Android release whose colours the app cannot compute yet. */
    public static final String REASON_NOT_YET = "notYet";
    /** A phone with a One UI version: Samsung builds its theme with its own service. */
    public static final String REASON_ONE_UI = "oneUi";
    /** No release matched (or the names could not be read). */
    public static final String REASON_UNKNOWN = "unknown";

    // ---- the probe names (colour resources of package "android")
    public static final String PROBE_INVERSE_SURFACE = "system_inverse_surface_light";
    public static final String PROBE_THEME_NOTIF = "system_theme_notif_light";
    public static final String PROBE_ON_THEME_APP_RING = "system_on_theme_app_ring_light";
    public static final String PROBE_PRIMARY_DIM = "system_primary_dim_light";
    public static final String PROBE_SEMANTIC_RED = "system_semantic_red_container_high_light";

    /** Every name {@link #of} looks at: MainActivity checks each with getIdentifier and passes the ones that exist. */
    public static final List<String> PROBES = Collections.unmodifiableList(Arrays.asList(
            PROBE_INVERSE_SURFACE, PROBE_THEME_NOTIF, PROBE_ON_THEME_APP_RING, PROBE_PRIMARY_DIM, PROBE_SEMANTIC_RED));

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
    private static Result notYet(String p) { return new Result(p, REASON_NOT_YET); }
    private static Result unknown() { return new Result(UNKNOWN, REASON_UNKNOWN); }

    /**
     * The decision. {@code sdk} is Build.VERSION.SDK_INT, {@code present} the names of {@link #PROBES} the phone has (null when they
     * could not be read: then nothing is known) and {@code oneUi} the One UI version ("" or null on a phone without one).
     * The order of the tests is part of the rule: One UI first, then per SDK level the newest release first.
     */
    public static Result of(int sdk, Set<String> present, String oneUi) {
        if (oneUi != null && !oneUi.trim().isEmpty()) return new Result(UNKNOWN, REASON_ONE_UI);
        if (sdk >= 31 && sdk <= 33) return ok(P12);
        if (sdk == 34) return ok(P14);
        if (sdk == 37) return notYet(P17);
        if (present == null) return unknown();
        if (sdk == 35) {
            if (present.contains(PROBE_INVERSE_SURFACE)) return ok(P15Q2);
            if (present.contains(PROBE_THEME_NOTIF)) return ok(P15Q1);
            if (present.contains(PROBE_ON_THEME_APP_RING)) return notYet(P15);
            return unknown();
        }
        if (sdk == 36) {
            if (present.contains(PROBE_SEMANTIC_RED)) return notYet(P16Q2);
            if (present.contains(PROBE_PRIMARY_DIM)) return notYet(P16Q1);
            if (present.contains(PROBE_INVERSE_SURFACE)) return ok(P16);
            return unknown();
        }
        return unknown();                                            // below Android 12 (no Material You) and every SDK level after 37
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
}
