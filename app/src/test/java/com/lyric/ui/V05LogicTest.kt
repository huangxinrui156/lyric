package com.lyric.ui

import com.lyric.core.*
import com.lyric.data.*
import org.junit.Assert.*
import org.junit.Test

class V05LogicTest {
    private val book = Book("书", chapters = listOf(Chapter(index = 0, title = "一", text = "春眠不觉晓处处闻啼鸟"), Chapter(index = 1, title = "二", text = "夜来风雨声花落知多少")))
    private fun shelf(id: String, title: String, author: String = "", added: Long = 0, read: Long = 0) =
        ShelfBook(id, "u", "$id.txt", title, author, "txt", totalChars = 1, chapterCount = 1, encoding = "UTF-8", addedAt = added, lastRead = read)

    @Test fun tocProgressMarksReadCurrentAndUnread() {
        val p = tocProgress(book, Locator(1, 5))
        assertEquals(1f, p[0]); assertEquals(.5f, p[1], .01f)
        assertEquals(listOf(0f, 0f), tocProgress(book, Locator(0, 0)))
    }

    @Test fun bookmarksSortByTimeOrChapter() {
        val a = BookmarkRow(1, "b", 1, 0, 0, "x", "二", createdAt = 1)
        val b = BookmarkRow(2, "b", 0, 3, 0, "y", "一", createdAt = 2)
        assertEquals(listOf(2L, 1L), sortBookmarks(listOf(a, b), false).map { it.id })
        assertEquals(listOf(2L, 1L), sortBookmarks(listOf(b, a), true).map { it.id })
        assertEquals(listOf(1L, 2L), sortBookmarks(listOf(a, b.copy(chapterIndex = 1, charOffset = 3)), true).map { it.id })
    }

    @Test fun oldExcerptsAreDetectedButPlainBookmarksAreNot() {
        val excerpt = BookmarkRow(1, "b", 0, 2, 0, "不觉", "一")
        val plain = BookmarkRow(2, "b", 0, 2, 0, book.excerpt(Locator(0, 2), 80), "一")
        assertTrue(Annotations.isExcerpt(book, excerpt))
        assertFalse(Annotations.isExcerpt(book, plain))
        assertFalse(Annotations.isExcerpt(book, excerpt.copy(excerpt = "不存在")))
        val h = Annotations.toHighlight(book, excerpt)
        assertEquals(2, h.start); assertEquals(4, h.end); assertEquals("一", h.chapterTitle)
    }

    @Test fun shelfSearchAndSort() {
        val list = listOf(shelf("1", "雨季", "张三", added = 3, read = 0), shelf("2", "回声", "李四", added = 1, read = 9), shelf("3", "抒情诗", "王五", added = 2, read = 5))
        assertEquals(listOf("2"), filterShelf(list, "李").map { it.id })
        assertEquals(listOf("1"), filterShelf(list, " 雨 ").map { it.id })
        assertEquals(listOf("2", "3", "1"), sortShelf(list, 0).map { it.id })
        assertEquals(listOf("1", "3", "2"), sortShelf(list, 1).map { it.id })
        assertEquals(3, sortShelf(list, 2).size)
    }
    @Test fun continueCardPicksTheMostRecentlyReadValidBook() {
        val older = shelf("old", "旧书", read = 10)
        val latest = shelf("new", "最近在读", read = 30)
        val unread = shelf("fresh", "刚导入", added = 40, read = 0)
        val broken = shelf("bad", "失效", read = 50).copy(invalid = true)
        assertEquals("new", continueReadingBook(listOf(unread, broken, older, latest))?.id)
        assertNull(continueReadingBook(listOf(unread, broken)))
        assertNull(continueReadingBook(emptyList()))
    }
}
