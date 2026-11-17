package com.lyric.ui

import com.lyric.core.Book
import com.lyric.core.Chapter
import com.lyric.core.ContentType
import com.lyric.core.ContentUnit
import com.lyric.core.Locator
import org.junit.Assert.assertEquals
import org.junit.Test

class ComicPagesTest {
    @Test fun emptyBookDoesNotThrowAndKeepsASafeLocator() {
        val book = Book("空", chapters = listOf(Chapter(0, "空章", content = emptyList())), contentType = ContentType.COMIC)
        val pages = ComicPages(book)
        assertEquals(0, pages.pages.size)
        assertEquals(Locator(), pages.locator(0))
        assertEquals(0, pages.indexOf(Locator(1, 0, 3)))
    }

    @Test fun locatorRoundTripsAPageInRange() {
        val book = Book(
            "漫",
            chapters = listOf(
                Chapter(0, "一", content = listOf(ContentUnit.ImagePage("a.jpg", 10, 10), ContentUnit.ImagePage("b.jpg", 10, 10))),
            ),
            contentType = ContentType.COMIC,
        )
        val pages = ComicPages(book)
        assertEquals(Locator(0, 0, 1), pages.locator(1))
        assertEquals(1, pages.indexOf(Locator(0, 0, 1)))
        assertEquals(Locator(0, 0, 1), pages.locator(99))
    }
}
