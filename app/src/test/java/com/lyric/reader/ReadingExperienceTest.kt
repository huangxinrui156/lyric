package com.lyric.reader

import com.lyric.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingExperienceTest {
    @Test fun selectionUsesCanonicalBoundariesAcrossPagesAndChapters() {
        val book = Book("test", chapters = listOf(Chapter(0, "a", "Hello world\n"), Chapter(1, "b", "第二章😀结束")))
        val word = TextSelection.word(book.chapters[0].text, 7)!!
        assertEquals("world", word.text(book))
        val cross = TextSelection(6, TextSelection.absolute(book, 1, 5))
        assertEquals("world\n第二章😀", cross.text(book))
        assertEquals(Locator(0, 6), cross.locator(book))
        assertEquals(cross.text(book), TextSelection(cross.focus, cross.anchor).text(book))
        val emoji = TextSelection.word("a😀b", 2)!!
        assertEquals("😀", emoji.text(Book("emoji", chapters = listOf(Chapter(0, "", "a😀b")))))
    }
    @Test fun volumeDefaultsOnIsSwappableAndDefersToSystemForControls() {
        assertTrue(ReaderSettings().volumeKeys)
        assertNull(VolumeNavigation.step(true, false, false, false))
        assertNull(VolumeNavigation.step(true, true, false, true))
        assertEquals(1, VolumeNavigation.step(true, true, false, false))
        assertEquals(-1, VolumeNavigation.step(false, true, false, false))
        assertEquals(-1, VolumeNavigation.step(true, true, true, false))
        assertEquals(1, VolumeNavigation.step(false, true, true, false))
    }
    @Test fun quietInfoDefaultsAndIndependentFieldsSurviveSerialization() {
        val defaults = ReaderSettings()
        assertTrue(defaults.showChapter && defaults.showPercent)
        assertFalse(defaults.showPage || defaults.showTime || defaults.showBattery)
        val configured = defaults.copy(showChapter = false, showPercent = false, showPage = true, showTime = true, showBattery = true, swapVolumeKeys = true)
        val json = kotlinx.serialization.json.Json
        assertEquals(configured, json.decodeFromString(ReaderSettings.serializer(), json.encodeToString(ReaderSettings.serializer(), configured)))
        assertEquals(defaults, json.decodeFromString(ReaderSettings.serializer(), "{}"))
    }
    @Test fun percentLabelDoesNotRoundEarlyPagesOfLongBooksBackToZero() {
        val original = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        try {
            assertEquals("0.0%", com.lyric.ui.readingPercentLabel(0f))
            assertEquals("0.01%", com.lyric.ui.readingPercentLabel(.0001f))
            assertEquals("0.02%", com.lyric.ui.readingPercentLabel(.0002f))
            assertEquals("50.0%", com.lyric.ui.readingPercentLabel(.5f))
            assertEquals("100.0%", com.lyric.ui.readingPercentLabel(1f))
        } finally { java.util.Locale.setDefault(original) }
    }
    @Test fun paginationPrioritizesCurrentAndNeighborsAndVisitsEveryWindow() {
        val order = PrePaginator.order(listOf(1, 24_001, 25_000, 0, 12_000), Locator(2, 14_000))
        assertEquals(PrePaginator.Window(2, 12_000), order.first())
        val chapters = order.map { it.chapter }.distinct()
        assertEquals(listOf(2, 1, 3, 0, 4), chapters)
        assertEquals(order.size, order.distinct().size)
        assertEquals(9, order.size)
    }

    @Test fun durableProgressStartsImmediatelyAndFinishesLatestAfterScreenScopeEnds() = runTest {
        val application = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val saved = mutableListOf<Int>()
        val writer = ProgressWriter<Int>(application, StandardTestDispatcher(testScheduler)) {
            delay(40); saved += it
        }
        writer.submit(1)
        runCurrent()
        advanceTimeBy(41); runCurrent()
        assertEquals(listOf(1), saved) // well within one second, no debounce starvation
        writer.submit(2); runCurrent()
        writer.submit(3); writer.submit(4)
        val flush = async { writer.flush(5) }
        advanceUntilIdle()
        flush.await()
        assertEquals(listOf(1, 2, 5), saved)
        writer.close()
        application.cancel()
    }
    @Test fun failedProgressFlushReportsErrorAndNextWriteCanRecover() = runTest {
        val application = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var attempts = 0
        val writer = ProgressWriter<Int>(application, StandardTestDispatcher(testScheduler)) {
            attempts++
            if (attempts == 1) throw java.io.IOException("disk unavailable")
        }
        val failed = async { runCatching { writer.flush(1) }.exceptionOrNull() }
        advanceUntilIdle()
        assertTrue(failed.await() is java.io.IOException)
        val next = async { writer.flush(2) }
        advanceUntilIdle(); next.await()
        assertEquals(2, attempts)
        writer.close(); application.cancel()
    }

}
