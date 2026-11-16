package com.lyric.core

import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class EpubCompatibilityTest {
    @Test fun storedDataDescriptorIsReadableWithCentralDirectory() {
        val bytes = javaClass.getResourceAsStream("/epub/stored-descriptor.epub")!!.use { it.readBytes() }
        // This reproduces the old parser's failure before parsing container.xml.
        assertThrows(java.util.zip.ZipException::class.java) {
            ZipInputStream(bytes.inputStream()).use { it.nextEntry }
        }
        assertTrue(EpubParser().parse(BytesSource(bytes, "descriptor.epub")).totalChars > 0)
    }

    @Test fun namespacedPackagesBomDoctypeEntitiesAndMalformedHtml() {
        val bytes = javaClass.getResourceAsStream("/epub/prefixed-gbk.epub")!!.use { it.readBytes() }
        val book = EpubParser().parse(BytesSource(bytes, "namespaces.epub"))
        assertEquals("前缀书名", book.title)
        assertEquals("作者", book.author)
        assertTrue(book.chapters.single().text.contains("正文"))
        assertTrue(book.chapters.single().text.contains("©"))
        assertFalse(book.chapters.single().text.contains("SECRET"))
        assertEquals(1, book.toc.size)
    }
}
