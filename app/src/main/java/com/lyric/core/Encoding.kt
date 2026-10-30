package com.lyric.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.mozilla.universalchardet.UniversalDetector

object Encoding {
    val choices = listOf("自动", "UTF-8", "GB18030", "GBK", "UTF-16LE", "UTF-16BE", "Big5")

    fun detect(bytes: ByteArray): String {
        fun b(i: Int) = bytes.getOrNull(i)?.toInt()?.and(255)
        if (b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return "UTF-8"
        if (b(0) == 0xFF && b(1) == 0xFE) return "UTF-16LE"
        if (b(0) == 0xFE && b(1) == 0xFF) return "UTF-16BE"
        val sample = bytes.copyOfRange(0, minOf(bytes.size, 65536))
        // UTF-16 ASCII, spaces and line breaks expose an alternating-zero signature.
        if (sample.size > 8) {
            val even = sample.indices.count { it % 2 == 0 && sample[it] == 0.toByte() }
            val odd = sample.indices.count { it % 2 == 1 && sample[it] == 0.toByte() }
            if (odd > sample.size / 5 && even < sample.size / 20) return "UTF-16LE"
            if (even > sample.size / 5 && odd < sample.size / 20) return "UTF-16BE"
            if (bytes.size % 2 == 0) {
                if (odd >= 2 && odd >= even * 2) return "UTF-16LE"
                if (even >= 2 && even >= odd * 2) return "UTF-16BE"
            }
        }
        // Validate the full input: sampling may cut a multibyte UTF-8 character in half.
        if (
            runCatching {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                    true
                }
                .getOrDefault(false)
        )
            return "UTF-8"
        val detector = UniversalDetector(null)
        detector.handleData(sample, 0, sample.size)
        detector.dataEnd()
        val detected = detector.detectedCharset?.uppercase().orEmpty()
        return when {
            detected.contains("BIG5") -> "Big5"
            detected.startsWith("UTF-16") -> detected
            else -> "GB18030" // Superset of GBK; also decodes four-byte extension characters.
        }
    }

    fun decode(bytes: ByteArray, override: String? = null): Pair<String, String> {
        val encoding = override?.takeUnless { it == "自动" } ?: detect(bytes)
        val text =
            String(bytes, Charset.forName(encoding))
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
        return text to encoding
    }
}
