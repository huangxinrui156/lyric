package com.lyric.data

import java.time.DayOfWeek
import java.time.LocalDate
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

    fun calendar(days: List<DailyStat>, today: LocalDate, weeks: Int = 26): List<CalendarDay> {
        val values = days.associateBy { it.day }
        val start = weekStart(today).minusWeeks(weeks.toLong() - 1)
        return List(weeks * 7) { index ->
            val date = start.plusDays(index.toLong())
            val seconds = values[date.toString()]?.durationSec ?: 0
            CalendarDay(date, seconds, level(seconds))
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
