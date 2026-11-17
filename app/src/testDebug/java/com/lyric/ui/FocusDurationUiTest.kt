package com.lyric.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FocusDurationUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun nativeGraphics() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
    }

    @Test fun customChipExpandsStepperAndPersistsChoice() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        runBlocking { app.repository.settings.focusPlan(25, custom = false) }
        lateinit var vm: DeskViewModel
        compose.runOnUiThread { vm = DeskViewModel(app) }
        compose.setContent { LyricTheme { DeskScreen(vm, { _, _ -> }, {}, {}, {}) } }
        compose.waitUntil(10_000) {
            idle()
            compose.onAllNodesWithText("自定义").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("25").assertIsDisplayed()
        compose.onNodeWithText("45").assertIsDisplayed()
        compose.onNodeWithText("自定义").performClick()
        compose.waitUntil(10_000) {
            idle()
            vm.customPlanned.value && compose.onAllNodesWithContentDescription("自定义时长 25 分钟").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("25 分钟").assertIsDisplayed()
        repeat(1) { compose.onNodeWithText("+").performClick() }
        compose.waitUntil(10_000) {
            idle()
            vm.plannedMin.value == 30
        }
        compose.onNodeWithText("30 分钟").assertIsDisplayed()
        assertEquals(30, runBlocking { app.repository.settings.focusPlannedMin.first() })
        assertTrue(runBlocking { app.repository.settings.focusCustom.first() })
        compose.onNodeWithText("45").performClick()
        compose.waitUntil(10_000) {
            idle()
            !vm.customPlanned.value && vm.plannedMin.value == 45
        }
        assertFalse(runBlocking { app.repository.settings.focusCustom.first() })
        assertEquals(45, runBlocking { app.repository.settings.focusPlannedMin.first() })
    }

    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
