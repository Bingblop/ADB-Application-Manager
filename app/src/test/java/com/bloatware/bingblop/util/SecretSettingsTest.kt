package com.bloatware.bingblop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretSettingsTest {

    private class FakeVault : SecretSettings.Vault {
        val store = mutableMapOf<String, String>()
        var shouldFail = false

        override fun get(id: String): String? = store[id]

        override fun put(id: String, secret: String) {
            if (shouldFail) throw RuntimeException("Keystore unavailable")
            store[id] = secret
        }

        override fun remove(id: String) {
            store.remove(id)
        }
    }

    private class FakePlain : SecretSettings.Plain {
        val store = mutableMapOf<String, String>()

        override fun get(name: String): String? = store[name]

        override fun remove(name: String) {
            store.remove(name)
        }
    }

    @Test
    fun testSecretKeyClassification() {
        assertTrue(SecretSettings.isSecretKv("vt_key"))
        assertTrue(SecretSettings.isSecretKv("vt_key_ok"))
        assertTrue(SecretSettings.isSecretKv("vt_api_key"))

        assertFalse(SecretSettings.isSecretKv("theme"))
        assertFalse(SecretSettings.isSecretKv("language"))
        assertFalse(SecretSettings.isSecretKv(null))
        assertEquals("kv_vt_key", SecretSettings.kvName("vt_key"))
    }

    @Test
    fun testPutAndGetFromVault() {
        val vault = FakeVault()
        val plain = FakePlain()
        val settings = SecretSettings(vault, plain)

        assertTrue(settings.put("github_token", "  ghp_secret_token  "))
        assertEquals("ghp_secret_token", vault.store["github_token"])
        assertTrue(plain.store.isEmpty())

        assertEquals("ghp_secret_token", settings.get("github_token"))
        assertTrue(settings.has("github_token"))
    }

    @Test
    fun testMigrationFromPlainToVault() {
        val vault = FakeVault()
        val plain = FakePlain()
        val settings = SecretSettings(vault, plain)

        plain.store["kv_vt_key"] = "plain_vt_secret"

        // First read migrates the plain value into the vault
        val retrieved = settings.get("kv_vt_key")
        assertEquals("plain_vt_secret", retrieved)
        assertEquals("plain_vt_secret", vault.store["kv_vt_key"])
        assertFalse(plain.store.containsKey("kv_vt_key"))
    }

    @Test
    fun testFailClosedWhenVaultRejects() {
        val vault = FakeVault()
        val plain = FakePlain()
        val settings = SecretSettings(vault, plain)

        vault.shouldFail = true
        plain.store["github_token"] = "old_plain"

        // Attempt to put new secret when vault fails
        val success = settings.put("github_token", "new_secret")
        assertFalse(success)

        // Verifies fail-closed: new secret is NEVER kept in plain text
        assertFalse(plain.store.containsKey("github_token"))
        assertFalse(vault.store.containsKey("github_token"))
    }

    @Test
    fun testIdempotentWriteWhenAlreadyStored() {
        val vault = FakeVault()
        val plain = FakePlain()
        val settings = SecretSettings(vault, plain)

        assertTrue(settings.put("vt_key", "same_secret"))
        vault.shouldFail = true

        // Re-saving the identical secret succeeds even if vault write would otherwise fail
        assertTrue(settings.put("vt_key", "same_secret"))
        assertEquals("same_secret", settings.get("vt_key"))
    }
}
