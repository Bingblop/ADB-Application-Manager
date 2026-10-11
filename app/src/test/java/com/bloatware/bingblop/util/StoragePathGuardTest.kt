package com.bloatware.bingblop.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoragePathGuardTest {

    @Test
    fun testBackupRefValidation() {
        val baseDir = File(System.getProperty("java.io.tmpdir"), "kt_test_backups")
        baseDir.mkdirs()
        val validBackup = File(baseDir, "app_1.0_20261010.adbbackup")

        // Null and invalid inputs
        assertFalse(StoragePathGuard.isSafeBackupRef(null, baseDir))
        assertFalse(StoragePathGuard.isSafeBackupRef("", baseDir))
        assertFalse(StoragePathGuard.isSafeBackupRef("   ", baseDir))
        assertFalse(StoragePathGuard.isSafeBackupRef("test\u0000bad", baseDir))

        // Non-adbbackup extensions
        assertFalse(StoragePathGuard.isSafeBackupRef(File(baseDir, "test.apk").absolutePath, baseDir))
        assertFalse(StoragePathGuard.isSafeBackupRef(File(baseDir, "test.sh").absolutePath, baseDir))

        // Traversal attempts
        val outside = File(baseDir.parentFile, "escaped.adbbackup")
        assertFalse(StoragePathGuard.isSafeBackupRef(outside.absolutePath, baseDir))
        assertFalse(StoragePathGuard.isSafeBackupRef(baseDir.absolutePath + "/../escaped.adbbackup", baseDir))

        // Valid backup inside base
        assertTrue(StoragePathGuard.isSafeBackupRef(validBackup.absolutePath, baseDir))

        // Content URIs
        assertFalse(StoragePathGuard.isSafeBackupRef("content://", baseDir))
        assertFalse(StoragePathGuard.isSafeBackupRef("content:///local", baseDir))
        assertTrue(StoragePathGuard.isSafeBackupRef("content://media/external/downloads/42", baseDir))

        // Indexed backup matching
        val indexedList = listOf(outside.absolutePath)
        assertTrue(StoragePathGuard.isSafeBackupRef(outside.absolutePath, baseDir, indexedList))
        assertFalse(StoragePathGuard.isSafeBackupRef(outside.absolutePath + ".bad", baseDir, indexedList))
    }

    @Test
    fun testShareFileValidation() {
        val allowedBase = File(System.getProperty("java.io.tmpdir"), "kt_test_share")
        allowedBase.mkdirs()
        val safeFile = File(allowedBase, "export.txt")

        // Null and invalid
        assertFalse(StoragePathGuard.isSafeShareFile(null, listOf(allowedBase)))
        assertFalse(StoragePathGuard.isSafeShareFile("", listOf(allowedBase)))
        assertFalse(StoragePathGuard.isSafeShareFile("bad\u0000path", listOf(allowedBase)))

        // Blocked private folders and extensions
        assertFalse(StoragePathGuard.isSafeShareFile("/data/data/com.bloatware/shared_prefs/prefs.xml", listOf(allowedBase)))
        assertFalse(StoragePathGuard.isSafeShareFile("/data/user/0/com.bloatware/databases/app.db", listOf(allowedBase)))
        assertFalse(StoragePathGuard.isSafeShareFile(File(allowedBase, "secret.xml").absolutePath, listOf(allowedBase)))
        assertFalse(StoragePathGuard.isSafeShareFile(File(allowedBase, "secret.db").absolutePath, listOf(allowedBase)))

        // Outside directory
        val outside = File(System.getProperty("java.io.tmpdir"), "leak.txt")
        assertFalse(StoragePathGuard.isSafeShareFile(outside.absolutePath, listOf(allowedBase)))

        // Safe file
        assertTrue(StoragePathGuard.isSafeShareFile(safeFile.absolutePath, listOf(allowedBase)))

        // Content URI
        assertTrue(StoragePathGuard.isSafeShareFile("content://downloads/doc_1", listOf(allowedBase)))
    }
}
