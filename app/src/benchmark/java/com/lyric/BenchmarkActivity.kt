package com.lyric

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyric.core.ContentType
import com.lyric.core.PageMode
import com.lyric.core.ReaderSettings
import com.lyric.data.SettingsScope
import com.lyric.ui.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*

/** Only shipped in the isolated .benchmark application, never in debug/release APKs. */
class BenchmarkActivity : ReaderActivity() {
    private data class Fixture(val name: String, val sha256: String? = null)
    private val fixtures = mapOf(
        "txt" to Fixture("benchmark-10mb.txt"),
        "epub" to Fixture("jianlai.epub", "8368c9b1a06a5ce8ba156fda5892cb20020b008680dbc8a99de2d833cc789940"),
        "cbz" to Fixture("lyric-test-500p.cbz", "db3269a3b229bc6f75e351ab10e94175a8bbbea63383eba66f229e48f841a4c7"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LyricApp
        val repo = app.repository
        lifecycleScope.launch {
            try {
                val kind = intent.getStringExtra("fixture") ?: "txt"
                val spec = requireNotNull(fixtures[kind]) { "Unknown fixture: $kind (txt|epub|cbz)" }
                val fixture = File(filesDir, spec.name)
                if (intent.getBooleanExtra("prepare", false)) {
                    withContext(Dispatchers.IO) { prepareFixture(kind, spec, fixture) }
                    repo.importUris(listOf(Uri.fromFile(fixture)))
                    check(repo.imports.value.failures == 0) { repo.imports.value.errors.joinToString() }
                    val row = withContext(Dispatchers.IO) {
                        repo.dao.all().firstOrNull { it.name == fixture.name }
                            ?: error("Import failed: ${fixture.name}")
                    }
                    repo.settings.reset(SettingsScope.GLOBAL, "")
                    ContentType.entries.forEach { repo.settings.reset(SettingsScope.TYPE, "", it) }
                    repo.settings.reset(SettingsScope.BOOK, row.id)
                    val settings = ReaderSettings(
                        volumeKeys = true,
                        pageMode = if (intent.getBooleanExtra("scroll", false)) PageMode.SCROLL else PageMode.SLIDE,
                    )
                    repo.settings.update(SettingsScope.GLOBAL, "", ReaderSettings(), settings)
                    repo.dao.saveProgress(com.lyric.data.ProgressRow(row.id, 0, 0, contentRevision = 3))
                    // Reuse the normal reader without first-import hint animations in measurements.
                    withContext(Dispatchers.IO) {
                        getSharedPreferences("comic_hints", 0).edit()
                            .putBoolean("chosen_${row.id}", true).putBoolean("hint_v3_${row.id}", true).commit()
                    }
                    repo.clearCaches()
                    setContent { LyricTheme { Text("PREPARED") } }
                } else {
                    val row = withContext(Dispatchers.IO) {
                        repo.dao.all().firstOrNull { it.name == fixture.name }
                            ?: error("Run prepare=true fixture=$kind before opening")
                    }
                    setContent {
                        LyricTheme {
                            val vm: ReaderViewModel = viewModel(factory = LyricFactory(app, row.id))
                            ReaderScreen(vm) { finish() }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                setContent { LyricTheme { Text("FAILED: ${error.message}") } }
            }
        }
    }

    /** All generation, copying and checksum validation is outside the measured open. */
    private fun prepareFixture(kind: String, spec: Fixture, target: File) {
        if (kind == "txt") {
            if (!target.exists() || target.length() != 10L * 1024 * 1024) {
                val line = "Reading quietly across the paper, one sentence at a time.\n".toByteArray(Charsets.UTF_8)
                target.outputStream().use { out ->
                    var left = 10 * 1024 * 1024
                    while (left > 0) {
                        val n = minOf(line.size, left)
                        out.write(line, 0, n)
                        left -= n
                    }
                }
                java.io.RandomAccessFile(target, "rw").use {
                    it.seek(6L * 1024 * 1024)
                    it.write("LYRIC_NEEDLE".toByteArray(Charsets.UTF_8))
                }
            }
            return
        }
        val candidates = listOfNotNull(
            intent.getStringExtra("fixturePath")?.let(::File),
            File("/data/local/tmp/lyric-fixtures", spec.name),
            File("/sdcard/Download/lyric-baseline", spec.name),
        )
        val source = candidates.firstOrNull { it.isFile && it.canRead() }
            ?: error("Missing/unreadable $kind fixture. Tried ${candidates.joinToString()}. " +
                "Run scripts/run-benchmarks.sh (Download fallback requires benchmark storage app-op).")
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        check(sha == spec.sha256) { "SHA-256 mismatch: $source ($sha)" }
        source.copyTo(target, overwrite = true)
    }
}
