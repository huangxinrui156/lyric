package com.lyric.core

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BookFormatsOpenWithTest {
    private fun zip(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = File.createTempFile("open-with-", name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test fun sniffAcceptsPdfEpubCbzAndDecodableText() {
        val pdf = File.createTempFile("open-with-", ".bin").apply { writeBytes("%PDF-1.7\n1 0 obj".toByteArray()) }
        assertEquals("pdf", BookFormats.sniffOpenWith(pdf))

        val epub = zip("book.bin", listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to "<container/>".toByteArray(),
        ))
        assertEquals("epub", BookFormats.sniffOpenWith(epub))

        val epubByContainer = zip("no-mime.bin", listOf(
            "META-INF/container.xml" to "<container/>".toByteArray(),
        ))
        assertEquals("epub", BookFormats.sniffOpenWith(epubByContainer))

        val comic = zip("pics.bin", listOf("page1.png" to tinyPng(2, 2)))
        assertEquals("cbz", BookFormats.sniffOpenWith(comic))

        val txt = File.createTempFile("open-with-", ".bin").apply { writeText("第一章 开始\n\n正文内容。") }
        assertEquals("txt", BookFormats.sniffOpenWith(txt))

        val gbk = File.createTempFile("open-with-gbk-", ".bin").apply {
            writeBytes("第一章 雨声\n正文".toByteArray(charset("GBK")))
        }
        assertEquals("txt", BookFormats.sniffOpenWith(gbk))

        val docx = zip("word.bin", listOf("word/document.xml" to "<w:document/>".toByteArray()))
        assertEquals("docx", BookFormats.sniffOpenWith(docx))

        val mobi = File.createTempFile("open-with-", ".bin").apply {
            writeBytes(ByteArray(80).also { bytes ->
                "BOOKMOBI".toByteArray().copyInto(bytes, 60)
            })
        }
        assertEquals("mobi", BookFormats.sniffOpenWith(mobi))
    }

    @Test fun sniffRejectsImagesAndGenericZipsWithoutCrashing() {
        val png = File.createTempFile("open-with-", ".bin").apply { writeBytes(tinyPng(4, 4)) }
        assertNull(BookFormats.sniffOpenWith(png))

        val randomZip = zip("src.bin", listOf("README.md" to "# notes".toByteArray(), "src/a.kt" to "class A".toByteArray()))
        assertNull(BookFormats.sniffOpenWith(randomZip))

        val binary = File.createTempFile("open-with-", ".bin").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3, 0, 0xFF.toByte(), 0, 0x10, 0, 0, 7, 8, 9, 0))
        }
        assertNull(BookFormats.sniffOpenWith(binary))

        val empty = File.createTempFile("open-with-", ".bin").apply { writeBytes(byteArrayOf()) }
        assertNull(BookFormats.sniffOpenWith(empty))
    }
}
