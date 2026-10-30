package com.lyric.reader

import com.lyric.core.Book
import com.lyric.core.Locator
import java.text.BreakIterator
import java.util.Locale

/** Absolute UTF-16 boundaries allow selection to survive pages, windows and chapters. */
data class TextSelection(val anchor: Int, val focus: Int) {
    val start get() = minOf(anchor, focus)
    val end get() = maxOf(anchor, focus)
    fun text(book: Book): String = buildString {
        var base = 0
        for (chapter in book.chapters) {
            val a = (start - base).coerceIn(0, chapter.text.length)
            val b = (end - base).coerceIn(0, chapter.text.length)
            if (a < b) append(chapter.text, a, b)
            base += chapter.text.length
        }
    }
    fun locator(book: Book): Locator {
        var remaining = start
        for ((index, chapter) in book.chapters.withIndex()) {
            if (remaining < chapter.text.length || index == book.chapters.lastIndex)
                return Locator(index, remaining.coerceIn(0, chapter.text.lastIndex.coerceAtLeast(0)))
            remaining -= chapter.text.length
        }
        return Locator()
    }
    companion object {
        fun absolute(book: Book, chapter: Int, offset: Int) =
            book.chapters.take(chapter).sumOf { it.text.length } + offset
        fun word(text: String, offset: Int, base: Int = 0): TextSelection? {
            if (text.isEmpty()) return null
            var at = offset.coerceIn(0, text.lastIndex)
            if (text[at].isLowSurrogate() && at > 0) at--
            val words = BreakIterator.getWordInstance(Locale.CHINA).apply { setText(text) }
            val start = words.preceding(at + 1).coerceAtLeast(0)
            val end = words.following(at).let { if (it == BreakIterator.DONE) text.length else it }
            return TextSelection(base + start, base + end)
        }
    }
}

object VolumeNavigation {
    /** Null means the Activity must let Android change system volume. */
    fun step(down: Boolean, enabled: Boolean, swapped: Boolean, controlsOpen: Boolean): Int? =
        if (!enabled || controlsOpen) null else (if (down) 1 else -1) * (if (swapped) -1 else 1)
}
