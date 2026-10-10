package com.bloatware.bingblop.data.model

import android.graphics.drawable.Drawable

enum class DebloatLevel(val title: String, val colorHex: Long) {
    RECOMMENDED("Recommended", 0xFF00E676),
    ADVANCED("Advanced", 0xFFFF9100),
    EXPERT("Expert", 0xFFFF5252),
    UNSAFE("Unsafe", 0xFFD50000)
}

enum class SettingNamespace {
    GLOBAL, SECURE, SYSTEM
}

enum class ShellMode {
    LOCAL_SHELL, SHIZUKU, ADB_WIRELESS, ROOT
}

data class AppItem(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val isSuspended: Boolean = false,
    val targetSdk: Int,
    val minSdk: Int = 21,
    val uid: Int,
    val apkPath: String,
    val dataDir: String,
    val isBloatware: Boolean = false,
    val bloatLevel: DebloatLevel = DebloatLevel.RECOMMENDED,
    val bloatDescription: String? = null,
    val permissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val trackers: List<String> = emptyList(),
    val appSize: Long = 0L,
    val icon: Drawable? = null
)

data class DebloatPackage(
    val packageName: String,
    val label: String,
    val level: DebloatLevel,
    val description: String,
    val isInstalled: Boolean,
    val isFrozen: Boolean,
    val isSelected: Boolean = false
)

data class SystemSettingItem(
    val namespace: SettingNamespace,
    val key: String,
    val currentValue: String,
    val defaultValue: String = "",
    val title: String,
    val description: String,
    val category: String,
    val isEditable: Boolean = true
)

data class ShellCommand(
    val id: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: ShellMode = ShellMode.LOCAL_SHELL
)

data class StorageCleanerItem(
    val id: String,
    val title: String,
    val description: String,
    val path: String,
    val sizeBytes: Long,
    val isSelected: Boolean = true,
    val category: String
)

data class DeviceInfo(
    val model: String,
    val manufacturer: String,
    val brand: String,
    val board: String,
    val soc: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val kernelVersion: String,
    val abi: String,
    val ramTotalBytes: Long,
    val ramAvailBytes: Long,
    val storageTotalBytes: Long,
    val storageAvailBytes: Long,
    val batteryPct: Int,
    val batteryStatus: String,
    val batteryHealth: String,
    val batteryTemp: Float,
    val uptimeMillis: Long
)

data class ApkInspectorInfo(
    val fileName: String,
    val fileSize: Long,
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String>,
    val activities: List<String>,
    val splits: List<String>,
    val isSafe: Boolean = true
)
