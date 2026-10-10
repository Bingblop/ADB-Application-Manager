package com.bloatware.bingblop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkScanUtilsTest {

    @Test
    fun testParseFindOutputValid() {
        val raw = """
            /sdcard/Download/app-release.apk
            /sdcard/Download/test.xapk
            /data/local/tmp/tool.apks
            /storage/emulated/0/apps/bundle.apkm
        """.trimIndent()

        val parsed = ApkScanUtils.parseFindOutput(raw)
        assertEquals(4, parsed.size)
        assertTrue(parsed.contains("/sdcard/Download/app-release.apk"))
        assertTrue(parsed.contains("/sdcard/Download/test.xapk"))
    }

    @Test
    fun testParseFindOutputFiltersInvalidAndTrash() {
        val raw = """
            
            not/an/absolute/path.apk
            /sdcard/Download/.trashed/deleted.apk
            /sdcard/Download/.trash/temp.apk
            /sdcard/DCIM/photo.jpg
            /sdcard/Download/valid.apk
        """.trimIndent()

        val parsed = ApkScanUtils.parseFindOutput(raw)
        assertEquals(1, parsed.size)
        assertEquals("/sdcard/Download/valid.apk", parsed.first())
    }

    @Test
    fun testParseFindOutputRespectsMaxLimit() {
        val raw = (1..50).joinToString("\n") { "/sdcard/Download/app$it.apk" }
        val parsed = ApkScanUtils.parseFindOutput(raw, maxEntries = 10)
        assertEquals(10, parsed.size)
    }

    @Test
    fun testParseStatOutput() {
        val raw = """
            1048576|1700000000|/sdcard/Download/test.apk
            2097152|1700000050|/sdcard/Download/test2.xapk
            malformed line without pipes
            bad|number|/sdcard/Download/bad.apk
        """.trimIndent()

        val parsed = ApkScanUtils.parseStatOutput(raw)
        assertEquals(2, parsed.size)
        assertEquals(Pair(1048576L, 1700000000L), parsed["/sdcard/Download/test.apk"])
        assertEquals(Pair(2097152L, 1700000050L), parsed["/sdcard/Download/test2.xapk"])
    }

    @Test
    fun testShellSentinelRandomnessAndPrefix() {
        val token1 = ShellSentinel.createToken()
        val token2 = ShellSentinel.createToken()

        assertTrue(token1.startsWith("__SDM_END_"))
        assertTrue(token1.endsWith("__"))
        assertNotEquals(token1, token2)
        assertEquals(36, token1.length) // "__SDM_END_" (10) + 24 hex chars + "__" (2) = 36

        assertTrue(ShellSentinel.isSentinel(token1, token1))
        assertFalse(ShellSentinel.isSentinel("__SDM_END__", token1))
        assertFalse(ShellSentinel.isSentinel(null, token1))
    }
}
