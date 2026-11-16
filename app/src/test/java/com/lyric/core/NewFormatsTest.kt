package com.lyric.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NewFormatsTest {
    private class Src(override val file: File) : FileContentSource {
        override val displayName = file.name
        override fun open() = file.inputStream()
    }

    private fun docx(): File {
        val f = File.createTempFile("lyric-docx", ".docx")
        fun p(style: String?, text: String, bold: Boolean = false) =
            "<w:p>${style?.let { "<w:pPr><w:pStyle w:val=\"$it\"/></w:pPr>" } ?: ""}<w:r>${if (bold) "<w:rPr><w:b/></w:rPr>" else ""}<w:t xml:space=\"preserve\">$text</w:t></w:r></w:p>"
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<Types/>")
            put("word/styles.xml", """<w:styles xmlns:w="w"><w:style w:styleId="1"><w:name w:val="heading 1"/></w:style><w:style w:styleId="Heading2"><w:name w:val="heading 2"/></w:style></w:styles>""")
            put("word/document.xml", """<w:document xmlns:w="w"><w:body>${p("1", "第一章 开始")}${p(null, "春风 &amp; 细雨", true)}${p("Heading2", "第一节")}${p(null, "正文二")}${p("1", "第二章")}${p(null, "结尾")}</w:body></w:document>""")
            put("docProps/core.xml", """<cp:coreProperties xmlns:cp="c" xmlns:dc="d"><dc:title>样例</dc:title><dc:creator>作者甲</dc:creator></cp:coreProperties>""")
        }
        return f
    }

    @Test fun docxHeadingsBecomeChapters() {
        val f = docx()
        val book = DocxParser().parse(Src(f))
        assertEquals("样例", book.title)
        assertEquals("作者甲", book.author)
        assertEquals(listOf("第一章 开始", "第一节", "第二章"), book.chapters.map { it.title })
        assertTrue(book.chapters[0].text.contains("春风 & 细雨"))
        assertTrue(book.chapters[0].styles.any { it.bold })
        assertEquals("docx", BookFormats.zipKind(f))
        f.delete()
    }

    @Test fun palmDocDecompression() {
        // literal "abc", copy distance 3 length 3, space+'x' pair, raw run of 2 bytes.
        val pair = (0x8000 or (3 shl 3) or (3 - 3))
        val input = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            (pair shr 8).toByte(), pair.toByte(), (0x80 xor 'x'.code).toByte(), 2, 0x01, 0x02)
        val out = MobiParser.palmDoc(input)
        assertArrayEquals("abcabc x".toByteArray() + byteArrayOf(1, 2), out)
    }

    private fun mobi(text: String, encryption: Int = 0): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val rec0 = ByteArray(16 + 232)
        fun w16(a: ByteArray, o: Int, v: Int) { a[o] = (v shr 8).toByte(); a[o + 1] = v.toByte() }
        fun w32(a: ByteArray, o: Int, v: Int) { w16(a, o, v ushr 16); w16(a, o + 2, v and 0xffff) }
        w16(rec0, 0, 1); w32(rec0, 4, body.size); w16(rec0, 8, 1); w16(rec0, 10, 4096); w16(rec0, 12, encryption)
        "MOBI".toByteArray().copyInto(rec0, 16); w32(rec0, 20, 232); w32(rec0, 28, 65001); w32(rec0, 36, 6); w32(rec0, 108, -1)
        val header = ByteArray(78 + 2 * 8 + 2)
        "BOOKMOBI".toByteArray().copyInto(header, 60); w16(header, 76, 2)
        w32(header, 78, header.size); w32(header, 86, header.size + rec0.size)
        return ByteArrayOutputStream().apply { write(header); write(rec0); write(body) }.toByteArray()
    }

    @Test fun mobiSplitsOnPageBreaks() {
        val chapter = "x".repeat(250)
        val book = MobiParser().parseBytes(mobi("<html><body><h1>第一章</h1><p>$chapter</p><mbp:pagebreak/><h2>第二章</h2><p>$chapter</p></body></html>"), "书.mobi")
        assertEquals(2, book.chapters.size)
        assertEquals("第二章", book.chapters[1].title)
        assertEquals("mobi", BookFormats.detect(mobi("a").copyOf(68), "书.mobi", null))
    }

    @Test fun mobiDrmIsFriendly() {
        val e = runCatching { MobiParser().parseBytes(mobi("abc", encryption = 2), "a.azw3") }.exceptionOrNull()
        assertEquals("这本书有版权保护，无法导入", e?.message)
    }

    @Test fun legacyDocAndPdfDetected() {
        val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())
        assertEquals("请另存为 DOCX 后再导入", runCatching { BookFormats.detect(ole, "a.doc", null) }.exceptionOrNull()?.message)
        assertEquals("pdf", BookFormats.detect("%PDF-1.7".toByteArray(), "x", null))
    }
}
