package com.bloatware.bingblop.util

import java.net.URI

/**
 * Validates that API requests carrying sensitive VirusTotal keys are directed exclusively
 * to the official HTTPS VirusTotal endpoint, preventing credential leakage (C-008).
 */
object VirusTotalHost {
    private const val DEFAULT_API_BASE = "https://www.virustotal.com/api/v3"

    fun isApiHost(targetUrl: String?, apiBase: String = DEFAULT_API_BASE, allowInsecureForTests: Boolean = false): Boolean {
        if (targetUrl.isNullOrEmpty()) return false
        return try {
            val u = URI(targetUrl)
            val api = URI(apiBase)

            if (!allowInsecureForTests && !u.scheme.equals("https", ignoreCase = true)) {
                return false
            }
            if (!u.scheme.equals(api.scheme, ignoreCase = true)) {
                return false
            }
            if (u.userInfo != null) {
                return false
            }

            val uPort = if (u.port == -1) (if (u.scheme.equals("https", true)) 443 else 80) else u.port
            val apiPort = if (api.port == -1) (if (api.scheme.equals("https", true)) 443 else 80) else api.port

            u.host != null && u.host.equals(api.host, ignoreCase = true) && uPort == apiPort
        } catch (_: Exception) {
            false
        }
    }
}
