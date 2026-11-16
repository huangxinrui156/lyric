package com.lyric.reader

import android.app.Application
import android.graphics.Typeface
import android.os.Looper
import com.lyric.core.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FirstWindowTest {
    @Test fun firstWindowIsOffMainAndDoesNotWaitForOtherChapters() = runBlocking {
        val book = Book("large", chapters = List(1248) { Chapter(it, "$it", "Reading a paragraph.\n".repeat(2000)) })
        val calls = CopyOnWriteArrayList<Pair<Int, Int>>()
        val pager = PrePaginator(book, this, { chapter, at ->
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            calls += chapter to at
            Typesetter.layout(book.chapters[chapter].text, at, 1000, 2200, 3f, 3f, ReaderSettings(), Typeface.SERIF)
        })
        try {
            val first = pager.get(0, 0)
            assertEquals(listOf(0 to 0), calls.toList())
            assertEquals(Typesetter.WINDOW, first.sourceEnd)
            assertTrue(first.pages.isNotEmpty())
            assertNull(pager.pagePosition(0, 0, 0)) // remaining chapter windows are not needed to read
        } finally { pager.close() }
    }

    @Test fun backgroundCompletesCurrentChapterWithoutPaginatingWholeBook() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = CopyOnWriteArrayList<Pair<Int, Int>>()
        val book = Book("large", chapters = List(1248) { Chapter(it, "$it", "Paragraph.\n".repeat(2500)) })
        val pager = PrePaginator(book, scope, { c, at ->
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            calls += c to at
            Typesetter.layout(book.chapters[c].text, at, 1000, 2200, 3f, 3f, ReaderSettings(), Typeface.SERIF)
        })
        try {
            pager.get(600, 0)
            pager.prioritize(Locator(600))
            withTimeout(5000) { while (pager.pagePosition(600, 0, 0) == null) delay(10) }
            delay(100)
            assertEquals(setOf(599, 600, 601), calls.map { it.first }.toSet())
            val count = pager.pagePosition(600, 0, 0)!!
            assertEquals(1, count.first)
            assertTrue(count.second > 1)
        } finally { pager.close(); scope.cancel(); Dispatchers.resetMain() }
    }
}
