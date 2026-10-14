package com.bloatware.bingblop;

/**
 * Whether a package opened from outside the app ("Open with", the default-installer intent filter) is handed to the Installer. It is handed
 * over once, when it arrives: a re-created window (recreate() after a WebView crash or a reload, or a restore after the process was killed)
 * and a launch from Recents carry the same old intent, and handing it over again would jump to the Installer, switch a turned-off Installer
 * tab back on and read a file the person did not just open.
 */
final class IncomingInstall {
    private IncomingInstall() {}

    /** Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY: the task was started again from Recents with its old intent. */
    static final int FLAG_LAUNCHED_FROM_HISTORY = 0x00100000;

    /** Intent.ACTION_VIEW and Intent.ACTION_INSTALL_PACKAGE, the actions of the intent filter. */
    static boolean isInstallAction(String action) {
        return "android.intent.action.VIEW".equals(action) || "android.intent.action.INSTALL_PACKAGE".equals(action);
    }

    /** In onCreate: {@code restored} is true when there is a saved state (recreate() or a restore), so the intent was seen before. */
    static boolean handOverAtCreate(String action, int flags, boolean restored) {
        return isInstallAction(action) && !restored && (flags & FLAG_LAUNCHED_FROM_HISTORY) == 0;
    }

    /** In onNewIntent: a package opened while the app runs is always new. */
    static boolean handOverAtNewIntent(String action) {
        return isInstallAction(action);
    }
}
