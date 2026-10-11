package com.bloatware.bingblop.util

/**
 * Android Keystore vault interface and secure settings store (C-008).
 *
 * Keeps sensitive API keys (e.g. GitHub personal access tokens, VirusTotal API keys)
 * encrypted inside the Android KeyStore instead of plain SharedPreferences.
 *
 * Security guarantees:
 * 1. Vault First: Reads from Keystore vault first; migrates legacy plain values on first read.
 * 2. Fail-Closed: If the vault refuses or errors during write, the secret is NEVER stored in plaintext.
 * 3. Secure Cleanup: Plaintext copies are wiped immediately upon migration or update.
 */
class SecretSettings(
    private val vault: Vault,
    private val plain: Plain
) {
    interface Vault {
        fun get(id: String): String?
        @Throws(Exception::class)
        fun put(id: String, secret: String)
        fun remove(id: String)
    }

    interface Plain {
        fun get(name: String): String?
        fun remove(name: String)
    }

    companion object {
        const val GITHUB_TOKEN = "github_token"
        const val VT_KEY = "vt_key"
        const val VT_KEY_OK = "vt_key_ok"
        const val VT_API_KEY = "vt_api_key"
        private const val KV_PREFIX = "kv_"

        fun isSecretKv(key: String?): Boolean {
            return key == "vt_key" || key == "vt_key_ok" || key == "vt_api_key"
        }

        fun kvName(key: String): String {
            return "$KV_PREFIX$key"
        }
    }

    @Synchronized
    fun get(name: String): String {
        val v = vault.get(name)
        if (!v.isNullOrEmpty()) {
            plain.remove(name)
            return v
        }
        val old = plain.get(name)
        if (old.isNullOrEmpty()) return ""
        try {
            vault.put(name, old)
            plain.remove(name)
        } catch (_: Exception) {
            // Keep old in plain store if vault is temporarily unavailable to avoid loss
        }
        return old
    }

    @Synchronized
    fun put(name: String, secret: String): Boolean {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) {
            vault.remove(name)
            plain.remove(name)
            return true
        }
        val current = vault.get(name)
        if (trimmed == current) {
            plain.remove(name)
            return true
        }
        return try {
            vault.put(name, trimmed)
            plain.remove(name)
            true
        } catch (_: Exception) {
            vault.remove(name)
            plain.remove(name)
            false
        }
    }

    @Synchronized
    fun has(name: String): Boolean {
        return get(name).isNotEmpty()
    }
}
