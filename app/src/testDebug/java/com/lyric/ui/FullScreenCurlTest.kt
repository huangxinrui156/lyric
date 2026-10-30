package com.lyric.ui

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.core.*
import com.lyric.data.SettingsScope
import com.lyric.reader.ReaderCanvas
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises actual Compose chrome + AndroidView capture + pointer routing + release animation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullScreenCurlTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun fullSurfaceCaptureContainsTextHeadingAndFooterAndDragCancelDoesNotCommit() {
        // Compose 1.7 hardcodes a software snapshot for the literal Robolectric fingerprint.
        // Native graphics on API 35 can render RenderNodes; exercise the device snapshot path.
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val row = runBlocking(Dispatchers.IO) {
            val file = File(app.filesDir, "curl-screen.txt").apply {
                writeText("屏幕翻页测试\n第1章 旧章节\n" + "当前页面的正文。\n".repeat(10) +
                    "第2章 新章节\n" + "下一页的正文。\n".repeat(10))
            }
            app.repository.importUris(listOf(Uri.fromFile(file)))
            val row = app.repository.dao.all().first { it.name == file.name }
            val prefs = app.repository.settings.settings(row.id).first()
            app.repository.settings.update(SettingsScope.BOOK, row.id, prefs,
                prefs.copy(pageMode = PageMode.CURL, showChapter = true, showPage = true,
                    showPercent = true, showTime = true, showBattery = true))
            row
        }
        lateinit var vm: ReaderViewModel
        compose.runOnUiThread { vm = ReaderViewModel(app, row.id) }
        compose.setContent { LyricTheme { ReaderScreen(vm) {} } }
        compose.waitUntil(15_000) { vm.state.value.book != null && vm.settings.value.pageMode == PageMode.CURL }
        compose.onNodeWithText("第1章 旧章节", substring = true).assertExists()
        compose.waitForIdle()
        fun reader(view: View): ReaderCanvas? = if (view is ReaderCanvas) view else
            (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { reader(group.getChildAt(it)) } }
        val decor = compose.activity.window.decorView
        lateinit var canvas: ReaderCanvas
        compose.runOnUiThread { canvas = requireNotNull(reader(decor)) }
        var recorded: android.graphics.RenderNode? = null
        fun drawFrame() {
            compose.runOnUiThread {
                val node = android.graphics.RenderNode("test-full-reader")
                node.setPosition(0, 0, decor.width, decor.height)
                decor.draw(node.beginRecording(decor.width, decor.height))
                node.endRecording()
                recorded = node
            }
            compose.waitForIdle()
        }
        fun snapshot(name: String): android.graphics.Bitmap {
            drawFrame()
            lateinit var bitmap: android.graphics.Bitmap
            compose.runOnUiThread {
                val method = android.graphics.HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
                    android.graphics.RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                method.isAccessible = true
                bitmap = (method.invoke(null, recorded, decor.width, decor.height) as android.graphics.Bitmap)
                    .copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            }
            File("build/reports/$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            return bitmap
        }
        drawFrame()
        compose.waitUntil(15_000) { canvas.contentDescription?.contains("屏幕翻页测试") == true }
        val root = compose.onRoot()
        val bounds = root.fetchSemanticsNode().boundsInRoot
        val w = bounds.width; val h = bounds.height
        val original = canvas.contentDescription.toString()
        val before = snapshot("full-reader-before")
        root.performTouchInput {
            down(Offset(w * .9f, h * .8f))
            moveTo(Offset(w * .65f, h * .8f), delayMillis = 200)
        }
        // Robolectric does not drive window drawing for PixelCopy; explicitly render real
        // hardware display lists while allowing capture/Compose coroutines to complete.
        repeat(5) { drawFrame() }
        try {
            compose.waitUntil(5_000) { drawFrame(); canvas.curlRunning && canvas.contentDescription.toString() != original }
        } catch (e: Throwable) {
            throw AssertionError("curlRunning=${canvas.curlRunning}, locator=${vm.locator.value}, notice=${vm.notice.value}, text=${canvas.contentDescription}, logs=${org.robolectric.shadows.ShadowLog.getLogsForTag("LyricCurl")}", e)
        }
        assertEquals("drag preview must not commit", Locator(), vm.locator.value)
        val overlay = compose.onNodeWithTag("page-curl").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.width, overlay.width, .1f)
        assertEquals(bounds.height, overlay.height, .1f)
        root.performTouchInput { moveTo(Offset(-w * .75f, h * .95f), delayMillis = 400) }
        repeat(5) { drawFrame() }
        val held = snapshot("full-reader-held")
        assertFalse("finger movement must change the rendered page", before.sameAs(held))
        fun changed(from: Int, until: Int) = (from until until).any { y ->
            (0 until before.width).any { x -> before.getPixel(x, y) != held.getPixel(x, y) }
        }
        assertTrue("the heading must be part of the curling sheet", changed(0, before.height / 6))
        assertTrue("the footer must be part of the curling sheet", changed(before.height * 5 / 6, before.height))
        root.performTouchInput { cancel() }
        repeat(10) { drawFrame() }
        compose.waitUntil(5_000) { drawFrame(); !canvas.curlRunning }
        assertEquals(Locator(), vm.locator.value)
        root.performTouchInput { click(Offset(w * .9f, h * .5f)) }
        repeat(10) { drawFrame() }
        compose.waitUntil(5_000) { drawFrame(); vm.locator.value != Locator() }
        assertTrue(vm.locator.value.charOffset > 0 || vm.locator.value.chapterIndex > 0)
        compose.runOnUiThread { vm.pause() }
    }
}
