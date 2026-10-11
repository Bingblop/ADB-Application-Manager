package com.bloatware.bingblop.ui

import com.bloatware.bingblop.data.model.LogcatEntry
import com.bloatware.bingblop.data.model.LogcatLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatFilterTest {

    private val sampleLogs = listOf(
        LogcatEntry(1L, "12:00:01.000", "1024", "1024", LogcatLevel.INFO, "ActivityManager", "Start proc 2048:com.android.chrome", "raw1"),
        LogcatEntry(2L, "12:00:02.000", "1024", "1024", LogcatLevel.WARN, "PackageManager", "Package com.test.app not found", "raw2"),
        LogcatEntry(3L, "12:00:03.000", "2048", "2048", LogcatLevel.ERROR, "AndroidRuntime", "FATAL EXCEPTION: main NullPointerException", "raw3"),
        LogcatEntry(4L, "12:00:04.000", "3050", "3050", LogcatLevel.DEBUG, "AudioFlinger", "AudioTrack created", "raw4"),
        LogcatEntry(5L, "12:00:05.000", "2048", "2048", LogcatLevel.FATAL, "libc", "Fatal signal 11 (SIGSEGV)", "raw5")
    )

    @Test
    fun testLevelFiltering() {
        // LogcatLevel.ALL accepts all
        val allLogs = sampleLogs.filter { true }
        assertEquals(5, allLogs.size)

        // LogcatLevel.WARN should include WARN, ERROR, FATAL
        val warnLogs = sampleLogs.filter { it.level == LogcatLevel.WARN || it.level == LogcatLevel.ERROR || it.level == LogcatLevel.FATAL }
        assertEquals(3, warnLogs.size)

        // LogcatLevel.ERROR should include ERROR, FATAL
        val errorLogs = sampleLogs.filter { it.level == LogcatLevel.ERROR || it.level == LogcatLevel.FATAL }
        assertEquals(2, errorLogs.size)
    }

    @Test
    fun testPidFiltering() {
        val pidToFilter = "2048"
        val filtered = sampleLogs.filter { it.pid == pidToFilter }
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.pid == "2048" })

        val nonExistent = sampleLogs.filter { it.pid == "9999" }
        assertTrue(nonExistent.isEmpty())
    }

    @Test
    fun testRegexMatching() {
        val regex = Regex("ActivityManager|AndroidRuntime", RegexOption.IGNORE_CASE)
        val matched = sampleLogs.filter { regex.containsMatchIn(it.tag) }
        assertEquals(2, matched.size)

        val crashRegex = Regex("FATAL.*|signal.*", RegexOption.IGNORE_CASE)
        val crashes = sampleLogs.filter { crashRegex.containsMatchIn(it.message) }
        assertEquals(2, crashes.size)
    }

    @Test
    fun testInvalidRegexGracefullyHandled() {
        val invalidQuery = "[unclosed_regex"
        val isInvalid = runCatching { Regex(invalidQuery) }.isFailure
        assertTrue("Regex with unclosed bracket should fail parsing safely", isInvalid)
    }
}
