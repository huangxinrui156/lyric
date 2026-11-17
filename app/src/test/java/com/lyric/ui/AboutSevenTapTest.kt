package com.lyric.ui

import com.lyric.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutSevenTapTest {
    @Test fun sevenTapsOpenSelfTestOnlyWhenDebug() {
        assertFalse(aboutVersionSevenTapsOpenSelfTest(debug = false, taps = 6))
        assertFalse(aboutVersionSevenTapsOpenSelfTest(debug = false, taps = 7))
        assertFalse(aboutVersionSevenTapsOpenSelfTest(debug = false, taps = 8))
        assertFalse(aboutVersionSevenTapsOpenSelfTest(debug = true, taps = 6))
        assertTrue(aboutVersionSevenTapsOpenSelfTest(debug = true, taps = 7))
        assertTrue(aboutVersionSevenTapsOpenSelfTest(debug = true, taps = 8))
    }

    @Test fun thisFlavorUnlocksSelfTestOnlyInDebugBuilds() {
        assertEquals(BuildConfig.DEBUG, aboutVersionSevenTapsOpenSelfTest(BuildConfig.DEBUG, 7))
    }
}
