package com.bloatware.bingblop.data

import com.bloatware.bingblop.data.repository.AppRepository
import com.bloatware.bingblop.util.ShellSafety
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorDebloatPresetTest {

    @Test
    fun testVendorPackagesAreValidAndNonEmpty() {
        val vendors = listOf("Samsung", "Xiaomi", "Google", "OnePlus", "Huawei", "Motorola")

        for (vendor in vendors) {
            val pkgs = AppRepository.getVendorDebloatPackages(vendor)
            assertFalse("Vendor preset for $vendor should not be empty", pkgs.isEmpty())

            for (pkg in pkgs) {
                assertTrue("Package '$pkg' in $vendor preset must be a valid package name", ShellSafety.isValidPackageName(pkg))
                assertFalse("Package '$pkg' must not contain directory traversal", pkg.contains("..") || pkg.contains("/"))
            }
        }
    }

    @Test
    fun testSamsungSpecificBloatware() {
        val samsungPkgs = AppRepository.getVendorDebloatPackages("Samsung")

        assertTrue(samsungPkgs.contains("com.samsung.android.bixby.agent"))
        assertTrue(samsungPkgs.contains("com.sec.android.app.samsungapps"))
        assertTrue(samsungPkgs.contains("com.samsung.android.game.gamehome"))
    }

    @Test
    fun testXiaomiSpecificBloatware() {
        val xiaomiPkgs = AppRepository.getVendorDebloatPackages("Xiaomi")

        assertTrue(xiaomiPkgs.contains("com.miui.analytics"))
        assertTrue(xiaomiPkgs.contains("com.miui.msa.global"))
        assertTrue(xiaomiPkgs.contains("com.xiaomi.mipicks"))
    }

    @Test
    fun testUnknownVendorReturnsEmptyList() {
        val unknownPkgs = AppRepository.getVendorDebloatPackages("GenericAcmeDevice")
        assertTrue("Unknown vendor should return empty list", unknownPkgs.isEmpty())
    }
}
