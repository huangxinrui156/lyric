package com.lyric.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.data.ImportStatus
import com.lyric.data.ShelfSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportSummarySnackbarTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun summarySnackbarShowsOnceAndDoesNotReturnAfterTabSwitch() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        lateinit var vm: ShelfViewModel
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
        }
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = {}) } }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.section.value == ShelfSection.BOOK
        }
        val expected = "导入 0 · 跳过 1 · 失败 0"
        assertEquals(expected, ImportStatus(duplicates = 1).summary)
        compose.runOnUiThread {
            app.repository.imports.value = ImportStatus(duplicates = 1, skipped = 0, message = expected, batchId = 42L)
        }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
        compose.onNodeWithText("我的").performClick()
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNodeWithText("书架").performClick()
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNodeWithText("我的").performClick()
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNodeWithText("书架").performClick()
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // First showing may still be on screen until Short duration elapses; it must not be started again.
        compose.mainClock.advanceTimeBy(5_000)
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(expected, substring = true).assertCountEquals(0)
        compose.onNodeWithText("我的").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("书架").performClick()
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onAllNodesWithText(expected, substring = true).assertCountEquals(0)
        assertEquals("", app.repository.imports.value.message)
        assertEquals(expected, app.repository.imports.value.summary)
        assertTrue(app.repository.imports.value.batchId == 42L)
    }
}
