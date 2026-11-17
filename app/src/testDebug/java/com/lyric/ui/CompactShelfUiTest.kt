package com.lyric.ui

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.data.ShelfBook
import com.lyric.data.ShelfSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w393dp-h873dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactShelfUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun nativeGraphics() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
    }

    @Test fun compactShowsAtLeastTwelveBooksOnPhonePortrait() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val titles = (1..16).map { "可见书${it.toString().padStart(2, '0')}" }
        val vm = showShelf(app, titles, lastRead = 0L, percent = 0f)
        compose.onNodeWithContentDescription("切换到紧凑封面").performClick()
        compose.waitUntil(10_000) {
            idle()
            runBlocking { app.repository.settings.shelfCompact(ShelfSection.BOOK).first() }
        }
        compose.waitForIdle()
        idle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val visible = titles.count { title ->
            compose.onAllNodesWithText(title, substring = false).fetchSemanticsNodes().any { node ->
                val b = node.boundsInRoot
                b.bottom > root.top && b.top < root.bottom && b.height > 0f
            }
        }
        assertTrue("expected ≥12 compact covers on first screen, saw $visible", visible >= 12)
        compose.onNodeWithText("可见书01").assertIsDisplayed()
        // Continue-reading card stays hidden when nothing has been opened.
        compose.onAllNodesWithText("第", substring = true).fetchSemanticsNodes()
        assertFalse(vm.books.value.any { it.lastRead > 0L })
        compose.onNodeWithContentDescription("切换到标准封面").assertIsDisplayed()
    }

    @Test fun compactAndStandardAreRememberedSeparatelyForShelfAndEasel() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val vm = showShelf(app, listOf("书架书A"), section = ShelfSection.BOOK)
        compose.onNodeWithContentDescription("切换到紧凑封面").performClick()
        compose.waitUntil(10_000) {
            idle()
            runBlocking { app.repository.settings.shelfCompact(ShelfSection.BOOK).first() }
        }
        runBlocking(Dispatchers.IO) {
            app.repository.dao.insert(
                ShelfBook(
                    id = "compact-comic-0", uri = "file:///compact-comic-0.cbz", name = "compact-comic-0.cbz",
                    title = "画架书A", author = "", extension = "cbz", contentType = "COMIC",
                    totalChars = 100, chapterCount = 1, encoding = "UTF-8", section = ShelfSection.COMIC.name,
                ),
            )
        }
        compose.onNodeWithContentDescription("切换到画架").performClick()
        compose.waitUntil(10_000) {
            idle()
            vm.section.value == ShelfSection.COMIC &&
                compose.onAllNodesWithContentDescription("切换到紧凑封面").fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(runBlocking { app.repository.settings.shelfCompact(ShelfSection.COMIC).first() })
        assertTrue(runBlocking { app.repository.settings.shelfCompact(ShelfSection.BOOK).first() })
    }

    @Test fun inProgressBooksShowNoProgressTextInCompact() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        runBlocking(Dispatchers.IO) { app.repository.settings.shelfCompact(ShelfSection.BOOK, true) }
        val vm = showShelf(app, listOf("在读书籍"), lastRead = 10L, percent = 0.4f, startCompact = true)
        compose.waitUntil(10_000) {
            idle()
            vm.compact.value && compose.onAllNodesWithText("在读书籍").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("书架书名 在读书籍").assertIsDisplayed()
        // Tile progress text is hidden; the continue-reading card still shows the percentage.
        assertTrue(compose.onAllNodesWithText("请重新导入").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("40%", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("切换到标准封面").assertIsDisplayed()
    }

    private fun showShelf(
        app: LyricApp,
        titles: List<String>,
        lastRead: Long = 0L,
        percent: Float = 0f,
        section: ShelfSection = ShelfSection.BOOK,
        startCompact: Boolean = false,
    ): ShelfViewModel {
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(section)
            app.repository.settings.shelfCompact(ShelfSection.BOOK, startCompact)
            app.repository.settings.shelfCompact(ShelfSection.COMIC, false)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
            val sources = File(app.filesDir, "sources-v1").apply { mkdirs() }
            titles.forEachIndexed { index, title ->
                val id = "compact-$index"
                File(sources, id).writeText(title)
                val file = File(app.cacheDir, "$id.txt").apply { writeText(title) }
                app.repository.dao.insert(
                    ShelfBook(
                        id = id,
                        uri = Uri.fromFile(file).toString(),
                        name = file.name,
                        title = title,
                        author = "",
                        extension = "txt",
                        contentType = if (section == ShelfSection.COMIC) "COMIC" else "TEXT",
                        totalChars = 100,
                        chapterCount = 1,
                        encoding = "UTF-8",
                        lastRead = lastRead,
                        percent = percent,
                        section = section.name,
                    ),
                )
            }
        }
        lateinit var vm: ShelfViewModel
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = {}) } }
        compose.waitUntil(10_000) {
            idle()
            vm.section.value != null && vm.books.value.isNotEmpty() && vm.compact.value == startCompact
        }
        return vm
    }

    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w393dp-h873dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactShelfFontScaleTest {
    @get:Rule(order = 0) val fontScale = object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(1.3f) }
        override fun after() { RuntimeEnvironment.setFontScale(1f) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun compactTitlesStayAtMostTwoLinesAndDoNotOverlapAtFontScale1_3() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val long = "这是一本名字特别特别特别特别特别特别特别长的书用来检查两行省略"
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            app.repository.settings.shelfCompact(ShelfSection.BOOK, true)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
            listOf(long, "另一本也很长的书名用来防止和上一本重叠检查").forEachIndexed { index, title ->
                app.repository.dao.insert(
                    ShelfBook(
                        id = "scale-$index", uri = "file:///scale-$index.txt", name = "scale-$index.txt",
                        title = title, author = "", extension = "txt", totalChars = 100, chapterCount = 1,
                        encoding = "UTF-8",
                    ),
                )
            }
        }
        lateinit var vm: ShelfViewModel
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        compose.setContent { LyricTheme { ShelfScreen(vm, me = {}, desk = {}, open = {}) } }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.section.value != null && runBlocking { app.repository.settings.shelfCompact(ShelfSection.BOOK).first() }
        }
        compose.waitForIdle()
        val titleNodes = compose.onAllNodesWithContentDescription("书架书名", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(titleNodes.size >= 2)
        val bounds = titleNodes.map { it.boundsInRoot }
        for (i in bounds.indices) {
            for (j in i + 1 until bounds.size) {
                val a = bounds[i]; val b = bounds[j]
                val overlap = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
                assertFalse("title $i overlaps $j: $a / $b", overlap)
            }
        }
        compose.onNodeWithContentDescription("书架书名 $long", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(action(layouts))
                layouts.forEach { layout ->
                    assertTrue("compact title exceeded 2 lines: ${layout.lineCount}", layout.lineCount <= 2)
                }
            }
    }
}

