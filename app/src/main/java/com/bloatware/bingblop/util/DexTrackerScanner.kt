package com.bloatware.bingblop.util

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * High-performance DEX scanner that inspects embedded class descriptors in APKs
 * against Exodus Privacy tracker code signatures (C-008, Review #8).
 */
object DexTrackerScanner {

    fun scanApkForTrackers(
        apkFile: File,
        trackers: List<Pair<String, List<String>>>
    ): List<String> {
        if (!apkFile.exists() || !apkFile.canRead() || trackers.isEmpty()) return emptyList()
        val foundTrackers = mutableSetOf<String>()
        try {
            ZipFile(apkFile).use { zip ->
                val dexEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
                    .take(5)
                    .toList()

                val sigToTracker = mutableListOf<Pair<String, String>>()
                for ((trackerName, signatures) in trackers) {
                    for (sig in signatures) {
                        val dexDescriptorPrefix = "L" + sig.replace('.', '/')
                        sigToTracker.add(dexDescriptorPrefix to trackerName)
                    }
                }

                for (entry in dexEntries) {
                    zip.getInputStream(entry).use { stream ->
                        val matched = scanDexStreamForSignatures(stream, sigToTracker)
                        foundTrackers.addAll(matched)
                    }
                    if (foundTrackers.size == trackers.size) break
                }
            }
        } catch (_: Exception) {}
        return foundTrackers.toList().sorted()
    }

    private fun scanDexStreamForSignatures(
        stream: InputStream,
        signatures: List<Pair<String, String>>
    ): Set<String> {
        val matches = mutableSetOf<String>()
        val header = ByteArray(0x70)
        var read = 0
        while (read < header.size) {
            val count = stream.read(header, read, header.size - read)
            if (count < 0) return matches
            read += count
        }

        // Verify dex magic
        if (header[0] != 'd'.code.toByte() || header[1] != 'e'.code.toByte() ||
            header[2] != 'x'.code.toByte() || header[3] != '\n'.code.toByte()
        ) {
            return matches
        }

        val buf = ByteArray(65536)
        var carryOver = 0
        var bytesRead: Int

        val bytePatterns = signatures.map { (sig, tracker) ->
            sig.toByteArray(Charsets.US_ASCII) to tracker
        }

        while (stream.read(buf, carryOver, buf.size - carryOver).also { bytesRead = it } > 0) {
            val totalInBuf = carryOver + bytesRead
            for ((pattern, tracker) in bytePatterns) {
                if (matches.contains(tracker)) continue
                if (indexOfSubarray(buf, totalInBuf, pattern) >= 0) {
                    matches.add(tracker)
                }
            }
            val maxPatternLen = 128
            if (totalInBuf > maxPatternLen) {
                System.arraycopy(buf, totalInBuf - maxPatternLen, buf, 0, maxPatternLen)
                carryOver = maxPatternLen
            } else {
                carryOver = 0
            }
        }
        return matches
    }

    private fun indexOfSubarray(array: ByteArray, arrayLength: Int, target: ByteArray): Int {
        if (target.isEmpty() || target.size > arrayLength) return -1
        outer@ for (i in 0..arrayLength - target.size) {
            for (j in target.indices) {
                if (array[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
