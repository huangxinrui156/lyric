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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ShelfViewModel(private val app: LyricApp) : ViewModel() {
    val repository = app.repository
    val books =
        repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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

    fun sort(value: Boolean) {
        viewModelScope.launch { repository.settings.shelf(sortTitle = value) }
    }

    fun import(uris: List<Uri>, folder: Boolean = false) {
        app.applicationScope.launch { repository.importUris(uris, folder) }
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
    val hits = MutableStateFlow<List<SearchHit>>(emptyList())
    val searching = MutableStateFlow(false)
    val highlight = MutableStateFlow("")
    val notice = MutableStateFlow<String?>(null)
    private val tracker = SessionTracker(id) { session ->
        app.applicationScope.launch(Dispatchers.IO) { repository.dao.addSession(session) }
    }
    private val tickJob = viewModelScope.launch {
        while (isActive) { delay(1000); tracker.tick() }
    }
    private var pendingJump: Locator? = null

    fun readingGesture() = tracker.onActivity()
    fun resume() = tracker.onResume()
    fun pause() { tracker.onPause(); flush() }
    fun readingActivity(next: Locator) {
        val book = state.value.book ?: return
        val old = locator.value
        if (next != old) {
            val a = book.chapters.take(old.chapterIndex).sumOf { it.text.length.toLong() } + old.charOffset
            val b = book.chapters.take(next.chapterIndex).sumOf { it.text.length.toLong() } + next.charOffset
            tracker.onActivity(kotlin.math.abs(b - a))
        }
        engine.goTo(next)
    }
    private var finishedRecorded = false
    fun finished() {
        val book = state.value.book ?: return
        if (finishedRecorded) return
        finishedRecorded = true
        viewModelScope.launch { repository.dao.finish(FinishedBook(id, book.title, System.currentTimeMillis())) }
    }
    fun jump(locator: Locator) {
        if (state.value.book == null) pendingJump = locator else goTo(locator)
    }
    private var searchJob: Job? = null
    private var saveJob: Job? = null
    private var settingsJob: Job? = null

    init {
        viewModelScope.launch {
            locator.drop(1).collect {
                saveJob?.cancel()
                saveJob = launch {
                    delay(450)
                    save()
                }
            }
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            state.value = ReaderState()
            try {
                val (row, book) = repository.load(id)
                val p = repository.dao.progress(id)
                engine.open(
                    book,
                    pendingJump ?: p?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: Locator(),
                )
                pendingJump = null
                settingsJob?.cancel()
                settingsJob =
                    viewModelScope.launch {
                        repository.settings.settings(id, book.contentType).collect {
                            engine.settings(it)
                        }
                    }
                state.value = ReaderState(row, book, false)
                save()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                state.value = ReaderState(loading = false, error = e.localizedMessage ?: "打开失败")
            }
        }
    }

    fun goTo(locator: Locator) {
        engine.goTo(locator)
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

    fun bookmark() {
        val b = state.value.book ?: return
        val l = locator.value
        viewModelScope.launch {
            repository.dao.addBookmark(
                BookmarkRow(
                    bookId = id,
                    chapterIndex = l.chapterIndex,
                    charOffset = l.charOffset,
                    excerpt = b.excerpt(l, 80),
                    bookTitle = b.title,
                    chapterTitle = b.chapters[l.chapterIndex].title,
                )
            )
            notice.value = "已夹入书签"
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.dao.deleteBookmark(id) }
    }

    fun search(query: String) {
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
        state.value.book?.let { repository.save(id, it, locator.value) }
    }

    fun flush() {
        saveJob?.cancel()
        val book = state.value.book ?: return
        val at = locator.value
        app.applicationScope.launch { repository.save(id, book, at) }
    }

    override fun onCleared() {
        tracker.onPause()
        tickJob.cancel()
        flush()
        super.onCleared()
    }
}

class LyricFactory(private val app: LyricApp, private val bookId: String? = null) :
    ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        (if (bookId == null) ShelfViewModel(app) else ReaderViewModel(app, bookId)) as T
}
