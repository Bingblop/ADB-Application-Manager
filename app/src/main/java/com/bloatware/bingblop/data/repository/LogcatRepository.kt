package com.bloatware.bingblop.data.repository

import com.bloatware.bingblop.data.model.LogcatItem
import com.bloatware.bingblop.data.model.ShellMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LogcatRepository(
    private val shellRepository: ShellRepository
) {

    private val cachedLogs = mutableListOf<LogcatItem>()
    private var nextId = 1L

    suspend fun getRecentLogs(
        filterTag: String = "",
        minLevel: String = "V"
    ): List<LogcatItem> = withContext(Dispatchers.IO) {
        val out = shellRepository.executeCommand("logcat -d -v time -t 150", ShellMode.LOCAL_SHELL)
        val lines = out.output.lines()
        val parsed = mutableListOf<LogcatItem>()

        for (line in lines) {
            val item = parseLogLine(line)
            if (item != null) {
                if (filterTag.isNotEmpty() && !item.tag.contains(filterTag, ignoreCase = true) && !item.message.contains(filterTag, ignoreCase = true)) {
                    continue
                }
                if (shouldIncludeLevel(item.level, minLevel)) {
                    parsed.add(item)
                }
            }
        }

        if (parsed.isEmpty() && cachedLogs.isEmpty()) {
            // Provide initial telemetry log if system logcat buffer is restricted
            parsed.add(
                LogcatItem(
                    id = nextId++,
                    timestamp = "SYSTEM",
                    pid = android.os.Process.myPid(),
                    tid = android.os.Process.myTid(),
                    level = "I",
                    tag = "ADBApp",
                    message = "Logcat reader initialized. Ready for ADB and app telemetry."
                )
            )
        }

        cachedLogs.clear()
        cachedLogs.addAll(parsed)
        cachedLogs.toList()
    }

    private fun parseLogLine(line: String): LogcatItem? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("---------")) return null

        val regex = Regex("""^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)\s+([VDIWEF])/([^\(:]+)(?:\(\s*(\d+)\))?:\s+(.*)$""")
        val match = regex.find(trimmed)
        return if (match != null) {
            val (ts, level, tag, pidStr, msg) = match.destructured
            val pid = pidStr.toIntOrNull() ?: 0
            LogcatItem(
                id = nextId++,
                timestamp = ts,
                pid = pid,
                tid = 0,
                level = level,
                tag = tag.trim(),
                message = msg.trim()
            )
        } else {
            LogcatItem(
                id = nextId++,
                timestamp = "",
                pid = 0,
                tid = 0,
                level = "D",
                tag = "Log",
                message = trimmed
            )
        }
    }

    private fun shouldIncludeLevel(level: String, minLevel: String): Boolean {
        val order = listOf("V", "D", "I", "W", "E", "F")
        val itemIdx = order.indexOf(level).let { if (it == -1) 0 else it }
        val minIdx = order.indexOf(minLevel).let { if (it == -1) 0 else it }
        return itemIdx >= minIdx
    }

    suspend fun clearBuffer() {
        shellRepository.executeCommand("logcat -c", ShellMode.LOCAL_SHELL)
        cachedLogs.clear()
    }
}
