package com.lyric.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.lyric.core.*
import java.io.ByteArrayOutputStream
import java.io.File

/** Random-access page bitmaps for the comic-style page reader. */
interface PageSource : java.io.Closeable {
    fun page(entry: String, maxWidth: Int, maxHeight: Int = Int.MAX_VALUE): Bitmap
}

const val PDF_PAGE_PREFIX = "pdf:"

/** PdfRenderer-backed pages: rendered lazily at the requested width on paper white, LRU-cached. */
class PdfPageSource(file: File) : PageSource {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw IllegalStateException("PDF 无法打开（可能已加密或损坏）", e) }
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    val pageCount get() = renderer.pageCount

    @Synchronized
    override fun page(entry: String, maxWidth: Int, maxHeight: Int): Bitmap {
        check(!closed) { "Page source is closed" }
        val index = entry.removePrefix(PDF_PAGE_PREFIX).toIntOrNull() ?: error("页码无效：$entry")
        val key = "$index@$maxWidth:$maxHeight"
        cache.get(key)?.let { return it }
        val bmp = synchronized(renderer) {
            renderer.openPage(index).use { page ->
                val (w, h) = pdfPageSize(page.width, page.height, maxWidth, maxHeight)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        cache.put(key, bmp)
        return bmp
    }

    private var closed = false
    // Eviction only drops references: Compose may still be drawing an evicted bitmap.
    @Synchronized
    override fun close() { if (closed) return; closed = true; cache.evictAll(); synchronized(renderer) { renderer.close() }; fd.close() }

    companion object {
        fun thumbnail(file: File, maxWidth: Int): ByteArray? = runCatching {
            PdfPageSource(file).use { src ->
                ByteArrayOutputStream().also { src.page("${PDF_PAGE_PREFIX}0", maxWidth).compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            }
        }.getOrNull()

        /**
         * Scanned heuristic without a PDF text library: a PDF with text declares fonts
         * (/Font, /FontDescriptor, /Type0...). Uncompressed object dictionaries carry these keys;
         * PDFs with compressed object streams may hide them, so those are treated as "has text".
         */
        fun looksScanned(file: File): Boolean = runCatching {
            var fonts = false; var images = false; var objStm = false
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(1 shl 16); var carry = ""
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    val chunk = carry + String(buf, 0, n, Charsets.ISO_8859_1)
                    if (chunk.contains("/Font")) fonts = true
                    if (chunk.contains("/Subtype/Image") || chunk.contains("/Subtype /Image")) images = true
                    if (chunk.contains("/ObjStm")) objStm = true
                    if (fonts) break
                    carry = chunk.takeLast(32)
                }
            }
            !fonts && images && !objStm
        }.getOrDefault(false)
    }
}

/** PDFs keep their original layout: one page image per PDF page, rendered on demand. */
class PdfParser : BookParser {
    override val extensions = setOf("pdf")
    override fun parse(source: ContentSource, encoding: String?, chapterRegex: String?): Book {
        val file = (source as? FileContentSource)?.file ?: error("PDF 需要本地副本")
        return ImportTrace.stage("PDF/读取页面") {
            PdfPageSource(file).use { src ->
                val count = src.pageCount
                require(count > 0) { "PDF 没有页面" }
                val pages = (0 until count).map { ContentUnit.ImagePage("$PDF_PAGE_PREFIX$it", null, null) }
                Book(source.displayName.substringBeforeLast('.'), "佚名",
                    listOf(Chapter(0, "全文", content = pages)), contentType = ContentType.COMIC,
                    coverResource = "${PDF_PAGE_PREFIX}0", readingDirection = "ltr")
            }
        }
    }
}
