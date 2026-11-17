package com.lyric.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.core.ReaderSettings
import com.lyric.data.ImportedWithin
import com.lyric.data.ReadingStatusFilter
import com.lyric.data.ShelfBook
import com.lyric.data.ShelfFilter
import com.lyric.data.ShelfSection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w393dp-h873dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AcceptanceScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var scene by mutableStateOf<@Composable () -> Unit>({})

    @Test fun renderAcceptanceScenes() {
        assumeTrue(System.getProperty("lyric.render.screens") == "true")
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val out = File(System.getProperty("lyric.screens.dir") ?: error("lyric.screens.dir is not set")).apply { mkdirs() }
        compose.setContent { scene() }

        plantBooks(app, count = 16)
        runBlocking { app.repository.settings.shelfCompact(ShelfSection.BOOK, true) }
        for (dark in listOf(false, true)) {
            showShelf(app, dark)
            waitFor("可见书01")
            capture(if (dark) "compact-shelf-night.png" else "compact-shelf-day.png", out)
        }

        runBlocking {
            app.repository.settings.shelfCompact(ShelfSection.BOOK, false)
            app.repository.settings.shelfFilter(ShelfSection.BOOK, ShelfFilter())
        }
        showShelf(app, dark = false)
        waitFor("筛选")
        compose.onNodeWithText("筛选").performClick()
        waitFor("阅读状态")
        capture("filter-sheet-day.png", out)
        showShelf(app, dark = true)
        waitFor("筛选")
        compose.onNodeWithText("筛选").performClick()
        waitFor("阅读状态")
        capture("filter-sheet-night.png", out)

        val active = ShelfFilter(statuses = setOf(ReadingStatusFilter.READING, ReadingStatusFilter.UNREAD), imported = ImportedWithin.DAYS_30)
        runBlocking { app.repository.settings.shelfFilter(ShelfSection.BOOK, active) }
        for (dark in listOf(false, true)) {
            showShelf(app, dark)
            waitFor("筛选 · 3")
            capture(if (dark) "filter-active-shelf-night.png" else "filter-active-shelf-day.png", out)
        }

        runBlocking { app.repository.settings.shelfFilter(ShelfSection.BOOK, ShelfFilter(statuses = setOf(ReadingStatusFilter.FINISHED))) }
        for (dark in listOf(false, true)) {
            showShelf(app, dark)
            waitFor("没有符合条件的书")
            capture(if (dark) "filtered-empty-night.png" else "filtered-empty-day.png", out)
        }

        runBlocking { app.repository.settings.focusPlan(30, custom = true) }
        for (dark in listOf(false, true)) {
            showDesk(app, dark)
            waitFor("30 分钟")
            capture(if (dark) "focus-stepper-night.png" else "focus-stepper-day.png", out)
        }
    }

    private fun plantBooks(app: LyricApp, count: Int) {
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
            val sources = File(app.filesDir, "sources-v1").apply { mkdirs() }
            repeat(count) { index ->
                val id = "shot-$index"
                val title = if (index == 0) "可见书01" else "样本书${(index + 1).toString().padStart(2, '0')}"
                File(sources, id).writeText(title)
                val file = File(app.cacheDir, "$id.txt").apply { writeText(title) }
                app.repository.dao.insert(
                    ShelfBook(
                        id = id, uri = Uri.fromFile(file).toString(), name = file.name, title = title,
                        author = "作者", extension = "txt", totalChars = 800, chapterCount = 3, encoding = "UTF-8",
                        addedAt = System.currentTimeMillis() - index * 86_400_000L,
                        lastRead = if (index % 3 == 0) 10L + index else 0L,
                        percent = if (index % 3 == 0) 0.3f else 0f,
                    ),
                )
            }
        }
    }

    private fun showShelf(app: LyricApp, dark: Boolean) {
        lateinit var vm: ShelfViewModel
        compose.runOnUiThread { vm = ShelfViewModel(app) }
        val hosted = vm
        scene = {
            LyricTheme(ReaderSettings(appTheme = if (dark) "dark" else "light")) {
                ShelfScreen(hosted, me = {}, desk = {}, open = {})
            }
        }
        idle()
        compose.waitUntil(10_000) {
            idle()
            vm.section.value != null
        }
    }

    private fun showDesk(app: LyricApp, dark: Boolean) {
        lateinit var vm: DeskViewModel
        compose.runOnUiThread { vm = DeskViewModel(app) }
        val hosted = vm
        scene = {
            LyricTheme(ReaderSettings(appTheme = if (dark) "dark" else "light")) {
                DeskScreen(hosted, { _, _ -> }, {}, {}, {})
            }
        }
        idle()
        compose.waitUntil(10_000) {
            idle()
            compose.onAllNodesWithText("专注阅读").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) {
            idle()
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun idle() {
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun capture(name: String, out: File) {
        idle()
        val bitmap = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrElse {
            val decor = compose.activity.window.decorView
            var bmp: Bitmap? = null
            compose.runOnUiThread {
                val created = Bitmap.createBitmap(decor.width.takeIf { it > 0 } ?: 1080, decor.height.takeIf { it > 0 } ?: 2400, Bitmap.Config.ARGB_8888)
                decor.draw(Canvas(created))
                bmp = created
            }
            idle()
            bmp!!
        }
        val fitted = if (bitmap.width == 1080 && bitmap.height == 2400) bitmap else Bitmap.createScaledBitmap(bitmap, 1080, 2400, true)
        val file = File(out, name)
        file.outputStream().use { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(file.length() > 8_000) { "$name is too small (${file.length()} bytes)" }
        println("LYRIC_SCREEN $name ${fitted.width}x${fitted.height} ${file.length()} bytes")
    }
}
