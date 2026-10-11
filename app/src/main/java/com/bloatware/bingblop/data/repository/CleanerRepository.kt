package com.bloatware.bingblop.data.repository

import android.content.Context
import com.bloatware.bingblop.data.model.StorageCleanerItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CleanerRepository(private val context: Context) {

    suspend fun scanStorage(): List<StorageCleanerItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<StorageCleanerItem>()

        // 1. App Cache
        val appCacheDir = context.cacheDir
        val cacheSize = getFolderSize(appCacheDir)
        if (cacheSize > 0) {
            items.add(
                StorageCleanerItem(
                    id = "cache_internal",
                    title = "App Cache & Web Cache",
                    description = "Temporary cached network responses, assets, and WebView buffers",
                    path = appCacheDir.absolutePath,
                    sizeBytes = cacheSize,
                    category = "System & App Cache"
                )
            )
        }

        // 2. Code Cache
        val codeCacheDir = context.codeCacheDir
        val codeCacheSize = getFolderSize(codeCacheDir)
        if (codeCacheSize > 0) {
            items.add(
                StorageCleanerItem(
                    id = "cache_code",
                    title = "JIT Compilation Cache",
                    description = "Pre-compiled bytecode cache files that can be regenerated on-demand",
                    path = codeCacheDir.absolutePath,
                    sizeBytes = codeCacheSize,
                    category = "System & App Cache"
                )
            )
        }

        // 3. External Cache
        val extCache = context.externalCacheDir
        if (extCache != null && extCache.exists()) {
            val extSize = getFolderSize(extCache)
            if (extSize > 0) {
                items.add(
                    StorageCleanerItem(
                        id = "cache_external",
                        title = "External Storage Cache",
                        description = "Shared media buffers and downloaded temporary streams",
                        path = extCache.absolutePath,
                        sizeBytes = extSize,
                        category = "System & App Cache"
                    )
                )
            }
        }

        // 4. Real Temporary Files & Logs (.tmp, .dmp, .log)
        val tempFiles = findTempAndLogFiles(context.cacheDir) + findTempAndLogFiles(context.filesDir)
        val tempBytes = tempFiles.sumOf { it.length() }
        if (tempBytes > 0) {
            items.add(
                StorageCleanerItem(
                    id = "clutter_logs",
                    title = "Temporary Logs & Crash Dumps",
                    description = "Temporary log files and execution dumps (${tempFiles.size} files found)",
                    path = "${context.cacheDir.absolutePath}/**/*.tmp",
                    sizeBytes = tempBytes,
                    category = "Temporary Files & Leftovers"
                )
            )
        }

        // 5. App Thumbnail & Media Cache
        val thumbnailDir = File(context.cacheDir, "thumbnails")
        val thumbSize = getFolderSize(thumbnailDir)
        if (thumbSize > 0) {
            items.add(
                StorageCleanerItem(
                    id = "clutter_thumbnails",
                    title = "Cached Image & Video Thumbnails",
                    description = "Decoded media and icon thumbnail disk caches",
                    path = thumbnailDir.absolutePath,
                    sizeBytes = thumbSize,
                    category = "Temporary Files & Leftovers"
                )
            )
        }

        // 6. Real Empty Directories within cache
        val emptyDirs = findEmptyDirectories(context.cacheDir)
        if (emptyDirs.isNotEmpty()) {
            items.add(
                StorageCleanerItem(
                    id = "clutter_empty_dirs",
                    title = "Orphaned Empty Directories",
                    description = "${emptyDirs.size} empty zero-byte directory structures taking up filesystem inodes",
                    path = "${context.cacheDir.absolutePath}/[empty folders]",
                    sizeBytes = emptyDirs.size * 4096L, // standard 4KB inode block size
                    category = "Filesystem Clutter"
                )
            )
        }

        items
    }

    suspend fun cleanItems(itemIds: Set<String>): CleanResult = withContext(Dispatchers.IO) {
        var reclaimedBytes = 0L
        var cleanedCount = 0

        if (itemIds.contains("cache_internal")) {
            val size = getFolderSize(context.cacheDir)
            if (clearDirectory(context.cacheDir)) {
                reclaimedBytes += size
                cleanedCount++
            }
        }

        if (itemIds.contains("cache_code")) {
            val size = getFolderSize(context.codeCacheDir)
            if (clearDirectory(context.codeCacheDir)) {
                reclaimedBytes += size
                cleanedCount++
            }
        }

        context.externalCacheDir?.let { ext ->
            if (itemIds.contains("cache_external") && ext.exists()) {
                val size = getFolderSize(ext)
                if (clearDirectory(ext)) {
                    reclaimedBytes += size
                    cleanedCount++
                }
            }
        }

        if (itemIds.contains("clutter_logs")) {
            val tempFiles = findTempAndLogFiles(context.cacheDir) + findTempAndLogFiles(context.filesDir)
            for (f in tempFiles) {
                if (isSafeToDelete(f)) {
                    val len = f.length()
                    if (f.delete()) {
                        reclaimedBytes += len
                    }
                }
            }
            cleanedCount++
        }

        if (itemIds.contains("clutter_thumbnails")) {
            val thumbDir = File(context.cacheDir, "thumbnails")
            if (thumbDir.exists() && isSafeToDelete(thumbDir)) {
                val size = getFolderSize(thumbDir)
                if (clearDirectory(thumbDir)) {
                    reclaimedBytes += size
                    cleanedCount++
                }
            }
        }

        if (itemIds.contains("clutter_empty_dirs")) {
            val emptyDirs = findEmptyDirectories(context.cacheDir)
            for (dir in emptyDirs) {
                if (isSafeToDelete(dir) && dir.delete()) {
                    reclaimedBytes += 4096L
                }
            }
            cleanedCount++
        }

        CleanResult(reclaimedBytes, cleanedCount)
    }

    private fun findTempAndLogFiles(dir: File?): List<File> {
        if (dir == null || !dir.exists() || !dir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        try {
            val files = dir.listFiles() ?: return emptyList()
            for (f in files) {
                if (f.isDirectory) {
                    result.addAll(findTempAndLogFiles(f))
                } else if (f.name.endsWith(".tmp") || f.name.endsWith(".log") || f.name.endsWith(".dmp") || f.name.startsWith("temp_")) {
                    result.add(f)
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun findEmptyDirectories(dir: File?): List<File> {
        if (dir == null || !dir.exists() || !dir.isDirectory) return emptyList()
        val result = mutableListOf<File>()
        try {
            val files = dir.listFiles() ?: return emptyList()
            if (files.isEmpty()) {
                result.add(dir)
            } else {
                for (f in files) {
                    if (f.isDirectory) {
                        result.addAll(findEmptyDirectories(f))
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun getFolderSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        try {
            val children = dir.listFiles() ?: return 0L
            for (child in children) {
                total += if (child.isDirectory) getFolderSize(child) else child.length()
            }
        } catch (_: Exception) {}
        return total
    }

    private fun isSafeToDelete(dir: File): Boolean {
        val path = dir.normalize().absolutePath
        val allowed = listOfNotNull(
            context.cacheDir.normalize().absolutePath,
            context.codeCacheDir.normalize().absolutePath,
            context.externalCacheDir?.normalize()?.absolutePath
        )
        return allowed.any { path == it || path.startsWith("$it/") }
    }

    private fun clearDirectory(dir: File?): Boolean {
        if (dir == null || !dir.exists() || !isSafeToDelete(dir)) return false
        var success = true
        try {
            val children = dir.listFiles() ?: return false
            for (child in children) {
                if (child.isDirectory) {
                    clearDirectory(child)
                    success = (child.delete() || !child.exists()) && success
                } else {
                    success = (child.delete() || !child.exists()) && success
                }
            }
        } catch (_: Exception) {
            success = false
        }
        return success
    }

    data class CleanResult(val bytesReclaimed: Long, val itemsCleaned: Int)
}
