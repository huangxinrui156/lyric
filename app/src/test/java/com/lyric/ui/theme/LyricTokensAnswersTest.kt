package com.lyric.ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.lyric.core.ReaderTheme
import org.junit.Assert.*
import org.junit.Test

class LyricTokensAnswersTest {
    @Test fun accentDayNightPairsMatchTokensMd() {
        val expected = listOf(
            "苔绿" to (0xFF5E6B4E.toInt() to 0xFFA3B38A.toInt()),
            "朱红" to (0xFFB5563A.toInt() to 0xFFE08A6E.toInt()),
            "枯茶" to (0xFF8A6A4A.toInt() to 0xFFC2A07E.toInt()),
            "黛蓝" to (0xFF4A5A6B.toInt() to 0xFF8FA3B8.toInt()),
            "灰紫" to (0xFF6E6278.toInt() to 0xFFA99BB5.toInt()),
            "石色" to (0xFF7A7770.toInt() to 0xFFADA89D.toInt()),
        )
        assertEquals(expected.map { it.first }, LyricTokens.Accents.names)
        expected.forEachIndexed { i, (_, pair) ->
            assertEquals(pair.first, LyricTokens.Accents.color(i, false).toArgb())
            assertEquals(pair.second, LyricTokens.Accents.color(i, true).toArgb())
        }
        val title = "剑来"
        val expectedCover = LyricTokens.Accents.color((title.hashCode() and Int.MAX_VALUE) % 6, false)
        assertEquals(expectedCover, LyricTokens.Accents.cover(title))
    }

    @Test fun savedMonospaceRendersAsHeiAndCustomPathsAreKept() {
        assertEquals(LyricTokens.Fonts.heiKey, LyricTokens.Fonts.resolveKey("monospace"))
        assertEquals(LyricTokens.Fonts.heiKey, LyricTokens.Fonts.resolveKey("sans-serif"))
        assertEquals(LyricTokens.Fonts.songKey, LyricTokens.Fonts.resolveKey("serif"))
        assertEquals("/fonts/custom.ttf", LyricTokens.Fonts.resolveKey("/fonts/custom.ttf"))
        assertTrue(LyricTokens.Fonts.isCustom("/fonts/custom.ttf"))
        assertFalse(LyricTokens.Fonts.isCustom("monospace"))
    }

    @Test fun shelfSwitchIs300MsAndTouchTargetsAreAtLeast48() {
        assertEquals(300L, LyricTokens.Motion.shelfSwitchMs)
        assertEquals(LyricTokens.Motion.longMs.toLong(), LyricTokens.Motion.shelfSwitchMs)
        listOf(
            LyricTokens.Component.touchTarget,
            LyricTokens.Component.buttonHeight,
            LyricTokens.Component.searchHeight,
            LyricTokens.Component.compactButtonHeight,
            LyricTokens.Component.smallControl,
        ).forEach { assertTrue(it.toString(), it >= 48.dp) }
    }

    @Test fun defaultReaderThemesFollowUiPalette() {
        assertEquals(LyricTokens.Day.background.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.PAPER.bg)
        assertEquals(LyricTokens.Day.ink.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.PAPER.ink)
        assertEquals(LyricTokens.Night.background.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.NIGHT.bg)
        assertEquals(LyricTokens.Night.ink.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.NIGHT.ink)
        assertEquals(.25f, LyricTokens.Accents.highlightDayAlpha)
        assertEquals(.30f, LyricTokens.Accents.highlightNightAlpha)
        assertEquals(0xFFF4EFE6.toInt(), LyricTokens.Comic.background(false).toArgb())
        assertEquals(0xFF0E0F11.toInt(), LyricTokens.Comic.background(true).toArgb())
    }
}
