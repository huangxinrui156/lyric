package com.lyric.ui

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.data.ImportedWithin
import com.lyric.data.ReadingStatusFilter
import com.lyric.data.ShelfBook
import com.lyric.data.ShelfFilter
import com.lyric.data.ShelfSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w393dp-h873dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfFilterUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun nativeGraphics() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
    }

    @Test fun filterChipOpensSheetAndEmptyStateCanClear() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val vm = showShelf(app, listOf(Sample("在读书", lastRead = 10, percent = 0.4f)))
        compose.onNodeWithText("筛选").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            idle()
            compose.onAllNodesWithText("阅读状态").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("未读").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(10_000) {
            idle()
            runBlocking { app.repository.settings.shelfFilter(ShelfSection.BOOK).first().isActive }
        }
        compose.onNodeWithText("筛选 · 1").assertIsDisplayed()
        compose.onNodeWithText("未读").assertIsDisplayed()
        compose.onNodeWithText("没有符合条件的书").assertIsDisplayed()
        compose.onNodeWithText("清除筛选").performClick()
        compose.waitUntil(10_000) {
            idle()
            !runBlocking { app.repository.settings.shelfFilter(ShelfSection.BOOK).first().isActive }
        }
        assertTrue(compose.onAllNodesWithText("在读书").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("筛选").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("没有符合条件的书").fetchSemanticsNodes().isEmpty())
        assertEquals(ShelfSection.BOOK, vm.section.value)
    }

    @Test fun compactAndFilterSurviveNewViewModel() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfCompact(ShelfSection.BOOK, true)
            app.repository.settings.shelfFilter(
                ShelfSection.BOOK,
                ShelfFilter(statuses = setOf(ReadingStatusFilter.READING), imported = ImportedWithin.DAYS_30),
            )
        }
        val vm = showShelf(app, listOf(Sample("在读书", lastRead = 10, percent = 0.4f)), resetPrefs = false)
        compose.waitUntil(10_000) {
            idle()
            vm.compact.value && vm.shelfFilter.value.isActive
        }
        assertTrue(runBlocking { app.repository.settings.shelfCompact(ShelfSection.BOOK).first() })
        assertEquals(2, runBlocking { app.repository.settings.shelfFilter(ShelfSection.BOOK).first().activeCount })
        compose.onNodeWithContentDescription("切换到标准封面").assertIsDisplayed()
        compose.onNodeWithText("筛选 · 2").assertIsDisplayed()
        compose.onNodeWithText("在读").assertIsDisplayed()
        compose.onNodeWithText("30 天内").assertIsDisplayed()
    }

    private data class Sample(val title: String, val lastRead: Long = 0, val percent: Float = 0f)

    private fun showShelf(app: LyricApp, samples: List<Sample>, resetPrefs: Boolean = true): ShelfViewModel {
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            if (resetPrefs) {
                app.repository.settings.shelfCompact(ShelfSection.BOOK, false)
                app.repository.settings.shelfFilter(ShelfSection.BOOK, ShelfFilter())
            }
            app.repository.books.first().forEach { app.repository.remove(it.id) }
            val sources = File(app.filesDir, "sources-v1").apply { mkdirs() }
            samples.forEachIndexed { index, sample ->
                val id = "filter-$index"
                File(sources, id).writeText(sample.title)
                val file = File(app.cacheDir, "$id.txt").apply { writeText(sample.title) }
                app.repository.dao.insert(
                    ShelfBook(
                        id = id, uri = Uri.fromFile(file).toString(), name = file.name, title = sample.title,
                        author = "", extension = "txt", totalChars = 100, chapterCount = 1, encoding = "UTF-8",
                        lastRead = sample.lastRead, percent = sample.percent, addedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
        lateinit var vm: ShelfViewModel
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = {}) } }
        compose.waitUntil(10_000) {
            idle()
            vm.section.value != null && vm.books.value.isNotEmpty()
        }
        return vm
    }

    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
