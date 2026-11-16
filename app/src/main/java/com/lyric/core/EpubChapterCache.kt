package com.lyric.core

import java.io.File
import kotlinx.serialization.json.Json

/** Manifest is the commit marker. Individual chapters and lengths are atomically replaceable. */
object EpubChapterCache {
    private val json = Json { ignoreUnknownKeys = true }
    fun seed(directory: File, book: Book) {
        directory.mkdirs()
        book.chapters.forEach { c ->
            atomic(File(directory, "chapter-${c.index}.json"), json.encodeToString(Chapter.serializer(), c))
        }
        atomic(File(directory, "lengths.txt"), book.chapters.joinToString(",") { it.text.length.toString() })
        publishManifest(directory, book)
    }
    fun publishManifest(directory: File, book: Book) {
        val descriptor = book.copy(chapters = book.chapters.map { c ->
            Chapter(c.index, c.title, href = c.href, anchors = c.anchors, pending = true)
        })
        atomic(File(directory, "book.json"), json.encodeToString(Book.serializer(), descriptor))
    }
    fun read(directory: File, current: Int, fallback: (Int) -> Chapter): Book? {
        val descriptor = runCatching { json.decodeFromString(Book.serializer(), File(directory, "book.json").readText()) }.getOrNull() ?: return null
        val chapters = DeferredChapters(descriptor.chapters, directory, fallback)
        val c = current.coerceIn(0, chapters.lastIndex)
        for (i in (c - 1).coerceAtLeast(0)..(c + 1).coerceAtMost(chapters.lastIndex)) chapters.load(i)
        return descriptor.copy(chapters = chapters).also { it.deferred = chapters }
    }
    fun atomic(file: File, text: String) {
        val tmp = File.createTempFile("write-", ".tmp", file.parentFile)
        try { tmp.writeText(text); check(tmp.renameTo(file)) }
        finally { tmp.delete() }
    }
}
