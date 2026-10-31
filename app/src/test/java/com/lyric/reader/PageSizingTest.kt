package com.lyric.reader

import org.junit.Assert.*
import org.junit.Test

class PageSizingTest {
    @Test fun comicSamplingPreservesWidthOnlyStripsAndFitsPagedImages() {
        assertEquals(1, pageSampleSize(800, 1200, 1080, 1920))
        assertEquals(2, pageSampleSize(2400, 3600, 1080, 1920))
        assertEquals(1, pageSampleSize(1080, 8000, 1080, Int.MAX_VALUE))
        assertEquals(2, pageSampleSize(1080, 8000, 1080, 2400))
    }
    @Test fun samplingCapsLargeImagesWithoutOverflow() {
        val sample = pageSampleSize(100000, 100000, Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(100000L / sample * (100000 / sample) * 4 <= 48L * 1024 * 1024)
        assertTrue(pageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1, 1) > 0)
        val huge = pageSampleSize(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(Int.MAX_VALUE.toLong() / huge * (Int.MAX_VALUE / huge) <= 48L * 1024 * 1024 / 4)
    }
    @Test fun pdfFitsBothAxesAndRetainsWidthOnlyThumbnailBehavior() {
        assertEquals(1000 to 1500, pdfPageSize(600, 900, 1000, 2000))
        assertEquals(400 to 2000, pdfPageSize(600, 3000, 1000, 2000))
        assertEquals(720 to 3600, pdfPageSize(600, 3000, 720, Int.MAX_VALUE))
        val (w, h) = pdfPageSize(1, 1000000, 2400, Int.MAX_VALUE)
        assertTrue(w.toLong() * h * 4 <= 48L * 1024 * 1024)
    }
}
