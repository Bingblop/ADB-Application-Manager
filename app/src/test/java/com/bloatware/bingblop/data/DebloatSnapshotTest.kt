package com.bloatware.bingblop.data

import com.bloatware.bingblop.data.model.DebloatSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloatSnapshotTest {

    @Test
    fun testDebloatSnapshotCreationAndFiltering() {
        val packageStates = mapOf(
            "com.facebook.katana" to false,
            "com.google.android.youtube" to true,
            "com.miui.analytics" to false
        )
        val disabled = packageStates.filter { !it.value }.keys.toList()

        val snapshot = DebloatSnapshot(
            id = "snap_101",
            name = "Pre-Debloat Backup",
            timestamp = 1700000000L,
            totalPackages = packageStates.size,
            disabledPackages = disabled,
            packageStates = packageStates
        )

        assertEquals("snap_101", snapshot.id)
        assertEquals("Pre-Debloat Backup", snapshot.name)
        assertEquals(3, snapshot.totalPackages)
        assertEquals(2, snapshot.disabledPackages.size)
        assertTrue(snapshot.disabledPackages.contains("com.facebook.katana"))
        assertTrue(snapshot.disabledPackages.contains("com.miui.analytics"))
        assertFalse(snapshot.disabledPackages.contains("com.google.android.youtube"))
        assertEquals(false, snapshot.packageStates["com.facebook.katana"])
        assertEquals(true, snapshot.packageStates["com.google.android.youtube"])
    }

    @Test
    fun testSnapshotStateConsistency() {
        val states = mapOf(
            "com.amazon.mShop.android.shopping" to false,
            "com.samsung.android.bixby.agent" to false,
            "com.android.chrome" to true
        )
        val snapshot = DebloatSnapshot(
            id = "snap_102",
            name = "Samsung Cleanup",
            totalPackages = 3,
            disabledPackages = listOf("com.amazon.mShop.android.shopping", "com.samsung.android.bixby.agent"),
            packageStates = states
        )

        assertEquals(3, snapshot.totalPackages)
        assertEquals(2, snapshot.disabledPackages.size)
        assertTrue(snapshot.packageStates.containsKey("com.android.chrome"))
        assertTrue(snapshot.packageStates["com.android.chrome"] == true)
    }
}
