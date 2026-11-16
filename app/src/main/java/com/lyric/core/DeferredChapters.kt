package com.lyric.core

import java.io.File
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** No Compose state per fill. A chapter is atomically published exactly once, after its cache write. */
class DeferredChapters(
    initial: List<Chapter>,
    private val directory: File,
    private val parse: (Int) -> Chapter,
) : AbstractList<Chapter>() {
    private val values = AtomicReferenceArray(initial.toTypedArray())
    private val json = Json { ignoreUnknownKeys = true }
    private val parseGuard = Any()
    val lengthsReady = MutableStateFlow(false)
    @Volatile var offsets: LongArray? = null
        private set
    override val size = initial.size
    init {
        val lengths = runCatching { File(directory, "lengths.txt").readText().split(',').map(String::toInt) }.getOrNull()
        if (lengths != null && lengths.size == size && lengths.all { it >= 0 }) {
            offsets = LongArray(size + 1).apply { lengths.forEachIndexed { i, n -> this[i + 1] = this[i] + n } }
            lengthsReady.value = true
        }
    }
    override fun get(index: Int): Chapter = values.get(index)

    fun titles(toc: List<TocEntry>) {
        toc.forEach { entry ->
            val c = get(entry.chapterIndex)
            if (c.pending) values.set(entry.chapterIndex, c.copy(title = entry.title))
        }
    }

    /** Called only from IO/Default. The guard never encloses UI work or suspension. */
    fun load(index: Int): Chapter = synchronized(parseGuard) {
        val old = get(index)
        if (!old.pending) return@synchronized old
        val file = File(directory, "chapter-$index.json")
        val cached = runCatching { json.decodeFromString(Chapter.serializer(), file.readText()) }
            .getOrNull()?.takeIf { !it.pending && it.index == index && it.href == old.href &&
                (offsets == null || it.text.length.toLong() == offsets!![index + 1] - offsets!![index]) }
        val chapter = cached ?: parse(index).also { c ->
            val tmp = File.createTempFile("chapter-$index-", ".tmp", directory)
            tmp.writeText(json.encodeToString(Chapter.serializer(), c))
            check(tmp.renameTo(file))
        }
        require(offsets == null || chapter.text.length.toLong() == offsets!![index + 1] - offsets!![index]) {
            "EPUB canonical chapter length mismatch"
        }
        // Legacy maps are only needed by the full-parser migration path.
        chapter.legacyOffsets = emptyList()
        values.set(index, chapter)
        chapter
    }

    suspend fun ensure(index: Int): Chapter = withContext(Dispatchers.Default) { load(index) }

    suspend fun complete(paced: Boolean = true) = withContext(Dispatchers.Default) {
        for (i in indices) { val pending = get(i).pending; ensure(i); if (pending && paced) delay(16) }
        val stable = LongArray(size + 1)
        for (i in indices) stable[i + 1] = stable[i] + get(i).text.length
        check(offsets == null || offsets!!.contentEquals(stable)) { "EPUB canonical lengths changed" }
        EpubChapterCache.atomic(File(directory, "lengths.txt"), joinToString(",") { it.text.length.toString() })
        offsets = stable
        lengthsReady.value = true
    }
    suspend fun awaitLengths() { lengthsReady.first { it } }
}

suspend fun Book.ensureChapter(index: Int): Chapter = deferred?.ensure(index.coerceIn(chapters.indices))
    ?: chapters[index.coerceIn(chapters.indices)]
