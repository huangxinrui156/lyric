package com.lyric.audio

import org.junit.Assert.*
import org.junit.Test

class NoiseStateTest {
    @Test fun selectionCannotPlayBeforeTimerStartsAndPausesResumeWithoutLosingChoice() {
        var s = NoiseState().select(NoiseSound.RAIN).volume(.7f)
        assertFalse(s.audible)
        s = s.timer(true, false); assertTrue(s.audible)
        s = s.timer(true, true); assertFalse(s.audible) // Includes the reader-leave auto-pause.
        s = s.select(NoiseSound.FIRE); assertFalse(s.audible)
        s = s.timer(true, false); assertTrue(s.audible); assertEquals(NoiseSound.FIRE, s.sound)
        assertFalse(s.copy(focused = false).audible)
        s = s.timer(false, false); assertFalse(s.audible)
        val (choice, volume) = s.persisted()
        val restored = NoiseState.restore(choice, volume)
        assertEquals(NoiseSound.FIRE, restored.sound); assertEquals(.7f, restored.volume)
        assertFalse(restored.audible) // Persistence never starts a new timer/audio session.
        assertEquals(NoiseSound.NONE, NoiseState.restore("corrupt", -1f).sound)
        assertEquals(0f, NoiseState.restore("RAIN", -1f).volume)
    }
    @Test fun crossfadeIsExactly300msAndInterruptedSwitchHasContinuousGain() {
        val gains = NoiseGains(1000)
        val running = NoiseState(NoiseSound.RAIN, 1f, running = true)
        gains.target(running)
        repeat(300) { gains.next() }
        assertEquals(1f, gains.next()[1], .0001f)
        gains.target(running.select(NoiseSound.PAGES))
        repeat(150) { gains.next() }
        val middle = gains.next().copyOf()
        assertEquals(.5f, middle[1], .01f); assertEquals(.5f, middle[2], .01f)
        gains.target(running.select(NoiseSound.FIRE))
        val interrupted = gains.next()
        assertEquals(middle[1], interrupted[1], .01f)
        repeat(300) { gains.next() }
        assertEquals(1f, gains.next()[3], .0001f)
        gains.target(running.select(NoiseSound.NONE))
        repeat(300) { gains.next() }
        assertTrue(gains.next().all { it == 0f })
        gains.reset(); assertTrue(gains.next().all { it == 0f })
    }
}
