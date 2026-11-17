package com.lyric.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfFilterTest {
    private val now = 1_700_000_000_000L
    private fun book(
        id: String,
        lastRead: Long = 0,
        percent: Float = 0f,
        addedAt: Long = now,
        section: String = "BOOK",
    ) = ShelfBook(
        id = id, uri = "file://$id", name = "$id.txt", title = id, author = "", extension = "txt",
        totalChars = 1, chapterCount = 1, encoding = "UTF-8", addedAt = addedAt, lastRead = lastRead,
        percent = percent, section = section,
    )

    @Test fun readingStatusAndImportWindows() {
        val unread = book("u")
        val reading = book("r", lastRead = 1, percent = 0.4f)
        val finished = book("f", lastRead = 2, percent = 0.99f)
        val recent = book("new", addedAt = now - 3 * DAY_MS)
        val old = book("old", addedAt = now - 20 * DAY_MS)
        val older = book("older", addedAt = now - 40 * DAY_MS)
        assertEquals(ReadingStatusFilter.UNREAD, unread.readingStatus())
        assertEquals(ReadingStatusFilter.READING, reading.readingStatus())
        assertEquals(ReadingStatusFilter.FINISHED, finished.readingStatus())
        val none = ShelfFilter()
        assertFalse(none.isActive)
        assertEquals(0, none.activeCount)
        val unreadOnly = ShelfFilter(statuses = setOf(ReadingStatusFilter.UNREAD))
        assertEquals(listOf("u"), applyShelfFilter(listOf(unread, reading, finished), unreadOnly, now).map { it.id })
        val mixed = ShelfFilter(statuses = setOf(ReadingStatusFilter.UNREAD, ReadingStatusFilter.READING))
        assertEquals(listOf("u", "r"), applyShelfFilter(listOf(unread, reading, finished), mixed, now).map { it.id })
        val week = ShelfFilter(imported = ImportedWithin.DAYS_7)
        assertEquals(listOf("new"), applyShelfFilter(listOf(recent, old, older), week, now).map { it.id })
        val month = ShelfFilter(imported = ImportedWithin.DAYS_30)
        assertEquals(listOf("new", "old"), applyShelfFilter(listOf(recent, old, older), month, now).map { it.id })
        val stacked = ShelfFilter(statuses = setOf(ReadingStatusFilter.UNREAD), imported = ImportedWithin.DAYS_7)
        assertEquals(listOf("new"), applyShelfFilter(listOf(recent, old, book("reading-new", lastRead = 1, percent = 0.2f, addedAt = now - 1 * DAY_MS)), stacked, now).map { it.id })
        assertEquals(2, stacked.activeCount)
        assertEquals("UNREAD|DAYS_7", stacked.encode())
        assertEquals(stacked, ShelfFilter.decode("UNREAD|DAYS_7"))
        assertEquals(ShelfFilter(), ShelfFilter.decode(null))
        assertEquals(listOf("未读", "7 天内"), stacked.chips().map { it.first })
    }
}
