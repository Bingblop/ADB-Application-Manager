package com.bloatware.bingblop;

import java.util.regex.Pattern;

/**
 * Reads what {@code pm install} / {@code adb install} printed. Plain string handling with no Android classes, so it can be
 * tested off the device. Android has the last word on signatures; this only tells the app which kind of "no" it got, so it can
 * fix what is fixable (sign a broken APK) and explain the rest.
 */
final class InstallHints {

    private InstallHints() {}

    /** The package manager accepted the install. */
    static boolean success(String out) {
        return out != null && out.contains("Success");
    }

    /**
     * Android could not verify the APK's own signature: edited after it was signed, no signature at all, or splits signed
     * with different keys. Signing the file (every split) with one key fixes this.
     */
    private static final Pattern BROKEN_SIGNATURE = Pattern.compile("(?i)"
            + "INSTALL_PARSE_FAILED_NO_CERTIFICATES|INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES|INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING"
            + "|Failed to collect certificates|digest of contents did not verify|has invalid digest|has no certificates"
            + "|No signature found in package|no certificates at entry|SHA-?\\d* digest.*did not verify");

    static boolean brokenSignature(String out) {
        return out != null && BROKEN_SIGNATURE.matcher(out).find();
    }

    /**
     * The app is already installed with a different signing key, so Android refuses the update. Only uninstalling the
     * installed copy first (which deletes its data) gets past it. A shared-user conflict is not this: removing one app
     * would not change the other apps' signature.
     */
    private static final Pattern UPDATE_INCOMPATIBLE = Pattern.compile("(?i)INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match");

    static boolean updateIncompatible(String out) {
        return out != null && UPDATE_INCOMPATIBLE.matcher(out).find();
    }

    /** One short line of advice for a failed install (empty when there is nothing useful to add). */
    static String advice(String out) {
        if (out == null || success(out)) return "";
        String o = out.toUpperCase(java.util.Locale.US);
        if (updateIncompatible(out)) {
            return "The installed copy is signed with a different key, so Android refuses to update it. Uninstalling it first (its data is deleted) is the only way past that.";
        }
        if (brokenSignature(out)) {
            return "Android could not verify this APK's signature (it was edited after signing, or it is unsigned). Signing it with this app's key fixes that.";
        }
        if (o.contains("INSTALL_FAILED_VERSION_DOWNGRADE") || o.contains("DOWNGRADE DETECTED")) {
            return "This is an older version than the installed one. Turn on \"Allow downgrade\" under Install options.";
        }
        if (o.contains("INSTALL_FAILED_TEST_ONLY")) {
            return "This is a test-only package. Turn on \"Allow test packages\" under Install options.";
        }
        if (o.contains("INSTALL_FAILED_DEPRECATED_SDK_VERSION") || o.contains("MUST TARGET AT LEAST SDK")) {
            return "This app targets a very old Android version. Turn on \"Bypass low target SDK block\" under Install options.";
        }
        if (o.contains("INSTALL_FAILED_OLDER_SDK")) {
            return "This app needs a newer Android version than this phone runs.";
        }
        if (o.contains("INSTALL_FAILED_NO_MATCHING_ABIS")) {
            return "This app has no code for this phone's processor type.";
        }
        if (o.contains("INSTALL_FAILED_INSUFFICIENT_STORAGE")) {
            return "There is not enough free storage to install this.";
        }
        if (o.contains("INSTALL_FAILED_VERIFICATION_FAILURE") || o.contains("INSTALL_FAILED_VERIFICATION_TIMEOUT")) {
            return "A package verifier (Play Protect) refused or timed out. On the phone, turn off Play Protect's app scanning, or \"Verify apps over USB\" in Developer options, and try again.";
        }
        return "";
    }
}
