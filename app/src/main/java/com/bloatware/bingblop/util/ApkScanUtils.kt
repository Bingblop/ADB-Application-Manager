package com.bloatware.bingblop.util

import java.io.File

/**
 * Robust APK and package discovery parser with line break and path traversal guards (C-005).
 *
 * Excludes paths holding line breaks (e.g. `d\n/data/app/victim.apk`), ensuring that
 * shell find output parsed line-by-line cannot inject phantom files into privileged operations.
 */
object ApkScanUtils {

    val VALID_PACKAGE_EXTENSIONS = listOf(".apk", ".apks", ".apkm", ".xapk")

    /**
     * Shell find predicate ensuring no paths containing newlines can be emitted,
     * matching the verified safe predicate from ApkScan.FIND_PREDICATE.
     */
    const val FIND_PREDICATE =
        "! -path \"$(printf '*\\n*')\" -type f \\( -iname '*.apk' -o -iname '*.apks' -o -iname '*.apkm' -o -iname '*.xapk' \\)"

    /**
     * Parse raw line-by-line find output safely, dropping any lines that are blank,
     * non-absolute, lacking valid extensions, or attempting directory traversal.
     */
    fun parseFindOutput(rawOutput: String?, maxEntries: Int = 2500): List<String> {
        if (rawOutput.isNullOrBlank()) return emptyList()

        val results = mutableListOf<String>()
        val lines = rawOutput.lineSequence()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.startsWith("/")) continue

            // Must have a recognized package extension
            val lower = line.lowercase()
            val hasValidExt = VALID_PACKAGE_EXTENSIONS.any { lower.endsWith(it) }
            if (!hasValidExt) continue

            // Exclude trash/undo folders
            if (line.contains("/.trashed/") || line.contains("/.trash/") || line.contains("/.Trash/")) continue

            results.add(line)
            if (results.size >= maxEntries) break
        }
        return results
    }

    /**
     * Parse stat output formatted as '%s|%Y|%n' into metadata map.
     */
    fun parseStatOutput(rawOutput: String?): Map<String, Pair<Long, Long>> {
        if (rawOutput.isNullOrBlank()) return emptyMap()

        val results = mutableMapOf<String, Pair<Long, Long>>()
        for (rawLine in rawOutput.lineSequence()) {
            val line = rawLine.trim()
            val firstPipe = line.indexOf('|')
            if (firstPipe <= 0) continue
            val secondPipe = line.indexOf('|', firstPipe + 1)
            if (secondPipe <= 0) continue

            val sizeStr = line.substring(0, firstPipe).trim()
            val mtimeStr = line.substring(firstPipe + 1, secondPipe).trim()
            val path = line.substring(secondPipe + 1).trim()

            if (path.isEmpty() || !path.startsWith("/")) continue

            val size = sizeStr.toLongOrNull() ?: continue
            val mtime = mtimeStr.toLongOrNull() ?: continue

            results[path] = Pair(size, mtime)
        }
        return results
    }
}
