package com.lyric.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.ui.theme.LyricTokens
import com.lyric.core.ReaderSettings
import com.lyric.data.ImportStatus
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
        for (dark in listOf(false, true)) {
            showShelf(app, dark)
            // Standard 2-col composes fewer tiles; default sort is 最近阅读, so 可见书01 may be below the fold.
            waitFor("16 本书")
            waitFor("样本书16")
            capture(if (dark) "standard-shelf-night.png" else "standard-shelf-day.png", out)
        }

        runBlocking(Dispatchers.IO) { app.repository.books.first().forEach { app.repository.remove(it.id) } }
        compose.runOnUiThread {
            app.repository.imports.value = ImportStatus(
                running = true, done = 0, total = 3, message = "正在扫描本地文件…", batchId = 1L,
            )
        }
        for (dark in listOf(false, true)) {
            showShelf(app, dark)
            waitFor(LyricTokens.Shelf.importingCopy(3))
            capture(if (dark) "importing-shelf-night.png" else "importing-shelf-day.png", out)
        }
        compose.runOnUiThread { app.repository.imports.value = ImportStatus() }
        plantBooks(app, count = 16)

        openFilterSheet(app, dark = false)
        capture("filter-sheet-day.png", out)
        openFilterSheet(app, dark = true)
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
                // DeskScreen is hosted in the shelf scaffold in the app; paint the theme
                // background here so night screenshots are not the light window color.
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    DeskScreen(hosted, { _, _ -> }, {}, {}, {})
                }
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

    private fun openFilterSheet(app: LyricApp, dark: Boolean) {
        showShelf(app, dark)
        waitFor("筛选")
        compose.onNodeWithText("筛选", substring = false).performClick()
        waitFor("阅读状态")
        waitFor("重置")
        waitFor("完成")
        // Let the sheet finish sliding in. Do not waitForIdle here: dismissing or
        // lingering sheet animations have hung the clock in this harness before.
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun capture(name: String, out: File) {
        idle()
        val bitmap = captureFrame(includeDialogs = name.startsWith("filter-sheet"))
        val fitted = if (bitmap.width == 1080 && bitmap.height == 2400) bitmap else Bitmap.createScaledBitmap(bitmap, 1080, 2400, true)
        val file = File(out, name)
        file.outputStream().use { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(file.length() > 8_000) { "$name is too small (${file.length()} bytes)" }
        println("LYRIC_SCREEN $name ${fitted.width}x${fitted.height} ${file.length()} bytes")
    }

    /**
     * ModalBottomSheet is shown in a dialog window. [androidx.compose.ui.test.onRoot]
     * only captures the activity, which is why filter-sheet previously looked like a
     * plain shelf. Composite every compose root and any shown dialog decor.
     */
    private fun captureFrame(includeDialogs: Boolean): Bitmap {
        val layers = mutableListOf<Bitmap>()
        val rootCount = compose.onAllNodes(isRoot()).fetchSemanticsNodes().size
        println("LYRIC_SCREEN roots=$rootCount dialogs=${org.robolectric.shadows.ShadowDialog.getShownDialogs().size} includeDialogs=$includeDialogs")
        val lastRoot = if (includeDialogs) rootCount else minOf(rootCount, 1)
        for (index in 0 until lastRoot) {
            runCatching { compose.onAllNodes(isRoot())[index].captureToImage().asAndroidBitmap() }
                .onSuccess { layers += it }
        }
        if (layers.isEmpty()) {
            runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.onSuccess { layers += it }
        }
        compose.runOnUiThread {
            fun draw(view: android.view.View): Bitmap? {
                val width = view.width.takeIf { it > 0 } ?: return null
                val height = view.height.takeIf { it > 0 } ?: return null
                val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(created))
                return created
            }
            if (layers.isEmpty()) draw(compose.activity.window.decorView)?.let { layers += it }
            if (includeDialogs) {
                org.robolectric.shadows.ShadowDialog.getShownDialogs().forEach { dialog ->
                    dialog.window?.decorView?.let { decor -> draw(decor)?.let { layers += it } }
                }
            }
        }
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        check(layers.isNotEmpty()) { "no window to capture" }
        val base = layers.first()
        if (layers.size == 1) return base
        val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (layer in layers) {
            if (layer.width == out.width && layer.height == out.height) {
                canvas.drawBitmap(layer, 0f, 0f, paint)
            } else {
                val left = (out.width - layer.width).coerceAtLeast(0) / 2f
                val top = (out.height - layer.height).coerceAtLeast(0).toFloat()
                canvas.drawBitmap(layer, left, top, paint)
            }
        }
        return out
    }
}
