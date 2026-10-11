package com.bloatware.bingblop.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallGuardsTest {

    private val validHashA = "a".repeat(64)
    private val validHashB = "b".repeat(64)

    @Test
    fun testChecksumVerificationFailsClosedOnMissingOrMalformed() {
        // Missing published checksum rejects
        val resNull = InstallGuards.verifyChecksum(validHashA, null)
        assertTrue(resNull is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.MALFORMED_CHECKSUM, (resNull as InstallGuards.GuardResult.Rejected).code)

        // Blank published checksum rejects
        val resBlank = InstallGuards.verifyChecksum(validHashA, "   ")
        assertTrue(resBlank is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.MALFORMED_CHECKSUM, (resBlank as InstallGuards.GuardResult.Rejected).code)

        // Short hex (e.g. truncated or invalid length) rejects
        val resShort = InstallGuards.verifyChecksum(validHashA, "abcdef1234")
        assertTrue(resShort is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.MALFORMED_CHECKSUM, (resShort as InstallGuards.GuardResult.Rejected).code)

        // Non-hex characters reject
        val resNonHex = InstallGuards.verifyChecksum(validHashA, "z".repeat(64))
        assertTrue(resNonHex is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.MALFORMED_CHECKSUM, (resNonHex as InstallGuards.GuardResult.Rejected).code)
    }

    @Test
    fun testChecksumMismatchRejects() {
        val resMismatch = InstallGuards.verifyChecksum(validHashA, validHashB)
        assertTrue(resMismatch is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.CHECKSUM_MISMATCH, (resMismatch as InstallGuards.GuardResult.Rejected).code)
    }

    @Test
    fun testChecksumMatchPasses() {
        val resMatch = InstallGuards.verifyChecksum(validHashA, validHashA.uppercase())
        assertTrue(resMatch is InstallGuards.GuardResult.Passed)
    }

    @Test
    fun testPackageIdentityFailsClosedOnEmptyOrMismatch() {
        // Empty declared package rejects
        val resEmpty = InstallGuards.verifyPackageIdentity("", null)
        assertTrue(resEmpty is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.EMPTY_PACKAGE_NAME, (resEmpty as InstallGuards.GuardResult.Rejected).code)

        // Invalid package name rejects
        val resInvalid = InstallGuards.verifyPackageIdentity("invalid; injection", null)
        assertTrue(resInvalid is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.EMPTY_PACKAGE_NAME, (resInvalid as InstallGuards.GuardResult.Rejected).code)

        // Mismatched package rejects
        val resMismatch = InstallGuards.verifyPackageIdentity("com.example.app", "com.example.other")
        assertTrue(resMismatch is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.PACKAGE_MISMATCH, (resMismatch as InstallGuards.GuardResult.Rejected).code)

        // Valid match passes
        val resMatch = InstallGuards.verifyPackageIdentity("com.example.app", "com.example.app")
        assertTrue(resMatch is InstallGuards.GuardResult.Passed)
    }

    @Test
    fun testSignersFailsClosedOnEmptyOrMismatch() {
        // Empty signers list rejects
        val resEmpty = InstallGuards.verifySigners(emptyList(), emptyList())
        assertTrue(resEmpty is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.MISSING_SIGNATURE, (resEmpty as InstallGuards.GuardResult.Rejected).code)

        // Malformed fingerprint rejects
        val resMalformed = InstallGuards.verifySigners(listOf("invalid_fp"), emptyList())
        assertTrue(resMalformed is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.MISSING_SIGNATURE, (resMalformed as InstallGuards.GuardResult.Rejected).code)

        // Signature mismatch on update rejects
        val resMismatch = InstallGuards.verifySigners(listOf(validHashA), listOf(validHashB))
        assertTrue(resMismatch is InstallGuards.GuardResult.Rejected)
        assertEquals(InstallGuards.RejectCode.SIGNATURE_MISMATCH, (resMismatch as InstallGuards.GuardResult.Rejected).code)

        // Matching signature on update passes
        val resMatch = InstallGuards.verifySigners(listOf(validHashA), listOf(validHashA))
        assertTrue(resMatch is InstallGuards.GuardResult.Passed)
    }
}
