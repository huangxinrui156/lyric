package com.lyric

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.lyric.core.*
import com.lyric.data.SettingsScope
import com.lyric.ui.ReaderViewModel
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Debug APK only. Allows the host kill script to compare live anchors against durable Room data. */
internal object ReaderProbe {
    var vm: ReaderViewModel? = null
    const val NAME = "lyric-kill-fixture.txt"
}
class ReaderProbeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = context!!.applicationContext as LyricApp
        val repo = app.repository
        val result = runBlocking(Dispatchers.IO) {
            if (method == "prepare") {
                val file = File(app.filesDir, ReaderProbe.NAME)
                if (!file.exists()) file.writeText("Kill recovery fixture\n" + "安静阅读，保存每一页。\n".repeat(50_000))
                repo.importUris(listOf(Uri.fromFile(file)))
                val row = repo.dao.all().first { it.name == ReaderProbe.NAME }
                val prefs = repo.settings.settings(row.id).first()
                repo.settings.update(SettingsScope.BOOK, row.id, prefs,
                    prefs.copy(volumeKeys = true, swapVolumeKeys = false, pageMode = PageMode.NONE))
            }
            val row = repo.dao.all().first { it.name == ReaderProbe.NAME }
            val persisted = repo.dao.progress(row.id)
            val total = repo.dao.sessions().filter { it.bookId == row.id }.sumOf { it.durationSec }
            val live = if (method == "live") withContext(Dispatchers.Main) {
                ReaderProbe.vm?.let { vm -> Triple(vm.locator.value, vm.tracker.activeDurationSec(), !vm.state.value.loading) }
            } else null
            val loc = live?.first ?: persisted?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: Locator()
            """{"chapter":${loc.chapterIndex},"offset":${loc.charOffset},"page":${loc.page},"total":$total,"active":${live?.second ?: 0},"ready":${live?.third ?: false}}"""
        }
        return Bundle().apply { putString("snapshot", result) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
