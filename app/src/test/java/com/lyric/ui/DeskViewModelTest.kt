package com.lyric.ui

import com.lyric.data.HighlightRow
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DeskViewModelTest {
    @Test fun formatRemainPadsSeconds() {
        assertEquals("25:00", DeskViewModel.formatRemain(25 * 60))
        assertEquals("0:05", DeskViewModel.formatRemain(5))
        assertEquals("1:01", DeskViewModel.formatRemain(61))
        assertEquals("0:00", DeskViewModel.formatRemain(-3))
    }

    @Test fun relativeDayLabels() {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.of(2026, 10, 31)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals("今天", DeskViewModel.relativeDay(start + 3_600_000, today, zone))
        assertEquals("昨天", DeskViewModel.relativeDay(start - 3_600_000, today, zone))
        assertEquals("10月29日", DeskViewModel.relativeDay(start - 2 * 86_400_000L, today, zone))
    }

    @Test fun filterNotesByQueryAndSort() {
        val vmHelpers = object {
            fun filter(all: List<HighlightRow>, query: String, byBook: Boolean): List<HighlightRow> {
                val q = query.trim()
                val filtered = if (q.isEmpty()) all else all.filter {
                    it.text.contains(q, true) || it.note.contains(q, true) ||
                        it.bookTitle.contains(q, true) || it.chapterTitle.contains(q, true)
                }
                return if (byBook) filtered.sortedWith(compareBy({ it.bookTitle }, { it.chapterIndex }, { it.start }))
                else filtered.sortedByDescending { it.createdAt }
            }
        }
        val rows = listOf(
            HighlightRow(id = 1, bookId = "b", chapterIndex = 0, start = 0, end = 2, text = "窗外的雨", bookTitle = "雨季", createdAt = 100),
            HighlightRow(id = 2, bookId = "a", chapterIndex = 1, start = 0, end = 2, text = "阳光", note = "想法", bookTitle = "顾城", createdAt = 200),
        )
        assertEquals(listOf(2L, 1L), vmHelpers.filter(rows, "", false).map { it.id })
        assertEquals(listOf(2L), vmHelpers.filter(rows, "顾城", false).map { it.id })
        assertEquals(listOf("雨季", "顾城"), vmHelpers.filter(rows, "", true).map { it.bookTitle })
    }
}
