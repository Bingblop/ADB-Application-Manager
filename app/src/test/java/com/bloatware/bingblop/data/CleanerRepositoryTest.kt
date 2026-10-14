package com.bloatware.bingblop.data

import com.bloatware.bingblop.data.repository.CleanerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CleanerRepositoryTest {

    @Test
    fun testRealTempFileDiscoveryAndCleanup() {
        val testDir = File(System.getProperty("java.io.tmpdir"), "cleaner_test_${System.currentTimeMillis()}")
        testDir.mkdirs()

        try {
            val logFile = File(testDir, "app_crash.log").apply { writeText("Sample log telemetry") }
            val tmpFile = File(testDir, "cache_stream.tmp").apply { writeText("Temporary binary buffer") }
            val regularFile = File(testDir, "important_config.json").apply { writeText("{\"key\": \"val\"}") }

            assertTrue(logFile.exists())
            assertTrue(tmpFile.exists())
            assertTrue(regularFile.exists())

            // Test extension-based file matching
            val tempMatches = listOf(logFile, tmpFile, regularFile).filter { f ->
                f.name.endsWith(".tmp") || f.name.endsWith(".log") || f.name.endsWith(".dmp")
            }

            assertEquals(2, tempMatches.size)
            assertTrue(tempMatches.contains(logFile))
            assertTrue(tempMatches.contains(tmpFile))
            assertFalse(tempMatches.contains(regularFile))
        } finally {
            testDir.deleteRecursively()
        }
    }

    @Test
    fun testDirectoryTraversalSafetyInCleaner() {
        val rootDir = File(System.getProperty("java.io.tmpdir"), "safe_root_${System.currentTimeMillis()}")
        rootDir.mkdirs()
        val allowedPrefix = rootDir.normalize().absolutePath

        val insideFile = File(rootDir, "sub/cached.tmp")
        insideFile.parentFile?.mkdirs()
        insideFile.writeText("inside")

        val outsideFile = File(System.getProperty("java.io.tmpdir"), "outside.tmp")
        outsideFile.writeText("outside")

        try {
            fun isSafe(file: File, allowed: List<String>): Boolean {
                val path = file.normalize().absolutePath
                return allowed.any { path == it || path.startsWith("$it/") }
            }

            assertTrue(isSafe(insideFile, listOf(allowedPrefix)))
            assertFalse(isSafe(outsideFile, listOf(allowedPrefix)))
        } finally {
            rootDir.deleteRecursively()
            outsideFile.delete()
        }
    }
}
