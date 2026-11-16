package com.lyric.ui

import android.graphics.Color
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lyric.LyricApp
import com.lyric.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenWindowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun edgeToEdgeWindowLeavesSystemBarsTransparentAndSurfaceFillsWindow() {
        compose.waitForIdle()
        val window = compose.activity.window
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        assertFalse(window.isNavigationBarContrastEnforced)
        val bounds = compose.onRoot().fetchSemanticsNode().boundsInWindow
        assertEquals(0f, bounds.left, 1f)
        assertEquals(0f, bounds.top, 1f)
        assertEquals(window.decorView.width.toFloat(), bounds.right, 1f)
        assertEquals(window.decorView.height.toFloat(), bounds.bottom, 1f)
    }
}
