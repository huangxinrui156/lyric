package com.lyric.data

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class ReadingStatsTest {
    @Test fun fourLevelsAndBlankDays() {
        assertEquals(listOf(0, 1, 2, 3, 4), listOf(0L, 1, 900, 1800, 3600).map(ReadingStats::level))
        val leapFebruary = YearMonth.of(2024, 2)
        val leapDay = leapFebruary.atEndOfMonth()
        val days = listOf(DailyStat(leapDay.toString(), 900, 100)).associateBy { it.day }
        val grid = ReadingStats.calendarMonth(days, leapFebruary)
        val dates = grid.flatten().filterNotNull()
        assertEquals(5, grid.size)
        assertEquals(List(3) { null }, grid.first().take(3))
        assertEquals(leapFebruary.atDay(1), grid.first()[3]?.date)
        assertEquals(29, dates.size)
        assertEquals(CalendarDay(leapDay, 900, 2), dates.last())
        assertEquals(28, dates.count { it.level == 0 })
        assertNull(grid.last().last())
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
        val january = YearMonth.of(2026, 1)
        val start = january.atDay(1)
        val days = (0L..364L).map { DailyStat(start.plusDays(it).toString(), it * 60, 10) }.associateBy { it.day }
        val months = (0L..11L).map(january::plusMonths)
        repeat(10) { months.forEach { ReadingStats.calendarMonth(days, it) } }
        val began = System.nanoTime()
        val result = months.flatMap { ReadingStats.calendarMonth(days, it).flatten().filterNotNull() }
        assertEquals(365, result.size)
        assertEquals(start, result.first().date)
        assertEquals(start.plusDays(364), result.last().date)
        assertTrue("Calendar preparation must be <100ms", System.nanoTime() - began < 100_000_000)
    }
}
