package com.lyric.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lyric.core.BookFormats
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TimedSnackbarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryVisibleAt3900GoneAt4000() {
        assertEquals(4000L, LyricTokens.Motion.ephemeralSnackbarMs)
        assertDismissesAtExactlyFourSeconds("导入 0 · 跳过 1 · 失败 0")
    }

    @Test fun unsupportedOpenWithVisibleAt3900GoneAt4000() {
        assertEquals("不支持这种文件", BookFormats.UNSUPPORTED_OPEN_WITH)
        assertDismissesAtExactlyFourSeconds(BookFormats.UNSUPPORTED_OPEN_WITH)
    }

    @Test fun summaryDismissesOnTap() {
        assertTapDismissesAtOnce("导入 0 · 跳过 1 · 失败 0")
    }

    @Test fun unsupportedOpenWithDismissesOnTap() {
        assertTapDismissesAtOnce(BookFormats.UNSUPPORTED_OPEN_WITH)
    }

    private fun assertDismissesAtExactlyFourSeconds(text: String) {
        val host = SnackbarHostState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LyricTheme {
                Box(Modifier.fillMaxSize()) {
                    TimedSnackbarHost(host)
                    LaunchedEffect(Unit) { host.showTimedSnackbar(text) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        var appeared = false
        repeat(30) {
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                appeared = true
                return@repeat
            }
            compose.mainClock.advanceTimeByFrame()
        }
        assert(appeared) { "snackbar never appeared: $text" }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_900)
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText(text).assertCountEquals(0)
    }

    private fun assertTapDismissesAtOnce(text: String) {
        val host = SnackbarHostState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LyricTheme {
                Box(Modifier.fillMaxSize()) {
                    TimedSnackbarHost(host)
                    LaunchedEffect(Unit) { host.showTimedSnackbar(text) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        repeat(30) {
            if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) return@repeat
            compose.mainClock.advanceTimeByFrame()
        }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onNodeWithText(text).performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithText(text).assertCountEquals(0)
    }
}
