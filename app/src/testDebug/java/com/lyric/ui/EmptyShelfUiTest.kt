package com.lyric.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.data.ShelfSection
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

    private fun openEmptyShelf(open: (String) -> Unit = {}): Pair<LyricApp, ShelfViewModel> {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        lateinit var vm: ShelfViewModel
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
        }
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = open) } }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.section.value == ShelfSection.BOOK && vm.books.value.isEmpty() &&
                compose.onAllNodesWithText(LyricTokens.Shelf.emptyCopy)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        return app to vm
    }

    @Test fun firstOpenShowsGuidanceImportButtonAndHeaderAdd() {
        openEmptyShelf()
        compose.onNodeWithText(LyricTokens.Shelf.emptyCopy).assertIsDisplayed()
        compose.onNodeWithText(LyricTokens.Shelf.emptyImportLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(LyricTokens.Shelf.addLabel).assertIsDisplayed()
        listOf("最近阅读", "导入时间", "书名", "0 本书", "＋ 导入本地书籍", "把一本书带进来", "选择文件夹", "本地珍藏 0 本").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        // Center button is the only 「导入书籍」; header "+" is a separate add control.
        compose.onAllNodesWithText(LyricTokens.Shelf.emptyImportLabel).assertCountEquals(1)
        compose.onNodeWithText(LyricTokens.Shelf.emptyImportLabel).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("把一本书带进来").assertDoesNotExist()
        compose.onNodeWithText("选择文件").assertDoesNotExist()
        compose.onNodeWithText("选择文件夹").assertDoesNotExist()
    }

    @Test fun emptyHeaderAddOpensFileAndFolderSheet() {
        openEmptyShelf()
        compose.onNodeWithContentDescription(LyricTokens.Shelf.addLabel).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("把一本书带进来").assertIsDisplayed()
        compose.onNodeWithText("选择文件").assertIsDisplayed()
        compose.onNodeWithText("选择文件夹").assertIsDisplayed()
    }

    @Test fun emptyEaselUsesTheSameLayoutWithComicCopy() {
        openEmptyShelf()
        compose.onNodeWithContentDescription("切换到画架").performClick()
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(LyricTokens.Shelf.emptyComicCopy).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(LyricTokens.Shelf.emptyComicCopy).assertIsDisplayed()
        compose.onNodeWithText(LyricTokens.Shelf.emptyComicImportLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription(LyricTokens.Shelf.addLabel).assertIsDisplayed()
        compose.onNodeWithText(LyricTokens.Shelf.emptyCopy).assertDoesNotExist()
        compose.onNodeWithText(LyricTokens.Shelf.emptyImportLabel).assertDoesNotExist()
    }

    @Test fun emptyImportOpensTheBookWithoutAFourthStep() {
        val opened = mutableListOf<String>()
        val (app, _) = openEmptyShelf { opened += it }
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

    @Test fun multiFileImportStaysOnShelfAndShowsLegacySummary() {
        val opened = mutableListOf<String>()
        val (app, _) = openEmptyShelf { opened += it }
        val a = java.io.File(app.cacheDir, "multi-a.txt").apply { writeText("第一章 A\n\n" + "正文。".repeat(80)) }
        val b = java.io.File(app.cacheDir, "multi-b.txt").apply { writeText("第一章 B\n\n" + "正文。".repeat(80)) }
        runBlocking(Dispatchers.IO) {
            app.repository.importUris(listOf(android.net.Uri.fromFile(a), android.net.Uri.fromFile(b)))
        }
        val expected = "导入 2 · 跳过 0 · 失败 0"
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(expected, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(expected, substring = true).assertIsDisplayed()
        assertTrue(opened.isEmpty())
        assertNull(app.repository.imports.value.openBookId)
        assertEquals(expected, app.repository.imports.value.summary)
        assertEquals(2, runBlocking { app.repository.books.first().size })
    }
}
