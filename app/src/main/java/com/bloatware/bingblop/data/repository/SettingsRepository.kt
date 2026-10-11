package com.bloatware.bingblop.data.repository

import android.content.Context
import android.content.pm.PackageManager
import com.bloatware.bingblop.data.model.PowerUserApp
import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import com.bloatware.bingblop.data.model.SystemSettingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class SettingsRepository(
    private val context: Context,
    private val shellRepository: ShellRepository
) {

    private val managedSettings = listOf(
        // ==========================================
        // PERFORMANCE & ANIMATIONS
        // ==========================================
        SystemSettingItem(
            key = "window_animation_scale",
            title = "Window Animation Scale",
            description = "Speed of window openings (0.0 = Instant, 0.5 = Snappy, 1.0 = Default)",
            category = "Animations",
            type = "string",
            currentValue = "1.0",
            defaultValue = "1.0"
        ),
        SystemSettingItem(
            key = "transition_animation_scale",
            title = "Transition Animation Scale",
            description = "Speed of activity transitions between screens",
            category = "Animations",
            type = "string",
            currentValue = "1.0",
            defaultValue = "1.0"
        ),
        SystemSettingItem(
            key = "animator_duration_scale",
            title = "Animator Duration Scale",
            description = "Duration of in-app interactive UI element animations",
            category = "Animations",
            type = "string",
            currentValue = "1.0",
            defaultValue = "1.0"
        ),

        // ==========================================
        // DISPLAY & REFRESH RATE
        // ==========================================
        SystemSettingItem(
            key = "peak_refresh_rate",
            title = "Peak Refresh Rate (Hz)",
            description = "Force 90Hz / 120Hz continuous refresh rate without throttling",
            category = "Display",
            type = "string",
            currentValue = "120.0",
            defaultValue = "60.0"
        ),
        SystemSettingItem(
            key = "min_refresh_rate",
            title = "Minimum Refresh Rate (Hz)",
            description = "Prevent display panel from dropping down to low Hz",
            category = "Display",
            type = "string",
            currentValue = "60.0",
            defaultValue = "60.0"
        ),

        // ==========================================
        // PRIVACY & NETWORKING
        // ==========================================
        SystemSettingItem(
            key = "private_dns_mode",
            title = "Private DNS Resolver Mode",
            description = "DNS privacy resolver: off, opportunistic, or hostname",
            category = "Network & Privacy",
            type = "string",
            currentValue = "opportunistic",
            defaultValue = "opportunistic"
        ),
        SystemSettingItem(
            key = "wifi_scan_always_enabled",
            title = "Wi-Fi Background Scanning",
            description = "Allow location services to scan Wi-Fi networks when Wi-Fi is toggled off",
            category = "Network & Privacy",
            type = "boolean",
            currentValue = "0",
            defaultValue = "0"
        ),
        SystemSettingItem(
            key = "ble_scan_always_enabled",
            title = "Bluetooth LE Background Scanning",
            description = "Allow background BLE beacon scanning for location telemetry",
            category = "Network & Privacy",
            type = "boolean",
            currentValue = "0",
            defaultValue = "0"
        ),
        SystemSettingItem(
            key = "mobile_data_always_on",
            title = "Mobile Data Always Active",
            description = "Keep cellular data network connected even when connected to Wi-Fi",
            category = "Network & Privacy",
            type = "boolean",
            currentValue = "0",
            defaultValue = "0"
        ),

        // ==========================================
        // SYSTEM UI & DEVELOPER TOOLS
        // ==========================================
        SystemSettingItem(
            key = "heads_up_notifications_enabled",
            title = "Heads-Up Banner Notifications",
            description = "Display floating notification popups at the top of the screen",
            category = "System UI",
            type = "boolean",
            currentValue = "1",
            defaultValue = "1"
        ),
        SystemSettingItem(
            key = "stay_on_while_plugged_in",
            title = "Stay Awake While Charging",
            description = "Screen will never sleep while charging (3 = AC + USB)",
            category = "System UI",
            type = "string",
            currentValue = "0",
            defaultValue = "0"
        ),
        SystemSettingItem(
            key = "show_touches",
            title = "Show Visual Taps",
            description = "Show visual touch circles on the screen during touch interactions",
            category = "System UI",
            type = "boolean",
            currentValue = "0",
            defaultValue = "0"
        )
    )

    private val supportedPowerUserApps = listOf(
        PowerUserApp(
            appName = "Shizuku",
            packageName = "moe.shizuku.privileged.api",
            description = "Privileged system service runner using ADB / root"
        ),
        PowerUserApp(
            appName = "Tasker",
            packageName = "net.dinglisch.android.taskerm",
            description = "Automation engine for settings, triggers and system profiles"
        ),
        PowerUserApp(
            appName = "MacroDroid",
            packageName = "com.arlosoft.macrodroid",
            description = "Device automation requiring WRITE_SECURE_SETTINGS"
        ),
        PowerUserApp(
            appName = "SystemUI Tuner",
            packageName = "com.zacharee1.systemuituner",
            description = "Quick Settings, status bar icons and lock screen customization"
        ),
        PowerUserApp(
            appName = "App Ops",
            packageName = "rikka.appops",
            description = "Fine-grained per-app permission and telemetry manager"
        )
    )

    suspend fun loadSettings(): List<SystemSettingItem> = withContext(Dispatchers.IO) {
        managedSettings.map { setting ->
            val table = if (setting.category == "Animations") "global" else "global"
            val out = shellRepository.executeCommand("settings get $table ${setting.key}", ShellMode.LOCAL_SHELL)
            val currentVal = if (out.exitCode == 0 && out.output.isNotBlank() && out.output != "null") {
                out.output.trim()
            } else {
                setting.defaultValue
            }
            setting.copy(currentValue = currentVal)
        }
    }

    suspend fun updateSetting(key: String, value: String, table: String = "global"): ShellCommand {
        return shellRepository.executeCommand("settings put $table $key $value", ShellMode.LOCAL_SHELL)
    }

    suspend fun resetSetting(key: String, table: String = "global"): ShellCommand {
        return shellRepository.executeCommand("settings delete $table $key", ShellMode.LOCAL_SHELL)
    }

    // ==========================================
    // DISPLAY RESOLUTION & DENSITY (WM SIZE/DENSITY)
    // ==========================================

    suspend fun getDisplaySize(): String = withContext(Dispatchers.IO) {
        val out = shellRepository.executeCommand("wm size", ShellMode.LOCAL_SHELL)
        out.output.trim()
    }

    suspend fun setDisplaySize(width: Int, height: Int): ShellCommand {
        return shellRepository.executeCommand("wm size ${width}x${height}", ShellMode.LOCAL_SHELL)
    }

    suspend fun resetDisplaySize(): ShellCommand {
        return shellRepository.executeCommand("wm size reset", ShellMode.LOCAL_SHELL)
    }

    suspend fun getDisplayDensity(): String = withContext(Dispatchers.IO) {
        val out = shellRepository.executeCommand("wm density", ShellMode.LOCAL_SHELL)
        out.output.trim()
    }

    suspend fun setDisplayDensity(density: Int): ShellCommand {
        return shellRepository.executeCommand("wm density $density", ShellMode.LOCAL_SHELL)
    }

    suspend fun resetDisplayDensity(): ShellCommand {
        return shellRepository.executeCommand("wm density reset", ShellMode.LOCAL_SHELL)
    }

    // ==========================================
    // ANIMATION QUICK PRESETS
    // ==========================================

    suspend fun setAllAnimationScales(scale: Float): List<ShellCommand> = withContext(Dispatchers.IO) {
        val scaleStr = scale.toString()
        listOf(
            updateSetting("window_animation_scale", scaleStr),
            updateSetting("transition_animation_scale", scaleStr),
            updateSetting("animator_duration_scale", scaleStr)
        )
    }

    // ==========================================
    // PRIVATE DNS SWITCHER
    // ==========================================

    suspend fun setPrivateDns(mode: String, hostname: String? = null): List<ShellCommand> = withContext(Dispatchers.IO) {
        val commands = mutableListOf<ShellCommand>()
        if (hostname != null && hostname.isNotBlank()) {
            commands.add(updateSetting("private_dns_mode", "hostname"))
            commands.add(updateSetting("private_dns_specifier", hostname.trim()))
        } else {
            commands.add(updateSetting("private_dns_mode", mode))
            if (mode == "off") {
                commands.add(resetSetting("private_dns_specifier"))
            }
        }
        commands
    }

    // ==========================================
    // WIRELESS DEBUGGING & LOCAL ADB
    // ==========================================

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    suspend fun pairWirelessAdb(pairingPort: String, pairingCode: String): ShellCommand {
        val port = pairingPort.trim()
        val code = pairingCode.trim()
        val cmd = "adb pair 127.0.0.1:$port $code"
        return shellRepository.executeCommand(cmd, ShellMode.LOCAL_SHELL)
    }

    suspend fun connectWirelessAdb(connectPort: String): ShellCommand {
        val port = connectPort.trim()
        val cmd = "adb connect 127.0.0.1:$port"
        return shellRepository.executeCommand(cmd, ShellMode.LOCAL_SHELL)
    }

    suspend fun disconnectWirelessAdb(): ShellCommand {
        return shellRepository.executeCommand("adb disconnect", ShellMode.LOCAL_SHELL)
    }

    suspend fun checkAdbDevices(): String = withContext(Dispatchers.IO) {
        val out = shellRepository.executeCommand("adb devices", ShellMode.LOCAL_SHELL)
        out.output.trim()
    }

    // ==========================================
    // POWER-USER PERMISSION DISPATCHER
    // ==========================================

    fun getPowerUserApps(): List<PowerUserApp> {
        val pm = context.packageManager
        return supportedPowerUserApps.map { app ->
            val isInstalled = try {
                pm.getPackageInfo(app.packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            app.copy(isInstalled = isInstalled)
        }
    }

    suspend fun grantPowerUserPermissions(packageName: String): List<ShellCommand> = withContext(Dispatchers.IO) {
        val permissions = listOf(
            "android.permission.WRITE_SECURE_SETTINGS",
            "android.permission.DUMP",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.SYSTEM_ALERT_WINDOW"
        )
        val results = mutableListOf<ShellCommand>()
        for (perm in permissions) {
            val cmd = "pm grant $packageName $perm"
            results.add(shellRepository.executeCommand(cmd, ShellMode.LOCAL_SHELL))
        }
        results
    }
}
