package com.lyric.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingProgressLabelUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun knownPercentAppearsImmediatelyWithoutChapterPlaceholder() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            compose.setContent {
                LyricTheme {
                    ReaderProgressLabel(
                        lengthsReady = true,
                        chapterIndex = 2,
                        fraction = .123f,
                        night = false,
                        percentColor = Color(LyricTokens.ReaderContent.paperMeta),
                        style = LyricTokens.Type.auxiliary,
                        chapterCount = 12,
                    )
                }
            }
            compose.onNodeWithText("12.3%").assertExists()
            compose.onNodeWithText("第 3 章").assertDoesNotExist()
            compose.onNodeWithTag("reading-progress").assertExists()
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun unknownChapterLabelTransitionsToExactPercent() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.US)
        val ready = mutableStateOf(false)
        try {
            assertEquals(150, LyricTokens.Motion.shortMs)
            assertEquals(LyricTokens.Motion.easeOut, LyricTokens.Motion.short<Float>().easing)
            compose.setContent {
                LyricTheme {
                    ReaderProgressLabel(
                        lengthsReady = ready.value,
                        chapterIndex = 2,
                        fraction = .123f,
                        night = false,
                        percentColor = Color(LyricTokens.ReaderContent.paperMeta),
                        style = LyricTokens.Type.auxiliary,
                        chapterCount = 12,
                    )
                }
            }
            compose.onNodeWithText("第 3 章").assertExists()
            compose.onNodeWithText("12.3%").assertDoesNotExist()

            compose.runOnIdle { ready.value = true }
            compose.waitForIdle()
            compose.onNodeWithText("12.3%").assertExists()
            compose.onNodeWithText("第 3 章").assertDoesNotExist()
        } finally {
            Locale.setDefault(original)
        }
    }
}
