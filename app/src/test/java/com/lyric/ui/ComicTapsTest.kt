package com.lyric.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicTapsTest {
    @Test fun rightToLeftTurnsForwardOnLeftTap() {
        assertEquals(1, ComicTaps.step(10f, 300f, rtl = true))
        assertEquals(-1, ComicTaps.step(290f, 300f, rtl = true))
        assertEquals(0, ComicTaps.step(150f, 300f, rtl = true))
        assertEquals(-1, ComicTaps.step(10f, 300f, rtl = false))
        assertEquals(1, ComicTaps.step(290f, 300f, rtl = false))
    }
}
