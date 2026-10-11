package com.bloatware.bingblop.data

import com.bloatware.bingblop.util.ShellSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSafetyTest {

    @Test
    fun testValidSettingKeys() {
        assertTrue(ShellSafety.isValidSettingKey("animator_duration_scale"))
        assertTrue(ShellSafety.isValidSettingKey("window_animation_scale"))
        assertTrue(ShellSafety.isValidSettingKey("transition_animation_scale"))
        assertTrue(ShellSafety.isValidSettingKey("private_dns_mode"))
        assertTrue(ShellSafety.isValidSettingKey("private_dns_specifier"))
        assertTrue(ShellSafety.isValidSettingKey("bluetooth_ldac_quality"))
    }

    @Test
    fun testDangerousSettingKeysRejected() {
        assertFalse(ShellSafety.isValidSettingKey("scale; reboot"))
        assertFalse(ShellSafety.isValidSettingKey("scale && rm -rf /"))
        assertFalse(ShellSafety.isValidSettingKey("scale`id`"))
        assertFalse(ShellSafety.isValidSettingKey("scale\nreboot"))
        assertFalse(ShellSafety.isValidSettingKey("scale\rreboot"))
        assertFalse(ShellSafety.isValidSettingKey("scale\" injection"))
        assertFalse(ShellSafety.isValidSettingKey(""))
        assertFalse(ShellSafety.isValidSettingKey("   "))
    }

    @Test
    fun testSettingValueShellEscaping() {
        val valueWithQuotes = "test'value\"injection"
        val quoted = ShellSafety.quoteArg(valueWithQuotes)
        assertTrue(quoted.startsWith("'"))
        assertTrue(quoted.endsWith("'"))
        assertEquals("'test'\\''value\"injection'", quoted)
    }

    @Test
    fun testPrivateDnsHostnameValidation() {
        val hostnameRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")

        // Valid hostnames
        assertTrue("dns.google".matches(hostnameRegex))
        assertTrue("one.one.one.one".matches(hostnameRegex))
        assertTrue("dns.adguard-dns.com".matches(hostnameRegex))
        assertTrue("pki.example.com".matches(hostnameRegex))

        // Injections & invalid hostnames
        assertFalse("\"; reboot; #".matches(hostnameRegex))
        assertFalse("dns.google; id".matches(hostnameRegex))
        assertFalse("dns.google | rm -rf".matches(hostnameRegex))
        assertFalse("dns.google\nreboot".matches(hostnameRegex))
        assertFalse("".matches(hostnameRegex))
    }
}
