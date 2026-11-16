package com.lyric.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.lyric.core.ReaderSettings
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypesetSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun customMarkShowsUntilAPresetIsChosenAndFineTuneStartsCollapsed() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        var prefs by mutableStateOf(ReaderSettings(fontSize = 17f, lineHeight = 1.9f, margin = 28f, paragraphSpace = .6f))
        compose.setContent {
            LyricTheme {
                Column {
                    TypesetSettingsBlock(prefs) { prefs = it }
                }
            }
        }
        compose.onNodeWithText(LyricTokens.Reading.presetRowLabel).assertIsDisplayed()
        LyricTokens.Reading.presetLabels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(LyricTokens.Reading.customLabel).assertIsDisplayed()
        compose.onNodeWithText(LyricTokens.Reading.fineTuneLabels[0]).assertDoesNotExist()
        compose.onNodeWithText(LyricTokens.Reading.moreLabel).performClick()
        LyricTokens.Reading.fineTuneLabels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(LyricTokens.Reading.presetLabels[1]).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(LyricTokens.Reading.customLabel).assertDoesNotExist()
        assertEquals(LyricTokens.Reading.comfortable.fontSizeSp, prefs.fontSize)
        assertEquals(LyricTokens.Reading.comfortable.lineHeight, prefs.lineHeight)
        assertEquals(LyricTokens.Reading.comfortable.marginDp, prefs.margin)
        assertEquals(LyricTokens.Reading.comfortable.paragraphEm, prefs.paragraphSpace)
    }
}
