package com.lyric.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lyric.LyricApp
import com.lyric.core.ReaderSettings
import com.lyric.data.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MeViewModel(private val app: LyricApp) : ViewModel() {
    val store = app.repository.settings
    private val dao = app.repository.dao
    val preferences = store.global.stateIn(viewModelScope, SharingStarted.Eagerly, ReaderSettings())
    val ownBooks = store.ownBookCount.stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val bookmarks = dao.observeAllBookmarks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val days = dao.observeDailyStats("0000-01-01", "9999-12-31")
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val totals = dao.observeTotals().stateIn(viewModelScope, SharingStarted.Eagerly, ReadingTotals(0, 0))
    val today = MutableStateFlow(LocalDate.now())
    val selectedDay = MutableStateFlow<LocalDate?>(null)
    val dayBooks = MutableStateFlow<List<DayBook>>(emptyList())
    val usage = MutableStateFlow(StorageUsage())
    val preview = MutableStateFlow<BackupPreview?>(null)
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    val backupFilename get() = app.backup.filename
    private var dayJob: Job? = null

    init { viewModelScope.launch { while (isActive) { today.value = LocalDate.now(); delay(30_000) } } }

    fun update(next: ReaderSettings) {
        val before = preferences.value
        // Keep rapid slider/theme changes responsive while DataStore serializes writes.
        viewModelScope.launch { store.update(SettingsScope.GLOBAL, "", before, next) }
    }

    fun resetInheritance() = action("已恢复所有书籍继承") { store.resetBookInheritance() }

    fun selectDay(date: LocalDate) {
        selectedDay.value = date
        dayBooks.value = emptyList()
        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            val zone = ZoneId.systemDefault()
            dayBooks.value = dao.dayBooks(date.atStartOfDay(zone).toInstant().toEpochMilli(),
                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        }
    }

    fun exportBackup(uri: Uri, fonts: Boolean) = action("备份已保存") { app.backup.export(uri, fonts) }
    fun previewBackup(uri: Uri) = action(null) { preview.value = app.backup.preview(uri) }
    fun restore() {
        val backup = preview.value ?: return
        preview.value = null
        action("已恢复；未匹配的书请在本地导入中重新选择原文件") {
            app.backup.restore(backup)
            app.repository.checkSources()
        }
    }
    fun inspect() = action(null) { usage.value = app.storage.inspect() }
    fun clearCache() = action("缓存已清理；下次打开书籍时自动重建") {
        app.storage.clearCache(); usage.value = app.storage.inspect()
    }
    fun removeInvalid() = action("已移除失效书籍，阅读足迹与摘录保留") {
        dao.all().filter { it.invalid }.forEach { app.repository.remove(it.id) }
    }

    fun exportBookmarks(uri: Uri, marks: List<BookmarkSummary>) = action("Markdown 已导出") {
        withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { out ->
                out.appendLine("# Lyric · 书签与摘录\n")
                marks.groupBy { it.mark.bookId }.values.forEach { group ->
                    out.appendLine("## ${markdown(group.first().title)}\n")
                    group.forEach { item ->
                        val m = item.mark
                        out.appendLine("### ${markdown(m.chapterTitle)} · ${java.time.Instant.ofEpochMilli(m.createdAt).atZone(ZoneId.systemDefault())}\n")
                        out.appendLine("> ${markdown(m.excerpt).replace("\n", "\n> ")}\n")
                        if (item.unavailable) out.appendLine("原文件不可用，请重新导入。\n")
                    }
                }
            }
        }
    }

    fun exportStats(uri: Uri) = action("统计已导出") {
        withContext(Dispatchers.IO) {
            requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { out ->
                out.appendLine("date,duration_seconds,characters")
                dao.dailyStats().forEach { out.appendLine("${it.day},${it.durationSec},${it.chars}") }
            }
        }
    }

    private fun action(message: String?, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block(); if (message != null) notice.value = message }
            catch (e: Exception) { if (e is CancellationException) throw e; notice.value = e.localizedMessage ?: "操作失败" }
            finally { busy.value = false }
        }
    }

    companion object {
        fun factory(app: LyricApp) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>) = MeViewModel(app) as T
        }
        fun markdown(value: String) = value.replace("\\", "\\\\").replace("<", "&lt;")
            .replace(">", "&gt;").replace("*", "\\*").replace("_", "\\_").replace("#", "\\#")
    }
}
