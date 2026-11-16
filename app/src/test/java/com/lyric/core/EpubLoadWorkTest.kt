package com.lyric.core

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubLoadWorkTest {
    @Test fun ordinaryParagraphsKeepTextAndMappingsWithoutEmptyStyles() {
        val body = Jsoup.parse("<p id='a'>first</p><p>second <b>bold</b></p><p style='margin:0'>third</p>").body()
        val anchors = mutableMapOf<String, Int>()
        val result = EpubLayout.extract(body, emptyList(), { it }) { id, at -> anchors[id] = at }
        assertEquals("first\nsecond bold\nthird\n", result.text)
        assertEquals(0, anchors["a"])
        assertTrue(result.legacyOffsets.isNotEmpty())
        assertEquals(1, result.styles.size)
        assertTrue(result.styles.single().bold)
        assertEquals("bold", result.styles.single().let { result.text.substring(it.start, it.end) })
    }

    @Test fun comicDetectionPreservesThresholdWithLeadingTextAndBlankPages() {
        val fixtures = ImageEpubTest()
        data class Case(val meta: String, val text: Int, val images: Int, val comic: Boolean)
        for ((meta, textCount, imageCount, comic) in listOf(
            Case("", 1, 9, true), Case("", 2, 8, false),
            Case("<meta property='rendition:layout'>pre-paginated</meta>", 4, 6, true),
            Case("<meta property='rendition:layout'>pre-paginated</meta>", 5, 5, false),
        )) {
            val pages = List(textCount) { "<p>${"words ".repeat(20)}</p>" } +
                List(3) { "<p></p>" } + List(imageCount) { "<img src='../Images/${it + 1}.png'/>" }
            val file = fixtures.epub(meta, "", pages, images = imageCount)
            try { assertEquals(comic, ImageEpub.detect(file, "threshold.epub") != null) }
            finally { file.delete() }
        }
    }
}
