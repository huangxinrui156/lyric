package com.lyric.data

import com.lyric.core.Book
import com.lyric.core.ContentUnit

/** Conservative retained-size estimate (UTF-16 plus object/collection overhead). */
internal fun Book.estimatedBytes(): Long {
    fun String.bytes() = 48L + length * 2L
    return 512L + title.bytes() + author.bytes() + chapters.sumOf { c ->
        256L + c.text.bytes() + c.title.bytes() + c.href.bytes() + c.legacyOffsets.size * 48L +
            c.styles.sumOf { s -> 128L + listOfNotNull(s.fontFamily, s.resource, s.ruby, s.align).sumOf { it.bytes() } } +
            c.content.sumOf { u -> 64L + when (u) {
                is ContentUnit.ImagePage -> u.resource.bytes()
                is ContentUnit.TextSpan -> u.text.bytes()
            } }
    } + toc.sumOf { 64L + it.title.bytes() } +
        resources.entries.sumOf { 64L + it.key.bytes() + it.value.bytes() } +
        embeddedFonts.entries.sumOf { 64L + it.key.bytes() + it.value.bytes() }
}

internal class BookMemoryCache(private val budget: Long) {
    private val entries = LinkedHashMap<String, Pair<Book, Long>>(4, .75f, true)
    internal var bytes = 0L
        private set
    @Synchronized operator fun get(key: String): Book? = entries[key]?.first
    @Synchronized operator fun set(key: String, book: Book) {
        remove(key)
        val size = book.estimatedBytes()
        if (size > budget) return // A large open book is owned by its reader, never pinned here.
        while (bytes + size > budget) remove(entries.keys.first())
        entries[key] = book to size
        bytes += size
    }
    @Synchronized fun remove(key: String) { entries.remove(key)?.let { bytes -= it.second } }
    @Synchronized fun clear() { entries.clear(); bytes = 0 }
}
