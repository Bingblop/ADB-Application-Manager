package com.bloatware.bingblop.util

import java.io.File
import java.io.IOException

/**
 * Validates file paths and references for backup operations, file deletion, and stored-file sharing
 * to prevent directory traversal and unauthorized file exposure.
 */
object StoragePathGuard {

    fun isSafeBackupRef(
        ref: String?,
        allowedDirectory: File?,
        knownIndexedRefs: Collection<String>? = null
    ): Boolean {
        if (ref == null) return false
        val trimmed = ref.trim()
        if (trimmed.isEmpty() || trimmed.contains("\u0000")) return false

        // Content URIs
        if (trimmed.startsWith("content://")) {
            val rest = trimmed.substring("content://".length)
            if (rest.isEmpty() || rest.startsWith("/")) return false
            if (!knownIndexedRefs.isNullOrEmpty()) {
                return knownIndexedRefs.contains(trimmed)
            }
            return true
        }

        // Must end with .adbbackup
        if (!trimmed.endsWith(".adbbackup", ignoreCase = true)) {
            return false
        }

        val targetFile = File(trimmed)
        if (!targetFile.isAbsolute) {
            return false
        }

        return try {
            val canonicalTarget = targetFile.canonicalPath

            if (allowedDirectory != null) {
                val canonicalBase = allowedDirectory.canonicalPath
                if (isContainedWithin(canonicalTarget, canonicalBase)) {
                    return true
                }
            }

            if (!knownIndexedRefs.isNullOrEmpty()) {
                knownIndexedRefs.any { indexed ->
                    if (!indexed.startsWith("content://")) {
                        try {
                            canonicalTarget == File(indexed.trim()).canonicalPath
                        } catch (_: IOException) {
                            false
                        }
                    } else false
                }
            } else false
        } catch (_: IOException) {
            false
        }
    }

    fun isSafeShareFile(
        path: String?,
        allowedDirectories: Collection<File>? = null
    ): Boolean {
        if (path == null) return false
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed.contains("\u0000")) return false

        if (trimmed.startsWith("content://")) {
            return true
        }

        val file = File(trimmed)
        if (!file.isAbsolute) {
            return false
        }

        return try {
            val canonical = file.canonicalPath
            val lower = canonical.lowercase()

            // Block app-private databases, keystore, and shared_prefs
            if (lower.contains("/shared_prefs/") ||
                lower.contains("/databases/") ||
                lower.contains("/app_keystore/") ||
                lower.contains("/key/") ||
                lower.endsWith(".xml") ||
                lower.endsWith(".db") ||
                lower.endsWith(".db-journal") ||
                lower.endsWith(".db-wal")
            ) {
                return false
            }

            if (!allowedDirectories.isNullOrEmpty()) {
                allowedDirectories.any { allowed ->
                    try {
                        isContainedWithin(canonical, allowed.canonicalPath)
                    } catch (_: IOException) {
                        false
                    }
                }
            } else {
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun isContainedWithin(childPath: String, parentPath: String): Boolean {
        if (childPath == parentPath) return true
        val prefix = if (parentPath.endsWith(File.separator)) parentPath else parentPath + File.separator
        return childPath.startsWith(prefix)
    }
}
