package com.bloatware.bingblop.util

/**
 * Security validation guards for package installation (addressing C-009).
 *
 * Implements strict "fail-closed" security policy:
 * 1. Malformed or blank published checksums strictly abort installation.
 * 2. Empty package names or mismatching identities strictly abort installation.
 * 3. Empty signer certificates or mismatching update signers strictly abort installation.
 */
object InstallGuards {

    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

    sealed class GuardResult {
        object Passed : GuardResult()
        data class Rejected(val reason: String, val code: RejectCode) : GuardResult()
    }

    enum class RejectCode {
        MALFORMED_CHECKSUM,
        CHECKSUM_MISMATCH,
        EMPTY_PACKAGE_NAME,
        PACKAGE_MISMATCH,
        MISSING_SIGNATURE,
        SIGNATURE_MISMATCH
    }

    /**
     * Validates file SHA-256 against a published checksum.
     * Fails closed: any malformed, blank, or mismatching checksum rejects the install.
     */
    fun verifyChecksum(computedSha256: String?, publishedSha256: String?): GuardResult {
        if (publishedSha256.isNullOrBlank()) {
            return GuardResult.Rejected("Published checksum is missing", RejectCode.MALFORMED_CHECKSUM)
        }
        val cleanPublished = publishedSha256.trim()
        if (!cleanPublished.matches(SHA256_HEX_REGEX)) {
            return GuardResult.Rejected("Published checksum is malformed: must be 64-character hex", RejectCode.MALFORMED_CHECKSUM)
        }
        if (computedSha256.isNullOrBlank() || !computedSha256.trim().matches(SHA256_HEX_REGEX)) {
            return GuardResult.Rejected("Computed file checksum is invalid or empty", RejectCode.MALFORMED_CHECKSUM)
        }
        if (!computedSha256.trim().equals(cleanPublished, ignoreCase = true)) {
            return GuardResult.Rejected(
                "Checksum mismatch: computed [${computedSha256.take(8)}...] vs published [${cleanPublished.take(8)}...]",
                RejectCode.CHECKSUM_MISMATCH
            )
        }
        return GuardResult.Passed
    }

    /**
     * Validates package identity.
     * Fails closed: empty or non-matching declared package name rejects the install.
     */
    fun verifyPackageIdentity(declaredPackage: String?, targetPackage: String? = null): GuardResult {
        if (!ShellSafety.isValidPackageName(declaredPackage)) {
            return GuardResult.Rejected("Declared package name is missing or invalid: $declaredPackage", RejectCode.EMPTY_PACKAGE_NAME)
        }
        if (targetPackage != null) {
            if (!ShellSafety.isValidPackageName(targetPackage)) {
                return GuardResult.Rejected("Target package name is invalid: $targetPackage", RejectCode.PACKAGE_MISMATCH)
            }
            if (declaredPackage != targetPackage) {
                return GuardResult.Rejected(
                    "Package mismatch: declared ($declaredPackage) != target ($targetPackage)",
                    RejectCode.PACKAGE_MISMATCH
                )
            }
        }
        return GuardResult.Passed
    }

    /**
     * Validates signer certificates against existing installed signers.
     * Fails closed: empty incoming signers or unreadable/mismatched existing signers reject the install.
     */
    fun verifySigners(newSignersSha256: List<String>, existingSignersSha256: List<String> = emptyList()): GuardResult {
        if (newSignersSha256.isEmpty()) {
            return GuardResult.Rejected("Package contains no valid signer certificates", RejectCode.MISSING_SIGNATURE)
        }
        for (sig in newSignersSha256) {
            if (!sig.matches(SHA256_HEX_REGEX)) {
                return GuardResult.Rejected("Signer fingerprint is malformed: $sig", RejectCode.MISSING_SIGNATURE)
            }
        }
        if (existingSignersSha256.isNotEmpty()) {
            val newSet = newSignersSha256.map { it.lowercase() }.toSet()
            val existingSet = existingSignersSha256.map { it.lowercase() }.toSet()
            // Android requires that the update matches the existing signing key
            val hasCommonSigner = newSet.intersect(existingSet).isNotEmpty()
            if (!hasCommonSigner) {
                return GuardResult.Rejected(
                    "Signer certificate mismatch: cannot update existing application with different signature",
                    RejectCode.SIGNATURE_MISMATCH
                )
            }
        }
        return GuardResult.Passed
    }
}
