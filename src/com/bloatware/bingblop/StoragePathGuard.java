package com.bloatware.bingblop;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

public final class StoragePathGuard {
    private StoragePathGuard() {}

    public static boolean isSafeBackupRef(String ref, File backupDir, Collection<String> known) {
        if (ref == null) return false;
        ref = ref.trim();
        if (ref.isEmpty() || ref.contains("\0")) return false;

        if (ref.startsWith("content://")) {
            String rest = ref.substring("content://".length());
            if (rest.isEmpty() || rest.startsWith("/")) return false;
            if (known != null && !known.isEmpty()) {
                return known.contains(ref);
            }
            return true;
        }

        if (!ref.toLowerCase().endsWith(".adbbackup")) return false;

        File file = new File(ref);
        if (!file.isAbsolute()) return false;

        try {
            String canonical = file.getCanonicalPath();
            if (backupDir != null) {
                String canonicalBackupDir = backupDir.getCanonicalPath();
                if (isContainedWithin(canonical, canonicalBackupDir)) return true;
            }

            if (known != null) {
                for (String k : known) {
                    if (k != null && !k.startsWith("content://")) {
                        try {
                            String canK = new File(k.trim()).getCanonicalPath();
                            if (canonical.equals(canK)) return true;
                        } catch (IOException ignored) {}
                    }
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    public static boolean isSafeShareFile(String fileRef, Collection<File> allowedDirs) {
        if (fileRef == null) return false;
        fileRef = fileRef.trim();
        if (fileRef.isEmpty() || fileRef.contains("\0")) return false;

        if (fileRef.startsWith("content://")) return true;

        File file = new File(fileRef);
        if (!file.isAbsolute()) return false;

        try {
            String canonical = file.getCanonicalPath();
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

            if (allowedDirs != null && !allowedDirs.isEmpty()) {
                for (File dir : allowedDirs) {
                    if (dir != null) {
                        String canonicalDir = dir.getCanonicalPath();
                        if (isContainedWithin(canonical, canonicalDir)) return true;
                    }
                }
                return false;
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isContainedWithin(String path, String parent) {
        if (path.equals(parent)) return true;
        String prefix = parent.endsWith(File.separator) ? parent : parent + File.separator;
        return path.startsWith(prefix);
    }
}
