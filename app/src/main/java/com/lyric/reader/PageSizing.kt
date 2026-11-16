package com.lyric.reader

/** Keep the existing power-of-two quality policy, with bounded arithmetic. */
internal fun pageSampleSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Int {
    require(width > 0 && height > 0 && maxWidth > 0 && maxHeight > 0)
    var sample = 1
    while (sample < (1 shl 30)) {
        val next = sample * 2
        // Two Int dimensions fit in Long; multiplying that product by four may not.
        val oversized = (width.toLong() / sample) * (height / sample) > 48L * 1024 * 1024 / 4
        if (width / next < maxWidth && height / next < maxHeight && !oversized) break
        sample = next
    }
    return sample
}

/** PDF pages fit the requested viewport, with the existing width/byte safety caps. */
internal fun pdfPageSize(width: Int, height: Int, maxWidth: Int, maxHeight: Int, maxBytes: Long = 48L * 1024 * 1024): Pair<Int, Int> {
    require(width > 0 && height > 0 && maxWidth > 0 && maxHeight > 0)
    val scale = minOf(maxWidth.coerceIn(64, 2400).toDouble() / width,
        maxHeight.toDouble() / height,
        kotlin.math.sqrt(maxBytes.toDouble() / (width.toDouble() * height * 4)))
    val w = (width * scale).toInt().coerceIn(1, (maxBytes / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    val h = (height * scale).toInt().coerceIn(1, (maxBytes / (w * 4L)).toInt())
    return w to h
}
