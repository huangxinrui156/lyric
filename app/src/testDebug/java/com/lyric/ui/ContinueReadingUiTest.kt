package com.lyric.ui

import android.net.Uri
import java.io.File

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.data.ShelfBook
import com.lyric.data.ShelfSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinueReadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun shelfShowsTheLatestBookAndOpensItFromTheCard() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
            fun book(id: String, title: String, author: String, lastRead: Long, addedAt: Long = 0, chapter: String = "未读", percent: Float = 0f, excerpt: String = "") : ShelfBook {
                val file = File(app.cacheDir, "$id.txt").apply { writeText("第一章 $title\n\n正文。") }
                return ShelfBook(id, Uri.fromFile(file).toString(), file.name, title, author, "txt",
                    totalChars = 100, chapterCount = 1, encoding = "UTF-8", addedAt = addedAt, lastRead = lastRead,
                    chapterTitle = chapter, percent = percent, excerpt = excerpt)
            }
            app.repository.dao.insert(book("older", "昨天那本", "甲", lastRead = 10, chapter = "第一章", percent = 0.2f, excerpt = "昨日摘录"))
            app.repository.dao.insert(book("latest", "刚才在读", "乙", lastRead = 20, chapter = "第三章", percent = 0.4f, excerpt = "刚才摘录"))
            app.repository.dao.insert(book("unread", "还没打开", "丙", lastRead = 0, addedAt = 30))
        }
        lateinit var vm: ShelfViewModel
        val opened = mutableListOf<String>()
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = { opened += it }) } }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.section.value == ShelfSection.BOOK && vm.books.value.any { it.id == "latest" && !it.invalid } &&
                compose.onAllNodesWithText("第三章 · 40%").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodesWithText("刚才在读").assertCountEquals(2)
        compose.onNodeWithText("第三章 · 40%").assertIsDisplayed()
        compose.onNodeWithText("第三章 · 40%").performClick()
        compose.waitForIdle()
        assertEquals(listOf("latest"), opened)
    }
}
