package com.lyric.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.Job
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.core.*
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
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
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenAdaptationUiTest {
    // fontScale is a Configuration field, not an Android resource qualifier. Set it before Activity creation.
    @get:Rule(order = 0) val fontScale = object : ExternalResource() {
        override fun before() { RuntimeEnvironment.setFontScale(1.3f) }
        override fun after() { RuntimeEnvironment.setFontScale(1f) }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Before fun nativeGraphics() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
    }

    private fun assertTextFits(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        val bounds = try { node.getUnclippedBoundsInRoot() } catch (error: AssertionError) {
            throw AssertionError("$text missing: " + compose.onRoot(useUnmergedTree = true).printToString(), error)
        }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        try { node.assertIsDisplayed() } catch (error: AssertionError) {
            throw AssertionError("$text is not displayed: $bounds / $root", error)
        }
        assertTrue("$text outside horizontal viewport: $bounds / $root", bounds.left >= root.left && bounds.right <= root.right)
        assertTrue("$text outside vertical viewport: $bounds / $root", bounds.top >= root.top && bounds.bottom <= root.bottom)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(action(layouts))
            layouts.forEach { layout ->
                val ellipsized = (0 until layout.lineCount).any(layout::isLineEllipsized)
                assertTrue("$text clipped vertically", !layout.didOverflowHeight || ellipsized)
                for (line in 0 until layout.lineCount) {
                    assertTrue("$text clipped horizontally",
                        layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1 || layout.isLineEllipsized(line))
                }
                assertTrue("$text measured taller than its bounds", layout.size.height <= (bounds.bottom - bounds.top).value * compose.density.density + 1)
            }
        }
    }

    private fun mainSurfaces() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        // Let initial sample import finish before inspecting the shelf's count/layout.
        compose.waitUntil(15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            app.applicationScope.coroutineContext[Job]!!.children.none { it.isActive }
        }
        lateinit var shelf: ShelfViewModel
        lateinit var me: MeViewModel
        lateinit var desk: DeskViewModel
        compose.runOnUiThread {
            shelf = ShelfViewModel(app)
            me = MeViewModel(app)
            desk = DeskViewModel(app)
        }
        compose.setContent {
            val scale = LocalDensity.current.fontScale
            SideEffect { assertEquals(1.3f, scale, .001f) }
            LyricTheme {
                ShelfScreen(shelf, me = { MeScreen(me) {} },
                    desk = { DeskScreen(desk, { _, _ -> }, {}, {}, {}) }, open = {})
            }
        }
        compose.waitUntil(10_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            shelf.section.value != null
        }
        assertTextFits(" · 书架")
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        val countNode = compose.onNode(SemanticsMatcher("shelf book count") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { Regex("\\d+ 本书").matches(it.text) } == true
        }, useUnmergedTree = true)
        val countLabel = countNode.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        listOf("最近阅读", "导入时间", "书名", countLabel).forEach(::assertTextFits)
        val sort = compose.onNodeWithText("书名", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val count = countNode.getUnclippedBoundsInRoot()
        assertTrue("Count must wrap or follow sort chips", count.top >= sort.bottom || count.left >= sort.right)
        compose.onNodeWithText("案头").performClick()
        listOf(" · 案头", "今日一句", "专注阅读", "25 分钟", "45 分钟").forEach(::assertTextFits)
        compose.onNodeWithText("我的").performClick()
        listOf("阅读足迹", "累计读完", "连续阅读").forEach(::assertTextFits)
        val left = compose.onNodeWithText("今日 · 本周 0 分", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val middle = compose.onNodeWithText("累计读完", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("Statistics columns overlap", left.right <= middle.left)
    }

    @Test fun phoneMainSurfacesAtFontScale13() = mainSurfaces()
    @Test @Config(qualifiers = "sw800dp-w800dp-h1000dp")
    fun tabletMainSurfacesAtFontScale13() = mainSurfaces()

    private fun settings() {
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        lateinit var vm: MeViewModel
        compose.runOnUiThread { vm = MeViewModel(app) }
        val section = mutableStateOf("appearance")
        compose.setContent { LyricTheme(vm.preferences.collectAsState().value) { SettingsScreen(vm, section.value) {} } }
        listOf("跟随系统", "浅色", "深色", "阅读主题跟随系统深色").forEach(::assertTextFits)
        listOf("浅色" to "light", "深色" to "dark", "跟随系统" to "system").forEach { (label, mode) ->
            compose.onNodeWithText(label).performClick()
            compose.waitUntil(5_000) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                vm.preferences.value.appTheme == mode
            }
            compose.onNodeWithText(label).assertIsSelected()
        }
        compose.runOnIdle { section.value = "reading" }
        listOf("排版", "紧凑", "舒适", "宽松", "更多", "字体", "首行缩进").forEach(::assertTextFits)
        compose.onNodeWithText("更多").performClick()
        listOf("字号", "行距", "段距", "页边距", "字体", "首行缩进").forEach(::assertTextFits)
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        listOf("阅读时保持屏幕常亮", "跟随系统", "竖屏", "横屏").forEach(::assertTextFits)
    }

    @Test fun phoneSettingsAtFontScale13() = settings()
    @Test @Config(qualifiers = "sw600dp-w600dp-h1000dp")
    fun tabletSettingsAtFontScale13() = settings()

    private fun chrome() {
        val book = Book("adapt", chapters = listOf(Chapter(0, "章节", "正文")))
        compose.setContent {
            LyricTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    ComicTopBar("漫画标题", "章节副标题", "rtl", {}, {},
                        insets = WindowInsets(left = 40.dp, top = 28.dp, right = 16.dp))
                    ComicDirectionHint("rtl") {}
                    ReaderFooter(book, ReaderSettings(showPage = true, showPercent = true, showTime = true, showBattery = true),
                        LyricTokens.Day.secondary, false, remember { mutableStateOf("12345 / 12345") }, remember { mutableStateOf(Locator()) })
                    SettingStepper("字号", 18f, listOf(16f, 18f, 20f), "18sp") {}
                    SettingToggle("阅读时保持屏幕常亮", true) {}
                }
            }
        }
        listOf("漫画标题", "章节副标题", ComicDirections.hint("rtl"), "12345 / 12345", "0.0%", "字号", "18sp", "阅读时保持屏幕常亮").forEach(::assertTextFits)
        val hintText = compose.onNodeWithText(ComicDirections.hint("rtl")).getUnclippedBoundsInRoot()
        val hint = compose.onNodeWithTag("direction-hint").getUnclippedBoundsInRoot()
        ComicDirections.all.forEach { direction ->
            val description = ComicDirections.label(direction) + if (direction == "rtl") "（当前）" else ""
            val button = compose.onNodeWithContentDescription(description).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Direction button outside hint", button.left >= hint.left && button.right <= hint.right && button.bottom <= hint.bottom)
            assertTrue("Direction button overlaps hint text", button.top >= hintText.bottom || button.left >= hintText.right)
        }
        val title = compose.onNodeWithText("漫画标题").getUnclippedBoundsInRoot()
        val chapter = compose.onNodeWithText("章节副标题").getUnclippedBoundsInRoot()
        assertTrue("Title enters side cutout", title.left >= 40.dp)
        assertTrue("Title enters status bar", title.top >= 28.dp)
        assertTrue("Chapter overlaps title", chapter.top >= title.bottom)
        val toggleText = compose.onNodeWithText("阅读时保持屏幕常亮").getUnclippedBoundsInRoot()
        val toggle = compose.onNode(isToggleable()).getUnclippedBoundsInRoot()
        assertTrue("Toggle overlaps label", toggleText.right <= toggle.left)
    }

    @Test fun phoneReaderAndComicChromeAtFontScale13() = chrome()
    @Test @Config(qualifiers = "sw600dp-w800dp-h600dp")
    fun wideReaderAndComicChromeAtFontScale13() = chrome()
}
