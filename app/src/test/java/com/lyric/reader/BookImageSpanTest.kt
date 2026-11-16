package com.lyric.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BookImageSpanTest {
    @Test fun standaloneUsesBodyWidthWithOneEmAboveAndBelow() {
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val span = BookImageSpan("a.png", bmp, 320, 1000, space = 16, standalone = true)
        val fm = Paint.FontMetricsInt()
        assertEquals(320, span.getSize(Paint(), "\uFFFC", 0, 1, fm)) // never wider than the text column
        assertEquals(-(160 + 16), fm.ascent)
        assertEquals(16, fm.descent)
    }
    @Test fun tallImageFitsHeight() {
        val bmp = Bitmap.createBitmap(100, 1000, Bitmap.Config.ARGB_8888)
        val fm = Paint.FontMetricsInt()
        BookImageSpan("a.png", bmp, 320, 500, space = 16, standalone = true).getSize(Paint(), "\uFFFC", 0, 1, fm)
        assertEquals(-(500 + 16), fm.ascent)
    }
}
