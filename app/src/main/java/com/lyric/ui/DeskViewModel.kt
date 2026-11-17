@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.lyric.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lyric.LyricApp
import com.lyric.core.ContentType
import com.lyric.core.Locator
import com.lyric.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow

@androidx.compose.runtime.Immutable
data class DeskSummary(val minutes: Int = 0, val pages: Int = 0, val notes: Int = 0)

@androidx.compose.runtime.Immutable
data class FocusUi(
    val running: Boolean = false,
    val paused: Boolean = false,
    val bookId: String = "",
    val bookTitle: String = "",
    val plannedMin: Int = 25,
    val remainSec: Long = 25 * 60,
    val elapsedSec: Long = 0,
)

class DeskViewModel(private val app: LyricApp) : ViewModel() {
    private val dao = app.repository.dao
    private val zone = ZoneId.systemDefault()

    val books = app.repository.books.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val highlights = dao.observeAllHighlights()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val dayBounds = MutableStateFlow(dayRange(LocalDate.now()))
    val summary: StateFlow<DeskSummary> = dayBounds.flatMapLatest { (from, to) ->
        combine(
            dao.observeDailyStats(
                Instant.ofEpochMilli(from).atZone(zone).toLocalDate().toString(),
                Instant.ofEpochMilli(to - 1).atZone(zone).toLocalDate().toString(),
            ),
            dao.observeApproxPages(from, to),
            dao.observeHighlightCount(from, to),
        ) { days, pages, notes ->
            val today = LocalDate.now().toString()
            val readSec = days.firstOrNull { it.day == today }?.durationSec ?: 0L
            // Focus duration is written into reading_sessions on complete, so minutes stay single-sourced.
            DeskSummary(
                minutes = (readSec / 60).toInt(),
                pages = pages.toInt().coerceAtLeast(0),
                notes = notes,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeskSummary())

    private val quoteId = MutableStateFlow<Long?>(null)
    val quote: StateFlow<HighlightRow?> = combine(highlights, quoteId) { all, id ->
        if (all.isEmpty()) null
        else all.firstOrNull { it.id == id } ?: preferShort(all).let { it[Random.nextInt(it.size)] }.also { quoteId.value = it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val focusBookId = MutableStateFlow<String?>(null)
    val plannedMin = MutableStateFlow(25)
    val customPlanned = MutableStateFlow(false)
    val focus = MutableStateFlow(FocusUi())
    val focusPulse = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val selectedBook: StateFlow<ShelfBook?> = combine(books, focusBookId) { list, id ->
        val text = list.filter { it.contentType == ContentType.TEXT.name && !it.invalid }
        when {
            id != null -> text.firstOrNull { it.id == id } ?: list.firstOrNull { it.id == id }
            else -> text.maxByOrNull { it.lastRead } ?: list.filter { !it.invalid }.maxByOrNull { it.lastRead }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val noisePlayer = com.lyric.audio.NoisePlayer(app, viewModelScope, ::pauseFocus)
    val noise = noisePlayer.settings
    fun noiseSound(sound: com.lyric.audio.NoiseSound) = noisePlayer.select(sound)
    fun noiseVolume(volume: Float) = noisePlayer.volume(volume)
    override fun onCleared() { noisePlayer.close(); super.onCleared() }

    private var tickJob: Job? = null
    private var focusId: String? = null
    private var wallStart = 0L
    private var pausedMs = 0L
    private var pausedAt = 0L

    init {
        viewModelScope.launch {
            focus.map { it.running to it.paused }.distinctUntilChanged().collect { (running, paused) -> noisePlayer.timer(running, paused) }
        }
        viewModelScope.launch {
            while (isActive) {
                dayBounds.value = dayRange(LocalDate.now())
                delay(30_000)
            }
        }
        viewModelScope.launch {
            val minutes = app.repository.settings.focusPlannedMin.first()
            val custom = app.repository.settings.focusCustom.first()
            if (!focus.value.running) {
                plannedMin.value = minutes
                customPlanned.value = custom
                focus.value = focus.value.copy(plannedMin = minutes, remainSec = minutes * 60L)
            }
        }
        viewModelScope.launch { restoreActiveFocus() }
    }

    fun reshuffleQuote() {
        val all = highlights.value
        if (all.isEmpty()) { quoteId.value = null; return }
        val current = quoteId.value
        val next = preferShort(all).filter { it.id != current }.ifEmpty { all }
        quoteId.value = next[Random.nextInt(next.size)].id
    }

    fun pickBook(id: String) { focusBookId.value = id }

    fun planned(min: Int, custom: Boolean = false) {
        if (focus.value.running) return
        val snapped = com.lyric.ui.theme.LyricTokens.Focus.snapMinutes(min)
        plannedMin.value = snapped
        customPlanned.value = custom
        focus.value = focus.value.copy(plannedMin = snapped, remainSec = snapped * 60L)
        viewModelScope.launch { app.repository.settings.focusPlan(snapped, custom) }
    }

    fun stepPlanned(delta: Int) = planned(plannedMin.value + delta, custom = true)


    fun startFocus(): Boolean {
        if (focus.value.running) return true
        val book = selectedBook.value ?: return false
        val id = UUID.randomUUID().toString()
        focusId = id
        wallStart = System.currentTimeMillis()
        pausedMs = 0
        pausedAt = 0
        val plan = plannedMin.value
        focus.value = FocusUi(
            running = true, paused = false, bookId = book.id, bookTitle = book.title,
            plannedMin = plan, remainSec = plan * 60L, elapsedSec = 0,
        )
        viewModelScope.launch {
            dao.upsertFocus(
                FocusSession(
                    id = id, bookId = book.id, bookTitle = book.title,
                    startAt = wallStart, endAt = 0, durationSec = 0, plannedMin = plan,
                )
            )
        }
        tick()
        return true
    }

    fun pauseFocus() {
        val f = focus.value
        if (!f.running || f.paused) return
        pausedAt = System.currentTimeMillis()
        focus.value = f.copy(paused = true)
        persistActive()
        tickJob?.cancel()
    }

    fun resumeFocus() {
        val f = focus.value
        if (!f.running || !f.paused) return
        if (pausedAt > 0) {
            pausedMs += System.currentTimeMillis() - pausedAt
            pausedAt = 0
        }
        focus.value = f.copy(paused = false)
        persistActive()
        tick()
    }

    fun pauseFocusFor(bookId: String) { if (focus.value.running && focus.value.bookId == bookId) pauseFocus() }
    fun resumeFocusFor(bookId: String) { if (focus.value.running && focus.value.bookId == bookId) resumeFocus() }

    /** 重置: drop the session; elapsed ≥1 min still counts as reading time. */
    fun resetFocus() = finishFocus(completed = false)

    fun abandonFocus() {
        finishFocus(completed = false)
    }

    private fun tick() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val f = focus.value
                if (!f.running || f.paused) continue
                val elapsed = ((System.currentTimeMillis() - wallStart - pausedMs) / 1000).coerceAtLeast(0)
                val remain = (f.plannedMin * 60L - elapsed).coerceAtLeast(0)
                focus.value = f.copy(elapsedSec = elapsed, remainSec = remain)
                if (elapsed != f.elapsedSec && elapsed % 15L == 0L) persistActive()
                if (remain <= 0L) {
                    finishFocus(completed = true)
                    break
                }
            }
        }
    }

    private fun finishFocus(completed: Boolean) {
        tickJob?.cancel()
        val f = focus.value
        val id = focusId
        val end = System.currentTimeMillis()
        val elapsed = if (f.running) {
            ((end - wallStart - pausedMs - if (pausedAt > 0) end - pausedAt else 0) / 1000).coerceAtLeast(0)
        } else 0L
        val duration = if (completed) f.plannedMin * 60L else elapsed
        if (completed) focusPulse.tryEmit(Unit)
        focus.value = FocusUi(plannedMin = plannedMin.value, remainSec = plannedMin.value * 60L)
        focusId = null
        if (id == null) return
        viewModelScope.launch {
            dao.upsertFocus(
                FocusSession(
                    id = id, bookId = f.bookId, bookTitle = f.bookTitle,
                    startAt = wallStart, endAt = end, durationSec = duration,
                    plannedMin = f.plannedMin, completed = completed && duration >= 10,
                    pausedMs = pausedMs, pausedAt = 0,
                )
            )
            if (completed || duration >= 60) {
                // Feed 今日小结 minutes via reading_sessions (chars=0 so pages stay from real reading).
                dao.addSession(
                    ReadingSession(
                        id = "focus:$id", bookId = f.bookId,
                        startAt = wallStart, endAt = end, durationSec = duration, chars = 0,
                    )
                )
            }
            dayBounds.value = dayRange(LocalDate.now())
        }
    }

    private fun persistActive() {
        val id = focusId ?: return
        val f = focus.value
        if (!f.running) return
        viewModelScope.launch {
            dao.upsertFocus(
                FocusSession(
                    id = id, bookId = f.bookId, bookTitle = f.bookTitle,
                    startAt = wallStart, endAt = 0, durationSec = f.elapsedSec,
                    plannedMin = f.plannedMin, completed = false,
                    pausedMs = pausedMs, pausedAt = pausedAt,
                )
            )
        }
    }

    private suspend fun restoreActiveFocus() {
        val active = dao.activeFocus() ?: return
        focusId = active.id
        wallStart = active.startAt
        pausedMs = active.pausedMs
        pausedAt = active.pausedAt
        plannedMin.value = active.plannedMin
        focusBookId.value = active.bookId
        val now = System.currentTimeMillis()
        val extraPause = if (active.pausedAt > 0) now - active.pausedAt else 0L
        val elapsed = ((now - active.startAt - active.pausedMs - extraPause) / 1000).coerceAtLeast(0)
        val remain = (active.plannedMin * 60L - elapsed).coerceAtLeast(0)
        if (remain <= 0) {
            focus.value = FocusUi(
                running = true, paused = false, bookId = active.bookId, bookTitle = active.bookTitle,
                plannedMin = active.plannedMin, remainSec = 0, elapsedSec = active.plannedMin * 60L,
            )
            finishFocus(completed = true)
            return
        }
        focus.value = FocusUi(
            running = true, paused = active.pausedAt > 0,
            bookId = active.bookId, bookTitle = active.bookTitle,
            plannedMin = active.plannedMin, remainSec = remain, elapsedSec = elapsed,
        )
        if (active.pausedAt == 0L) tick()
    }

    suspend fun exportNotes(bookId: String? = null): java.io.File {
        val notes = dao.allHighlights().let { all -> if (bookId == null) all else all.filter { it.bookId == bookId } }
        val authors = dao.all().associate { it.id to it.author }
        return com.lyric.export.NoteExport.markdownFile(app, notes, authors)
    }

    suspend fun exportCard(note: HighlightRow, night: Boolean): java.io.File =
        com.lyric.export.NoteExport.imageFile(app, note, dao.book(note.bookId)?.author.orEmpty(), night)

    fun filterNotes(all: List<HighlightRow>, query: String, byBook: Boolean): List<HighlightRow> {
        val q = query.trim()
        val filtered = if (q.isEmpty()) all else all.filter {
            it.text.contains(q, true) || it.note.contains(q, true) ||
                it.bookTitle.contains(q, true) || it.chapterTitle.contains(q, true)
        }
        return if (byBook) filtered.sortedWith(compareBy({ it.bookTitle }, { it.chapterIndex }, { it.start }))
        else filtered.sortedByDescending { it.createdAt }
    }

    fun openLocator(h: HighlightRow) = Locator(h.chapterIndex, h.start, 0)

    /** 今日一句 prefers short highlights (≤ 80 chars) when any exist. */
    private fun preferShort(all: List<HighlightRow>) = all.filter { it.text.trim().length in 1..80 }.ifEmpty { all }

    companion object {
        fun factory(app: LyricApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>) = DeskViewModel(app) as T
        }

        fun dayRange(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            return from to to
        }

        fun formatRemain(sec: Long): String {
            val s = sec.coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }

        fun relativeDay(epoch: Long, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
            val day = Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate()
            return when (day) {
                today -> "今天"
                today.minusDays(1) -> "昨天"
                else -> "${day.monthValue}月${day.dayOfMonth}日"
            }
        }
    }
}
