package com.bloatware.bingblop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellOutcomeIntegrationTest {

    @Test
    fun testShellOutcomeRejectsFalsePositiveErrors() {
        // C-002: exit code 0 but stderr has error indicator
        val outcome1 = ShellSafety.parseShellOutcome(0, "", "Error: device unauthorized")
        assertTrue(outcome1 is ShellOutcome.Failure)

        val outcome2 = ShellSafety.parseShellOutcome(0, "adb: error: closed", "")
        assertTrue(outcome2 is ShellOutcome.Failure)

        val outcome3 = ShellSafety.parseShellOutcome(0, "", "[Process timed out after 20s]")
        assertTrue(outcome3 is ShellOutcome.Failure)
        assertTrue((outcome3 as ShellOutcome.Failure).isTimeout)

        val outcome4 = ShellSafety.parseShellOutcome(0, "java.lang.SecurityException: Permission Denial", "")
        assertTrue(outcome4 is ShellOutcome.Failure)
        assertTrue((outcome4 as ShellOutcome.Failure).isPermissionDenied)
    }

    @Test
    fun testShellOutcomeAcceptsValidOutput() {
        val outcome = ShellSafety.parseShellOutcome(0, "package:com.android.settings", "")
        assertTrue(outcome is ShellOutcome.Success)
        assertEquals("package:com.android.settings", (outcome as ShellOutcome.Success).output)
        assertEquals(0, outcome.exitCode)
    }

    @Test
    fun testShellOutcomeFailsOnNonZeroExitCode() {
        val outcome = ShellSafety.parseShellOutcome(127, "", "sh: cmd not found")
        assertTrue(outcome is ShellOutcome.Failure)
        assertEquals(127, (outcome as ShellOutcome.Failure).exitCode)
    }
}
