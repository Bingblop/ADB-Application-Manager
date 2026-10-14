package com.bloatware.bingblop.util

sealed class ShellOutcome {
    data class Success(val exitCode: Int, val output: String) : ShellOutcome()
    data class Failure(val exitCode: Int, val message: String) : ShellOutcome() {
        val isTimeout: Boolean get() = message.contains("timed out", ignoreCase = true)
        val isPermissionDenied: Boolean get() = message.contains("SecurityException", ignoreCase = true) ||
                message.contains("Permission Denial", ignoreCase = true) ||
                message.contains("unauthorized", ignoreCase = true)
    }
}

object ShellSafety {
    fun parseShellOutcome(rawExitCode: Int, stdout: String, stderr: String): ShellOutcome {
        val trimmedOut = stdout.trim()
        val trimmedErr = stderr.trim()
        val combined = when {
            trimmedOut.isEmpty() -> trimmedErr
            trimmedErr.isEmpty() -> trimmedOut
            else -> "$trimmedOut\n$trimmedErr"
        }

        val hasFailureIndicator = trimmedOut.startsWith("Error:") ||
                trimmedOut.startsWith("Failure [") ||
                trimmedErr.startsWith("Error:") ||
                trimmedOut.contains("adb: error:") ||
                trimmedErr.contains("adb: error:") ||
                trimmedErr.contains("[Process timed out") ||
                trimmedOut.contains("SecurityException") ||
                trimmedErr.contains("SecurityException") ||
                trimmedOut.contains("Permission Denial") ||
                trimmedErr.contains("Permission Denial") ||
                trimmedOut.contains("unauthorized") ||
                trimmedErr.contains("unauthorized")

        return if (rawExitCode == 0 && !hasFailureIndicator) {
            ShellOutcome.Success(rawExitCode, combined)
        } else {
            val code = if (rawExitCode == 0) 1 else rawExitCode
            val msg = if (combined.isEmpty()) "Command failed with exit code $rawExitCode" else combined
            ShellOutcome.Failure(code, msg)
        }
    }

    fun quoteArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}
