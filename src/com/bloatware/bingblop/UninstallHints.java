package com.bloatware.bingblop;

/**
 * Reads what {@code pm uninstall} / {@code pm uninstall -k} printed for a system app. Plain string handling with no
 * Android classes, so it can be tested off the device. A privileged (ADB / Shizuku / Root) caller can remove a system
 * app for one user the same way {@code pm uninstall --user 0} always could, but a few specific refusals are common
 * enough, and cryptic enough, to be worth explaining instead of just showing the raw line.
 */
final class UninstallHints {

    private UninstallHints() {}

    static boolean success(String out) {
        return out != null && out.contains("Success");
    }

    /**
     * Removing a system app for one user needs actual root (UID 0) - shell alone (plain ADB, or Shizuku run without
     * root) has never been enough, on any Android version or device brand. This is the exact line Android's own
     * PackageInstallerService prints for that case.
     */
    private static final String ROOT_REQUIRED = "only root can delete system app for a particular user";

    static boolean rootRequired(String out) {
        return out != null && out.toLowerCase(java.util.Locale.US).contains(ROOT_REQUIRED);
    }

    /**
     * Blocked by a device policy (an enterprise / MDM profile, or a device or profile owner that requires this app).
     */
    static boolean policyBlocked(String out) {
        return out != null && out.toUpperCase(java.util.Locale.US).contains("DELETE_FAILED_DEVICE_POLICY_MANAGER");
    }

    /** Blocked by a user restriction (DISALLOW_UNINSTALL_APPS or similar - also usually an enterprise or parental-control setup). */
    static boolean userRestricted(String out) {
        return out != null && out.toUpperCase(java.util.Locale.US).contains("DELETE_FAILED_USER_RESTRICTED");
    }

    /** The same for an app removed on ANOTHER device (Connected Devices), where "switch to Root mode" is not the way out. */
    static String adviceForDevice(String out) {
        if (out == null || success(out)) return "";
        if (rootRequired(out)) {
            return "That device only lets root remove a system app for one user. The direct Binder call this app tries as a fallback (a small helper sent to the device and run there) was not able to either; the reason is in the text above. Disable the app instead: it disappears from the launcher and stops running, but stays installed.";
        }
        return advice(out);
    }

    /** One short line of advice for a failed uninstall / disable (empty when there is nothing useful to add). */
    static String advice(String out) {
        if (out == null || success(out)) return "";
        if (rootRequired(out)) {
            return "Shell-level ADB or Shizuku cannot remove a system app for one user this way, and the direct Binder call this app automatically tries as a fallback for exactly this case was not able to either (the reason is in the text above). If this phone is rooted, switch to Root mode in Working Modes and try again. Freeze is the next best option: the app disappears from the launcher and stops running, but stays installed (just disabled) rather than showing as uninstalled.";
        }
        if (policyBlocked(out)) {
            return "A device policy (an enterprise / work profile, or this phone's device owner) requires this app and is blocking its removal. That can't be bypassed from here while the policy applies.";
        }
        if (userRestricted(out)) {
            return "A restriction on this phone (often set by an enterprise profile or parental controls) blocks uninstalling apps entirely. That can't be bypassed from here while it is set.";
        }
        return "";
    }
}
