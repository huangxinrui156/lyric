package com.lyric.data

import com.lyric.core.Book
import com.lyric.core.ContentType
import com.lyric.core.Locator
import com.lyric.core.excerpt
import com.lyric.core.ensureChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** v0.5: pre-0.5 「摘录」 were stored as bookmarks holding the selected text. */
object Annotations {
    /** True when a bookmark row was produced by 摘录 (selected text) rather than by 添加书签. */
    fun isExcerpt(book: Book, mark: BookmarkRow): Boolean {
        if (book.contentType != ContentType.TEXT || mark.note.isNotEmpty()) return false
        val chapter = book.chapters.getOrNull(mark.chapterIndex) ?: return false
        if (mark.excerpt.isBlank() || !chapter.text.startsWith(mark.excerpt, mark.charOffset)) return false
        return mark.excerpt != book.excerpt(Locator(mark.chapterIndex, mark.charOffset), 80)
    }

    fun toHighlight(book: Book, mark: BookmarkRow) = HighlightRow(
        bookId = mark.bookId, chapterIndex = mark.chapterIndex, start = mark.charOffset,
        end = mark.charOffset + mark.excerpt.length, text = mark.excerpt,
        chapterTitle = book.chapters[mark.chapterIndex].title, bookTitle = book.title, createdAt = mark.createdAt,
    )

    /** Moves this book's old excerpts into highlights; returns how many moved. */
    suspend fun migrateExcerpts(dao: LibraryDao, bookId: String, book: Book): Int = withContext(Dispatchers.IO) {
        val olds = dao.bookmarks(bookId).first().filter { book.ensureChapter(it.chapterIndex); isExcerpt(book, it) }
        olds.forEach { dao.addHighlight(toHighlight(book, it)); dao.deleteBookmark(it.id) }
        olds.size
    }
}
