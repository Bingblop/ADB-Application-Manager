package com.bloatware.bingblop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerSecurityTest {

    @Test
    fun testPackageIdentityGuards() {
        val validPackages = listOf(
            "com.android.settings",
            "moe.shizuku.privileged.api",
            "org.fdroid.fdroid",
            "com.bloatware.bingblop"
        )

        validPackages.forEach { pkg ->
            val result = InstallGuards.verifyPackageIdentity(pkg)
            assertTrue("Expected valid: $pkg", result is InstallGuards.GuardResult.Passed)
        }

        val hostilePackages = listOf(
            "",
            "   ",
            "../bad",
            "com.app; rm -rf /",
            "pkg with spaces",
            "$(reboot)",
            "com.app\u0000injected"
        )

        hostilePackages.forEach { bad ->
            val result = InstallGuards.verifyPackageIdentity(bad)
            assertTrue("Expected rejected: $bad", result is InstallGuards.GuardResult.Rejected)
        }
    }

    @Test
    fun testShellQuoteArgForInstallerPaths() {
        val path1 = "/sdcard/Download/app.apk"
        assertEquals("'/sdcard/Download/app.apk'", ShellSafety.quoteArg(path1))

        val pathWithQuotes = "/sdcard/Download/O'Reilly.apk"
        assertEquals("'/sdcard/Download/O'\\''Reilly.apk'", ShellSafety.quoteArg(pathWithQuotes))
    }
}
