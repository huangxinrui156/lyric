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
}
