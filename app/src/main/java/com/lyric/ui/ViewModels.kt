package com.lyric.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lyric.LyricApp
import com.lyric.core.*
import com.lyric.data.*
import com.lyric.reader.ReaderSession
import com.lyric.reader.SessionTracker
import com.lyric.reader.ProgressWriter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ShelfViewModel(private val app: LyricApp) : ViewModel() {
    val repository = app.repository
    val books =
        repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // null prevents briefly showing the wrong shelf before DataStore has loaded.
    val section = repository.settings.shelfSection
        .map<ShelfSection, ShelfSection?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun section(value: ShelfSection) {
        viewModelScope.launch { repository.settings.shelfSection(value) }
    }
    fun move(book: ShelfBook) {
        viewModelScope.launch {
            repository.dao.moveToSection(book.id, ShelfSection.valueOf(book.section).other().name)
        }
    }
    val imports = repository.imports.asStateFlow()
    val grid =
        repository.settings.grid.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val sortTitle =
        repository.settings.sortTitle.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false,
        )

    fun grid(value: Boolean) {
        viewModelScope.launch { repository.settings.shelf(grid = value) }
    }

    val sortMode =
        repository.settings.shelfSort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val comicSortMode =
        repository.settings.comicShelfSort.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    fun sort(mode: Int, section: ShelfSection) {
        viewModelScope.launch { repository.settings.shelfSort(mode, section) }
    }

    fun import(uris: List<Uri>, folder: Boolean = false, grantFlags: Int? = null) {
        app.applicationScope.launch { repository.importUris(uris, folder, grantFlags) }
    }

    fun remove(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun refresh() {
        viewModelScope.launch { repository.checkSources() }
    }
}

