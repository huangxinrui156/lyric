package com.lyric.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.core.ReaderSettings
import com.lyric.core.ReaderTheme
import com.lyric.data.SettingsScope
import com.lyric.data.ShelfBook
import com.lyric.data.ShelfSection
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

/**
 * Opt-in JVM screenshots for README candidates. Run via tools/render-screens.sh
 * (or -Plyric.render.screens=true). Uses public-domain titles and generated covers.
 * 1080×2400 at density 2.75 → w393dp-h873dp-440dpi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w393dp-h873dp-440dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadmeScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var scene by mutableStateOf<@Composable () -> Unit>({})

    private fun host() {
        compose.setContent { scene() }
    }

    @Test fun renderReadmeCandidates() {
        assumeTrue(
            "Pass -Plyric.render.screens=true to render README screenshots",
            System.getProperty("lyric.render.screens") == "true",
        )
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val out = File(System.getProperty("lyric.screens.dir") ?: error("lyric.screens.dir is not set")).apply { mkdirs() }
        host()

        clearShelf(app)
        showShelf(app, dark = false)
        waitForText(LyricTokensEmpty)
        capture("empty-shelf-day.png", out)

        showShelf(app, dark = true)
        waitForText(LyricTokensEmpty)
        capture("empty-shelf-night.png", out)

        plantShelf(app)
        showShelf(app, dark = false)
        waitForText("红楼梦")
        capture("shelf-day.png", out)

        showShelf(app, dark = true)
        waitForText("红楼梦")
        capture("shelf-night.png", out)

        val textId = plantReaderBook(app)
        showReader(app, textId, dark = false, nightPaper = false)
        waitForPaintedPage()
        capture("reader-day.png", out)

        showReader(app, textId, dark = true, nightPaper = true)
        waitForPaintedPage()
        capture("reader-night.png", out)

        val comicId = plantComic(app)
        showReader(app, comicId, dark = false, nightPaper = false)
        waitForComic()
        revealComicChrome()
        capture("comic-day.png", out)

        showReader(app, comicId, dark = true, nightPaper = true)
        waitForComic()
        revealComicChrome()
        capture("comic-night.png", out)
    }

    private val LyricTokensEmpty = com.lyric.ui.theme.LyricTokens.Shelf.emptyCopy

    private fun idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun clearShelf(app: LyricApp) {
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
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
            vm.section.value == ShelfSection.BOOK
        }
    }

    private fun showReader(app: LyricApp, id: String, dark: Boolean, nightPaper: Boolean) {
        runBlocking(Dispatchers.IO) {
            val prefs = app.repository.settings.settings(id).first()
            app.repository.settings.update(
                SettingsScope.BOOK, id, prefs,
                prefs.copy(
                    appTheme = if (dark) "dark" else "light",
                    theme = if (nightPaper) ReaderTheme.NIGHT else ReaderTheme.PAPER,
                    followSystem = false,
                    showChapter = true,
                    showPercent = true,
                ),
            )
        }
        lateinit var vm: ReaderViewModel
        compose.runOnUiThread { vm = ReaderViewModel(app, id) }
        lastVm = vm
        val hosted = vm
        scene = {
            LyricTheme(ReaderSettings(appTheme = if (dark) "dark" else "light")) {
                ReaderScreen(hosted) {}
            }
        }
        idle()
    }

    private var lastVm: ReaderViewModel? = null

    private fun waitUntilBook() {
        val vm = requireNotNull(lastVm)
        compose.waitUntil(15_000) {
            idle()
            vm.state.value.book != null && vm.state.value.error == null
        }
        compose.mainClock.advanceTimeBy(600)
        idle()
    }

    /** Let typography and the first-content fade finish without capturing on the UI thread. */
    private fun waitForPaintedPage() {
        waitUntilBook()
        repeat(12) {
            compose.mainClock.advanceTimeBy(250)
            idle()
            Thread.sleep(80)
            idle()
        }
    }

    private fun waitForComic() {
        waitUntilBook()
        compose.waitUntil(10_000) {
            idle()
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("comic-reader"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun revealComicChrome() {
        // Let the first-open hint fade, then tap the middle to show the page chrome.
        compose.mainClock.advanceTimeBy(2_500)
        idle()
        runCatching {
            compose.onNodeWithTag("comic-reader").performTouchInput {
                click(Offset(width * .5f, height * .5f))
            }
        }
        compose.mainClock.advanceTimeBy(400)
        idle()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) {
            idle()
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun plantShelf(app: LyricApp) {
        runBlocking(Dispatchers.IO) {
            app.repository.settings.shelfSection(ShelfSection.BOOK)
            app.repository.books.first().forEach { app.repository.remove(it.id) }
            val samples = listOf(
                Sample("sample-a.txt", "红楼梦", "曹雪芹", "第一回 甄士隐梦幻识通灵", 0.34f, lastRead = 40),
                Sample("sample-b.txt", "浮生六记", "沈复", "闺房记乐", 0.12f, lastRead = 20),
                Sample("sample-c.txt", "西游记", "吴承恩", "第一回 灵根育孕源流出", 0f, lastRead = 0),
                Sample("sample-d.txt", "水浒传", "施耐庵", "未读", 0f, lastRead = 0),
                Sample("sample-e.txt", "三国演义", "罗贯中", "未读", 0f, lastRead = 0),
                Sample("sample-f.txt", "牡丹亭", "汤显祖", "未读", 0f, lastRead = 0),
            )
            samples.forEachIndexed { index, sample ->
                val file = File(app.cacheDir, sample.file).apply {
                    writeText(sampleBody(sample.title, sample.chapter))
                }
                app.repository.dao.insert(
                    ShelfBook(
                        id = "sample-$index",
                        uri = Uri.fromFile(file).toString(),
                        name = file.name,
                        title = sample.title,
                        author = sample.author,
                        extension = "txt",
                        totalChars = 800,
                        chapterCount = 3,
                        encoding = "UTF-8",
                        addedAt = index.toLong(),
                        lastRead = sample.lastRead,
                        chapterTitle = sample.chapter,
                        percent = sample.percent,
                        excerpt = "此开卷第一回也。",
                    ),
                )
            }
        }
    }

    private fun plantReaderBook(app: LyricApp): String = runBlocking(Dispatchers.IO) {
        val file = File(app.cacheDir, "reader-sample.txt").apply {
            writeText(
                """
                第一回 甄士隐梦幻识通灵

                此开卷第一回也。作者自云：因曾历过一番梦幻之后，故将真事隐去，而借通灵之说，撰此石头记一书也。故曰甄士隐云云。

                看官：你道此书从何而来？说起根由虽近荒唐，细按则深有趣味。待在下将此来历注明，方使阅者了然不惑。

                满纸荒唐言，一把辛酸泪。都云作者痴，谁解其中味？
                """.trimIndent().let { it + "\n\n" + "列位看官：你道此书从何而来？说起根由虽近荒唐，细按则深有趣味。\n".repeat(16) },
            )
        }
        app.repository.importUris(listOf(Uri.fromFile(file)))
        app.repository.dao.all().first { it.name == file.name }.id
    }

    private fun plantComic(app: LyricApp): String = runBlocking(Dispatchers.IO) {
        val file = File(app.cacheDir, "点石斋画报.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(3) { page ->
                zip.putNextEntry(ZipEntry("p${page + 1}.png"))
                zip.write(comicPage(page, 3))
                zip.closeEntry()
            }
        }
        app.repository.importUris(listOf(Uri.fromFile(file)))
        val row = app.repository.dao.all().first { it.name == file.name }
        val book = row.copy(title = "点石斋画报", author = "吴友如")
        app.repository.dao.update(book)
        row.id
    }

    private fun comicPage(index: Int, total: Int): ByteArray {
        val bmp = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paper = intArrayOf(0xFFF4EFE6.toInt(), 0xFFE8E1CF.toInt(), 0xFFC9D3C0.toInt())
        canvas.drawColor(paper[index % paper.size])
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2B2A27.toInt()
            textSize = 72f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2B2A27.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRect(72f, 96f, 1008f, 1344f, frame)
        canvas.drawText("点石斋画报", 140f, 280f, ink)
        canvas.drawText("第 ${index + 1} 页 / $total", 140f, 400f, ink)
        val note = Paint(ink).apply { textSize = 42f; color = 0xFF8A8478.toInt() }
        canvas.drawText("为截图绘制的彩页，不是扫描件。", 140f, 500f, note)
        return java.io.ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
    }

    private fun sampleBody(title: String, chapter: String) =
        "$chapter\n\n$title\n\n此开卷第一回也。作者自云：因曾历过一番梦幻之后，故将真事隐去。\n".repeat(6)

    private data class Sample(
        val file: String,
        val title: String,
        val author: String,
        val chapter: String,
        val percent: Float,
        val lastRead: Long,
    )

    private fun capture(name: String, out: File) {
        idle()
        val bitmap = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }
            .getOrElse { drawDecor() }
        val fitted = fitScreenshot(bitmap)
        val file = File(out, name)
        file.outputStream().use { fitted.compress(Bitmap.CompressFormat.PNG, 100, it) }
        check(file.length() > 8_000) { "$name is too small (${file.length()} bytes), capture likely failed" }
        println("LYRIC_SCREEN $name ${fitted.width}x${fitted.height} ${file.length()} bytes (source ${bitmap.width}x${bitmap.height})")
    }

    /** Crop or pad when the capture is off by a single pixel; scale only if the size is farther off. */
    private fun fitScreenshot(src: Bitmap): Bitmap {
        if (src.width == 1080 && src.height == 2400) return src
        val nearWidth = kotlin.math.abs(src.width - 1080) <= 1
        val nearHeight = kotlin.math.abs(src.height - 2400) <= 1
        if (nearWidth && nearHeight) {
            val out = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, (1080 - src.width) / 2f, (2400 - src.height) / 2f, null)
            return out
        }
        return Bitmap.createScaledBitmap(src, 1080, 2400, true)
    }

    private fun drawDecor(): Bitmap {
        val decor = compose.activity.window.decorView
        var bmp: Bitmap? = null
        compose.runOnUiThread {
            val width = decor.width.takeIf { it > 0 } ?: 1080
            val height = decor.height.takeIf { it > 0 } ?: 2400
            val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            decor.draw(Canvas(created))
            bmp = created
        }
        idle()
        return requireNotNull(bmp) { "decor view could not be drawn" }
    }
}
