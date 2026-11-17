package com.lyric.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ReadingProgressLabelTest {
    @Test fun unknownProgressShowsChapterLabelAndNeverPlaceholderDashes() {
        assertEquals("第 1 章", readingChapterPlaceholder(0, 12))
        assertEquals("第 3 章", readingChapterPlaceholder(2, 12))
        assertEquals("第 12 章", readingChapterPlaceholder(11, 12))
        assertEquals("第 12 章", readingChapterPlaceholder(99, 12))
        assertEquals("第 1 章", readingChapterPlaceholder(-1, 12))
        assertEquals("第 1 章", readingChapterPlaceholder(0, 0))
        assertEquals("第 3 章", readingProgressText(false, 2, .5f, 12))
        assertFalse(readingProgressText(false, 2, .5f, 12).contains("--"))
        assertFalse(readingProgressText(false, 2, .5f, 12).contains("%"))
    }

    @Test fun knownProgressMatchesExactPercentLabel() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals(readingPercentLabel(.0001f), readingProgressText(true, 2, .0001f, 12))
            assertEquals("0.01%", readingProgressText(true, 2, .0001f, 12))
            assertEquals("12.3%", readingProgressText(true, 0, .123f, 12))
            assertEquals("50.0%", readingProgressText(true, 4, .5f, 12))
            assertEquals("100.0%", readingProgressText(true, 11, 1f, 12))
            assertFalse(readingProgressText(true, 0, 0f, 12).contains("--"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun unknownUsesTokenSecondaryAndKnownKeepsPercentColor() {
        val percent = Color(0xFF123456)
        assertEquals(LyricTokens.Day.secondary, readingProgressColor(false, false, percent))
        assertEquals(LyricTokens.Night.secondary, readingProgressColor(false, true, percent))
        assertEquals(0xFF8A8478.toInt(), readingProgressColor(false, false, percent).toArgb())
        assertEquals(0xFF7D786E.toInt(), readingProgressColor(false, true, percent).toArgb())
        assertEquals(percent, readingProgressColor(true, false, percent))
        assertEquals(percent, readingProgressColor(true, true, percent))
    }
}
