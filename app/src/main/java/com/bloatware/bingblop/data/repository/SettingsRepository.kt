package com.bloatware.bingblop.data.repository

import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import com.bloatware.bingblop.data.model.SystemSettingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsRepository(
    private val shellRepository: ShellRepository
) {

    private val managedSettings = listOf(
        SystemSettingItem(
            key = "window_animation_scale",
            title = "Window Animation Scale",
            description = "Control the speed of window transitions (0.0 = Instant, 0.5 = Snappy, 1.0 = Default)",
            category = "Performance & UI",
            type = "string",
            currentValue = "1.0",
            defaultValue = "1.0"
        ),
        SystemSettingItem(
            key = "transition_animation_scale",
            title = "Transition Animation Scale",
            description = "Control the speed of activity transitions",
            category = "Performance & UI",
            type = "string",
            currentValue = "1.0",
            defaultValue = "1.0"
        ),
        SystemSettingItem(
            key = "animator_duration_scale",
            title = "Animator Duration Scale",
            description = "Control duration of in-app element animations",
            category = "Performance & UI",
            type = "string",
            currentValue = "1.0",
            defaultValue = "1.0"
        ),
        SystemSettingItem(
            key = "peak_refresh_rate",
            title = "Peak Refresh Rate (Hz)",
            description = "Force 90Hz / 120Hz continuous refresh rate",
            category = "Display",
            type = "string",
            currentValue = "120.0",
            defaultValue = "60.0"
        ),
        SystemSettingItem(
            key = "min_refresh_rate",
            title = "Minimum Refresh Rate (Hz)",
            description = "Prevent refresh rate from dropping down to save battery",
            category = "Display",
            type = "string",
            currentValue = "60.0",
            defaultValue = "60.0"
        ),
        SystemSettingItem(
            key = "private_dns_mode",
            title = "Private DNS Mode",
            description = "Configure system DNS resolver (off, opportunistic, or hostname)",
            category = "Network & Privacy",
            type = "string",
            currentValue = "opportunistic",
            defaultValue = "opportunistic"
        ),
        SystemSettingItem(
            key = "wifi_scan_always_enabled",
            title = "Wi-Fi Background Scanning",
            description = "Allow system services to scan for Wi-Fi networks even when Wi-Fi is disabled",
            category = "Network & Privacy",
            type = "boolean",
            currentValue = "0",
            defaultValue = "0"
        ),
        SystemSettingItem(
            key = "ble_scan_always_enabled",
            title = "Bluetooth Low Energy Scanning",
            description = "Allow background BLE beacon discovery for location assistance",
            category = "Network & Privacy",
            type = "boolean",
            currentValue = "0",
            defaultValue = "0"
        )
    )

    suspend fun loadSettings(): List<SystemSettingItem> = withContext(Dispatchers.IO) {
        managedSettings.map { setting ->
            val out = shellRepository.executeCommand("settings get global ${setting.key}", ShellMode.LOCAL_SHELL)
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
}
