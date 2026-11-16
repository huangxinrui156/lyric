package com.lyric

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyric.core.ReaderSettings
import com.lyric.data.SettingsScope
import com.lyric.ui.*
import java.io.File
import kotlinx.coroutines.*

/** Only shipped in the isolated .benchmark application, never in debug/release APKs. */
class BenchmarkActivity : ReaderActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LyricApp
        val repo = app.repository
        lifecycleScope.launch {
            val fixture = File(filesDir, "benchmark-10mb.txt")
            if (intent.getBooleanExtra("prepare", false)) {
                withContext(Dispatchers.IO) {
                    if (!fixture.exists() || fixture.length() != 10L * 1024 * 1024) {
                        val line = "Reading quietly across the paper, one sentence at a time.\n".toByteArray()
                        fixture.outputStream().use { out ->
                            var left = 10 * 1024 * 1024
                            while (left > 0) {
                                val n = minOf(line.size, left)
                                out.write(line, 0, n); left -= n
                            }
                        }
                        java.io.RandomAccessFile(fixture, "rw").use {
                            it.seek(6L * 1024 * 1024)
                            it.write("LYRIC_NEEDLE".toByteArray())
                        }
                    }
                }
                repo.importUris(listOf(Uri.fromFile(fixture)))
                val row = withContext(Dispatchers.IO) { repo.dao.all().first { it.name == fixture.name } }
                repo.settings.reset(SettingsScope.GLOBAL, "")
                repo.settings.reset(SettingsScope.TYPE, "")
                repo.settings.reset(SettingsScope.BOOK, row.id)
                repo.settings.update(SettingsScope.GLOBAL, "", ReaderSettings(), ReaderSettings(volumeKeys = true))
                repo.dao.saveProgress(com.lyric.data.ProgressRow(row.id, 0, 0, contentRevision = 3))
                repo.clearCaches()
                setContent { LyricTheme { Text("PREPARED") } }
            } else {
                val row = withContext(Dispatchers.IO) { repo.dao.all().first { it.name == fixture.name } }
                setContent {
                    LyricTheme {
                        val vm: ReaderViewModel = viewModel(factory = LyricFactory(app, row.id))
                        ReaderScreen(vm) { finish() }
                    }
                }
            }
        }
    }
}
