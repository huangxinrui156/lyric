package com.lyric.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.ui.theme.LyricTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class EmptyShelfUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun firstOpenShowsOnlyGuidanceAndImportButton() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        lateinit var vm: ShelfViewModel
        var opened = 0
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = { opened++ }) } }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.section.value != null && vm.books.value.isEmpty()
        }
        compose.onNodeWithText(LyricTokens.Shelf.emptyCopy).assertIsDisplayed()
        compose.onNodeWithText(LyricTokens.Shelf.emptyImportLabel).assertIsDisplayed()
        listOf("最近阅读", "导入时间", "书名", "0 本书", "＋ 导入本地书籍", "把一本书带进来", "选择文件夹", "本地珍藏 0 本").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        // One import control only: the empty-state button, not the header "+" or the grid tile.
        compose.onAllNodesWithText(LyricTokens.Shelf.emptyImportLabel).assertCountEquals(1)
        compose.onNodeWithText(LyricTokens.Shelf.emptyImportLabel).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("把一本书带进来").assertDoesNotExist()
        compose.onNodeWithText("选择文件").assertDoesNotExist()
        assertEquals(0, opened)
    }

    @Test fun emptyImportOpensTheBookWithoutAFourthStep() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        lateinit var vm: ShelfViewModel
        val opened = mutableListOf<String>()
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = { opened += it }) } }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.section.value != null
        }
        val file = java.io.File(app.cacheDir, "empty-open.txt").apply {
            writeText("第一章 开始\n\n" + "正文。".repeat(80))
        }
        runBlocking(Dispatchers.IO) { app.repository.importUris(listOf(android.net.Uri.fromFile(file))) }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            opened.isNotEmpty()
        }
        val id = runBlocking { app.repository.books.first().single().id }
        assertEquals(listOf(id), opened)
        assertNull(app.repository.imports.value.openBookId)
    }
}
