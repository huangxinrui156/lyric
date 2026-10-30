package com.lyric.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageCurlTest {
    @Test fun curlRevealsDestinationWithCurvedShadedPaperInBothDirections() {
        val front = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        fun render(progress: Float, direction: Int): Bitmap {
            val output = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr,
                androidx.compose.ui.graphics.Canvas(output.asImageBitmap()), Size(240f, 320f)) {
                drawCurl(front.asImageBitmap(), progress, direction, Color.White)
            }
            return output
        }
        for (direction in listOf(1, -1)) {
            assertEquals(android.graphics.Color.WHITE, render(0f, direction).getPixel(120, 160))
            assertEquals(android.graphics.Color.BLUE, render(1f, direction).getPixel(120, 160))
            val middle = render(.5f, direction)
            assertEquals(android.graphics.Color.WHITE, middle.getPixel(if (direction > 0) 10 else 230, 160))
            assertEquals(android.graphics.Color.BLUE, middle.getPixel(if (direction > 0) 230 else 10, 160))
            val shades = (60..180).map { middle.getPixel(it, 160) }.toSet()
            assertTrue("fold must contain paper shading", shades.size > 5)
        }
    }
}
