package com.bloatware.bingblop;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure checks run on a downloaded APK before it is handed to the installer. They fail closed: a checksum
 * the repository published but that is not a SHA-256, or an APK whose signing certificate cannot be read,
 * stops the install instead of skipping the check. (Android still refuses a mismatching update; these
 * checks make the refusal early and readable, and cover paths where the installer would not look.)
 */
final class InstallGuards {
    private InstallGuards() {}

    /** True for exactly 64 hex digits. */
    static boolean isSha256(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    /** The checksum without blanks and without a leading "sha256:" label some indexes add. */
    static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.regionMatches(true, 0, "sha256:", 0, 7)) t = t.substring(7).trim();
        return t;
    }

    /** True when the repository published something (not null/blank), valid or not. */
    static boolean hasPublishedHash(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /**
     * Null when the download may continue; otherwise the reason. {@code published} is the repository's
     * checksum (null/blank = it publishes none, which is allowed), {@code actual} the hash of the file.
     */
    static String checkHash(String published, String actual) {
        if (!hasPublishedHash(published)) return null;
        String p = normalize(published);
        if (!isSha256(p)) return "the repository published a checksum that is not a SHA-256, so the download cannot be verified - not installed";
        if (actual == null || !actual.equalsIgnoreCase(p)) {
            String got = actual == null ? "nothing" : actual.substring(0, Math.min(12, actual.length())) + "…";
            return "the download doesn't match the checksum published by the repository (expected " + p.substring(0, 12) + "…, got " + got + ") - not installed";
        }
        return null;
    }

    /**
     * Null when an update may continue. Both callers have already found the package installed, so an installed app whose signers cannot
     * be read is a failure ("installed-unreadable"), and so is an APK whose signers cannot be read ("unreadable") or one that shares no
     * certificate with the installed app ("different").
     */
    static String checkSigners(Set<String> installed, Set<String> incoming) {
        if (installed == null || installed.isEmpty()) return "installed-unreadable";
        if (incoming == null || incoming.isEmpty()) return "unreadable";
        Set<String> common = new HashSet<String>(installed);
        common.retainAll(incoming);
        return common.isEmpty() ? "different" : null;
    }

    /** ADB (TCP or Wireless Debugging), Shizuku or Root: a backend that runs `pm` with shell or root rights. */
    static boolean isPrivilegedMode(String mode) {
        return "adb_tcp".equals(mode) || "adb_wireless".equals(mode) || "shizuku".equals(mode) || "root".equals(mode);
    }

    /**
     * The authorizer an install may use. {@code requested} is what the page asked for ("adb", "shizuku", "root", "none", or anything else, which means
     * the working mode); {@code execMode} is the mode the app itself is in right now (adb_tcp, adb_wireless, shizuku, root or standard). Standard mode
     * is the Read-Only mode: there a privileged authorizer is never honoured, whatever the page asks for or whatever happens to be connected, and
     * the install goes through the system installer ("none"), which asks the person itself. A mode that is not one of the four privileged ones is
     * treated the same way (fail closed). In a privileged mode the request is passed on unchanged.
     */
    static String authorizerFor(String requested, String execMode) {
        if ("none".equals(requested)) return "none";
        if (!isPrivilegedMode(execMode)) return "none";
        return requested == null ? "" : requested;
    }
}
