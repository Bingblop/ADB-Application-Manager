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
}
