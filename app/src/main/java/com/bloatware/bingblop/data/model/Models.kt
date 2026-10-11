package com.bloatware.bingblop.data.model

data class StorageCleanerItem(
    val id: String,
    val title: String,
    val description: String,
    val path: String,
    val sizeBytes: Long,
    val category: String,
    val isSelected: Boolean = true
)

enum class ShellMode {
    LOCAL_SHELL,
    ROOT_SU,
    ADB_DAEMON,
    SHIZUKU
}

data class ShellCommand(
    val id: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long,
    val mode: ShellMode
)

data class ApkInspectorInfo(
    val fileName: String,
    val fileSize: Long,
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Int,
    val minSdk: Int,
    val targetSdk: Int,
    val permissions: List<String> = emptyList(),
    val activities: List<String> = emptyList(),
    val splits: List<String> = emptyList(),
    val isSafe: Boolean = true
)

enum class DexOptMode(val arg: String, val label: String) {
    SPEED("speed", "Speed (Full AOT)"),
    SPEED_PROFILE("speed-profile", "Profile-Guided (Hybrid)"),
    QUICKEN("quicken", "Quicken (Fast compile)"),
    EVERYTHING("everything", "Everything (Maximum bytecode)")
}

enum class DebloatSafetyTier(val displayName: String, val levelKey: String) {
    SAFE("Safe to remove", "Recommended"),
    RECOMMENDED("Recommended", "Recommended"),
    ADVANCED("Advanced", "Advanced"),
    EXPERT("Expert", "Expert"),
    UNSAFE("Unsafe / Bootloop risk", "Unsafe");

    companion object {
        fun fromUad(str: String?): DebloatSafetyTier {
            return when (str?.lowercase()?.trim()) {
                "safe", "recommended" -> SAFE
                "advanced" -> ADVANCED
                "expert" -> EXPERT
                "unsafe" -> UNSAFE
                else -> ADVANCED
            }
        }
    }
}

enum class DebloatCategory(val displayName: String, val id: String) {
    ALL("All Bloatware", "all"),
    SAMSUNG("Samsung OneUI", "samsung"),
    XIAOMI("Xiaomi MIUI / HyperOS", "xiaomi"),
    GOOGLE("Google / AOSP", "google"),
    CARRIER("Carrier Bloat", "carrier"),
    TELEMETRY("Analytics & Telemetry", "telemetry"),
    OEM("OEM & Other", "oem")
}

data class DebloatInfo(
    val packageName: String,
    val category: DebloatCategory,
    val safetyTier: DebloatSafetyTier,
    val summary: String,
    val description: String,
    val dependencies: List<String> = emptyList(),
    val neededBy: List<String> = emptyList()
)

data class PackageItem(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val isEnabled: Boolean,
    val isPreserved: Boolean = false,
    val apkPath: String = "",
    val sizeBytes: Long = 0L,
    val isVendorBloatware: Boolean = false,
    val vendorPresetName: String? = null,
    val debloatInfo: DebloatInfo? = null,
    val isSelected: Boolean = false
)

data class DebloatProfile(
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val deviceModel: String = "",
    val packages: List<DebloatProfileEntry>
)

data class DebloatProfileEntry(
    val packageName: String,
    val action: String = "DISABLE", // "DISABLE", "ENABLE", "UNINSTALL"
    val safety: String = "SAFE",
    val comment: String = ""
)

data class SystemSettingItem(
    val key: String,
    val title: String,
    val description: String,
    val category: String,
    val type: String, // "boolean", "int", "string"
    val currentValue: String,
    val defaultValue: String,
    val isDangerous: Boolean = false
)

data class LogcatItem(
    val id: Long,
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: String, // "V", "D", "I", "W", "E", "F"
    val tag: String,
    val message: String
)

data class MonitorTelemetry(
    val cpuUsagePercent: Float,
    val cpuFrequencyMhz: Int,
    val totalRamBytes: Long,
    val usedRamBytes: Long,
    val batteryLevelPercent: Int,
    val batteryTempCelsius: Float,
    val batteryCharging: Boolean,
    val uptimeSeconds: Long
)
