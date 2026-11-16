package com.lyric.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w800dp-h600dp-xhdpi")
class ReaderWidthTest {
    @Test fun wideCanvasKeeps640DpBodyCenteredAndPhoneKeepsUserMargin() {
        val canvas = ReaderCanvas(ApplicationProvider.getApplicationContext<Application>())
        val density = canvas.resources.displayMetrics.density
        val sidePadding = ReaderCanvas::class.java.getDeclaredMethod("getHorizontal").apply { isAccessible = true }
        for (widthDp in listOf(360, 800, 1200)) {
            canvas.layout(0, 0, (widthDp * density).toInt(), (600 * density).toInt())
            val left = sidePadding.invoke(canvas) as Float
            val bodyWidth = canvas.width - left * 2
            val right = canvas.width - (left + bodyWidth)
            assertEquals("equal side space at $widthDp dp", left, right, .01f)
            assertTrue(bodyWidth <= LyricTokens.Reading.bodyMaxWidthDp * density)
            val expected = if (widthDp > 640) (widthDp - 640) / 2f else LyricTokens.Reading.default.marginDp
            assertEquals(expected * density, left, .01f)
        }
    }
}
