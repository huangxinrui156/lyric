package com.lyric.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
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
    private fun page() = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val c = Canvas(this)
        c.drawRect(0f, 0f, 240f, 32f, Paint().apply { color = Color.RED })
        c.drawRect(0f, 288f, 240f, 320f, Paint().apply { color = Color.GREEN })
    }
    private fun render(touch: Offset, direction: Int, top: Boolean = false): Bitmap {
        val target = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
            drawPageCurl(Canvas(this), page(), target, 240f, 320f, touch, direction, top, Color.WHITE)
        }
    }

    @Test fun entireSheetIncludingHeaderAndFooterTurnsInBothDirections() {
        for (direction in listOf(1, -1)) {
            val start = render(Offset(if (direction > 0) 239.5f else .5f, 319.5f), direction)
            assertEquals(Color.RED, start.getPixel(120, 10))
            assertEquals(Color.GREEN, start.getPixel(120, 310))
            val end = render(Offset(if (direction > 0) -240f else 480f, 319.5f), direction)
            assertEquals(Color.BLUE, end.getPixel(120, 10))
            assertEquals(Color.BLUE, end.getPixel(120, 310))
            val middle = render(Offset(120f, 220f), direction)
            assertEquals(Color.WHITE, middle.getPixel(if (direction > 0) 10 else 230, 160))
            assertEquals(Color.BLUE, middle.getPixel(if (direction > 0) 230 else 10, 310))
            val shades = (0 until 240).flatMap { x -> (0 until 320 step 8).map { middle.getPixel(x, it) } }.toSet()
            assertTrue("fold, reverse paper and cast shadows must be visible", shades.size > 20)
            val output = java.io.File("build/reports/curl-${direction}.png").apply { parentFile?.mkdirs() }
            output.outputStream().use { middle.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun cornerTracksFingerAndFoldChangesWithBothTouchAxes() {
        for (direction in listOf(1, -1)) for (top in listOf(true, false)) {
            for (touch in listOf(Offset(100f, 60f), Offset(160f, 200f), Offset(239f, 1f), Offset(1f, 319f))) {
                val geometry = CurlGeometry(240f, 320f, touch, direction, top)
                val corner = floatArrayOf(geometry.corner.x, geometry.corner.y)
                geometry.reflection.mapPoints(corner)
                assertEquals(geometry.finger.x, corner[0], .1f)
                assertEquals(geometry.finger.y, corner[1], .1f)
                assertTrue(corner.all { it.isFinite() })
            }
        }
        val low = render(Offset(120f, 260f), 1)
        val high = render(Offset(120f, 80f), 1)
        assertFalse(low.sameAs(high))
    }

    @Test fun releaseUsesDistanceVelocityAndDirection() {
        assertTrue(curlShouldComplete(1, 80f, 240f, 0f))
        assertFalse(curlShouldComplete(1, 220f, 240f, 0f))
        assertTrue(curlShouldComplete(1, 220f, 240f, -1000f))
        assertFalse(curlShouldComplete(1, 80f, 240f, 1000f))
        assertTrue(curlShouldComplete(-1, 160f, 240f, 0f))
        assertFalse(curlShouldComplete(-1, 20f, 240f, 0f))
        assertTrue(curlShouldComplete(-1, 20f, 240f, 1000f))
        assertFalse(curlShouldComplete(-1, 160f, 240f, -1000f))
    }
}
