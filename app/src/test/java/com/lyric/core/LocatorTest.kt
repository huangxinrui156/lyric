package com.lyric.core

import com.lyric.reader.TextReaderEngine
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocatorTest {
    private val book =
        Book(
            "测试",
            chapters =
                listOf(Chapter(0, "清晨", "字".repeat(1000)), Chapter(1, "黄昏", "雨".repeat(2000))),
        )

    @Test
    fun settingsAndModeChangesKeepCanonicalLocation() {
        val engine = TextReaderEngine()
        engine.open(book, Locator(1, 437))
        PageMode.entries.forEach { mode ->
            listOf(12f, 18f, 36f).forEach { size ->
                engine.settings(
                    ReaderSettings(
                        fontSize = size,
                        lineHeight = 2.2f,
                        margin = 40f,
                        pageMode = mode,
                        indent = false,
                    )
                )
                assertEquals(Locator(1, 437), engine.currentLocator.value)
            }
        }
        val saved = Json.encodeToString(Locator.serializer(), engine.currentLocator.value)
        val reopened = TextReaderEngine()
        reopened.open(book, Json.decodeFromString(Locator.serializer(), saved))
        assertEquals(Locator(1, 437), reopened.currentLocator.value)
    }

    @Test
    fun percentUsesCharactersRatherThanChapterCount() {
        assertEquals(.5f, book.percent(Locator(1, 500)), .00001f)
    }

    @Test
    fun outOfRangeAndSurrogatesClampSafely() {
        assertEquals(Locator(1, 1999), book.clamp(Locator(99, 99999)))
        assertEquals(Locator(), book.clamp(Locator(-1, -99)))
        val emoji = Book("emoji", chapters = listOf(Chapter(0, "", "a🌿b")))
        assertEquals(1, emoji.clamp(Locator(0, 2)).charOffset)
    }

    @Test
    fun bookCacheRoundTripPreservesLocationsAndToc() {
        val restored =
            Json.decodeFromString(Book.serializer(), Json.encodeToString(Book.serializer(), book))
        assertEquals(book, restored)
        assertEquals(book.percent(Locator(1, 437)), restored.percent(Locator(1, 437)), 0f)
    }

    @Test
    fun comicUnitsUseSameProgressAndLocatorContract() {
        val comic =
            Book(
                "漫画",
                chapters =
                    listOf(
                        Chapter(
                            0,
                            "第一话",
                            content = (0..2).map { ContentUnit.ImagePage("$it.jpg", null, null) },
                        ),
                        Chapter(
                            1,
                            "第二话",
                            content = (3..5).map { ContentUnit.ImagePage("$it.jpg", null, null) },
                        ),
                    ),
                contentType = ContentType.COMIC,
            )
        val at = Locator(1, page = 1)
        assertEquals(4f / 6, comic.percent(at), .00001f)
        assertEquals(Locator(1, page = 2), comic.clamp(Locator(99, 200, 900)))
        assertEquals("第二话 · 第 2 页", comic.excerpt(at))
        assertEquals(
            comic,
            Json.decodeFromString(Book.serializer(), Json.encodeToString(Book.serializer(), comic)),
        )
    }
}
