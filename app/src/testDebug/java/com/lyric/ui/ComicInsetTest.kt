package com.lyric.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.RenderNode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.lyric.reader.PageCrop
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** Render the production crop/viewport, including actual scroll and graphics-layer transforms. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicInsetTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun verify(fillWidth: Boolean, spread: Boolean = false, resetZoom: Boolean = false) {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        val page = PageArtwork(bitmap, PageCrop(.1f, .1f, .9f, .9f))
        val art = if (spread) listOf(page, page) else listOf(page)
        val scale = mutableFloatStateOf(1f)
        compose.setContent {
            Box(Modifier.size(300.dp, 200.dp).background(Color.Magenta).testTag("viewport")) {
                ComicArtworkViewport(art, trimmed = true, fillWidth = fillWidth, scale = scale.floatValue, offset = Offset.Zero)
            }
        }
        fun checkEdges() {
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInWindow
            lateinit var snapshot: Bitmap
            compose.runOnUiThread {
                val decor = compose.activity.window.decorView
                val node = RenderNode("comic-inset-test")
                node.setPosition(0, 0, decor.width, decor.height)
                decor.draw(node.beginRecording(decor.width, decor.height)); node.endRecording()
                val method = HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap", RenderNode::class.java,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
                snapshot = (method.invoke(null, node, decor.width, decor.height) as Bitmap).copy(Bitmap.Config.ARGB_8888, false)
            }
            val inset = with(compose.density) { 4.dp.roundToPx() }
            val w = bounds.width.roundToInt(); val h = bounds.height.roundToInt()
            var artworkPixels = 0
            for (y in 0 until h) for (x in 0 until w) {
                val color = snapshot.getPixel(bounds.left.roundToInt() + x, bounds.top.roundToInt() + y)
                if (x < inset || y < inset || x >= w - inset || y >= h - inset)
                    assertEquals("Artwork entered safe inset at $x,$y (zoom=${scale.floatValue})", android.graphics.Color.MAGENTA, color)
                else if (color == android.graphics.Color.RED) artworkPixels++
            }
            assertTrue("The test must actually render cropped artwork", artworkPixels > w * h / 10)
            snapshot.recycle()
        }
        checkEdges()
        if (resetZoom) {
            compose.runOnIdle { scale.floatValue = 2f }
            checkEdges()
            compose.runOnIdle { scale.floatValue = 1f }
            checkEdges()
        }
        if (fillWidth) {
            compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
            compose.mainClock.advanceTimeBy(1000)
            checkEdges()
        }
    }

    @Test fun fitKeepsAllFourInsets() = verify(fillWidth = false)
    @Test fun landscapeFillWidthKeepsInsetsAtBothScrollEnds() = verify(fillWidth = true)
    @Test fun spreadKeepsInsetsAtBothScrollEnds() = verify(fillWidth = true, spread = true)
    @Test fun fitZoomBackToOneKeepsInsets() = verify(fillWidth = false, resetZoom = true)
    @Test fun landscapeZoomBackToOneKeepsInsets() = verify(fillWidth = true, resetZoom = true)
}
