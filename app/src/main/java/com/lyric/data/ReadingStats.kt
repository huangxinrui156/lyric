package com.lyric.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** One O(days) pass, reused by both the footprint and the calendar. */
object ReadingStats {
    fun streak(days: List<DailyStat>, today: LocalDate): Int {
        val active = days.asSequence().filter { it.durationSec > 0 }.map { it.day }.toHashSet()
        var day = if (today.toString() in active) today else today.minusDays(1)
        var count = 0
        while (day.toString() in active) { count++; day = day.minusDays(1) }
        return count
    }

    fun weekStart(today: LocalDate): LocalDate =
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun level(seconds: Long): Int = when {
        seconds <= 0 -> 0
        seconds < 15 * 60 -> 1
        seconds < 30 * 60 -> 2
        seconds < 60 * 60 -> 3
        else -> 4
    }

    /** Monday-first rows, with empty slots outside this calendar month. */
    fun calendarMonth(daysByDate: Map<String, DailyStat>, month: YearMonth): List<List<CalendarDay?>> {
        val leading = month.atDay(1).dayOfWeek.value - 1
        val daysInMonth = month.lengthOfMonth()
        val weeks = (leading + daysInMonth + 6) / 7
        return List(weeks) { week ->
            List(7) { weekday ->
                val day = week * 7 + weekday - leading + 1
                if (day !in 1..daysInMonth) null else {
                    val date = month.atDay(day)
                    val seconds = daysByDate[date.toString()]?.durationSec ?: 0
                    CalendarDay(date, seconds, level(seconds))
                }
            }
        }
    }
}

data class CalendarDay(val date: LocalDate, val seconds: Long, val level: Int)

object BookmarkFilter {
    fun filter(marks: List<BookmarkSummary>, query: String): List<BookmarkSummary> =
        if (query.isBlank()) marks else marks.filter {
            it.title.contains(query, true) || it.mark.excerpt.contains(query, true) ||
                it.mark.chapterTitle.contains(query, true)
        }
}
