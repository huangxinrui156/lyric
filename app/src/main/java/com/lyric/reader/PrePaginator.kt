package com.lyric.reader

import com.lyric.core.Book
import com.lyric.core.Locator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All page boundaries are retained; only a bounded set of expensive layouts/bitmaps is kept. */
class PrePaginator(
    private val book: Book,
    private val scope: CoroutineScope,
    private val layout: (Int, Int) -> TypesetChapter,
    private val changed: (Int) -> Unit = {},
) {
    data class Window(val chapter: Int, val offset: Int)
    private val lock = Mutex()
    private val layouts = object : LinkedHashMap<Window, TypesetChapter>(8, .75f, true) {}
    private val boundaries = mutableMapOf<Window, List<TextPage>>()
    @Volatile private var active: Window? = null
    @Volatile private var protected = emptySet<Window>()
    private var background: Job? = null

    suspend fun get(chapter: Int, offset: Int): TypesetChapter = withContext(Dispatchers.Default) {
        lock.withLock {
            val key = Window(chapter, offset.coerceAtLeast(0) / Typesetter.WINDOW * Typesetter.WINDOW)
            layouts[key] ?: layout(chapter, key.offset).also { result ->
                layouts[key] = result
                boundaries[key] = result.pages
                while (layouts.size > 8) {
                    val victim = layouts.keys.firstOrNull { it != active && it != key && it !in protected } ?: break
                    layouts.remove(victim)
                }
            }
        }
    }

    fun prioritize(at: Locator) {
        val next = Window(at.chapterIndex, at.charOffset / Typesetter.WINDOW * Typesetter.WINDOW)
        if (active == next && background?.isActive == true) return
        active = next
        protected = buildSet {
            val c = at.chapterIndex
            val w = at.charOffset / Typesetter.WINDOW * Typesetter.WINDOW
            for (offset in listOf(w - Typesetter.WINDOW, w, w + Typesetter.WINDOW)) {
                if (offset >= 0 && offset < book.chapters[c].text.length.coerceAtLeast(1)) add(Window(c, offset))
            }
            if (c > 0) add(Window(c - 1, windows(book.chapters[c - 1].text.length).last()))
            if (c < book.chapters.lastIndex) add(Window(c + 1, 0))
        }
        background?.cancel()
        val nearby = protected.toList()
        background = scope.launch(Dispatchers.Default) {
            // Finish the adjacent windows before doing whole-book page counts.
            for (window in nearby) {
                ensureActive()
                get(window.chapter, window.offset)
                withContext(Dispatchers.Main) { changed(window.chapter) }
            }
            for (window in order(book.chapters.map { it.text.length }, at)) {
                ensureActive()
                val near = window in protected
                val known = lock.withLock { boundaries.containsKey(window) }
                if (!known || near) get(window.chapter, window.offset)
                if (!known) withContext(Dispatchers.Main) { changed(window.chapter) }
                yield()
            }
        }
    }

    suspend fun pagePosition(chapter: Int, sourceStart: Int, page: Int): Pair<Int, Int>? = withContext(Dispatchers.Default) { lock.withLock {
        val keys = windows(book.chapters[chapter].text.length).map { Window(chapter, it) }
        if (keys.all { boundaries.containsKey(it) }) {
            val number = keys.filter { it.offset < sourceStart }.sumOf { boundaries.getValue(it).size } + page + 1
            number to keys.sumOf { boundaries.getValue(it).size }
        } else null
    } }
    fun close() { background?.cancel() }

    companion object {
        private fun windows(length: Int) = (0 until length.coerceAtLeast(1) step Typesetter.WINDOW).toList()
        fun order(lengths: List<Int>, at: Locator): List<Window> {
            val chapters = listOf(at.chapterIndex, at.chapterIndex - 1, at.chapterIndex + 1)
                .filter { it in lengths.indices } + lengths.indices.filter { kotlin.math.abs(it - at.chapterIndex) > 1 }
            return chapters.flatMap { c ->
                val offsets = windows(lengths[c])
                val first = if (c == at.chapterIndex) at.charOffset / Typesetter.WINDOW * Typesetter.WINDOW else 0
                (listOf(first) + offsets).distinct().filter { it >= 0 && it < lengths[c].coerceAtLeast(1) }.map { Window(c, it) }
            }
        }
    }
}
