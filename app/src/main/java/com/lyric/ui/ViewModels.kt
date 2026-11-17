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
    fun cancelImport() = repository.cancelImport()
    fun scannedToComic(id: String, move: Boolean) {
        repository.dismissScanned(id)
        if (move) viewModelScope.launch { repository.dao.moveToSection(id, ShelfSection.COMIC.name) }
    }
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

    val compact =
        repository.settings.shelfCompact(ShelfSection.BOOK)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val comicCompact =
        repository.settings.shelfCompact(ShelfSection.COMIC)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun compact(section: ShelfSection, compact: Boolean) {
        viewModelScope.launch { repository.settings.shelfCompact(section, compact) }
    }

    val shelfFilter =
        repository.settings.shelfFilter(ShelfSection.BOOK)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShelfFilter())
    val comicFilter =
        repository.settings.shelfFilter(ShelfSection.COMIC)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShelfFilter())
    fun filter(section: ShelfSection, filter: ShelfFilter) {
        viewModelScope.launch { repository.settings.shelfFilter(section, filter) }
    }

    fun import(uris: List<Uri>, folder: Boolean = false, grantFlags: Int? = null) {
        app.applicationScope.launch { repository.importUris(uris, folder, grantFlags) }
    }

    fun consumeOpenBook(): String? = repository.consumeOpenBook()
    fun consumeImportMessage(batchId: Long) = repository.consumeImportMessage(batchId)

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

class ReaderViewModel(private val app: LyricApp, val id: String, initialLocator: Locator? = null, initialBookmark: Long = 0L) : ViewModel() {
    val repository = app.repository
    val engine = ReaderSession()
    val locator = engine.currentLocator
    val settings = engine.settings
    val state = MutableStateFlow(ReaderState())
    val contentGeneration = MutableStateFlow(0)
    val navigationFailure = MutableStateFlow(0)
    private var jumpJob: Job? = null
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
    private var pendingJump: Locator? = initialLocator
    private var pendingBookmark = initialBookmark

    private var backgroundStarted = false
    fun firstWindowReady() {
        val book = state.value.book ?: return
        if (book.deferred == null || backgroundStarted) return
        backgroundStarted = true
        viewModelScope.launch { repository.startBackground(book) }
    }
    fun readingGesture() = tracker.onActivity()
    fun resume() = tracker.onResume()
    fun pause() {
        if (closed) return
        tracker.onPause()
        // Queue both final snapshots before waiting. Application-scope FIFO writers survive
        // the bounded lifecycle wait and retain their ordering after a slow-disk timeout.
        val target = state.value.book?.let { progressWriter.submit(it to locator.value) }
        val barrier = CompletableDeferred<Unit>()
        check(sessionWrites.trySend { barrier.complete(Unit); Unit }.isSuccess)
        com.lyric.reader.awaitPauseWrites {
            try {
                if (target != null) progressWriter.await(target)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice.value = "阅读进度保存失败：${e.localizedMessage}"
            }
            barrier.await()
        }
    }
    fun readingActivity(next: Locator) {
        val book = state.value.book ?: return
        if (book.chapters.isEmpty()) return
        val target = book.clamp(next)
        if (book.chapters[target.chapterIndex].pending) { goTo(target); return }
        val old = locator.value
        if (target != old && book.contentType == ContentType.COMIC) {
            val pages = { l: Locator -> book.chapters.take(l.chapterIndex).sumOf { it.units.size.toLong() } + l.page }
            tracker.onActivity(kotlin.math.abs(pages(target) - pages(old)))
        } else if (target != old) {
            val distance = if (book.progressReady) {
                val a = book.textOffset(old.chapterIndex) + old.charOffset
                val b = book.textOffset(target.chapterIndex) + target.charOffset
                kotlin.math.abs(b - a)
            } else if (old.chapterIndex == target.chapterIndex) kotlin.math.abs(target.charOffset - old.charOffset).toLong()
            else 0L
            tracker.onActivity(distance)
        }
        engine.goTo(target)
        flush()
        com.lyric.reader.KillCheck.activity(app)
        com.lyric.reader.KillCheck.record(app, id, book.title, target)
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
        backgroundStarted = false
        com.lyric.reader.ReaderMetrics.beginOpen()
        com.lyric.reader.PerfLog.beginOpen(fallback = true)
        viewModelScope.launch {
            state.value = ReaderState()
            try {
                val metadata = repository.dao.book(id)
                state.value = ReaderState(row = metadata)
                val initialSettings = async {
                    repository.settings.settings(id, metadata?.contentType?.let(ContentType::valueOf) ?: ContentType.TEXT).first()
                }
                val beforeMark = if (pendingBookmark != 0L) repository.dao.bookmark(pendingBookmark) else null
                val requested = beforeMark?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: pendingJump
                val (row, book) = repository.load(id, requested = requested)
                val firstSettings = initialSettings.await()
                engine.settings(if (metadata?.contentType == book.contentType.name) firstSettings else
                    repository.settings.settings(id, book.contentType).first())
                val p = repository.dao.progress(id)
                val requestedMark = if (pendingBookmark != 0L) repository.dao.bookmark(pendingBookmark) else null
                val initial = requestedMark?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: resumeLocator(pendingJump, p)
                book.ensureChapter(initial.chapterIndex)
                engine.open(book, initial)
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
                viewModelScope.launch {
                    runCatching { Annotations.migrateExcerpts(repository.dao, id, book) }
                }
                save()
                viewModelScope.launch {
                    book.deferred?.awaitLengths()
                    flush()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.value = ReaderState(loading = false, error = e.localizedMessage ?: "打开失败")
            }
        }
    }

    fun goTo(locator: Locator) = prepareChapter(locator) {
        engine.goTo(locator)
        flush()
    }

    fun prepareChapter(locator: Locator, ready: () -> Unit) {
        val book = state.value.book ?: return
        val interrupted = jumpJob?.isActive == true
        jumpJob?.cancel()
        if (!book.chapters[locator.chapterIndex.coerceIn(book.chapters.indices)].pending) {
            if (interrupted) navigationFailure.value++
            ready()
            return
        }
        contentGeneration.value++
        jumpJob = viewModelScope.launch {
            try {
                book.ensureChapter(locator.chapterIndex)
                ready()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                notice.value = "章节读取失败：${e.localizedMessage}"
                navigationFailure.value++
            }
        }
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
        if (start !in 0..text.length || end !in 0..text.length || start >= end) return
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

class LyricFactory(private val app: LyricApp, private val bookId: String? = null,
    private val initialLocator: Locator? = null, private val initialBookmark: Long = 0L) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        (if (bookId == null) ShelfViewModel(app) else ReaderViewModel(app, bookId, initialLocator, initialBookmark)) as T
}