data class ReaderState(
    val row: ShelfBook? = null,
    val book: Book? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class ReaderViewModel(private val app: LyricApp, val id: String) : ViewModel() {
    val repository = app.repository
    val engine = ReaderSession()
    val locator = engine.currentLocator
    val settings = engine.settings
    val state = MutableStateFlow(ReaderState())
    val bookmarks =
        repository.dao
            .bookmarks(id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val highlights =
        repository.dao
            .highlights(id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searching = MutableStateFlow(false)
    val highlight = MutableStateFlow("")
    val notice = MutableStateFlow<String?>(null)
    private var closed = false
    private val sessionWrites = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val sessionWriter = app.applicationScope.launch(Dispatchers.IO) {
        for (write in sessionWrites) {
            try { write() } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice.value = "阅读记录保存失败：${e.localizedMessage}"
            }
        }
    }
    private val progressWriter = ProgressWriter<Pair<Book, Locator>>(app.applicationScope) {
        repository.save(id, it.first, it.second)
    }
    internal val tracker = SessionTracker(id) { session ->
        check(sessionWrites.trySend { repository.dao.checkpointSession(session) }.isSuccess)
    }
    private val tickJob = viewModelScope.launch {
        while (isActive) { delay(1000); tracker.tick() }
    }
    private var pendingJump: Locator? = null
    private var pendingBookmark = 0L

    fun readingGesture() = tracker.onActivity()
    fun resume() = tracker.onResume()
    fun pause() {
        if (closed) return
        tracker.onPause()
        // Lifecycle must not return while its final durable snapshots are still queued.
        // The writers run on IO, so this barrier does not depend on the main dispatcher.
        val book = state.value.book
        runBlocking(Dispatchers.IO) {
            try {
                if (book != null) progressWriter.flush(book to locator.value)
            } catch (e: Exception) {
                notice.value = "阅读进度保存失败：${e.localizedMessage}"
            }
            val barrier = CompletableDeferred<Unit>()
            sessionBarrier(barrier)
            barrier.await()
        }
    }
    private suspend fun sessionBarrier(done: CompletableDeferred<Unit>) {
        // A checkpoint drain shares the same FIFO as the rolling writes.
        sessionWrites.send { done.complete(Unit); Unit }
    }
    fun readingActivity(next: Locator) {
        val book = state.value.book ?: return
        val old = locator.value
        if (next != old && book.contentType == ContentType.COMIC) {
            val pages = { l: Locator -> book.chapters.take(l.chapterIndex).sumOf { it.units.size.toLong() } + l.page }
            tracker.onActivity(kotlin.math.abs(pages(next) - pages(old)))
        } else if (next != old) {
            val a = book.chapters.take(old.chapterIndex).sumOf { it.text.length.toLong() } + old.charOffset
            val b = book.chapters.take(next.chapterIndex).sumOf { it.text.length.toLong() } + next.charOffset
            tracker.onActivity(kotlin.math.abs(b - a))
        }
        engine.goTo(next)
        flush()
        com.lyric.reader.KillCheck.activity(app)
        com.lyric.reader.KillCheck.record(app, id, book.title, book.clamp(next))
    }
    private var finishedRecorded = false
    fun finished() {
        val book = state.value.book ?: return
        if (finishedRecorded) return
        finishedRecorded = true
        viewModelScope.launch { repository.dao.finish(FinishedBook(id, book.title, System.currentTimeMillis())) }
    }
    fun jump(locator: Locator, bookmarkId: Long = 0L) {
        if (state.value.book == null) {
            pendingJump = locator
            pendingBookmark = bookmarkId
        } else if (bookmarkId != 0L) viewModelScope.launch {
            // Resolve by ID after EPUB upgrades, rather than using a stale navigation offset.
            val mark = repository.dao.bookmark(bookmarkId)
            goTo(mark?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: locator)
        } else goTo(locator)
    }
    private var searchJob: Job? = null
    private var settingsJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        com.lyric.reader.ReaderMetrics.beginOpen()
        viewModelScope.launch {
            state.value = ReaderState()
            try {
                val (row, book) = repository.load(id)
                val p = repository.dao.progress(id)
                val requestedMark = if (pendingBookmark != 0L) repository.dao.bookmark(pendingBookmark) else null
                engine.open(
                    book,
                    requestedMark?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: pendingJump ?: p?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: Locator(),
                )
                pendingJump = null
                pendingBookmark = 0L
                settingsJob?.cancel()
                settingsJob =
                    viewModelScope.launch {
                        repository.settings.settings(id, book.contentType).collect {
                            engine.settings(it)
                        }
                    }
                state.value = ReaderState(row, book, false)
                runCatching { Annotations.migrateExcerpts(repository.dao, id, book) }
                save()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.value = ReaderState(loading = false, error = e.localizedMessage ?: "打开失败")
            }
        }
    }

    fun goTo(locator: Locator) {
        engine.goTo(locator)
        flush()
    }

    fun updateSettings(scope: SettingsScope, next: ReaderSettings) {
        val before = settings.value
        engine.settings(next)
        viewModelScope.launch {
            repository.settings.update(scope, id, before, next, engine.contentType)
        }
    }

    fun resetSettings(scope: SettingsScope) {
        viewModelScope.launch { repository.settings.reset(scope, id, engine.contentType) }
    }

    fun bookmark(at: Locator = locator.value, excerpt: String? = null) {
        val b = state.value.book ?: return
        val l = b.clamp(at)
        viewModelScope.launch {
            repository.dao.addBookmark(
                BookmarkRow(
                    bookId = id,
                    chapterIndex = l.chapterIndex,
                    charOffset = l.charOffset,
                    excerpt = excerpt ?: b.excerpt(l, 80),
                    bookTitle = b.title,
                    contentRevision = 3,
                    chapterTitle = b.chapters[l.chapterIndex].title,
                )
            )
            notice.value = "已夹入书签"
        }
    }

    fun addHighlight(chapter: Int, start: Int, end: Int, text: String, onCreated: (Long) -> Unit = {}) {
        val b = state.value.book ?: return
        viewModelScope.launch {
            val rowId = repository.dao.addHighlight(
                HighlightRow(bookId = id, chapterIndex = chapter, start = start, end = end, text = text,
                    chapterTitle = b.chapters.getOrNull(chapter)?.title.orEmpty(), bookTitle = b.title)
            )
            onCreated(rowId)
        }
    }

    fun highlightNote(rowId: Long, note: String) { viewModelScope.launch { repository.dao.highlightNote(rowId, note.trim()) } }
    fun highlightRange(rowId: Long, chapter: Int, start: Int, end: Int) {
        val text = state.value.book?.chapters?.getOrNull(chapter)?.text ?: return
        viewModelScope.launch { repository.dao.highlightRange(rowId, start, end, text.substring(start, end)) }
    }
    fun highlightColor(rowId: Long, color: Int) { viewModelScope.launch { repository.dao.highlightColor(rowId, color) } }
    fun deleteHighlight(rowId: Long) { viewModelScope.launch { repository.dao.deleteHighlight(rowId) } }
    fun bookmarkNote(rowId: Long, note: String) { viewModelScope.launch { repository.dao.bookmarkNote(rowId, note.trim()) } }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.dao.deleteBookmark(id) }
    }

    fun search(query: String) {
        com.lyric.reader.ReaderMetrics.endSearch()
        if (query.isNotBlank()) com.lyric.reader.ReaderMetrics.beginSearch()
        searchJob?.cancel()
        hits.value = emptyList()
        highlight.value = query
        val book = state.value.book ?: return
        if (query.isBlank()) {
            searching.value = false
            return
        }
        searchJob =
            viewModelScope.launch {
                searching.value = true
                delay(180)
                val found = mutableListOf<SearchHit>()
                var lastFlush = 0L
                repository.search(book, query).collect { hit ->
                    found += hit
                    val now = System.nanoTime()
                    if (found.size == 1 || now - lastFlush > 50_000_000) {
                        hits.value = found.toList()
                        lastFlush = now
                        yield()
                    }
                    if (found.size >= 2000) {
                        hits.value = found.toList()
                        searching.value = false
                        notice.value = "显示前 2000 处匹配，请缩小关键词"
                        cancel()
                    }
                }
                hits.value = found.toList()
                searching.value = false
            }
    }

    fun reparse(encoding: String?, regex: String?) {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true)
            try {
                save()
                val (row, book) = repository.reparse(id, encoding, regex)
                val p = repository.dao.progress(id)
                engine.open(
                    book,
                    p?.let { Locator(it.chapterIndex, it.charOffset) } ?: locator.value,
                )
                state.value = ReaderState(row, book, false)
                search("")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.value = state.value.copy(loading = false)
                notice.value = e.localizedMessage ?: "解析失败"
            }
        }
    }

    private suspend fun save() {
        state.value.book?.let { progressWriter.flush(it to locator.value) }
    }

    fun flush() {
        if (closed) return
        val book = state.value.book ?: return
        val at = locator.value
        progressWriter.submit(book to at)
    }

    override fun onCleared() {
        pause()
        closed = true
        sessionWrites.close()
        progressWriter.close()
        tickJob.cancel()
        super.onCleared()
    }
}

class LyricFactory(private val app: LyricApp, private val bookId: String? = null) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        (if (bookId == null) ShelfViewModel(app) else ReaderViewModel(app, bookId)) as T
}
