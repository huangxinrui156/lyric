package com.lyric.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.lyric.data.ShelfSection
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfTransitionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun frameClockCompletesBothDirectionsAndDoesNotSkipReducedCrossfade() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val state = ShelfTransitionState(ShelfSection.BOOK)
        compose.mainClock.autoAdvance = false
        activity.get().setContent {
            LyricTheme {
                ShelfTransition(state) { section ->
                    Box(Modifier.fillMaxSize()) { Text(section.label) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("书架").assertExists()
        for ((next, duration) in listOf(ShelfSection.COMIC to 700L, ShelfSection.BOOK to 700L, ShelfSection.COMIC to 200L)) {
            compose.runOnIdle { assertTrue(state.start(next, duration == 200L)) }
            compose.waitForIdle()
            // One composition frame and one frame to establish the start timestamp.
            compose.mainClock.advanceTimeBy(32)
            compose.mainClock.advanceTimeBy(duration / 2)
            compose.runOnIdle {
                assertTrue(state.running)
                assertTrue("$next / $duration: fraction=${state.fraction}", state.fraction > 0f && state.fraction < 1f)
                assertFalse(state.start(next.other(), false))
            }
            compose.mainClock.advanceTimeBy(duration / 2 + 48)
            compose.runOnIdle { assertFalse(state.running); assertEquals(next, state.target) }
            compose.onNodeWithText(next.label).assertExists()
            compose.onNodeWithText(next.other().label).assertDoesNotExist()
        }
        activity.pause().stop().destroy()
    }
}
