package com.bloatware.bingblop.util

import java.security.SecureRandom

/**
 * End-of-output token generator for shell streams.
 *
 * Prevents phantom termination or truncated listings caused by hostile or accidental
 * file names containing fixed sentinel markers (such as `__SDM_END__` or line breaks).
 *
 * Ported from and aligns with SD Maid framing safeguards (C-005).
 */
object ShellSentinel {

    private val secureRandom = SecureRandom()

    /**
     * Generates a unique, cryptographically random sentinel line for a shell operation.
     * Guaranteed to have no line breaks and be unforgeable by file contents or directory names.
     */
    fun createToken(prefix: String = "__SDM_END_"): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${prefix}${hex}__"
    }

    /**
     * Checks if a line matches the generated sentinel token.
     */
    fun isSentinel(line: String?, token: String): Boolean {
        return line != null && line.trim() == token
    }
}
