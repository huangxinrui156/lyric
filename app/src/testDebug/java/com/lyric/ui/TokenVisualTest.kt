package com.lyric.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
class TokenVisualTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun emptyShelfAnchorsAtFortyPercentOfRemainingAndRetainsImportAction() {
        var imports = 0
        compose.setContent {
            LyricTheme {
                Column(Modifier.fillMaxWidth().height(640.dp).testTag("viewport")) {
                    Spacer(Modifier.height(64.dp)) // Existing shelf header, before the visual empty state.
                    EmptyShelf(640.dp) { imports++ }
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val copy = compose.onNodeWithText(LyricTokens.Shelf.emptyCopy).getUnclippedBoundsInRoot()
        val action = compose.onNodeWithText(LyricTokens.Shelf.emptyImportLabel).getUnclippedBoundsInRoot()
        // 40% of the remaining 576.dp under the header lands the copy at 294.4.dp.
        assertEquals(294.4f, (copy.top - viewport.top).value, 1f)
        // Button padding sits around the label, so the visible text is a little more than the 16.dp spacer.
        assertTrue((action.top - copy.bottom).value >= LyricTokens.Shelf.emptyActionGap.value - 1f)
        compose.onNodeWithText(LyricTokens.Shelf.emptyImportLabel).performClick()
        assertEquals(1, imports)
    }

    @Test fun nightThemeKeepsPrimaryButtonNeutralAndAccentsSelectable() {
        compose.setContent {
            LyricTheme(ReaderSettings(appTheme = "dark", accentColor = 3)) {
                val scheme = MaterialTheme.colorScheme
                val typography = MaterialTheme.typography
                val ink = Ink
                val secondary = Stone
                SideEffect {
                    assertEquals(0xFF16171A.toInt(), scheme.background.toArgb())
                    assertEquals(0xFF1E1F23.toInt(), scheme.surface.toArgb())
                    assertEquals(0xFFD0CABE.toInt(), scheme.primary.toArgb())
                    assertEquals(0xFF16171A.toInt(), scheme.onPrimary.toArgb())
                    assertEquals(0xFFD0CABE.toInt(), ink.toArgb())
                    assertEquals(0xFF7D786E.toInt(), secondary.toArgb())
                    assertEquals(LyricTokens.Accents.color(3, true), scheme.secondary)
                    assertEquals(14f, typography.bodyLarge.fontSize.value)
                    assertEquals(20f, typography.bodyLarge.lineHeight.value)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun marginsUseSmallestWidthBreakpoint() {
        assertEquals(16.dp, LyricTokens.Page.margin(599))
        assertEquals(24.dp, LyricTokens.Page.margin(600))
        assertEquals(24.dp, LyricTokens.Page.margin(840))
    }
    private fun verifyThemeModes(systemDark: Boolean) {
        assertEquals("system", ReaderSettings().appTheme)
        compose.setContent {
            Column {
                listOf("system", "light", "dark").forEach { mode ->
                    LyricTheme(ReaderSettings(appTheme = mode)) {
                        val background = MaterialTheme.colorScheme.background
                        val expected = LyricTokens.colors(if (mode == "system") systemDark else mode == "dark").background
                        SideEffect { assertEquals("$mode with systemDark=$systemDark", expected, background) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test @Config(qualifiers = "w360dp-h800dp-notnight")
    fun defaultFollowsLightSystemAndManualModesOverrideIt() = verifyThemeModes(systemDark = false)

    @Test @Config(qualifiers = "w360dp-h800dp-night")
    fun defaultFollowsDarkSystemAndManualModesOverrideIt() = verifyThemeModes(systemDark = true)

}
