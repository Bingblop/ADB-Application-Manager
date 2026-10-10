package com.bloatware.bingblop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellSafetyTest {

    @Test
    fun testQuoteArgWrapsAndEscapes() {
        assertEquals("'hello'", ShellSafety.quoteArg("hello"))
        assertEquals("'hello world'", ShellSafety.quoteArg("hello world"))
        assertEquals("'don'\\''t'", ShellSafety.quoteArg("don't"))
        assertEquals("'; rm -rf / ;'", ShellSafety.quoteArg("; rm -rf / ;"))
        assertEquals("'\$(whoami)'", ShellSafety.quoteArg("\$(whoami)"))
    }

    @Test
    fun testValidPackageNameAcceptsStandardPackages() {
        assertTrue(ShellSafety.isValidPackageName("com.android.settings"))
        assertTrue(ShellSafety.isValidPackageName("moe.shizuku.privileged.api"))
        assertTrue(ShellSafety.isValidPackageName("com.google.android.gms"))
        assertTrue(ShellSafety.isValidPackageName("org.fdroid.fdroid"))
        assertTrue(ShellSafety.isValidPackageName("a.b"))
    }

    @Test
    fun testValidPackageNameRejectsInjectionsAndInvalidNames() {
        assertFalse(ShellSafety.isValidPackageName(""))
        assertFalse(ShellSafety.isValidPackageName(null))
        assertFalse(ShellSafety.isValidPackageName("   "))
        assertFalse(ShellSafety.isValidPackageName("com.android.settings; reboot"))
        assertFalse(ShellSafety.isValidPackageName("com.android.`reboot`"))
        assertFalse(ShellSafety.isValidPackageName("com.android\$(whoami)"))
        assertFalse(ShellSafety.isValidPackageName("com/android/settings"))
        assertFalse(ShellSafety.isValidPackageName("../etc/passwd"))
        assertFalse(ShellSafety.isValidPackageName("com android settings"))
        assertFalse(ShellSafety.isValidPackageName("singleword"))
    }

    @Test
    fun testValidComponentName() {
        assertTrue(ShellSafety.isValidComponentName("com.example.MainActivity"))
        assertTrue(ShellSafety.isValidComponentName("com.example.App\$InnerService"))
        assertFalse(ShellSafety.isValidComponentName("com.example.Main; reboot"))
        assertFalse(ShellSafety.isValidComponentName(null))
        assertFalse(ShellSafety.isValidComponentName(""))
    }

    @Test
    fun testValidHostnameOrIp() {
        assertTrue(ShellSafety.isValidHostnameOrIp("1.1.1.1"))
        assertTrue(ShellSafety.isValidHostnameOrIp("8.8.8.8"))
        assertTrue(ShellSafety.isValidHostnameOrIp("dns.google"))
        assertTrue(ShellSafety.isValidHostnameOrIp("one.one.one.one"))
        assertTrue(ShellSafety.isValidHostnameOrIp("p0.freedns.controld.com"))

        assertFalse(ShellSafety.isValidHostnameOrIp(null))
        assertFalse(ShellSafety.isValidHostnameOrIp(""))
        assertFalse(ShellSafety.isValidHostnameOrIp("1.1.1.1; echo evil"))
        assertFalse(ShellSafety.isValidHostnameOrIp("dns.google & reboot"))
    }

    @Test
    fun testValidSettingKey() {
        assertTrue(ShellSafety.isValidSettingKey("animator_duration_scale"))
        assertTrue(ShellSafety.isValidSettingKey("private_dns_mode"))
        assertTrue(ShellSafety.isValidSettingKey("location_mode"))
        assertTrue(ShellSafety.isValidSettingKey("policy_control"))

        assertFalse(ShellSafety.isValidSettingKey(null))
        assertFalse(ShellSafety.isValidSettingKey(""))
        assertFalse(ShellSafety.isValidSettingKey("key; rm -rf /"))
        assertFalse(ShellSafety.isValidSettingKey("key`id`"))
    }

    @Test
    fun testSafePathTraversals() {
        val allowedRoots = listOf("/data/user/0/com.bloatware.bingblop/cache", "/sdcard/Download/ADBManager")

        assertTrue(ShellSafety.isSafePath("/data/user/0/com.bloatware.bingblop/cache/file.tmp", allowedRoots))
        assertTrue(ShellSafety.isSafePath("/sdcard/Download/ADBManager/backup.apk", allowedRoots))

        // Directory traversal attacks
        assertFalse(ShellSafety.isSafePath("/data/user/0/com.bloatware.bingblop/cache/../../../system/bin/sh", allowedRoots))
        assertFalse(ShellSafety.isSafePath("/etc/hosts", allowedRoots))
        assertFalse(ShellSafety.isSafePath("/sdcard/Download/ADBManager/../evil", allowedRoots))
        assertFalse(ShellSafety.isSafePath(null, allowedRoots))
        assertFalse(ShellSafety.isSafePath("", allowedRoots))
    }

    @Test
    fun testParseShellOutcomeSuccess() {
        val outcome = ShellSafety.parseShellOutcome(0, "Package: com.test\nState: enabled", "")
        assertTrue(outcome is ShellOutcome.Success)
        assertEquals("Package: com.test\nState: enabled", (outcome as ShellOutcome.Success).output)
        assertEquals(0, outcome.exitCode)
    }

    @Test
    fun testParseShellOutcomeCatchesNonZeroExitCode() {
        val outcome = ShellSafety.parseShellOutcome(1, "", "Usage: pm [options]")
        assertTrue(outcome is ShellOutcome.Failure)
        assertEquals(1, (outcome as ShellOutcome.Failure).exitCode)
    }

    @Test
    fun testParseShellOutcomeCatchesC002Defects() {
        // C-002: exit code 0 but stdout starts with Error:
        val errOutcome = ShellSafety.parseShellOutcome(0, "Error: package not found", "")
        assertTrue(errOutcome is ShellOutcome.Failure)

        // C-002: adb error prefix
        val adbErr = ShellSafety.parseShellOutcome(0, "adb: error: closed", "")
        assertTrue(adbErr is ShellOutcome.Failure)

        // C-002: Timeout marker
        val timeoutOutcome = ShellSafety.parseShellOutcome(0, "[Process timed out after 5000ms]", "")
        assertTrue(timeoutOutcome is ShellOutcome.Failure)
        assertTrue((timeoutOutcome as ShellOutcome.Failure).isTimeout)

        // C-002: Permission Denial
        val permOutcome = ShellSafety.parseShellOutcome(0, "java.lang.SecurityException: Permission Denial: not allowed", "")
        assertTrue(permOutcome is ShellOutcome.Failure)
        assertTrue((permOutcome as ShellOutcome.Failure).isPermissionDenied)
    }
}
