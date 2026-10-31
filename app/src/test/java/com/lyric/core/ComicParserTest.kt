package com.lyric.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

fun tinyPng(w: Int, h: Int): ByteArray {
    fun chunk(out: java.io.DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size); val t = type.toByteArray(); out.write(t); out.write(data)
        out.writeInt(java.util.zip.CRC32().apply { update(t); update(data) }.value.toInt())
    }
    val raw = ByteArrayOutputStream().apply { repeat(h) { write(0); write(ByteArray(w * 3) { 0x80.toByte() }) } }.toByteArray()
    val idat = ByteArrayOutputStream().also { java.util.zip.DeflaterOutputStream(it).use { d -> d.write(raw) } }.toByteArray()
    val bytes = ByteArrayOutputStream()
    java.io.DataOutputStream(bytes).use { o ->
        o.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(o, "IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array())
        chunk(o, "IDAT", idat); chunk(o, "IEND", ByteArray(0))
    }
    return bytes.toByteArray()
}
/** Header-only JPEG (SOI, APP0, SOF0): enough for size sniffing, not for decoding. */
fun tinyJpeg(w: Int, h: Int): ByteArray = byteArrayOf(0xff.toByte(), 0xd8.toByte(),
    0xff.toByte(), 0xe0.toByte(), 0, 16) + ByteArray(14) + byteArrayOf(0xff.toByte(), 0xc0.toByte(), 0, 17, 8,
    (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3) + ByteArray(9) + ByteArray(200)
fun cbz(entries: List<Pair<String, ByteArray>>, charset: java.nio.charset.Charset = Charsets.UTF_8): File =
    File.createTempFile("comic", ".cbz").apply {
        ZipOutputStream(outputStream(), charset).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        deleteOnExit()
    }
private fun parse(f: File) = ComicParser().parse(object : FileContentSource {
    override val file = f; override val displayName = "测试漫画.cbz"; override fun open() = f.inputStream() })

class ComicParserTest {
    @Test fun naturalOrderPutsTwoBeforeTen() {
        val names = listOf("p10.jpg", "p2.jpg", "P1a.jpg", "p02a.jpg", "x.jpg")
        assertEquals(listOf("P1a.jpg", "p2.jpg", "p02a.jpg", "p10.jpg", "x.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test fun subfoldersBecomeChaptersInNaturalOrderAndCoverIsFirstImage() {
        val png = tinyPng(30, 40)
        val f = cbz(listOf("第10话/2.png" to png, "第2话/10.png" to png, "第2话/2.png" to png, "第10话/1.png" to png,
            "__MACOSX/第2话/._2.png" to png, "readme.txt" to "x".toByteArray(), "第2话/.hidden.png" to png))
        val book = parse(f)
        assertEquals(ContentType.COMIC, book.contentType)
        assertEquals(listOf("第2话", "第10话"), book.chapters.map { it.title })
        assertEquals(listOf("第2话/2.png", "第2话/10.png"), book.chapters[0].units.map { (it as ContentUnit.ImagePage).resource })
        assertEquals("第2话/2.png", book.coverResource)
        val first = book.chapters[0].units[0] as ContentUnit.ImagePage
        assertEquals(30, first.width); assertEquals(40, first.height)
        assertEquals("测试漫画", book.title)
        assertEquals(.5f, book.percent(Locator(1, 0, 0)), 1e-6f)
    }

    @Test fun filenamePrefixGroupsChaptersWhenFlat() {
        val png = tinyPng(4, 4)
        val book = parse(cbz(listOf("ch2_01.png", "ch1_02.png", "ch1_01.png", "ch10_01.png").map { it to png }))
        assertEquals(listOf("ch1", "ch2", "ch10"), book.chapters.map { it.title })
    }

    @Test fun mixedZipSeparatorsKeepLiteralResourcesButShareChapterAndMetadataRules() {
        val png = tinyPng(3, 4)
        val file = cbz(listOf(
            "第10话\\1.png" to png, "第2话/10.png" to png, "第2话\\2.png" to png,
            "__MACOSX\\cover.png" to png, "第2话\\.hidden.png" to png,
            "meta\\ComicInfo.xml" to "<ComicInfo><Title>混合路径</Title><Manga>No</Manga></ComicInfo>".toByteArray(),
        ), charset("GBK"))
        val book = parse(file)
        assertEquals(listOf("第2话", "第10话"), book.chapters.map { it.title })
        assertEquals(listOf("第2话\\2.png", "第2话/10.png"),
            book.chapters.first().units.map { (it as ContentUnit.ImagePage).resource })
        assertEquals("第2话\\2.png", book.coverResource)
        assertEquals("混合路径", book.title)
        assertEquals("ltr", book.readingDirection)
        java.util.zip.ZipFile(file, charset("GBK")).use { zip ->
            book.chapters.flatMap { it.units }.forEach {
                val entry = zip.getEntry((it as ContentUnit.ImagePage).resource)
                assertNotNull(entry)
                assertArrayEquals(png, zip.getInputStream(entry).use { stream -> stream.readBytes() })
            }
        }
    }

    @Test fun gbkNamesAndJpegHeaders() {
        val book = parse(cbz(listOf("漫画/第1页.jpg" to tinyJpeg(123, 77)), charset("GBK")))
        val page = book.chapters.single().units.single() as ContentUnit.ImagePage
        assertEquals("漫画/第1页.jpg", page.resource)
        assertEquals(123, page.width); assertEquals(77, page.height)
    }

    @Test fun zipWithoutImagesExplainsWhy() {
        val e = assertThrows(IllegalArgumentException::class.java) { parse(cbz(listOf("a.txt" to "x".toByteArray()))) }
        assertTrue(e.message!!.contains("没有可识别的图片"))
    }

    @Test fun zipKindSeparatesEpubFromComic() {
        assertEquals("cbz", BookFormats.zipKind(cbz(listOf("1.png" to tinyPng(2, 2)))))
        assertEquals("epub", BookFormats.zipKind(cbz(listOf("META-INF/container.xml" to "<x/>".toByteArray(), "c.png" to tinyPng(2, 2)))))
    }

    @Test fun webpAndGifHeaders() {
        // VP8X canvas 300x200, GIF 5x7
        val webp = ByteArray(30).also { b ->
            "RIFF".toByteArray().copyInto(b, 0); "WEBPVP8X".toByteArray().copyInto(b, 8)
            b[24] = (299 and 0xff).toByte(); b[25] = (299 shr 8).toByte(); b[27] = (199).toByte()
        }
        assertEquals(300 to 200, ImageHeader.size(webp))
        val gif = "GIF89a".toByteArray() + byteArrayOf(5, 0, 7, 0)
        assertEquals(5 to 7, ImageHeader.size(gif))
    }

    @Test fun fiveHundredPageCbzParsesUnderThreeSeconds() {
        // ~300 KB per page of incompressible data, like real scans (~150 MB archive).
        val jpg = tinyJpeg(800, 1200) + ByteArray(300_000).also { java.util.Random(1).nextBytes(it) }
        val f = File.createTempFile("c500", ".cbz").apply { deleteOnExit()
            ZipOutputStream(outputStream().buffered()).use { z -> z.setLevel(0); (1..500).forEach { z.putNextEntry(ZipEntry("vol/$it.jpg")); z.write(jpg); z.closeEntry() } } }
        val t = System.nanoTime()
        val book = parse(f)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("PERF comic500 parse=${ms}ms size=${f.length()}")
        assertEquals(500, book.chapters.sumOf { it.units.size })
        assertTrue("500 pages took $ms ms", ms < 3000)
    }
}
