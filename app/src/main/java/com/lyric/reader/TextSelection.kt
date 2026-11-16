package com.lyric.reader

import com.lyric.core.Book
import com.lyric.core.Locator
import java.util.Locale

/** Absolute UTF-16 boundaries allow selection to survive pages, windows and chapters. */
data class TextSelection(val anchor: Long, val focus: Long) {
    val start get() = minOf(anchor, focus)
    val end get() = maxOf(anchor, focus)
    fun text(book: Book): String = buildString {
        for (chapter in book.chapters) {
            val base = absolute(book, chapter.index, 0)
            val a = (start - base).coerceIn(0, chapter.text.length.toLong()).toInt()
            val b = (end - base).coerceIn(0, chapter.text.length.toLong()).toInt()
            if (a < b) append(chapter.text, a, b)
        }
    }
    fun locator(book: Book): Locator {
        for (chapter in book.chapters) {
            val base = absolute(book, chapter.index, 0)
            if (start < base + chapter.text.length || chapter.index == book.chapters.lastIndex)
                return Locator(chapter.index, (start - base).coerceIn(0, chapter.text.lastIndex.coerceAtLeast(0).toLong()).toInt())
        }
        return Locator()
    }
    companion object {
        fun absolute(book: Book, chapter: Int, offset: Int) =
            // Sparse chapter-local coordinates remain fixed while other chapters fill.
            if (book.deferred != null) (chapter.toLong() shl 32) + offset
            else book.textOffset(chapter.coerceAtMost(book.chapters.size)) + offset
        fun word(text: String, offset: Int, base: Long = 0): TextSelection? {
            if (text.isEmpty()) return null
            var at = offset.coerceIn(0, text.lastIndex)
            if (text[at].isLowSurrogate() && at > 0) at--
            // ICU's dictionary word breaker gives real CJK words (e.g. 春眠) on device.
            val icu = runCatching {
                val w = android.icu.text.BreakIterator.getWordInstance(android.icu.util.ULocale.CHINA)!!
                w.setText(text)
                w.preceding(at + 1).coerceAtLeast(0) to w.following(at).let { if (it == android.icu.text.BreakIterator.DONE) text.length else it }
            }.getOrNull()
            val (s0, e0) = icu ?: java.text.BreakIterator.getWordInstance(java.util.Locale.CHINA).let { w ->
                w.setText(text)
                w.preceding(at + 1).coerceAtLeast(0) to w.following(at).let { if (it == java.text.BreakIterator.DONE) text.length else it }
            }
            var start = s0; var end = e0
            // A lone CJK character still selects a two-character phrase so the first touch is useful.
            fun cjk(i: Int) = i in text.indices && Character.UnicodeScript.of(text[i].code) == Character.UnicodeScript.HAN
            if (end - start == 1 && cjk(start)) { if (cjk(end)) end++ else if (cjk(start - 1)) start-- }
            return TextSelection(base + start, base + end)
        }
    }
}

object VolumeNavigation {
    /** Null means the Activity must let Android change system volume. */
    fun step(down: Boolean, enabled: Boolean, swapped: Boolean, controlsOpen: Boolean): Int? =
        if (!enabled || controlsOpen) null else (if (down) 1 else -1) * (if (swapped) -1 else 1)
}
