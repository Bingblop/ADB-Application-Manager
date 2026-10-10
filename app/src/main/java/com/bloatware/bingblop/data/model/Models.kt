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

enum class ComponentType(val label: String) {
    SERVICE("Service"),
    RECEIVER("Receiver"),
    ACTIVITY("Activity")
}

data class ComponentItem(
    val packageName: String,
    val name: String,
    val simpleName: String,
    val type: ComponentType,
    val isEnabled: Boolean
)

enum class LogcatLevel(val code: String, val label: String, val colorHex: Long) {
    ALL("*", "All", 0xFF90CAF9),
    VERBOSE("V", "Verbose", 0xFF9E9E9E),
    DEBUG("D", "Debug", 0xFF00E5FF),
    INFO("I", "Info", 0xFF00E676),
    WARN("W", "Warn", 0xFFFFB300),
    ERROR("E", "Error", 0xFFFF5252),
    FATAL("F", "Fatal", 0xFFFF1744)
}

data class LogcatEntry(
    val id: Long,
    val timestamp: String,
    val pid: String,
    val tid: String,
    val level: LogcatLevel,
    val tag: String,
    val message: String,
    val raw: String
)

enum class DexOptMode(
    val arg: String,
    val title: String,
    val description: String,
    val isReset: Boolean = false
) {
    SPEED_PROFILE("speed-profile", "Speed Profile (Recommended)", "Profile-guided AOT using Baseline Profiles for optimal launch latency & small disk footprint"),
    SPEED("speed", "Speed (Full AOT)", "Compiles all bytecode to native machine code for maximum execution performance"),
    EVERYTHING("everything", "Everything", "Compiles all code including debugging stubs and rare branches"),
    SPACE_PROFILE("space-profile", "Space Profile", "Profile-guided compilation optimized to save storage space"),
    SPACE("space", "Space", "Compiles with optimization prioritizing minimal disk usage"),
    QUICKEN("quicken", "Quicken", "Fast bytecode verification and dex-to-dex optimizations"),
    VERIFY("verify", "Verify Only", "Bytecode verification without compiling machine code"),
    RESET("--reset", "Reset / Uncompile", "Clears compiled oat/odex files and reverts app to default JIT interpretation", isReset = true)
}

data class DexOptResult(
    val packageName: String,
    val mode: String,
    val success: Boolean,
    val output: String,
    val durationMs: Long = 0L
)

data class BatchDexOptSummary(
    val total: Int,
    val succeeded: Int,
    val failed: Int,
    val results: List<DexOptResult>,
    val cancelled: Boolean = false
)

enum class StandbyBucket(val arg: String, val title: String, val colorHex: Long) {
    ACTIVE("active", "Active", 0xFF00E676),
    WORKING_SET("working_set", "Working Set", 0xFF00E5FF),
    FREQUENT("frequent", "Frequent", 0xFFFFB300),
    RARE("rare", "Rare", 0xFFFF9100),
    RESTRICTED("restricted", "Restricted", 0xFFFF5252)
}

enum class AppOpType(val opName: String, val title: String, val description: String) {
    RUN_IN_BACKGROUND("RUN_IN_BACKGROUND", "Run In Background", "Prevent background battery drain and rogue services"),
    BOOT_COMPLETED("BOOT_COMPLETED", "Start At Boot", "Prevent app from auto-starting on device startup"),
    SYSTEM_ALERT_WINDOW("SYSTEM_ALERT_WINDOW", "Draw Over Apps", "Display floating windows or overlay alerts"),
    WAKE_LOCK("WAKE_LOCK", "Wake Lock", "Prevent CPU wake locks from keeping device awake"),
    TOAST_WINDOW("TOAST_WINDOW", "Toast Alerts", "Block spamming toast popups")
}

data class CrashLogEntry(
    val id: String,
    val tag: String,
    val timestamp: String,
    val summary: String,
    val details: String
)

data class PowerUserApp(
    val packageName: String,
    val name: String,
    val description: String,
    val permissions: List<String>
)

data class PrivateDnsPreset(
    val id: String,
    val title: String,
    val mode: String, // "hostname", "opportunistic", "off"
    val hostname: String,
    val description: String,
    val isEncrypted: Boolean = true,
    val primaryIp: String = "",
    val category: String = "General"
)

enum class DnsPingStatus {
    FAST,
    MODERATE,
    SLOW,
    TIMEOUT,
    TESTING,
    IDLE
}

data class DnsBenchmarkResult(
    val presetId: String,
    val title: String,
    val hostname: String,
    val ip: String,
    val latencyMs: Long?,
    val status: DnsPingStatus,
    val details: String = ""
)

data class BatteryDiagnostics(
    val voltageMv: Int = 0,
    val chargeCounterUah: Long = 0L,
    val chargeCycles: Int? = null,
    val technology: String = "Li-ion",
    val health: String = "Good",
    val status: String = "Discharging",
    val topWakelocks: List<String> = emptyList()
)

data class AndroidUser(
    val id: Int,
    val name: String,
    val flags: String = "",
    val isOwner: Boolean = false
)

data class RuntimePermissionItem(
    val permission: String,
    val simpleName: String,
    val isGranted: Boolean
)

data class ScreenCaptureResult(
    val filePath: String,
    val isSuccess: Boolean,
    val message: String
)
