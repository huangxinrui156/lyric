package com.lyric.perf

import android.app.Application
import android.graphics.Typeface
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.*
import com.lyric.data.*
import com.lyric.reader.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in host comparison, same source and dimensions before/after. No device frame claims. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadJankBenchmarkTest {
    @Test fun coldAndWarmLoads() = runBlocking {
        assumeTrue(System.getenv("LYRIC_LOAD_BENCH") == "1")
        val app = ApplicationProvider.getApplicationContext<Application>()
        val txt = File("/tmp/lyric-load-10m.txt")
        // Exact deterministic 10MiB UTF-8, plain ASCII so the last byte cannot split a codepoint.
        txt.outputStream().buffered().use { out ->
            val line = "A quiet page in Lyric. Reading continues across paragraphs.\n".toByteArray()
            var left = 10 * 1024 * 1024
            while (left > 0) { val n = minOf(left, line.size); out.write(line, 0, n); left -= n }
        }
        val samples = listOf(File("/workspace/epub-real/jianlai.epub"), txt, File("/workspace/lyric-test-500p.cbz"))
        assertTrue(samples.all { it.isFile })
        val main = Executors.newSingleThreadExecutor { r -> Thread(r, "load-bench-ui-surrogate") }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val store = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "load-bench.preferences_pb") })
        val timer = Executors.newSingleThreadScheduledExecutor()
        val maxLag = AtomicLong()
        val tick = timer.scheduleAtFixedRate({
            val sent = System.nanoTime()
            main.executor.execute { maxLag.accumulateAndGet(System.nanoTime() - sent, ::maxOf) }
        }, 0, 10, TimeUnit.MILLISECONDS)
        suspend fun measured(label: String, action: suspend () -> Unit) {
            maxLag.set(0)
            val start = System.nanoTime()
            withContext(main) { action() }
            withContext(main) { /* drain heartbeat */ }
            println("LOAD_HOST $label wall_ms=${(System.nanoTime() - start) / 1_000_000} ui_queue_max_ms=${maxLag.get() / 1_000_000}")
        }
        suspend fun firstWindow(repo: LibraryRepository, id: String, label: String) {
            measured(label) {
                val start = System.nanoTime()
                val book = repo.load(id).second
                val openMs = (System.nanoTime() - start) / 1_000_000
                if (book.contentType == ContentType.TEXT) {
                    val pager = PrePaginator(book, scope, { c, at -> Typesetter.layout(book.chapters[c].text,
                        at, 1000, 2200, 3f, 3f, ReaderSettings(), Typeface.SERIF, book.chapters[c]) })
                    assertTrue(pager.get(0, 0).pages.isNotEmpty())
                    pager.close()
                } else {
                    withContext(Dispatchers.IO) {
                        ComicArchive(repo.comicFile(id)).use { source ->
                            val entry = (book.chapters.first().units.first() as ContentUnit.ImagePage).resource
                            assertTrue(source.page(entry, 1080 * 2, 2400 * 2).width > 0)
                        }
                    }
                }
                println("LOAD_HOST $label open_ms=$openMs chapters=${book.chapters.size} chars=${book.totalChars}")
            }
        }
        try {
            for (file in samples) repeat(2) { round ->
                var repo = LibraryRepository(app, db, store)
                measured("${file.extension} import${round + 1}") { repo.importUris(listOf(Uri.fromFile(file))) }
                assertEquals(repo.imports.value.toString(), 1, repo.imports.value.added)
                val id = repo.dao.all().single().id
                repo.clearCaches()
                firstWindow(repo, id, "${file.extension} cold${round + 1}")
                repo = LibraryRepository(app, db, store)
                firstWindow(repo, id, "${file.extension} disk${round + 1}")
                firstWindow(repo, id, "${file.extension} memory${round + 1}")
                repo.remove(id)
            }
        } finally {
            tick.cancel(false); timer.shutdownNow(); main.close(); scope.cancel(); db.close()
        }
    }
}
