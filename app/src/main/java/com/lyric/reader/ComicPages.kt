package com.lyric.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Random-access page decoder over the private CBZ copy. Thread-safe; bounded memory. */
class ComicArchive(file: File, private val sampleEntry: String? = null) : Closeable {
    private val zip: ZipFile = openMatching(file, sampleEntry)
    // ~1/8 of heap for decoded pages (current ±2 preloaded fit comfortably).
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun bytes(entry: String): ByteArray = synchronized(zip) {
        val e = zip.getEntry(entry) ?: error("${entry.substringAfterLast('/')} · 压缩包中找不到该图片")
        require(e.size <= 64L * 1024 * 1024) { "${entry.substringAfterLast('/')} · 图片过大" }
        zip.getInputStream(e).use { it.readBytes() }
    }

    /** Decodes at most [maxWidth] wide (power-of-two sampling); null bitmap → explicit error. */
    fun page(entry: String, maxWidth: Int, maxHeight: Int = Int.MAX_VALUE): Bitmap {
        val key = "$entry@$maxWidth"
        cache.get(key)?.let { return it }
        val data = bytes(entry)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        require(bounds.outWidth > 0) { "${entry.substringAfterLast('/')} · 无法解码图片" }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight ||
            (bounds.outWidth.toLong() / sample) * (bounds.outHeight / sample) * 4 > 48L * 1024 * 1024) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: error("${entry.substringAfterLast('/')} · 图片已损坏")
        cache.put(key, bmp)
        return bmp
    }

    override fun close() { cache.evictAll(); synchronized(zip) { zip.close() } }

    companion object {
        fun openMatching(file: File, entry: String?): ZipFile {
            for (cs in listOf("UTF-8", "GBK", "Cp437")) {
                val z = runCatching { ZipFile(file, Charset.forName(cs)) }.getOrNull() ?: continue
                if (entry == null || z.getEntry(entry) != null) return z
                z.close()
            }
            error("无法打开漫画压缩包")
        }
    }
}

object ComicPages {
    fun thumbnail(file: File, entry: String, maxWidth: Int): ByteArray? = runCatching {
        ComicArchive(file, entry).use { archive ->
            val bmp = archive.page(entry, maxWidth)
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        }
    }.getOrNull()
}
