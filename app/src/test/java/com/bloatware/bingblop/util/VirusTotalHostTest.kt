package com.bloatware.bingblop.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirusTotalHostTest {

    @Test
    fun testValidVirusTotalApiEndpoints() {
        assertTrue(VirusTotalHost.isApiHost("https://www.virustotal.com/api/v3/files"))
        assertTrue(VirusTotalHost.isApiHost("https://www.virustotal.com/api/v3/files/upload_url"))
        assertTrue(VirusTotalHost.isApiHost("https://www.virustotal.com/gui/file/abc"))
    }

    @Test
    fun testRejectsInsecureHttp() {
        // Plain HTTP must be rejected
        assertFalse(VirusTotalHost.isApiHost("http://www.virustotal.com/api/v3/files"))
    }

    @Test
    fun testRejectsLookalikeOrAttackerHosts() {
        assertFalse(VirusTotalHost.isApiHost("https://evil.com/api/v3/files"))
        assertFalse(VirusTotalHost.isApiHost("https://virustotal.com.evil.com/api/v3/files"))
        assertFalse(VirusTotalHost.isApiHost("https://www.virustotal.com:8443/api/v3/files"))
        assertFalse(VirusTotalHost.isApiHost("https://user:pass@www.virustotal.com/api/v3/files"))
        assertFalse(VirusTotalHost.isApiHost("https://127.0.0.1/api/v3/files"))
    }

    @Test
    fun testNullAndMalformedHandling() {
        assertFalse(VirusTotalHost.isApiHost(null))
        assertFalse(VirusTotalHost.isApiHost(""))
        assertFalse(VirusTotalHost.isApiHost("not-a-valid-url"))
    }
}
