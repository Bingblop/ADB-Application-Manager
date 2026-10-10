package com.bloatware.bingblop.data.repository

import com.bloatware.bingblop.data.model.LogcatEntry
import com.bloatware.bingblop.data.model.LogcatLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class LogcatRepository {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var streamJob: Job? = null
    private var process: Process? = null

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _entries = MutableStateFlow<List<LogcatEntry>>(emptyList())
    val entries: StateFlow<List<LogcatEntry>> = _entries.asStateFlow()

    private val buffer = ArrayList<LogcatEntry>(MAX_BUFFER_SIZE + 50)
    private val idCounter = AtomicLong(0L)

    // Regex for: "MM-dd HH:mm:ss.SSS L/Tag(PID): message" or "MM-dd HH:mm:ss.SSS PID TID L Tag: message"
    private val patternTime = Pattern.compile("^([0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]+)\\s+([VDIWEF])/([^(:\\s]+)(?:\\s*\\(\\s*([0-9]+)\\))?:\\s*(.*)$")
    private val patternThreadTime = Pattern.compile("^([0-9]{2}-[0-9]{2}\\s+[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]+)\\s+([0-9]+)\\s+([0-9]+)\\s+([VDIWEF])\\s+([^:]+):\\s*(.*)$")
    private val patternBrief = Pattern.compile("^([VDIWEF])/([^(:\\s]+)(?:\\s*\\(\\s*([0-9]+)\\))?:\\s*(.*)$")

    companion object {
        const val MAX_BUFFER_SIZE = 800
        const val BATCH_FLUSH_INTERVAL_MS = 80L
    }

    init {
        startStreaming()
    }

    @Synchronized
    fun startStreaming() {
        if (_isStreaming.value && streamJob?.isActive == true) return
        _isStreaming.value = true

        streamJob = scope.launch {
            try {
                // Clear and stream with threadtime or time format
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time"))
                process = proc

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                var lastFlushTime = System.currentTimeMillis()
                val pendingBatch = ArrayList<LogcatEntry>(64)

                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() || line.startsWith("--------- beginning of")) continue

                    val entry = parseLogLine(line)
                    pendingBatch.add(entry)

                    val now = System.currentTimeMillis()
                    if (now - lastFlushTime >= BATCH_FLUSH_INTERVAL_MS || pendingBatch.size >= 40) {
                        flushBatch(pendingBatch)
                        pendingBatch.clear()
                        lastFlushTime = now
                    }
                }

                if (pendingBatch.isNotEmpty()) {
                    flushBatch(pendingBatch)
                    pendingBatch.clear()
                }
            } catch (_: Exception) {
            } finally {
                _isStreaming.value = false
            }
        }
    }

    private fun flushBatch(batch: List<LogcatEntry>) {
        synchronized(buffer) {
            buffer.addAll(batch)
            if (buffer.size > MAX_BUFFER_SIZE) {
                val excess = buffer.size - MAX_BUFFER_SIZE
                buffer.subList(0, excess).clear()
            }
            _entries.value = ArrayList(buffer)
        }
    }

    @Synchronized
    fun pauseStreaming() {
        _isStreaming.value = false
        streamJob?.cancel()
        streamJob = null
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
    }

    @Synchronized
    fun destroy() {
        pauseStreaming()
        try {
            scope.cancel()
        } catch (_: Exception) {}
    }

    fun toggleStreaming() {
        if (_isStreaming.value) {
            pauseStreaming()
        } else {
            startStreaming()
        }
    }

    fun clearBuffer() {
        synchronized(buffer) {
            buffer.clear()
            _entries.value = emptyList()
        }
        scope.launch {
            try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
            } catch (_: Exception) {}
        }
    }

    fun getExportText(): String {
        synchronized(buffer) {
            return buffer.joinToString(separator = "\n") { entry ->
                "${entry.timestamp} ${entry.level.code}/${entry.tag}(${entry.pid}): ${entry.message}"
            }
        }
    }

    private fun parseLogLine(line: String): LogcatEntry {
        val id = idCounter.incrementAndGet()

        // Try patternTime
        val mTime = patternTime.matcher(line)
        if (mTime.find()) {
            val ts = mTime.group(1) ?: ""
            val lvl = parseLevel(mTime.group(2))
            val tag = mTime.group(3)?.trim() ?: "System"
            val pid = mTime.group(4) ?: "-"
            val msg = mTime.group(5) ?: ""
            return LogcatEntry(id, ts, pid, "", lvl, tag, msg, line)
        }

        // Try patternThreadTime
        val mThread = patternThreadTime.matcher(line)
        if (mThread.find()) {
            val ts = mThread.group(1) ?: ""
            val pid = mThread.group(2) ?: "-"
            val tid = mThread.group(3) ?: "-"
            val lvl = parseLevel(mThread.group(4))
            val tag = mThread.group(5)?.trim() ?: "System"
            val msg = mThread.group(6) ?: ""
            return LogcatEntry(id, ts, pid, tid, lvl, tag, msg, line)
        }

        // Try patternBrief
        val mBrief = patternBrief.matcher(line)
        if (mBrief.find()) {
            val lvl = parseLevel(mBrief.group(1))
            val tag = mBrief.group(2)?.trim() ?: "System"
            val pid = mBrief.group(3) ?: "-"
            val msg = mBrief.group(4) ?: ""
            return LogcatEntry(id, "", pid, "", lvl, tag, msg, line)
        }

        return LogcatEntry(id, "", "-", "", LogcatLevel.VERBOSE, "Log", line, line)
    }

    private fun parseLevel(code: String?): LogcatLevel {
        return when (code) {
            "V" -> LogcatLevel.VERBOSE
            "D" -> LogcatLevel.DEBUG
            "I" -> LogcatLevel.INFO
            "W" -> LogcatLevel.WARN
            "E" -> LogcatLevel.ERROR
            "F", "A" -> LogcatLevel.FATAL
            else -> LogcatLevel.INFO
        }
    }

    suspend fun exportLogs(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val targetDir = java.io.File("/sdcard/Download/ADBManager")
            targetDir.mkdirs()
            val file = java.io.File(targetDir, "logcat_${System.currentTimeMillis()}.txt")
            val lines = synchronized(buffer) { buffer.map { it.raw } }
            file.bufferedWriter().use { writer ->
                lines.forEach { line ->
                    writer.write(line)
                    writer.newLine()
                }
            }
            Result.success(file.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
