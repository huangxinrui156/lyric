package com.lyric.core

import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures mimic Kindle/Calibre comic exports, svg-wrapped pages and pre-paginated layouts. */
class ImageEpubTest {
    internal fun png(w: Int, h: Int): ByteArray = ByteBuffer.allocate(33).apply {
        put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        putInt(13); put("IHDR".toByteArray()); putInt(w); putInt(h); put(byteArrayOf(8, 6, 0, 0, 0)); putInt(0)
    }.array()

    internal fun epub(opfMeta: String, spineAttr: String, pages: List<String>, extra: Map<String, String> = emptyMap(),
                     images: Int = pages.size, nav: String? = null): File {
        val f = File.createTempFile("img-epub", ".epub")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            val items = pages.indices.joinToString("") { """<item id="p$it" href="Text/p$it.xhtml" media-type="application/xhtml+xml"/>""" } +
                (1..images).joinToString("") { """<item id="i$it" href="Images/$it.png" media-type="image/png"/>""" } +
                (if (nav != null) """<item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/>""" else "")
            val spine = pages.indices.joinToString("") { """<itemref idref="p$it"/>""" }
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>再见绘梨</dc:title><dc:creator>藤本树</dc:creator>$opfMeta</metadata><manifest>$items</manifest><spine $spineAttr>$spine</spine></package>""".toByteArray())
            pages.forEachIndexed { i, body -> put("OEBPS/Text/p$i.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:xlink="http://www.w3.org/1999/xlink"><head><title>p</title></head><body>$body</body></html>""".toByteArray()) }
            (1..images).forEach { put("OEBPS/Images/$it.png", png(800, 1200)) }
            nav?.let { put("OEBPS/nav.xhtml", it.toByteArray()) }
            extra.forEach { (k, v) -> put(k, v.toByteArray()) }
        }
        return f
    }

    @Test fun kindleCalibreImgPagesRtl() {
        val pages = (1..6).map { """<div class="fs"><img src="../Images/$it.png" alt=""/></div>""" }
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml"><body><nav epub:type="toc"><ol><li><a href="Text/p0.xhtml">第一话</a></li><li><a href="Text/p3.xhtml">第二话</a></li></ol></nav></body></html>"""
        val book = ImageEpub.detect(epub("", """page-progression-direction="rtl"""", pages, nav = nav), "x.epub")!!
        assertEquals(ContentType.COMIC, book.contentType)
        assertEquals("rtl", book.readingDirection)
        assertEquals("再见绘梨", book.title)
        assertEquals(listOf("第一话", "第二话"), book.chapters.map { it.title })
        val res = book.chapters.flatMap { it.content }.map { (it as ContentUnit.ImagePage) }
        assertEquals((1..6).map { "OEBPS/Images/$it.png" }, res.map { it.resource })
        assertEquals(800, res.first().width); assertEquals(1200, res.first().height)
    }

    @Test fun svgWrappedLtr() {
        val pages = (1..4).map { """<div><svg xmlns="http://www.w3.org/2000/svg" version="1.1" viewBox="0 0 800 1200"><image width="800" height="1200" xlink:href="../Images/$it.png"/></svg></div>""" }
        val book = ImageEpub.detect(epub("", """page-progression-direction="ltr"""", pages), "x.epub")!!
        assertEquals("ltr", book.readingDirection)
        assertEquals(4, book.chapters.sumOf { it.content.size })
    }

    @Test fun prePaginatedWithTextCreditsNoDirection() {
        val pages = (1..4).map { """<img src="../Images/$it.png"/>""" } +
            listOf("<p>版权页：本书由某某出版社出版，未经许可不得转载。这里有足够多的文字来超过图注的长度限制，所以它是文字页。</p>")
        val book = ImageEpub.detect(epub("""<meta property="rendition:layout">pre-paginated</meta>""", "", pages, images = 4), "x.epub")!!
        assertNull(book.readingDirection)
        assertEquals(4, book.chapters.sumOf { it.content.size })
    }

    @Test fun textEpubIsNotComic() {
        val pages = (1..5).map { "<h1>第$it 章</h1><p>" + "正文内容。".repeat(50) + "</p>" } + listOf("""<p><img src="../Images/1.png"/></p>""")
        assertNull(ImageEpub.detect(epub("", "", pages, images = 1), "x.epub"))
    }

    @Test fun epubParserRoutesImageEpubToComic() {
        val pages = (1..3).map { """<img src="../Images/$it.png"/>""" }
        val f = epub("", "", pages)
        val book = EpubParser().parse(object : FileContentSource {
            override val file = f; override val displayName = "再见绘梨.epub"; override fun open() = f.inputStream()
        }, null, null)
        assertEquals(ContentType.COMIC, book.contentType)
    }
}
