package com.bloatware.bingblop;

/**
 * The root gate: the Root Features surface unlocks only when the resolved working mode is root AND su is actually granted.
 * No Android classes: tested on a computer (tests/java/src/RootGateTest.java). MainActivity.probeWorkingMode reports the same
 * verdict to the page as {@code rootWorkingModeActive}.
 */
public final class RootGate {
    private RootGate() {}

    /** True when Root Features should be unlocked. Null-safe: a mode that is not "root" (null included) is locked whatever su says. */
    public static boolean active(String mode, boolean suGranted) { return "root".equals(mode) && suGranted; }

    /**
     * The Root Features surface unlocks when the resolved working mode is privileged above the ADB shell: Root with su
     * granted (active), or Shizuku running as the Android system user (uid 1000) or as root (uid 0). Shizuku started over
     * ADB runs as the shell user (uid 2000) and does not unlock; an unknown uid (-1) does not either. uid 1000 is the system
     * user, not root - see {@link #uid0} for features Android allows to root alone. Null-safe through active().
     */
    public static boolean unlocked(String mode, boolean suGranted, boolean shizukuAuthorized, int shizukuUid) {
        if (active(mode, suGranted)) return true;
        return "shizuku".equals(mode) && shizukuAuthorized && (shizukuUid == 0 || shizukuUid == 1000);
    }

    /**
     * The stricter tier for a feature Android allows only to the root user (uid 0), such as writing a fabricated colour
     * overlay: Root with su granted, or Shizuku started as root (uid 0). Shizuku as the system user (uid 1000) is privileged
     * but Android refuses these to anyone but uid 0, so it stays locked here. Null-safe through active().
     */
    public static boolean uid0(String mode, boolean suGranted, boolean shizukuAuthorized, int shizukuUid) {
        if (active(mode, suGranted)) return true;
        return "shizuku".equals(mode) && shizukuAuthorized && shizukuUid == 0;
    }
}
