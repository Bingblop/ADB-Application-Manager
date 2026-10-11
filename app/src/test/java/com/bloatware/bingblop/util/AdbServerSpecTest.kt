package com.bloatware.bingblop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbServerSpecTest {

    @Test
    fun testSocketPathConstruction() {
        val files = "/data/user/0/com.bloatware.bingblop/files"
        val path = AdbServerSpec.socketPath(files)
        assertEquals("$files/adb_sock/s", path)
    }

    @Test
    fun testPathFitsConstraints() {
        val valid = "/data/user/0/com.bloatware.bingblop/files/adb_sock/s"
        assertTrue(AdbServerSpec.pathFits(valid))

        assertFalse(AdbServerSpec.pathFits(null))
        assertFalse(AdbServerSpec.pathFits(""))
        assertFalse(AdbServerSpec.pathFits("/some/path\u0000with/null"))

        // Length limit tests (MAX_SOCKET_PATH = 100)
        val exactly100 = "a".repeat(100)
        assertTrue(AdbServerSpec.pathFits(exactly100))

        val exceeds100 = "a".repeat(101)
        assertFalse(AdbServerSpec.pathFits(exceeds100))
    }

    @Test
    fun testArgsSpecGeneration() {
        val files = "/data/user/0/com.bloatware.bingblop/files"
        val validPath = AdbServerSpec.socketPath(files)
        val privateArgs = AdbServerSpec.args(validPath)

        assertEquals(listOf("-L", "localfilesystem:$validPath"), privateArgs)
        assertTrue(AdbServerSpec.isPrivate(privateArgs))

        // Fallbacks to legacy TCP port
        val fallbackNull = AdbServerSpec.args(null)
        assertEquals(listOf("-P", "5042"), fallbackNull)
        assertFalse(AdbServerSpec.isPrivate(fallbackNull))

        val fallbackEmpty = AdbServerSpec.args("")
        assertEquals(listOf("-P", "5042"), fallbackEmpty)
        assertFalse(AdbServerSpec.isPrivate(fallbackEmpty))

        val longPath = "x".repeat(110)
        val fallbackLong = AdbServerSpec.args(longPath)
        assertEquals(listOf("-P", "5042"), fallbackLong)
        assertFalse(AdbServerSpec.isPrivate(fallbackLong))
    }

    @Test
    fun testIsPrivateRejectsInvalid() {
        assertFalse(AdbServerSpec.isPrivate(null))
        assertFalse(AdbServerSpec.isPrivate(emptyList()))
        assertFalse(AdbServerSpec.isPrivate(listOf("-P", "5042")))
        assertFalse(AdbServerSpec.isPrivate(listOf("-L")))
        assertFalse(AdbServerSpec.isPrivate(listOf("-L", "tcp:5042")))
    }
}
