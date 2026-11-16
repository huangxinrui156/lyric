package com.lyric.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderNavigationTest {
    private val book = Book("reader", chapters = listOf(
        Chapter(0, "Rain", "Reading in the rain, one page at a time.\n".repeat(70)),
        Chapter(1, "Sun", "Sunlight on the page.\n".repeat(40))))

    /** Pump real background typesetting and the test Main dispatcher until a page is visible. */
    private fun awaitPage(view: ReaderCanvas, scheduler: TestCoroutineScheduler, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!predicate() && System.nanoTime() < deadline) {
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("typesetting did not finish", predicate())
        view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun everyModeUpdatesVisibleAndCanonicalProgressWithoutWaitingForComposeRebind() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            for (mode in PageMode.entries) {
                val view = ReaderCanvas(ApplicationProvider.getApplicationContext<Application>())
                view.layout(0, 0, 320, 240)
                var visible: Locator? = null
                var canonical = Locator()
                view.onVisibleLocator = { visible = it }
                view.onLocator = { canonical = it } // Deliberately no bind() feedback from Compose.
                view.bind(book, Locator(), ReaderSettings(pageMode = mode), 0xff222222.toInt(), 0xffeeeeee.toInt(), "")
                awaitPage(view, dispatcher.scheduler) { visible != null }
                assertEquals(0f, book.percent(visible!!), 0f)
                view.turn(1)
                awaitPage(view, dispatcher.scheduler) { book.percent(visible!!) > 0f }
                val first = book.percent(canonical)
                assertTrue("$mode must publish progress", first > 0f)
                assertEquals(first, book.percent(visible!!), .05f)
                view.curlRunning = false // Compose completes the overlay animation.
                view.turn(1)
                awaitPage(view, dispatcher.scheduler) { book.percent(canonical) > first }
                view.curlRunning = false
                view.turn(-1)
                awaitPage(view, dispatcher.scheduler) { book.percent(canonical) <= first }
                assertTrue("$mode must go backwards", book.percent(canonical) <= first)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun controlsAndUnrelatedPreferencesReuseTheSameStaticLayout() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val view = ReaderCanvas(ApplicationProvider.getApplicationContext<Application>())
            view.layout(0, 0, 320, 240)
            var visible: Locator? = null
            view.onVisibleLocator = { visible = it }
            val prefs = ReaderSettings(pageMode = PageMode.NONE)
            view.bind(book, Locator(), prefs, 0xff222222.toInt(), 0xffeeeeee.toInt(), "")
            awaitPage(view, dispatcher.scheduler) { visible != null }
            val field = ReaderCanvas::class.java.getDeclaredField("typeset").apply { isAccessible = true }
            val original = field.get(view)
            view.controlsOpen = true
            view.bind(book, Locator(), prefs, 0xff222222.toInt(), 0xffeeeeee.toInt(), "")
            view.bind(book, Locator(), prefs.copy(brightness = .2f, showBattery = true, volumeKeys = false),
                0xff222222.toInt(), 0xffeeeeee.toInt(), "")
            dispatcher.scheduler.runCurrent()
            assertSame(original, field.get(view))
            view.turn(1)
            assertSame(original, field.get(view))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun dragScrollPublishesProgressAndReflowKeepsTheAnchor() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val view = ReaderCanvas(ApplicationProvider.getApplicationContext<Application>())
            view.layout(0, 0, 320, 240)
            var visible: Locator? = null
            var canonical = Locator()
            view.onVisibleLocator = { visible = it }
            view.onLocator = { canonical = it }
            val prefs = ReaderSettings(pageMode = PageMode.SCROLL)
            view.bind(book, canonical, prefs, 0xff222222.toInt(), 0xffeeeeee.toInt(), "")
            awaitPage(view, dispatcher.scheduler) { visible != null }
            fun event(action: Int, y: Float, time: Long) = MotionEvent.obtain(0, time, action, 160f, y, 0).also {
                view.onTouchEvent(it); it.recycle()
            }
            event(MotionEvent.ACTION_DOWN, 220f, 0)
            event(MotionEvent.ACTION_MOVE, 30f, 50)
            event(MotionEvent.ACTION_CANCEL, 30f, 60)
            assertTrue(book.percent(canonical) > 0f)
            assertEquals(canonical.charOffset, visible!!.charOffset)
            val anchor = canonical
            visible = null
            view.layout(0, 0, 640, 180)
            awaitPage(view, dispatcher.scheduler) { visible != null }
            // A scroll line may begin slightly before the original anchor after reflow.
            assertTrue(visible!!.charOffset <= anchor.charOffset)
            assertTrue(anchor.charOffset - visible!!.charOffset < 150)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun curlFollowsMovesPreviewsWithoutSavingAndCancelRestoresAcrossChapterBoundary() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val view = ReaderCanvas(ApplicationProvider.getApplicationContext<Application>())
            view.layout(0, 0, 320, 240)
            var visible: Locator? = null
            var canonical = Locator(1)
            var commits = 0
            var starts = 0
            var moves = 0
            var releaseVelocity = 0f
            var interactive = false
            view.onLocator = { canonical = it; commits++ }
            view.onVisibleLocator = { visible = it }
            view.onCurl = { _, dragging, _, _ -> starts++; interactive = dragging; view.prepareCurlTarget() }
            view.onCurlMove = { _, _ -> moves++ }
            view.onCurlRelease = { _, speed -> releaseVelocity = speed }
            view.bind(book, canonical, ReaderSettings(pageMode = PageMode.CURL), 0xff222222.toInt(), 0xffeeeeee.toInt(), "")
            awaitPage(view, dispatcher.scheduler) { visible != null }
            fun event(action: Int, x: Float, time: Long) = MotionEvent.obtain(0, time, action, x, 120f, 0).also {
                view.onTouchEvent(it); it.recycle()
            }
            event(MotionEvent.ACTION_DOWN, 20f, 0)
            event(MotionEvent.ACTION_MOVE, 170f, 150)
            awaitPage(view, dispatcher.scheduler) { visible?.chapterIndex == 0 }
            assertEquals(0, commits)
            assertTrue(interactive)
            assertEquals(1, starts)
            // A Compose rebind of the still-committed origin must not undo the preview.
            view.bind(book, canonical, ReaderSettings(pageMode = PageMode.CURL), 0xff222222.toInt(), 0xffeeeeee.toInt(), "")
            assertEquals(0, visible!!.chapterIndex)
            event(MotionEvent.ACTION_MOVE, 200f, 200)
            assertTrue(moves > 0)
            event(MotionEvent.ACTION_CANCEL, 200f, 250)
            assertTrue(releaseVelocity.isNaN())
            var restored = false
            view.finishCurl(false) { restored = true }
            awaitPage(view, dispatcher.scheduler) { visible?.chapterIndex == 1 }
            assertTrue(restored)
            assertEquals(Locator(1), canonical)
            assertEquals(0, commits)
            view.turn(-1)
            assertFalse(interactive)
            awaitPage(view, dispatcher.scheduler) { visible?.chapterIndex == 0 }
            view.finishCurl(true) { }
            assertEquals(1, commits)
            assertEquals(0, canonical.chapterIndex)
            assertFalse(view.curlRunning)
            view.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP))
            assertEquals(3, starts)
            assertFalse(interactive)
            view.abortCurl()
            assertEquals(1, commits)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun curlSnapshotOomAtEitherCaptureCommitsOnceWithoutAnimation() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            for (targetCapture in listOf(false, true)) {
                val view = ReaderCanvas(ApplicationProvider.getApplicationContext<Application>())
                view.layout(0, 0, 320, 240)
                var visible: Locator? = null
                var canonical = Locator()
                var commits = 0
                var cancelledOverlay = false
                view.onLocator = { canonical = it; commits++ }
                view.onVisibleLocator = { visible = it }
                view.onCurlCancel = { cancelledOverlay = true }
                fun failedCapture() {
                    val image = pageSnapshot<Bitmap> { throw OutOfMemoryError("capture") }
                    if (image == null) view.finishCurlWithoutAnimation()
                }
                view.onCurl = { _, _, _, _ ->
                    if (targetCapture) view.prepareCurlTarget() else failedCapture()
                }
                view.bind(book, Locator(), ReaderSettings(pageMode = PageMode.CURL),
                    0xff222222.toInt(), 0xffeeeeee.toInt(), "")
                awaitPage(view, dispatcher.scheduler) { visible != null }
                view.turn(1)
                if (targetCapture) {
                    awaitPage(view, dispatcher.scheduler) { visible != Locator() }
                    assertEquals(0, commits)
                    failedCapture()
                }
                awaitPage(view, dispatcher.scheduler) { book.percent(canonical) > 0f }
                assertEquals(1, commits)
                assertFalse(view.curlRunning)
                assertTrue(cancelledOverlay)
                assertEquals(canonical.chapterIndex, visible!!.chapterIndex)
                assertEquals(canonical.charOffset, visible!!.charOffset)
            }
        } finally { Dispatchers.resetMain() }
    }

}
