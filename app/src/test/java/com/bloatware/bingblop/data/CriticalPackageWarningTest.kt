package com.bloatware.bingblop.data

import com.bloatware.bingblop.data.repository.AppRepository
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CriticalPackageWarningTest {

    @Test
    fun testCriticalCoreSystemPackagesProduceWarnings() {
        val criticalCases = listOf(
            "android",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.webview",
            "com.android.webview",
            "com.android.phone",
            "com.google.android.dialer",
            "com.android.dialer",
            "com.android.server.telecom",
            "com.android.providers.telephony",
            "com.google.android.ims",
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        for (pkg in criticalCases) {
            val warning = AppRepository.isCriticalSystemPackage(pkg)
            assertNotNull("Package '$pkg' must have a critical system warning", warning)
            assertTrue("Warning for '$pkg' should be descriptive", warning!!.length > 10)
        }
    }

    @Test
    fun testNonCriticalUserPackagesDoNotProduceWarnings() {
        val nonCriticalCases = listOf(
            "com.spotify.music",
            "com.facebook.katana",
            "com.microsoft.skydrive",
            "com.netflix.mediaclient",
            "com.king.candycrushsaga",
            "com.bloatware.bingblop"
        )

        for (pkg in nonCriticalCases) {
            val warning = AppRepository.isCriticalSystemPackage(pkg)
            assertNull("Package '$pkg' should not produce a critical system warning", warning)
        }
    }
}
