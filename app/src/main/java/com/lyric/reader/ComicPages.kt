package com.lyric.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** Random-access page decoder over the private CBZ copy. Thread-safe; bounded memory. */
class ComicArchive internal constructor(file: File, private val sampleEntry: String?, private val budget: PageBitmapBudget) : Closeable, PageSource {
    private val zip: ZipFile = openMatching(file, sampleEntry)
    constructor(file: File, sampleEntry: String? = null) : this(file, sampleEntry, PageBitmapBudget.shared)
    private val prefix = PageBitmapBudget.sourceKey()

    fun bytes(entry: String): ByteArray = synchronized(zip) {
        val e = zip.getEntry(entry) ?: error("${entry.substringAfterLast('/')} · 压缩包中找不到该图片")
        require(e.size <= 64L * 1024 * 1024) { "${entry.substringAfterLast('/')} · 图片过大" }
        zip.getInputStream(e).use { it.readBytes() }
    }

    /** Decodes at most [maxWidth] wide (power-of-two sampling); null bitmap → explicit error. */
    @Synchronized
    override fun page(entry: String, maxWidth: Int, maxHeight: Int): Bitmap {
        check(!closed) { "Page source is closed" }
        val key = "$prefix$entry@$maxWidth:$maxHeight"
        budget.get(key)?.let { return it }
        val e = zip.getEntry(entry) ?: error("${entry.substringAfterLast('/')} · 压缩包中找不到该图片")
        require(e.size <= 64L * 1024 * 1024) { "${entry.substringAfterLast('/')} · 图片过大" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(e).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "${entry.substringAfterLast('/')} · 无法解码图片" }
        val normalSample = pageSampleSize(bounds.outWidth, bounds.outHeight, maxWidth, maxHeight)
        fun bytes(sample: Int) = ((bounds.outWidth.toLong() + sample - 1) / sample) *
            ((bounds.outHeight.toLong() + sample - 1) / sample) * 4
        return budget.decode(key, bytes(normalSample)) { allowed ->
            var sample = normalSample
            while (bytes(sample) > allowed && sample < (1 shl 30)) sample *= 2
            // Reopen the ZIP stream for decode; no full compressed image ByteArray is retained.
            zip.getInputStream(e).use { input -> BitmapFactory.decodeStream(input, null,
                BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
                ?: error("${entry.substringAfterLast('/')} · 图片已损坏")
        }
    }

    private var closed = false
    // Eviction only drops references: Compose may still be drawing an evicted bitmap.
    @Synchronized
    override fun close() { if (closed) return; closed = true; budget.close(prefix); synchronized(zip) { zip.close() } }

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
