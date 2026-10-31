package com.lyric.ui

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lyric.LyricApp
import com.lyric.core.*
import com.lyric.data.SettingsScope
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = LyricApp::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicReaderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun png(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(30, 40, android.graphics.Bitmap.Config.ARGB_8888)
        return java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test fun rightToLeftTapsTurnPagesSaveProgressAndDirectionToggles() {
        // Compose caches its Robolectric-fingerprint check per JVM; keep it identical to FullScreenCurlTest.
        org.robolectric.shadows.ShadowBuild.setFingerprint("lyric-native-curl-test")
        val app = ApplicationProvider.getApplicationContext<LyricApp>()
        val row = runBlocking(Dispatchers.IO) {
            val file = File(app.filesDir, "测试漫画.cbz")
            ZipOutputStream(file.outputStream()).use { z -> listOf("a/1.png", "a/2.png", "a/10.png", "b/1.png").forEach {
                z.putNextEntry(ZipEntry(it)); z.write(png()); z.closeEntry() } }
            app.repository.importUris(listOf(Uri.fromFile(file)))
            app.repository.dao.all().first { it.contentType == "COMIC" }
        }
        lateinit var vm: ReaderViewModel
        compose.runOnUiThread { vm = ReaderViewModel(app, row.id) }
        compose.setContent { LyricTheme { ReaderScreen(vm) {} } }
        runCatching { compose.waitUntil(15_000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            vm.state.value.book != null || vm.state.value.error != null
        } }
        assertNotNull(vm.state.value.error.toString(), vm.state.value.book)
        compose.onNodeWithTag("comic-reader").assertExists()
        // First-time hint for the default right-to-left direction.
        compose.onNodeWithText("从右往左读 · 点左侧翻下一页").assertExists()
        compose.onNodeWithTag("direction-hint").assertExists()
        compose.onNodeWithContentDescription("从右往左（当前）").assertExists()
        compose.onNodeWithContentDescription("条漫").assertExists()
        compose.onNodeWithContentDescription("从左往右").assertExists()
        val reader = compose.onNodeWithTag("comic-reader")
        // RTL: left third = next page.
        reader.performTouchInput { click(Offset(width * .1f, height * .5f)) }
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.waitUntil(5000) { vm.locator.value == Locator(0, 0, 1) }
        reader.performTouchInput { click(Offset(width * .1f, height * .5f)) }
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.waitUntil(5000) { vm.locator.value == Locator(0, 0, 2) }
        // Right third goes back.
        reader.performTouchInput { click(Offset(width * .9f, height * .5f)) }
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        compose.waitUntil(5000) { vm.locator.value == Locator(0, 0, 1) }
        // Middle toggles chrome showing "2 / 4".
        reader.performTouchInput { click(Offset(width * .5f, height * .5f)) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithText("2 / 4").assertExists()
        vm.pause()
        runBlocking(Dispatchers.IO) {
            val p = app.repository.dao.progress(row.id)!!
            assertEquals(0, p.chapterIndex); assertEquals(1, p.page)
        }
        // Per-book direction switch to left-to-right.
        compose.onAllNodesWithContentDescription("当前从右往左，切换为从左往右")[0].performClick()
        compose.waitUntil(5000) { vm.settings.value.comicDirection == "ltr" }
        var stored = "rtl"
        repeat(60) {
            if (stored == "ltr") return@repeat
            compose.mainClock.advanceTimeBy(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(50)
            stored = runBlocking(Dispatchers.IO) { app.repository.settings.settings(row.id, ContentType.COMIC).first().comicDirection }
        }
        assertEquals("ltr", stored)
        // Hand-picked: recorded per book, so a declared direction no longer overrides it.
        assertTrue(app.getSharedPreferences("comic_hints", 0).getBoolean("chosen_${row.id}", false))
        // Cycling on to 条漫 swaps the pager for the vertical strip.
        compose.onAllNodesWithContentDescription("当前从左往右，切换为条漫")[0].performClick()
        compose.waitUntil(5000) { vm.settings.value.comicDirection == "vertical" }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithTag("comic-strip").assertExists()
    }
}
