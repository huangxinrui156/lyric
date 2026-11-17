package com.lyric.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.lyric.core.ReaderSettings
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
import org.junit.Before
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

    @Before fun nativeGraphics() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
    }


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

    @Test fun nightPageTitlesUseBodyInk() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        compose.setContent {
            LyricTheme(ReaderSettings(appTheme = "dark")) {
                Column(Modifier.background(LyricTokens.Night.background).padding(LyricTokens.Space.lg)) {
                    LyricWordmarkTitle("案头")
                    LyricWordmarkTitle("书架")
                    LyricWordmarkTitle("画架")
                    Text("我的", fontFamily = Serif, style = LyricTokens.Type.title, color = Ink)
                }
            }
        }
        listOf(" · 案头", " · 书架", " · 画架", "我的").forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
        val bitmap = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
            val decor = compose.activity.window.decorView
            var drawn: Bitmap? = null
            compose.runOnUiThread {
                val created = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                decor.draw(android.graphics.Canvas(created))
                drawn = created
            }
            drawn!!
        }
        assertTrue("Night tab titles should be painted with body ink, not black", paintedWith(bitmap, LyricTokens.Night.ink.toArgb()))
    }

    private fun paintedWith(bitmap: Bitmap, target: Int): Boolean {
        val tr = android.graphics.Color.red(target)
        val tg = android.graphics.Color.green(target)
        val tb = android.graphics.Color.blue(target)
        var closest = Int.MAX_VALUE
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val d = kotlin.math.abs(android.graphics.Color.red(p) - tr) +
                    kotlin.math.abs(android.graphics.Color.green(p) - tg) +
                    kotlin.math.abs(android.graphics.Color.blue(p) - tb)
                if (d < closest) closest = d
            }
        }
        return closest <= 30
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
