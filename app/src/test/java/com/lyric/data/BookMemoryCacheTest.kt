package com.lyric.data

import com.lyric.core.*
import org.junit.Assert.*
import org.junit.Test

class BookMemoryCacheTest {
    @Test fun byteBudgetEvictsLeastRecentAndDoesNotPinOversizedBooks() {
        val small = Book("small", chapters = listOf(Chapter(0, "c", "text")))
        val size = small.estimatedBytes()
        val cache = BookMemoryCache(size * 3)
        repeat(3) { cache["$it"] = small }
        assertSame(small, cache["0"])
        cache["3"] = small
        assertNull(cache["1"])
        assertSame(small, cache["0"])
        assertSame(small, cache["2"])
        assertEquals(size * 3, cache.bytes)
        val large = small.copy(resources = mapOf("image" to "x".repeat((size * 4).toInt())))
        cache["huge"] = large
        assertNull(cache["huge"])
        assertEquals(size * 3, cache.bytes)
        cache.remove("0")
        assertEquals(size * 2, cache.bytes)
        cache.clear()
        assertEquals(0, cache.bytes)
    }
}
