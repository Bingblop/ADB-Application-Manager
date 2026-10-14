package com.bloatware.bingblop.util

object InstallGuards {
    sealed class GuardResult {
        object Passed : GuardResult()
        data class Rejected(val reason: String) : GuardResult()
    }

    fun verifyPackageIdentity(packageName: String): GuardResult {
        val trimmed = packageName.trim()
        if (trimmed.isEmpty()) return GuardResult.Rejected("Package name cannot be empty")
        if (trimmed.contains(" ") || trimmed.contains(";") || trimmed.contains("&") ||
            trimmed.contains("|") || trimmed.contains("$") || trimmed.contains("`") ||
            trimmed.contains("\u0000") || trimmed.contains("..") || trimmed.contains("/") ||
            trimmed.contains("\n") || trimmed.contains("\r")
        ) {
            return GuardResult.Rejected("Package contains illegal or shell-injection characters")
        }
        val regex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        if (!regex.matches(trimmed)) {
            return GuardResult.Rejected("Invalid Android package naming convention: $trimmed")
        }
        return GuardResult.Passed
    }
}
