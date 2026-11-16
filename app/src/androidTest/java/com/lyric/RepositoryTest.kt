package com.lyric

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lyric.core.*
import com.lyric.data.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real SQLite/filesystem/DataStore boundaries; fixtures never touch the user's documents. */
@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: LibraryDatabase
    private lateinit var prefs: SettingsStore
    private lateinit var repo: LibraryRepository
    private lateinit var dir: File
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "validation-${System.nanoTime()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        prefs =
            SettingsStore(
                context,
                PreferenceDataStoreFactory.create(scope = storeScope) {
                    File(dir, "test.preferences_pb")
                },
            )
        repo = LibraryRepository(context, db, prefs)
    }

    @After
    fun tearDown() = runBlocking {
        repo.dao.all().forEach { repo.remove(it.id) }
        storeScope.cancel()
        db.close()
        dir.deleteRecursively()
        Unit
    }

    @Test
    fun hundredFileBatchDedupeCacheProgressAndMissingSource() = runBlocking {
        val files =
            (1..100).map { index ->
                File(dir, "书籍$index.txt").apply {
                    writeText("第一章 清晨 $index\n" + "风吹过竹林。\n".repeat(40))
                }
            }
        repo.importUris(files.map(Uri::fromFile))
        assertEquals(100, repo.imports.value.added)
        assertEquals(100, repo.imports.value.done)
        assertEquals(0, repo.imports.value.failures)
        assertFalse(repo.imports.value.running)
        repo.importUris(files.map(Uri::fromFile))
        assertEquals(100, repo.imports.value.duplicates)
        val row = repo.dao.all().first()
        val parsed = repo.load(row.id).second
        repo.save(row.id, parsed, Locator(0, 73))
        assertEquals(73, repo.dao.progress(row.id)!!.charOffset)
        assertEquals(parsed, repo.load(row.id).second)
        repo.dao.addBookmark(
            BookmarkRow(
                bookId = row.id,
                chapterIndex = 0,
                charOffset = 73,
                excerpt = parsed.excerpt(Locator(0, 73)),
                chapterTitle = parsed.chapters[0].title,
            )
        )
        assertEquals(1, repo.dao.bookmarks(row.id).first().size)
        File(requireNotNull(Uri.parse(row.uri).path)).delete()
        repo.checkSources()
        assertTrue(repo.dao.book(row.id)!!.invalid)
        var failed = false
        try {
            repo.load(row.id)
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
        // Removing from the shelf must not delete remaining original documents.
        val existing = repo.dao.all().first { !it.invalid }
        val original = File(requireNotNull(Uri.parse(existing.uri).path))
        repo.remove(existing.id)
        assertTrue(original.exists())
        assertNull(repo.dao.progress(existing.id))
    }

    @Test
    fun epubFixturesThroughRealSafProviderBothMimeTypesAndMissingExtensions() = runBlocking {
        for (name in listOf("epub3-basic.epub", "epub2-root.epub", "epub2-nested.epub",
            "epub3-nested.epub", "epub3-literal-percent.epub")) {
            for (mime in listOf("epub", "octet")) for (display in listOf("named", "bare")) {
                val uri = android.provider.DocumentsContract.buildDocumentUri(
                    "com.lyric.reader.test.epubs", "$name|$mime|$display")
                repo.importUris(listOf(uri))
                assertEquals("$name/$mime/$display: ${repo.imports.value}", 0, repo.imports.value.failures)
                assertEquals(1, repo.imports.value.added + repo.imports.value.duplicates)
                val row = repo.dao.all().first { it.uri == uri.toString() }
                assertEquals("epub", row.extension)
                assertTrue(repo.load(row.id, force = true).second.totalChars > 0)
            }
        }
        assertEquals(5, repo.dao.all().size)
    }

    @Test
    fun sparseSettingsInheritanceAndBookOnlyReset() = runBlocking {
        val defaults = ReaderSettings()
        prefs.update(SettingsScope.GLOBAL, "a", defaults, defaults.copy(fontSize = 22f))
        val global = prefs.settings("a").first()
        assertEquals(22f, global.fontSize)
        prefs.update(SettingsScope.TYPE, "a", global, global.copy(lineHeight = 2.2f))
        val type = prefs.settings("a").first()
        prefs.update(SettingsScope.BOOK, "a", type, type.copy(theme = ReaderTheme.BLACK))
        val personal = prefs.settings("a").first()
        assertEquals(22f, personal.fontSize)
        assertEquals(2.2f, personal.lineHeight)
        assertEquals(ReaderTheme.BLACK, personal.theme)
        prefs.update(SettingsScope.GLOBAL, "a", personal, personal.copy(fontSize = 26f))
        assertEquals(26f, prefs.settings("a").first().fontSize)
        assertEquals(ReaderTheme.PAPER, prefs.settings("b").first().theme)
        prefs.reset(SettingsScope.BOOK, "a")
        assertEquals(ReaderTheme.PAPER, prefs.settings("a").first().theme)
        assertEquals(2.2f, prefs.settings("a").first().lineHeight)
    }

    @Test
    fun realLayoutPagesCoverCanonicalTextAndReflowAroundAnchor() {
        val text = "清晨推开窗，雾从竹林缓缓走来。🌿\n\n".repeat(400)
        val anchor = 437
        listOf(14f, 18f, 32f).forEach { size ->
            val layout =
                com.lyric.reader.Typesetter.layout(
                    text,
                    anchor,
                    304,
                    628,
                    1f,
                    1f,
                    ReaderSettings(fontSize = size),
                    android.graphics.Typeface.SERIF,
                )
            assertEquals(0, layout.pages.first().start)
            assertEquals(layout.layout.text.length, layout.pages.last().end)
            layout.pages.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
            assertTrue(layout.pages.any { anchor >= it.start && anchor < it.end })
            assertEquals(text.substring(layout.sourceStart, layout.sourceEnd), layout.plainText)
        }
        val crossing = "字".repeat(11999) + "🌿" + "文".repeat(12001)
        val window =
            com.lyric.reader.Typesetter.layout(
                crossing,
                0,
                304,
                628,
                1f,
                1f,
                ReaderSettings(),
                android.graphics.Typeface.SERIF,
            )
        assertEquals(12001, window.sourceEnd)
        val next =
            com.lyric.reader.Typesetter.layout(
                crossing,
                window.sourceEnd,
                304,
                628,
                1f,
                1f,
                ReaderSettings(),
                android.graphics.Typeface.SERIF,
            )
        assertFalse(next.plainText.first().isLowSurrogate())
        assertTrue(next.sourceEnd > window.sourceEnd)
    }
}
