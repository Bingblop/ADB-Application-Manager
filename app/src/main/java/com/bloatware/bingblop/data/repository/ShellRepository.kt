package com.bloatware.bingblop.data.repository

import com.bloatware.bingblop.data.model.ShellCommand
import com.bloatware.bingblop.data.model.ShellMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.UUID

class ShellRepository {

    private val commandHistory = mutableListOf<ShellCommand>()

    val presets = listOf(
        "pm list packages -3" to "List installed 3rd-party user packages",
        "pm list packages -d" to "List frozen/disabled packages",
        "dumpsys battery" to "Query battery hardware telemetry",
        "getprop ro.product.model" to "Query Android device product model",
        "df -h /data" to "Show data storage disk utilization",
        "uname -a" to "Display Linux kernel architecture",
        "top -b -n 1" to "Snapshot of top CPU active processes",
        "ip address show" to "Query active network interface IPs"
    )

    suspend fun executeCommand(commandStr: String, mode: ShellMode): ShellCommand = withContext(Dispatchers.IO) {
        val trimmed = commandStr.trim()
        val startTime = System.currentTimeMillis()
        var process: Process? = null

        try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", trimmed))
            val stdoutDef = async(Dispatchers.IO) {
                process.inputStream.bufferedReader().use { it.readText() }.trim()
            }
            val stderrDef = async(Dispatchers.IO) {
                process.errorStream.bufferedReader().use { it.readText() }.trim()
            }

            val stdout = stdoutDef.await()
            val stderr = stderrDef.await()
            val exitCode = process.waitFor()

            val combinedOutput = when {
                stdout.isNotEmpty() && stderr.isNotEmpty() -> "$stdout\n\n[STDERR]:\n$stderr"
                stdout.isNotEmpty() -> stdout
                stderr.isNotEmpty() -> "[ERROR]:\n$stderr"
                else -> "(Command executed with exit code $exitCode - no output)"
            }

            val result = ShellCommand(
                id = UUID.randomUUID().toString(),
                command = trimmed,
                output = combinedOutput,
                exitCode = exitCode,
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
            process?.destroy()
        }
    }

    fun getHistory(): List<ShellCommand> = commandHistory.toList()

    fun clearHistory() {
        commandHistory.clear()
    }
}
