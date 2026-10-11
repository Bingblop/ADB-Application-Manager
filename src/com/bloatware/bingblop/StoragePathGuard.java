package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

/**
 * Validates file paths and references for backup deletion and stored-file sharing
 * to prevent directory traversal and unauthorized file exposure.
 *
 * Rules:
 * - Fail closed on null, empty, or NUL-containing paths.
 * - Require canonical path resolution when comparing against allowed directory boundaries.
 * - Backups must strictly carry the .adbbackup extension and be contained within
 *   the app's designated backup storage directory or recorded backup index.
 * - Stored file sharing must never allow arbitrary traversal into app-private
 *   databases, shared_prefs, or system files.
 */
public final class StoragePathGuard {

    private StoragePathGuard() {}

    /**
     * Checks if a reference is safe for backup deletion.
     *
     * @param ref The path or content:// URI passed from caller/bridge.
     * @param allowedDirectory The app's designated backup directory (may be null if unknown).
     * @param knownIndexedRefs Known backup refs from the backup index (may be null/empty).
     * @return true if the reference is safe to delete.
     */
    public static boolean isSafeBackupRef(String ref, File allowedDirectory, Collection<String> knownIndexedRefs) {
        if (ref == null) return false;
        String trimmed = ref.trim();
        if (trimmed.isEmpty() || trimmed.contains("\0")) return false;

        // Content URIs
        if (trimmed.startsWith("content://")) {
            // Must have a valid authority after content://
            String rest = trimmed.substring("content://".length());
            if (rest.isEmpty() || rest.startsWith("/")) return false;
            // If we have an index of known backups, confirm it was indexed
            if (knownIndexedRefs != null && !knownIndexedRefs.isEmpty()) {
                return knownIndexedRefs.contains(trimmed);
            }
            return true;
        }

        // Must end with .adbbackup
        if (!trimmed.toLowerCase().endsWith(".adbbackup")) {
            return false;
        }

        // Must not be relative or contain unnormalized relative components that escape
        File targetFile = new File(trimmed);
        if (!targetFile.isAbsolute()) {
            return false;
        }

        try {
            String canonicalTarget = targetFile.getCanonicalPath();

            // Check against allowed base directory if provided
            if (allowedDirectory != null) {
                String canonicalBase = allowedDirectory.getCanonicalPath();
                if (isContainedWithin(canonicalTarget, canonicalBase)) {
                    return true;
                }
            }

            // Check against known indexed file paths
            if (knownIndexedRefs != null) {
                for (String indexed : knownIndexedRefs) {
                    if (indexed != null && !indexed.startsWith("content://")) {
                        try {
                            String canonicalIndexed = new File(indexed.trim()).getCanonicalPath();
                            if (canonicalTarget.equals(canonicalIndexed)) {
                                return true;
                            }
                        } catch (IOException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }

        return false;
    }

    /**
     * Checks if a file path is safe for sharing via shareStoredFile.
     * Prevents exfiltration of app-private data (databases, shared_prefs, keystore files).
     *
     * @param path The file path to verify.
     * @param allowedDirectories Allowed parent directories (e.g. external files, download, cache).
     * @return true if the file path is safe to share.
     */
    public static boolean isSafeShareFile(String path, Collection<File> allowedDirectories) {
        if (path == null) return false;
        String trimmed = path.trim();
        if (trimmed.isEmpty() || trimmed.contains("\0")) return false;

        if (trimmed.startsWith("content://")) {
            return true;
        }

        File file = new File(trimmed);
        if (!file.isAbsolute()) {
            return false;
        }

        try {
            String canonical = file.getCanonicalPath();

            // Block explicit private locations
            String lower = canonical.toLowerCase();
            if (lower.contains("/shared_prefs/") ||
                lower.contains("/databases/") ||
                lower.contains("/app_keystore/") ||
                lower.contains("/key/") ||
                lower.endsWith(".xml") ||
                lower.endsWith(".db") ||
                lower.endsWith(".db-journal") ||
                lower.endsWith(".db-wal")) {
                return false;
            }

            if (allowedDirectories != null && !allowedDirectories.isEmpty()) {
                for (File allowed : allowedDirectories) {
                    if (allowed != null) {
                        String canonicalBase = allowed.getCanonicalPath();
                        if (isContainedWithin(canonical, canonicalBase)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isContainedWithin(String childPath, String parentPath) {
        if (childPath.equals(parentPath)) return true;
        String prefix = parentPath.endsWith(File.separator) ? parentPath : parentPath + File.separator;
        return childPath.startsWith(prefix);
    }
}
