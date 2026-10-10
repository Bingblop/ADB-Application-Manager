package com.bloatware.bingblop.data.repository

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import com.bloatware.bingblop.data.model.SettingNamespace
import com.bloatware.bingblop.data.model.SystemSettingItem
import com.bloatware.bingblop.util.ShellOutcome
import com.bloatware.bingblop.util.ShellSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

class SettingsRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    // Curated essential hidden system settings with descriptions and categories
    private val curatedSettings = listOf(
        SystemSettingItem(
            namespace = SettingNamespace.GLOBAL,
            key = "animator_duration_scale",
            currentValue = "1.0",
            defaultValue = "1.0",
            title = "Animator Duration Scale",
            description = "Controls UI transition and animation speeds (0.0 for instant, 0.5 for fast snappy, 1.0 default)",
            category = "Animation & Display"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.GLOBAL,
            key = "window_animation_scale",
            currentValue = "1.0",
            defaultValue = "1.0",
            title = "Window Animation Scale",
            description = "Controls window opening and closing speeds",
            category = "Animation & Display"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.GLOBAL,
            key = "transition_animation_scale",
            currentValue = "1.0",
            defaultValue = "1.0",
            title = "Transition Animation Scale",
            description = "Controls screen-to-screen activity transition durations",
            category = "Animation & Display"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.GLOBAL,
            key = "adb_enabled",
            currentValue = "1",
            defaultValue = "0",
            title = "USB Debugging (ADB)",
            description = "Allows ADB bridge connections over USB cable",
            category = "Developer Options"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.GLOBAL,
            key = "stay_on_while_plugged_in",
            currentValue = "0",
            defaultValue = "0",
            title = "Keep Screen On While Charging",
            description = "Prevents display sleep when connected to power (0=off, 3=all chargers, 7=dock/wireless)",
            category = "Power & Screen"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.GLOBAL,
            key = "private_dns_mode",
            currentValue = "off",
            defaultValue = "off",
            title = "Private DNS Mode",
            description = "Encrypted DoT DNS resolver mode ('off', 'opportunistic', 'hostname')",
            category = "Network & Privacy"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.GLOBAL,
            key = "mobile_data_always_on",
            currentValue = "0",
            defaultValue = "0",
            title = "Mobile Data Always Active",
            description = "Keeps cellular connection active in background even when connected to Wi-Fi",
            category = "Network & Privacy"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.SECURE,
            key = "location_mode",
            currentValue = "3",
            defaultValue = "3",
            title = "Location Accuracy Mode",
            description = "System geolocation accuracy provider level",
            category = "Sensors & Privacy"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.SECURE,
            key = "screensaver_enabled",
            currentValue = "0",
            defaultValue = "0",
            title = "Interactive Screensaver / Ambient",
            description = "Enables ambient clock or screensaver when docked or charging",
            category = "Power & Screen"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.SYSTEM,
            key = "screen_brightness_mode",
            currentValue = "0",
            defaultValue = "0",
            title = "Adaptive Brightness Mode",
            description = "Automatic ambient brightness adjustment (0=Manual, 1=Automatic)",
            category = "Power & Screen"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.SYSTEM,
            key = "accelerometer_rotation",
            currentValue = "1",
            defaultValue = "1",
            title = "Auto-Rotate Display",
            description = "Automatically switch orientation based on accelerometer sensor",
            category = "Sensors & Privacy"
        ),
        SystemSettingItem(
            namespace = SettingNamespace.SYSTEM,
            key = "haptic_feedback_enabled",
            currentValue = "1",
            defaultValue = "1",
            title = "Haptic Vibration Feedback",
            description = "Vibrate on touch navigation buttons, long-press, and keyboard presses",
            category = "Audio & Haptics"
        )
    )

    private val undoHistory = mutableListOf<Pair<SystemSettingItem, String>>()

    suspend fun getSettings(namespace: SettingNamespace): List<SystemSettingItem> = withContext(Dispatchers.IO) {
        val filtered = curatedSettings.filter { it.namespace == namespace }
        filtered.map { item ->
            val liveVal = readSetting(item.namespace, item.key)
            item.copy(currentValue = liveVal ?: item.currentValue)
        }
    }

    private suspend fun readSetting(namespace: SettingNamespace, key: String): String? {
        return try {
            when (namespace) {
                SettingNamespace.GLOBAL -> Settings.Global.getString(contentResolver, key)
                SettingNamespace.SECURE -> Settings.Secure.getString(contentResolver, key)
                SettingNamespace.SYSTEM -> Settings.System.getString(contentResolver, key)
            }
        } catch (_: Exception) {
            val nsStr = namespace.name.lowercase()
            runShellRead("settings get $nsStr $key")
        }
    }

    suspend fun writeSetting(
        item: SystemSettingItem,
        newValue: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!ShellSafety.isValidSettingKey(item.key)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid setting key: ${item.key}"))
        }
        val previousVal = item.currentValue
        val nsStr = item.namespace.name.lowercase()
        val quotedKey = ShellSafety.quoteArg(item.key)
        val quotedVal = ShellSafety.quoteArg(newValue)
        val cmd = "settings put $nsStr $quotedKey $quotedVal"
        val res = runShellWrite(cmd)
        if (res.isSuccess) {
            undoHistory.add(Pair(item, previousVal))
            Result.success("Applied ${item.key} = $newValue")
        } else {
            Result.failure(Exception(res.exceptionOrNull()?.message ?: "Permission denied: Requires WRITE_SECURE_SETTINGS or ADB/Root"))
        }
    }

    suspend fun undoLastChange(): Result<String> = withContext(Dispatchers.IO) {
        if (undoHistory.isEmpty()) {
            return@withContext Result.failure(Exception("No changes in undo stack"))
        }
        val (item, oldValue) = undoHistory.removeAt(undoHistory.lastIndex)
        if (!ShellSafety.isValidSettingKey(item.key)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid setting key in undo stack"))
        }
        val nsStr = item.namespace.name.lowercase()
        val quotedKey = ShellSafety.quoteArg(item.key)
        val quotedVal = ShellSafety.quoteArg(oldValue)
        val cmd = "settings put $nsStr $quotedKey $quotedVal"
        val res = runShellWrite(cmd)
        if (res.isSuccess) {
            Result.success("Reverted ${item.key} to $oldValue")
        } else {
            Result.failure(Exception("Failed to revert: ${res.exceptionOrNull()?.message}"))
        }
    }

    fun canUndo(): Boolean = undoHistory.isNotEmpty()

    private suspend fun runShellRead(cmd: String): String? = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            if (text == "null" || text.isEmpty()) null else text
        } catch (_: Exception) {
            null
        } finally {
            process?.destroy()
        }
    }

    private suspend fun runShellWrite(cmd: String): Result<Unit> = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val stderrDef = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText() }.trim() }
            val stdoutDef = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() }.trim() }
            val stderr = stderrDef.await()
            val stdout = stdoutDef.await()
            val exitCode = process.waitFor()
            val outcome = ShellSafety.parseShellOutcome(exitCode, stdout, stderr)
            if (outcome is ShellOutcome.Success) {
                Result.success(Unit)
            } else {
                val err = (outcome as ShellOutcome.Failure).message
                Result.failure(Exception(err.ifEmpty { "Command failed with exit code $exitCode" }))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            process?.destroy()
        }
    }
}
