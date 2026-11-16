package com.lyric.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReadingStatsTest {
    @Test fun fourLevelsAndBlankDays() {
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0L, 1, 900, 1800, 3600).map(ReadingStats::level))
        val today = LocalDate.of(2026, 10, 30)
        val days = listOf(DailyStat(today.toString(), 900, 100))
        val grid = ReadingStats.calendar(days, today)
        assertEquals(182, grid.size)
        assertEquals(2, grid.single { it.date == today }.level)
        assertEquals(181, grid.count { it.level == 0 })
    }
    @Test fun streakAllowsTodayToBeUnfinishedButNotYesterday() {
        val today = LocalDate.of(2026, 10, 30)
        val days = (1L..3L).map { DailyStat(today.minusDays(it).toString(), 10, 1) }
        assertEquals(3, ReadingStats.streak(days, today))
        assertEquals(0, ReadingStats.streak(days.drop(1), today))
    }
    @Test fun bookmarkKeywordSearchStaysBelowThreeHundredMilliseconds() {
        val marks = List(10_000) { index -> BookmarkSummary(
            BookmarkRow(id = index.toLong(), bookId = "book", chapterIndex = 0, charOffset = index,
                excerpt = "林间的风与竹影。".repeat(10), chapterTitle = "第一章"), "山间来信", false) }
        val began = System.nanoTime()
        assertEquals(10_000, BookmarkFilter.filter(marks, "竹影").size)
        assertTrue("10k bookmarks filter must be <300ms", System.nanoTime() - began < 300_000_000)
        assertEquals(0, BookmarkFilter.filter(marks, "missing").size)
    }

    @Test fun aYearOfCalendarPreparationStaysBelowHundredMilliseconds() {
        val today = LocalDate.of(2026, 10, 30)
        val days = (0L..365L).map { DailyStat(today.minusDays(it).toString(), it * 60, 10) }
        repeat(10) { ReadingStats.calendar(days, today, 53) }
        val began = System.nanoTime()
        val result = ReadingStats.calendar(days, today, 53)
        assertEquals(371, result.size)
        assertTrue("Calendar preparation must be <100ms", System.nanoTime() - began < 100_000_000)
    }
}
