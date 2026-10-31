package com.lyric.reader

import android.graphics.Bitmap
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.*

/** Zero-based physical pages. Cover stays alone even with offset enabled. */
object Spreads {
    /** Per-book choice wins; otherwise spreads default on only for large screens (sw >= 600dp). */
    fun enabled(choice: Boolean?, largeScreen: Boolean) = choice ?: largeScreen
    fun pair(count: Int, landscape: Boolean, enabled: Boolean, offset: Boolean): List<List<Int>> {
        if (count <= 0) return emptyList()
        if (!landscape || !enabled) return (0 until count).map { listOf(it) }
        val result = mutableListOf(listOf(0))
        var page = 1
        if (offset && page < count) result += listOf(page++)
        while (page < count) { result += (page until minOf(page + 2, count)).toList(); page += 2 }
        return result
    }
    fun indexOf(spreads: List<List<Int>>, page: Int) = spreads.indexOfFirst { page in it }.coerceAtLeast(0)
}

data class PageCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f)

object PageTrim {
    // Weak bitmap identities are revision/size safe and cannot keep evicted page bitmaps alive.
    private val cache = Collections.synchronizedMap(WeakHashMap<Bitmap, PageCrop>())
    fun detect(bitmap: Bitmap): PageCrop = cache[bitmap] ?: detectUncached(bitmap).also { cache[bitmap] = it }
    private fun detectUncached(bitmap: Bitmap): PageCrop {
        val factor = minOf(1f, 384f / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * factor).roundToInt()); val h = max(1, (bitmap.height * factor).roundToInt())
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h); small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val candidate = detect(pixels, w, h)
        if (candidate == PageCrop()) return candidate
        // Validate proposed bands at source resolution: tiny marks lost in downsampling must survive.
        val left = (candidate.left * bitmap.width).toInt(); val top = (candidate.top * bitmap.height).toInt()
        val right = ceil(candidate.right * bitmap.width).toInt(); val bottom = ceil(candidate.bottom * bitmap.height).toInt()
        val white = near(pixels[0], true)
        val row = IntArray(bitmap.width)
        var l = left; var t = top; var r = right; var b = bottom
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) if ((x < left || x >= right || y < top || y >= bottom) && !near(row[x], white)) {
                l = min(l, x); r = max(r, x + 1); t = min(t, y); b = max(b, y + 1)
            }
        }
        return PageCrop(max(0, l - 2).toFloat() / bitmap.width, max(0, t - 2).toFloat() / bitmap.height,
            min(bitmap.width, r + 2).toFloat() / bitmap.width, min(bitmap.height, b + 2).toFloat() / bitmap.height)
    }
    private fun near(c: Int, white: Boolean): Boolean {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        return if (white) minOf(r, g, b) >= 240 else maxOf(r, g, b) <= 15
    }
    /** Tolerates near-white/black scan noise, never classifies grey or colored edges as margins. */
    fun detect(pixels: IntArray, width: Int, height: Int): PageCrop {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val white = near(pixels[0], true)
        if (!white && !near(pixels[0], false)) return PageCrop()
        var left = width; var top = height; var right = 0; var bottom = 0
        pixels.forEachIndexed { index, c -> if (!near(c, white)) {
            val x = index % width; val y = index / width
            left = min(left, x); right = max(right, x + 1); top = min(top, y); bottom = max(bottom, y + 1)
        } }
        // Blank pages and aggressive/ambiguous crops are left intact.
        if (right <= left || bottom <= top || right - left < width / 4 || bottom - top < height / 4) return PageCrop()
        val guard = 3
        return PageCrop(max(0, left - guard).toFloat() / width, max(0, top - guard).toFloat() / height,
            min(width, right + guard).toFloat() / width, min(height, bottom + guard).toFloat() / height)
    }
}
