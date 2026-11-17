package com.lyric.ui

import com.lyric.data.ShelfBook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactShelfLogicTest {
    @Test fun inProgressExcludesUnreadAndFinished() {
        val unread = ShelfBook("u", "file://u", "u.txt", "未读本", "", "txt", totalChars = 1, chapterCount = 1, encoding = "UTF-8")
        val reading = unread.copy(id = "r", lastRead = 1L, percent = 0.4f, title = "在读本")
        val finished = unread.copy(id = "f", lastRead = 2L, percent = 0.99f, title = "读完本")
        assertFalse(unread.isInProgress())
        assertTrue(reading.isInProgress())
        assertFalse(finished.isInProgress())
    }
}
