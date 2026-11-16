package com.lyric.reader

import com.lyric.data.ReadingSession
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SessionTrackerTest {
    private class Clock(var wall: Long = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli(), var elapsed: Long = 0) : SessionClock {
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    private val clock = Clock()
    private val stored = linkedMapOf<String, ReadingSession>()
    private val rows get() = stored.values.toList()
    private val tracker = SessionTracker("book", clock, ZoneId.of("UTC"), { stored[it.id] = it })

    @Test fun foregroundWithoutNavigationDoesNotCount() {
        tracker.onResume(); clock.advance(90_000); tracker.onPause()
        assertTrue(rows.isEmpty())
    }
    @Test fun pausesAtThirtySecondsEvenWhenTickIsLate() {
        tracker.onResume(); tracker.onActivity(20); clock.advance(90_000); tracker.tick()
        assertEquals(30L, rows.single().durationSec)
        assertEquals(rows.single().startAt + 30_000, rows.single().endAt)
        clock.advance(50_000); tracker.onPause()
        assertEquals(1, rows.size)
    }
    @Test fun activityExtendsWindowAndIdleActivityStartsANewSession() {
        tracker.onResume(); tracker.onActivity(10)
        clock.advance(25_000); tracker.onActivity(20)
        clock.advance(60_000); tracker.onActivity(30)
        assertEquals(55L, rows.single().durationSec)
        assertEquals(30L, rows.single().chars)
        clock.advance(10_000); tracker.onPause()
        assertEquals(listOf(55L, 10L), rows.map { it.durationSec })
        assertEquals(30L, rows.last().chars)
    }
    @Test fun discardsLessThanTenSecondsAndKeepsExactlyTen() {
        tracker.onResume(); tracker.onActivity(); clock.advance(9_999); tracker.onPause()
        assertTrue(rows.isEmpty())
        tracker.onResume(); tracker.onActivity(); clock.advance(10_000); tracker.onPause()
        assertEquals(10L, rows.single().durationSec)
    }
    @Test fun backgroundEventsNeverCountAndResumeRequiresAnotherEvent() {
        tracker.onActivity(); clock.advance(60_000)
        tracker.onResume(); tracker.onActivity(); clock.advance(12_000); tracker.onPause()
        tracker.onActivity(); clock.advance(60_000)
        tracker.onResume(); clock.advance(90_000); tracker.onPause()
        assertEquals(12L, rows.single().durationSec)
    }
    @Test fun pauseCapsIdleTailAndIsIdempotent() {
        tracker.onResume(); tracker.onActivity(); clock.advance(40_000)
        tracker.onPause(); tracker.onPause()
        assertEquals(30L, rows.single().durationSec)
    }
    @Test fun wallClockChangesDoNotChangeDuration() {
        tracker.onResume(); tracker.onActivity(); clock.advance(12_000); clock.wall -= 3_600_000
        tracker.onPause()
        assertEquals(12L, rows.single().durationSec)
        assertEquals(12_000L, rows.single().endAt - rows.single().startAt)
    }
    @Test fun midnightSessionIsSplitIntoLocalDays() {
        clock.wall = Instant.parse("2026-10-30T23:59:55.500Z").toEpochMilli()
        tracker.onResume(); tracker.onActivity(20); clock.advance(15_000); tracker.onPause()
        assertEquals(listOf(5L, 10L), rows.map { it.durationSec })
        assertEquals(20L, rows.sumOf { it.chars })
        assertEquals(rows.first().endAt, rows.last().startAt)
    }
    @Test fun rollingCheckpointUpdatesSameRowEveryFifteenSeconds() {
        tracker.onResume(); tracker.onActivity(10)
        clock.advance(14_999); tracker.tick(); assertTrue(rows.isEmpty())
        clock.advance(1); tracker.tick()
        val id = rows.single().id
        assertEquals(15L, rows.single().durationSec)
        clock.advance(9_000); tracker.onActivity(20)
        clock.advance(6_000); tracker.tick()
        assertEquals(id, rows.single().id)
        assertEquals(30L, rows.single().durationSec)
        assertEquals(30L, rows.single().chars)
        clock.advance(4_000); tracker.onPause(); tracker.onStop()
        assertEquals(34L, rows.single().durationSec)
        assertEquals(34_000L, rows.single().endAt - rows.single().startAt)
    }
    @Test fun midnightCheckpointsUpsertWithoutDuplicatingEarlierDay() {
        clock.wall = Instant.parse("2026-10-30T23:59:55Z").toEpochMilli()
        tracker.onResume(); tracker.onActivity(40)
        clock.advance(15_000); tracker.tick()
        assertEquals(listOf(5L, 10L), rows.map { it.durationSec })
        clock.advance(5_000); tracker.onActivity(20)
        clock.advance(10_000); tracker.tick()
        assertEquals(listOf(5L, 25L), rows.map { it.durationSec })
        assertEquals(60L, rows.sumOf { it.chars })
        tracker.onStop()
        assertEquals(30L, rows.sumOf { it.durationSec })
    }

}
