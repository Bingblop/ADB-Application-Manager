package com.bloatware.bingblop.data.repository

import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import com.bloatware.bingblop.util.ShellOutcome
import com.bloatware.bingblop.util.ShellSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

enum class PowerAction(
    val title: String,
    val command: String,
    val description: String,
    val isDangerous: Boolean
) {
    REBOOT("Reboot System", "reboot", "Standard hardware reboot of the device", false),
    SOFT_REBOOT("Soft Reboot", "setprop ctl.restart zygote", "Fast restart of the Android userspace framework without full kernel reload", false),
    REBOOT_RECOVERY("Reboot to Recovery", "reboot recovery", "Boot device into Android Recovery mode for maintenance or sideloading", true),
    REBOOT_BOOTLOADER("Reboot to Bootloader", "reboot bootloader", "Boot into Fastboot / Bootloader mode for flashing or low-level ADB", true),
    POWER_OFF("Power Off", "reboot -p", "Completely shut down and power off hardware", true),
    RESTART_SYSTEM_UI("Restart SystemUI", "pkill -f com.android.systemui", "Restart status bar, navigation bar, and notifications without closing apps", false)
}

class ShellRepository {

    private val commandHistory = mutableListOf<ShellCommand>()

    val presets = listOf(
        "pm list packages -3" to "List installed 3rd-party user packages",
        "pm list packages -d" to "List frozen/disabled packages",
        "dumpsys battery" to "Query battery hardware telemetry",
        "dumpsys meminfo" to "Query total RAM & memory breakdown",
        "dumpsys window displays" to "Display refresh rate & resolution",
        "getprop ro.product.model" to "Query Android device product model",
        "getprop ro.build.version.release" to "Query Android OS release version",
        "df -h /data" to "Show data storage disk utilization",
        "uname -a" to "Display Linux kernel architecture",
        "top -b -n 1" to "Snapshot of top CPU active processes",
        "ip address show" to "Query active network interface IPs",
        "uptime" to "System uptime & load average"
    )

    suspend fun executeCommand(commandStr: String, mode: ShellMode): ShellCommand = withContext(Dispatchers.IO) {
        val trimmed = commandStr.trim()
        val startTime = System.currentTimeMillis()
        var process: Process? = null

        try {
            val executionResult = withTimeoutOrNull(20_000L) {
                val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", trimmed))
                process = proc
                val stdoutDef = async(Dispatchers.IO) {
                    proc.inputStream.bufferedReader().use { it.readText() }.trim()
                }
                val stderrDef = async(Dispatchers.IO) {
                    proc.errorStream.bufferedReader().use { it.readText() }.trim()
                }

                val stdout = stdoutDef.await()
                val stderr = stderrDef.await()
                val exitCode = proc.waitFor()
                Triple(exitCode, stdout, stderr)
            }

            val (rawExitCode, stdout, stderr) = executionResult ?: Triple(-1, "", "[Process timed out after 20s]")
            val outcome = ShellSafety.parseShellOutcome(rawExitCode, stdout, stderr)
            val finalExitCode = when (outcome) {
                is ShellOutcome.Success -> outcome.exitCode
                is ShellOutcome.Failure -> outcome.exitCode
            }
            val combinedOutput = when (outcome) {
                is ShellOutcome.Success -> outcome.output.ifEmpty { "(Command executed with exit code $finalExitCode - no output)" }
                is ShellOutcome.Failure -> outcome.message
            }

            val result = ShellCommand(
                id = UUID.randomUUID().toString(),
                command = trimmed,
                output = combinedOutput,
                exitCode = finalExitCode,
                timestamp = startTime,
                mode = mode
            )
            commandHistory.add(0, result)
            result
        } catch (e: Exception) {
            val result = ShellCommand(
                id = UUID.randomUUID().toString(),
                command = trimmed,
                output = "Execution failed: ${e.localizedMessage ?: "Unknown shell error"}",
                exitCode = -1,
                timestamp = startTime,
                mode = mode
            )
            commandHistory.add(0, result)
            result
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {}
        }
    }

    suspend fun executePowerAction(action: PowerAction, mode: ShellMode = ShellMode.LOCAL_SHELL): ShellCommand {
        return executeCommand(action.command, mode)
    }

    fun getHistory(): List<ShellCommand> = commandHistory.toList()

    fun clearHistory() {
        commandHistory.clear()
    }
}
