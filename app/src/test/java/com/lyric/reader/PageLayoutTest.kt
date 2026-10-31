package com.lyric.reader

import org.junit.Assert.*
import org.junit.Test

class PageLayoutTest {
    @Test fun coverOffsetOddEvenAndPortraitPairing() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), Spreads.pair(5, true, true, false))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4), listOf(5)), Spreads.pair(6, true, true, false))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2, 3), listOf(4)), Spreads.pair(5, true, true, true))
        assertEquals((0..4).map { listOf(it) }, Spreads.pair(5, false, true, true))
        assertEquals((0..4).map { listOf(it) }, Spreads.pair(5, true, false, false))
        assertEquals(listOf(listOf(0)), Spreads.pair(1, true, true, true))
        assertTrue(Spreads.pair(0, true, true, true).isEmpty())
        for (count in 1..20) for (offset in listOf(false, true)) {
            val pairs = Spreads.pair(count, true, true, offset)
            assertEquals((0 until count).toList(), pairs.flatten())
            for (page in 0 until count) assertTrue(page in pairs[Spreads.indexOf(pairs, page)])
        }
    }
    private fun image(bg: Int, noisy: Boolean = false): IntArray = IntArray(100 * 120) { i ->
        val x = i % 100; val y = i / 100
        if (x in 20..79 && y in 20..99) 0xff666666.toInt()
        else if (noisy) { val n = 240 + i % 16; (0xff000000 or (n.toLong() shl 16) or (n.toLong() shl 8) or n.toLong()).toInt() }
        else bg
    }
    @Test fun whiteBlackAndScanNoiseMarginsKeepGuardPixels() {
        for (pixels in listOf(image(-1), image(0xff000000.toInt()), image(-1, true))) {
            val c = PageTrim.detect(pixels, 100, 120)
            assertEquals(.17f, c.left, .001f); assertEquals(.83f, c.right, .001f)
            assertTrue(c.top < 20f / 120); assertTrue(c.bottom > 100f / 120)
        }
    }
    @Test fun noMarginsBlankColoredAndTinyMarksAreConservative() {
        assertEquals(PageCrop(), PageTrim.detect(IntArray(12000) { 0xff777777.toInt() }, 100, 120))
        assertEquals(PageCrop(), PageTrim.detect(IntArray(12000) { -1 }, 100, 120))
        assertEquals(PageCrop(), PageTrim.detect(IntArray(12000) { 0xff000000.toInt() }, 100, 120))
        val marked = image(-1); marked[50 * 100] = 0xff000000.toInt()
        assertEquals(0f, PageTrim.detect(marked, 100, 120).left)
        val fullBleed = image(0xfff3d1d1.toInt())
        assertEquals(PageCrop(), PageTrim.detect(fullBleed, 100, 120))
    }
}
