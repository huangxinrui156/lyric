package com.lyric.ui

import com.lyric.core.*
import org.junit.Assert.*
import org.junit.Test

class TocProgressIndexTest {
    @Test fun duplicateAnchorsEmptyChaptersAndChapterEndsKeepExistingFractions() {
        val book = Book("test", chapters = listOf(Chapter(0, "a", "a".repeat(10)),
            Chapter(1, "empty"), Chapter(2, "b", "b".repeat(20))),
            toc = listOf(TocEntry("a", 0), TocEntry("duplicate", 0),
                TocEntry("b", 2), TocEntry("end", 2, 20)))
        val index = TocProgressIndex(book)
        val position = index.position(Locator(2, 5))
        assertEquals(15L, position)
        assertEquals(listOf(1f, 1f, .25f, 0f), book.toc.indices.map { index.fraction(it, position) })
        assertEquals(listOf(1f, 1f, 1f, 1f), tocProgress(book, Locator(2, 20)))
    }
    @Test fun prefixAccumulatesBeyondIntRange() {
        val chapter = "x".repeat(100000)
        val book = Book("large", chapters = List(22000) { Chapter(it, "", chapter) }, toc = emptyList())
        assertEquals(2200000000L, book.totalChars)
        assertEquals(2199900001L, TocProgressIndex(book).position(Locator(21999, 1)))
        assertEquals(2199900001.0 / 2200000000, book.percent(Locator(21999, 1)).toDouble(), .000001)
    }
}
