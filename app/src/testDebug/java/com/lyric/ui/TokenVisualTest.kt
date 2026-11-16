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

    @Test fun emptyShelfAnchorsAtFortyPercentAndRetainsImportAction() {
        var imports = 0
        compose.setContent {
            LyricTheme {
                Column(Modifier.fillMaxWidth().height(640.dp).testTag("viewport")) {
                    Spacer(Modifier.height(64.dp)) // Existing shelf header, before the visual empty state.
                    EmptyShelf(640.dp * .4f) { imports++ }
                }
            }
        }
        val viewport = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        val copy = compose.onNodeWithText("书架还空着").getUnclippedBoundsInRoot()
        assertEquals(256f, (copy.top - viewport.top).value, 1f)
        compose.onNodeWithText("导入书籍").performClick()
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
                    assertEquals(LyricTokens.Pending.accent(3, true), scheme.secondary)
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
}
