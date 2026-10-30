package com.lyric.core

import org.junit.Assert.*
import org.junit.Test

class BookFormatsTest {
    @Test fun safDetectionUsesMagicMimeAndUriSuffixRatherThanOnlyDisplayName() {
        val zip = byteArrayOf(0x50, 0x4b, 3, 4)
        for (mime in listOf("application/epub+zip", "application/octet-stream", null)) {
            assertEquals("epub", BookFormats.detect(zip, "阅读文档", mime))
            assertEquals("epub", BookFormats.detect(zip, "mistaken.txt", mime))
        }
        assertEquals("epub", BookFormats.detect(byteArrayOf(), "阅读文档", "application/epub+zip"))
        assertEquals("epub", BookFormats.detect(byteArrayOf(), "阅读文档", "application/octet-stream", "primary:Books/Rain.EPUB"))
        assertEquals("txt", BookFormats.detect("hello".toByteArray(), "hello.txt", "application/octet-stream"))
        assertThrows(IllegalStateException::class.java) {
            BookFormats.detect(byteArrayOf(), "unknown", "application/octet-stream")
        }
        assertTrue(BookFormats.pickerMimeTypes.toList().containsAll(listOf("application/epub+zip", "application/octet-stream")))
    }

    @Test fun archiveNamesAndUriReferencesHaveDifferentEscapingAndCannotTraverseRoot() {
        assertEquals("Books/100% + rain/a.xhtml", EpubPaths.archive("Books/100% + rain/a.xhtml"))
        assertEquals("Books/100% + rain/a.xhtml", EpubPaths.resolve("Books/100% + rain/nav/toc.ncx", "../a.xhtml"))
        assertEquals("Text/雨 + 100%.xhtml", EpubPaths.resolve("nav.xhtml", "Text/%E9%9B%A8%20%2B%20100%25.xhtml#leaf"))
        assertEquals("Text/percent%20literal.xhtml", EpubPaths.resolve("book.opf", "Text/percent%2520literal.xhtml"))
        assertEquals("Text/a.xhtml", EpubPaths.resolve("Text/a.xhtml", "#leaf"))
        for (bad in listOf("../outside", "/absolute", "\\absolute")) {
            assertThrows(IllegalArgumentException::class.java) { EpubPaths.archive(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { EpubPaths.resolve("book.opf", "%2E%2E/outside") }
        assertThrows(IllegalArgumentException::class.java) { EpubPaths.resolve("book.opf", "https://example.com/a.xhtml") }
    }
}
