package com.bloatware.bingblop;

/**
 * Decisions of a backup restore that need no Android classes, so they can be tested.
 */
final class RestorePlan {
    private RestorePlan() {}

    /** ApplicationInfo.FLAG_SYSTEM. */
    static final int FLAG_SYSTEM = 1;

    /**
     * Whether a restore brings the package back with {@code pm install-existing} instead of installing the backup's APK.
     * {@code found} is true when getPackageInfo(pkg, MATCH_UNINSTALLED_PACKAGES) returned something, and {@code appInfoFlags} are the flags of its
     * ApplicationInfo. That call also returns a user app that was removed with {@code pm uninstall -k} (data kept); such an app has no copy
     * on the system image and no signing info, so it takes the normal install path.
     */
    static boolean bringBackSystemApp(boolean found, int appInfoFlags) {
        return found && (appInfoFlags & FLAG_SYSTEM) != 0;
    }
}
