package com.lyric.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueReadingLabelTest {
    @Test fun usesTheChapterTitleWhenItIsPresent() {
        assertEquals("第三章", continueReadingChapterLabel("第三章", 0))
        assertEquals("第三章", continueReadingChapterLabel("  第三章  ", 11))
    }

    @Test fun fallsBackToNumberedChapterWhenTitleIsMissing() {
        assertEquals("", continueReadingChapterLabel("", null))
        assertEquals("", continueReadingChapterLabel("   ", null))
        assertEquals("", continueReadingChapterLabel("未读", null))
        assertEquals("第 5 章", continueReadingChapterLabel("", 4))
        assertEquals("第 5 章", continueReadingChapterLabel("未读", 4))
    }
}
