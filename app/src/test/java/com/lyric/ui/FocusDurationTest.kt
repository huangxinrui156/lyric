package com.lyric.ui

import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusDurationTest {
    @Test fun snapMinutesStaysOnFiveAndClampsRange() {
        assertEquals(5, LyricTokens.Focus.snapMinutes(0))
        assertEquals(5, LyricTokens.Focus.snapMinutes(4))
        assertEquals(5, LyricTokens.Focus.snapMinutes(5))
        assertEquals(10, LyricTokens.Focus.snapMinutes(8))
        assertEquals(30, LyricTokens.Focus.snapMinutes(30))
        assertEquals(180, LyricTokens.Focus.snapMinutes(180))
        assertEquals(180, LyricTokens.Focus.snapMinutes(200))
        assertEquals(175, LyricTokens.Focus.snapMinutes(177))
    }

    @Test fun customChipUsesMinuteLabel() {
        assertEquals("25 分钟", LyricTokens.Focus.chipLabel(25, custom = false))
        assertEquals("45 分钟", LyricTokens.Focus.chipLabel(45, custom = false))
        assertEquals("自定义", LyricTokens.Focus.customLabel)
        assertEquals("30 分钟", LyricTokens.Focus.chipLabel(30, custom = true))
        assertEquals("25 分钟", LyricTokens.Focus.chipLabel(25, custom = true))
        assertTrue(LyricTokens.Focus.isPreset(25, custom = false))
        assertFalse(LyricTokens.Focus.isPreset(25, custom = true))
        assertFalse(LyricTokens.Focus.isPreset(30, custom = true))
    }
}
