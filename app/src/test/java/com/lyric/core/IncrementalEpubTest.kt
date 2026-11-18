package com.lyric.core

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.data.*
import com.lyric.reader.TextSelection
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class IncrementalEpubTest {
    private fun fixture(root: File): File = File(root, "test.epub").apply {
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to "<package><metadata><title>Incremental</title></metadata><manifest>" +
                (0..8).joinToString("") { "<item id='c$it' href='c$it.xhtml' media-type='application/xhtml+xml'/>" } +
                "<item id='nav' href='nav.xhtml' properties='nav' media-type='application/xhtml+xml'/></manifest><spine>" +
                (0..8).joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>",
            "OPS/nav.xhtml" to "<html><body><nav epub:type='toc'>" + (0..8).joinToString("") {
                "<a href='c$it.xhtml#target'>Chapter $it</a>"
            } + "</nav></body></html>")
        for (i in 0..8) entries["OPS/c$i.xhtml"] = "<html><body><h1>Chapter $i</h1><p>prefix 😀 $i</p><p id='target'>needle$i canonical text</p></body></html>"
        ZipOutputStream(outputStream()).use { zip -> entries.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
    private fun source(file: File) = object : FileContentSource {
        override val file = file
        override val displayName = "test.epub"
        override fun open() = file.inputStream()
    }
    private suspend fun <T> sample(block: suspend (File, File, ContentSource) -> T): T {
        val root = kotlin.io.path.createTempDirectory("incremental-test").toFile()
        return try { val epub = fixture(root); block(root, File(root, "parts"), source(epub)) }
        finally { root.deleteRecursively() }
    }

    @Test fun firstPublicationOnlyContainsCurrentAndNeighboursAndNormalToc() = runBlocking {
        sample { _, cache, source ->
            val b = withContext(Dispatchers.Default) { EpubParser().openIncremental(source, cache, 4) }
            assertEquals(listOf(3, 4, 5), b.chapters.filterNot { it.pending }.map { it.index })
            assertEquals(3, cache.listFiles()!!.count { it.name.matches(Regex("chapter-\\d+.json")) })
            assertEquals(9, b.toc.size)
            assertEquals("Chapter 8", b.toc.last().title)
            assertFalse(b.progressReady)
        }
    }
    @Test fun backgroundCompletesAndCacheReopenDoesNotReextractChapters() = runBlocking {
        sample { _, cache, source ->
            val b = withContext(Dispatchers.Default) { EpubParser().openIncremental(source, cache, 4) }
            b.deferred!!.complete()
            assertTrue(b.progressReady)
            assertTrue(b.chapters.none { it.pending })
            val chapters = b.chapters.toList()
            val stamps = cache.listFiles()!!.filter { it.name.startsWith("chapter-") }.associate { it.name to it.lastModified() }
            val again = withContext(Dispatchers.Default) { EpubParser().openIncremental(source, cache, 4) }
            assertEquals(3, again.chapters.count { !it.pending })
            assertTrue(again.progressReady)
            assertEquals(b.totalChars, again.totalChars)
            assertEquals(b.chapters.map { it.text.length }, again.chapters.indices.map { again.chapterLength(it) })
            again.deferred!!.complete()
            assertEquals(chapters, again.chapters)
            assertEquals(stamps, cache.listFiles()!!.filter { it.name.startsWith("chapter-") }.associate { it.name to it.lastModified() })
        }
    }
    @Test fun demandJumpResolvesTocFragmentAndBookmarkWithoutOffsetDrift() = runBlocking {
        sample { _, cache, source ->
            val b = withContext(Dispatchers.Default) { EpubParser().openIncremental(source, cache, 4) }
            assertTrue(b.chapters[8].pending)
            val chapter = b.ensureChapter(8)
            val mark = Locator(8, chapter.text.indexOf("needle8"))
            val at = b.clamp(Locator(8, fragment = b.toc.last().fragment))
            assertEquals(mark, at)
            val selection = TextSelection.word(chapter.text, mark.charOffset, TextSelection.absolute(b, 8, 0))!!
            val text = selection.text(b)
            val selectedAt = selection.locator(b)
            b.deferred!!.complete()
            assertEquals(mark, b.clamp(mark))
            assertEquals(text, selection.text(b))
            assertEquals(selectedAt, selection.locator(b))
            assertEquals(chapter.text, b.chapters[8].text)
            val percent = b.percent(mark)
            b.ensureChapter(0)
            assertEquals(percent, b.percent(mark), 0f)
            assertEquals(b.chapters.sumOf { it.text.length.toLong() }, b.totalChars)
        }
    }
    @Test fun searchLoadsPendingChaptersAndEveryHitIsCanonical() = runBlocking {
        sample { _, cache, source ->
            val b = withContext(Dispatchers.Default) { EpubParser().openIncremental(source, cache, 4) }
            val app = ApplicationProvider.getApplicationContext<Application>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
            try {
                val settings = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "search-${System.nanoTime()}.preferences_pb") })
                val hits = LibraryRepository(app, db, settings).search(b, "needle").toList()
                assertEquals(9, hits.size)
                hits.forEach { assertTrue(b.chapters[it.chapterIndex].text.substring(it.charOffset).startsWith("needle")) }
            } finally { scope.cancel(); db.close() }
        }
    }
    @Test fun repositoryPublishesNeighboursBeforeFillAndStillReadsLegacyFullJson() = runBlocking {
        sample { root, _, source ->
            val app = ApplicationProvider.getApplicationContext<Application>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
            try {
                val settings = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "repo-${System.nanoTime()}.preferences_pb") })
                val repo = LibraryRepository(app, db, settings)
                repo.importUris(listOf(android.net.Uri.fromFile((source as FileContentSource).file)))
                assertEquals(1, repo.imports.value.added)
                val row = repo.dao.all().single()
                val parts = File(app.filesDir, "parsed-v1").listFiles()!!.single { it.name.endsWith(".parts") }
                repo.clearCaches()
                repo.dao.saveProgress(ProgressRow(row.id, 4, 10, contentRevision = 3))
                val book = repo.load(row.id).second
                assertEquals(listOf(3, 4, 5), book.chapters.filterNot { it.pending }.map { it.index })
                val mark = BookmarkRow(bookId = row.id, chapterIndex = 4, charOffset = 10, excerpt = book.excerpt(Locator(4, 10)), chapterTitle = book.chapters[4].title, contentRevision = 3)
                repo.dao.addBookmark(mark)
                repo.dao.addHighlight(HighlightRow(bookId = row.id, chapterIndex = 4, start = 10, end = 15, text = book.chapters[4].text.substring(10, 15)))
                repo.startBackground(book)
                withTimeout(5000) { book.deferred!!.awaitLengths() }
                assertTrue(book.chapters.none { it.pending })
                assertEquals(10, repo.dao.bookmarks(row.id).first().single().charOffset)
                assertEquals(10, repo.dao.highlights(row.id).first().single().start)
                val again = LibraryRepository(app, db, settings).load(row.id).second
                assertTrue(again.progressReady)
                assertEquals(book.totalChars, again.totalChars)
                assertEquals(listOf(3, 4, 5), again.chapters.filterNot { it.pending }.map { it.index })
                // Full parsed-v1 JSON from 0.9.7 remains a valid cache format.
                parts.deleteRecursively()
                val full = withContext(Dispatchers.Default) { EpubParser().parse(source) }
                File(parts.path.removeSuffix(".parts")).writeText(kotlinx.serialization.json.Json.encodeToString(Book.serializer(), full))
                val legacy = LibraryRepository(app, db, settings).load(row.id).second
                assertNull(legacy.deferred)
                assertEquals(full.totalChars, legacy.totalChars)
            } finally { scope.cancel(); db.close() }
        }
    }
    @Test fun workersYieldAndNeverParseOnCallerThread() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("chapter-worker").toFile()
        val caller = Thread.currentThread()
        try {
            val chapters = DeferredChapters((0..8).map { Chapter(it, "$it", pending = true) }, root) { i ->
                assertNotSame(caller, Thread.currentThread())
                Chapter(i, "$i", "canonical $i")
            }
            chapters.ensure(8)
            val fill = async { chapters.complete() }
            yield()
            assertFalse(fill.isCompleted)
            fill.await()
            assertTrue(chapters.lengthsReady.value)
        } finally { root.deleteRecursively() }
    }
    @Test fun mismatchedChapterIsNotCachedAndCanBeRetried() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("chapter-retry").toFile()
        try {
            File(root, "lengths.txt").writeText("4")
            var text = "wrong length"
            val chapters = DeferredChapters(listOf(Chapter(0, "chapter", pending = true)), root) {
                Chapter(0, "chapter", text)
            }

            val failed = runCatching { chapters.ensure(0) }
            assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
            assertTrue(chapters[0].pending)
            assertFalse(File(root, "chapter-0.json").exists())

            text = "book"
            assertEquals("book", chapters.ensure(0).text)
            assertTrue(File(root, "chapter-0.json").isFile)
        } finally { root.deleteRecursively() }
    }
    @Test fun seededCacheUsesOnlyRequestedChaptersWithoutReadingArchiveAndRepairsCorruption() = runBlocking {
        sample { _, cache, source ->
            val full = withContext(Dispatchers.Default) { EpubParser().parse(source) }
            withContext(Dispatchers.IO) { EpubChapterCache.seed(cache, full) }
            val unavailable = object : ContentSource {
                override val displayName = "test.epub"
                override fun open(): java.io.InputStream = error("Cache reopen must not read the archive")
            }
            val cached = withContext(Dispatchers.Default) { EpubParser().openIncremental(unavailable, cache, 4) }
            assertEquals(full.totalChars, cached.totalChars)
            val b = withContext(Dispatchers.Default) { EpubParser().openIncremental(source, cache, 4) }
            assertEquals(listOf(3, 4, 5), b.chapters.filterNot { it.pending }.map { it.index })
            assertTrue(b.progressReady)
            assertEquals(full.totalChars, b.totalChars)
            File(cache, "chapter-8.json").writeText("truncated")
            assertEquals(full.chapters[8].text, b.ensureChapter(8).text)
            b.deferred!!.complete()
        }
    }
}
