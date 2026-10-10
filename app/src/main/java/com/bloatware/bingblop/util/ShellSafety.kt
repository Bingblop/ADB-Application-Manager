package com.bloatware.bingblop.util

/**
 * Shell safety and input sanitization utilities for privileged execution.
 *
 * Implements strict defense against argument injection, malicious metacharacters,
 * directory traversal, and false-positive execution outcomes (addressing C-002, C-005, C-009).
 */
object ShellSafety {

    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val COMPONENT_NAME_REGEX = Regex("^[a-zA-Z0-9_$.]+$")
    private val SETTING_KEY_REGEX = Regex("^[a-zA-Z0-9._-]+$")
    private val HOST_OR_IP_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$|^[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")
    private val NUMERIC_REGEX = Regex("^[0-9]+$")

    /**
     * Safely wraps an argument in single quotes for POSIX sh/bash execution,
     * escaping any embedded single quotes.
     */
    fun quoteArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    /**
     * Validates whether a string conforms to a strict Android package name specification.
     * Rejects empty strings, path traversal, semicolons, backticks, spaces, and shell metacharacters.
     */
    fun isValidPackageName(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg.length > 256) return false
        return pkg.matches(PACKAGE_NAME_REGEX)
    }

    /**
     * Validates whether a component name (Activity/Service/Receiver) is safe.
     */
    fun isValidComponentName(component: String?): Boolean {
        if (component.isNullOrBlank()) return false
        if (component.length > 300) return false
        return component.matches(COMPONENT_NAME_REGEX)
    }

    /**
     * Validates whether a DNS host or IP address is strictly alphanumeric with dots and hyphens.
     */
    fun isValidHostnameOrIp(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        if (host.length > 253) return false
        return host.matches(HOST_OR_IP_REGEX)
    }

    /**
     * Validates Android settings provider keys (system, secure, global).
     */
    fun isValidSettingKey(key: String?): Boolean {
        if (key.isNullOrBlank()) return false
        if (key.length > 128) return false
        return key.matches(SETTING_KEY_REGEX)
    }

    /**
     * Validates strictly positive integer strings (e.g., PIDs, UIDs).
     */
    fun isValidNumeric(str: String?): Boolean {
        if (str.isNullOrBlank()) return false
        return str.matches(NUMERIC_REGEX)
    }

    /**
     * Checks if a filesystem path is safe against path traversal (../)
     * and is restricted to designated allowed root directories.
     */
    fun isSafePath(path: String?, allowedPrefixes: List<String>): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.contains("..") || path.contains("\u0000")) return false
        val normalized = java.io.File(path).normalize().path
        return allowedPrefixes.any { prefix ->
            val normPrefix = java.io.File(prefix).normalize().path
            normalized == normPrefix || normalized.startsWith(normPrefix + "/")
        }
    }

    /**
     * Outcome parsing based on C-002 specification:
     * Never report success when exit code is non-zero, or when raw output starts with or contains
     * critical error indicators such as "Error:", "error:", "adb: error:", "[Process timed out",
     * or "Permission Denial".
     */
    fun parseShellOutcome(exitCode: Int, stdout: String, stderr: String): ShellOutcome {
        val combined = when {
            stdout.isNotBlank() && stderr.isNotBlank() -> "$stdout\n$stderr"
            stdout.isNotBlank() -> stdout
            else -> stderr
        }.trim()

        val hasFailureIndicator = exitCode != 0 ||
                combined.startsWith("Error:", ignoreCase = true) ||
                combined.startsWith("error:", ignoreCase = false) ||
                combined.contains("adb: error:", ignoreCase = true) ||
                combined.contains("[Process timed out", ignoreCase = true) ||
                combined.contains("Permission Denial", ignoreCase = true) ||
                combined.contains("SecurityException", ignoreCase = true) ||
                combined.contains("Failure [", ignoreCase = true) ||
                combined.contains("Unknown package", ignoreCase = true) ||
                combined.contains("not found", ignoreCase = true)

        return if (hasFailureIndicator) {
            ShellOutcome.Failure(
                message = combined.ifEmpty { "Process terminated with exit code $exitCode" },
                exitCode = if (exitCode != 0) exitCode else -1
            )
        } else {
            ShellOutcome.Success(
                output = stdout.ifEmpty { combined },
                exitCode = exitCode
            )
        }
    }
}

sealed class ShellOutcome {
    data class Success(val output: String, val exitCode: Int) : ShellOutcome()
    data class Failure(val message: String, val exitCode: Int) : ShellOutcome() {
        val isTimeout: Boolean get() = message.contains("[Process timed out", ignoreCase = true)
        val isPermissionDenied: Boolean get() = message.contains("Permission Denial", ignoreCase = true) ||
                message.contains("SecurityException", ignoreCase = true)
    }
}
