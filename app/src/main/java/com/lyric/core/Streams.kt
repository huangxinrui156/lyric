package com.lyric.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded read compatible with Android 26 (InputStream.readNBytes requires newer Android). */
fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16384)
    var remaining = limit
    while (remaining > 0) {
        ImportProgress.check()
        val count = read(buffer, 0, minOf(buffer.size, remaining))
        if (count < 0) break
        if (count > 0) {
            output.write(buffer, 0, count)
            remaining -= count
        }
    }
    return output.toByteArray()
}
