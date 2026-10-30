package com.lyric.reader

import com.lyric.data.ReadingSession
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

interface SessionClock {
    fun wallMillis(): Long
    fun elapsedMillis(): Long
}

object SystemSessionClock : SessionClock {
    override fun wallMillis() = System.currentTimeMillis()
    override fun elapsedMillis() = System.nanoTime() / 1_000_000
}

/** Foreground alone never starts a session. Only actual reading navigation is activity.
 * Durations use a monotonic clock; idle tails are capped, including late lifecycle callbacks.
 */
class SessionTracker(
    private val bookId: String,
    private val clock: SessionClock = SystemSessionClock,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val record: (ReadingSession) -> Unit,
) {
    private var foreground = false
    private var start: Long? = null
    private var wallStart = 0L
    private var lastActivity = 0L
    private var chars = 0L

    fun onResume() { foreground = true }

    fun onActivity(characters: Long = 0) {
        if (!foreground) return
        tick()
        val now = clock.elapsedMillis()
        if (start == null) {
            start = now
            wallStart = clock.wallMillis()
        }
        lastActivity = now
        chars += characters.coerceAtLeast(0)
    }

    fun tick() {
        if (start != null && clock.elapsedMillis() - lastActivity >= IDLE_MS) {
            finish(lastActivity + IDLE_MS)
        }
    }

    fun onPause() {
        foreground = false
        if (start != null) finish(minOf(clock.elapsedMillis(), lastActivity + IDLE_MS))
    }

    private fun finish(endElapsed: Long) {
        val began = start ?: return
        val seconds = ((endElapsed - began).coerceAtLeast(0) / 1000)
        start = null
        if (seconds >= MIN_SECONDS) {
            // Split at local midnights so today's totals never include yesterday's time.
            // Records have whole-second precision, avoiding rounding loss at midnight.
            var cursor = wallStart - Math.floorMod(wallStart, 1000)
            val end = cursor + seconds * 1000
            var assignedChars = 0L
            while (cursor < end) {
                val midnight = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                    .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val stop = minOf(end, midnight)
                val duration = (stop - cursor) / 1000
                val partChars = if (stop == end) chars - assignedChars else chars * duration / seconds
                if (duration > 0) record(ReadingSession(UUID.randomUUID().toString(), bookId,
                    cursor, stop, duration, partChars))
                assignedChars += partChars
                cursor = stop
            }
        }
        chars = 0
    }

    companion object {
        const val IDLE_MS = 30_000L
        const val MIN_SECONDS = 10L
    }
}
