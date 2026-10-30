package com.lyric.data

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.ReaderSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackupManagerTest {
    @Test fun roundTripMatchesHashesKeepsNewerProgressAndRepairsMissingBookOnReimport() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun settings() = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) {
            File(context.cacheDir, "settings-${System.nanoTime()}.preferences_pb")
        })
        val source = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val target = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val aSettings = settings(); val bSettings = settings()
        val file = File(context.filesDir, "original.txt").apply { writeText("原书\n第一章 风\n\n" + "在字句间散步。".repeat(50)) }
        val missingFile = File(context.filesDir, "missing.txt").apply { writeText("待重导\n第一章 雨\n\n" + "雨落在书页上。".repeat(50)) }
        fun book(file: File): ShelfBook {
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            return ShelfBook(hash, Uri.fromFile(file).toString(), file.name, file.name, "作者", "txt", totalChars = 500, chapterCount = 1, encoding = "UTF-8")
        }
        val a = book(file); val b = book(missingFile)
        try {
            val dao = source.library()
            dao.insert(a); dao.insert(b)
            dao.saveProgress(ProgressRow(a.id, 0, 10, updatedAt = 100))
            dao.saveProgress(ProgressRow(b.id, 0, 25, updatedAt = 200))
            dao.addBookmark(BookmarkRow(bookId = b.id, chapterIndex = 0, charOffset = 25, excerpt = "雨", chapterTitle = "章", bookTitle = b.title))
            dao.addSession(ReadingSession("session", b.id, 10000, 30000, 20, 12))
            dao.finish(FinishedBook(b.id, b.title, 30000))
            aSettings.update(SettingsScope.GLOBAL, "", ReaderSettings(), ReaderSettings(fontSize = 24f, appTheme = "dark", accentColor = 2))
            aSettings.update(SettingsScope.BOOK, b.id, ReaderSettings(), ReaderSettings(lineHeight = 2.2f))
            aSettings.shelf(grid = false)
            val out = ByteArrayOutputStream()
            BackupManager(context, source, aSettings).export(out, false)
            val zip = out.toByteArray()
            target.library().insert(a.copy(uri = Uri.fromFile(file).toString(), title = "本机标题"))
            target.library().saveProgress(ProgressRow(a.id, 0, 99, updatedAt = 999))
            val manager = BackupManager(context, target, bSettings)
            val preview = manager.preview(ByteArrayInputStream(zip))
            assertEquals(1, preview.matched); assertEquals(1, preview.missing)
            assertTrue(preview.data.books.all { it.uri.isBlank() })
            assertEquals(0, target.library().allBookmarks().size) // preview is read only
            manager.restore(preview); manager.restore(preview)
            assertEquals(2, target.library().all().size)
            assertEquals(99, target.library().progress(a.id)!!.charOffset)
            assertEquals(Uri.fromFile(file).toString(), target.library().book(a.id)!!.uri)
            assertTrue(target.library().book(b.id)!!.invalid)
            assertTrue(target.library().observeAllBookmarks().first().single().unavailable)
            assertEquals(1, target.library().allBookmarks().size)
            assertEquals(1, target.library().sessions().size)
            assertEquals(1, target.library().finished().size)
            assertEquals(24f, bSettings.global.first().fontSize)
            assertEquals("dark", bSettings.global.first().appTheme)
            assertEquals(2.2f, bSettings.settings(b.id).first().lineHeight)
            assertFalse(bSettings.grid.first())
            val repo = LibraryRepository(context, target, bSettings)
            repo.importUris(listOf(Uri.fromFile(missingFile)))
            assertFalse(target.library().book(b.id)!!.invalid)
            assertEquals(25, target.library().progress(b.id)!!.charOffset)
            assertFalse(target.library().observeAllBookmarks().first().single().unavailable)
        } finally { source.close(); target.close(); scope.cancel(); file.delete(); missingFile.delete() }
    }

    @Test fun optionalFontsRoundTripRebasesPathsAndOmittedFontsFallBackToSerif() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun store() = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "font-settings-${System.nanoTime()}.preferences_pb")
        })
        val a = store(); val b = store()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val font = File(context.filesDir, "fonts/portable.otf").apply {
            parentFile!!.mkdirs()
            File("src/main/res/font/noto_serif_sc.otf").copyTo(this, overwrite = true)
        }
        try {
            a.update(SettingsScope.GLOBAL, "", ReaderSettings(), ReaderSettings(font = font.absolutePath))
            val source = BackupManager(context, db, a)
            val target = BackupManager(context, db, b)
            val out = ByteArrayOutputStream()
            source.export(out, false)
            val withoutFonts = target.preview(ByteArrayInputStream(out.toByteArray()))
            assertTrue(withoutFonts.fonts.isEmpty())
            target.restore(withoutFonts)
            assertEquals("serif", b.global.first().font)
            out.reset()
            source.export(out, true)
            val withFonts = target.preview(ByteArrayInputStream(out.toByteArray()))
            assertEquals(1, withFonts.fonts.size)
            target.restore(withFonts)
            val restored = File(b.global.first().font)
            assertTrue(restored.isFile)
            assertNotEquals(font.absolutePath, restored.absolutePath)
            assertArrayEquals(font.readBytes(), restored.readBytes())
        } finally { db.close(); scope.cancel() }
    }

    @Test fun rejectsTraversalAndMalformedZipBeforeAnyMutation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) { File(context.cacheDir, "bad-${System.nanoTime()}.preferences_pb") })
        try {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { it.putNextEntry(ZipEntry("fonts/../../escape.ttf")); it.write(byteArrayOf(1)); it.closeEntry() }
            val result = runCatching { BackupManager(context, db, store).preview(ByteArrayInputStream(out.toByteArray())) }
            assertTrue(result.isFailure)
            assertTrue(db.library().all().isEmpty())
        } finally { db.close(); scope.cancel() }
    }
    @Test fun restoringRollingCheckpointKeepsLongestSnapshotAndDeduplicatesRebasedExcerpt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "rolling-${System.nanoTime()}.preferences_pb")
        })
        try {
            val dao = db.library()
            val id = "a".repeat(64)
            val row = ShelfBook(id, "", "a.epub", "a", "author", "epub", totalChars = 100,
                chapterCount = 1, encoding = "UTF-8", contentRevision = 3)
            dao.insert(row)
            val mark = BookmarkRow(bookId = id, chapterIndex = 0, charOffset = 10, excerpt = "同一句", chapterTitle = "章", createdAt = 100, contentRevision = 3)
            dao.addBookmark(mark)
            val manager = BackupManager(context, db, settings)
            fun backup(seconds: Long) = BackupPreview(BackupData(books = listOf(row), progress = emptyList(),
                bookmarks = listOf(mark.copy(charOffset = 20, contentRevision = 0)),
                sessions = listOf(ReadingSession("rolling", id, 0, seconds * 1000, seconds, 30)),
                finished = emptyList()), emptyMap(), 0)
            manager.restore(backup(15)); manager.restore(backup(30)); manager.restore(backup(15))
            assertEquals(1, dao.sessions().size)
            assertEquals(30L, dao.sessions().single().durationSec)
            assertEquals(1, dao.allBookmarks().size)
            assertEquals(10, dao.allBookmarks().single().charOffset)
        } finally { db.close(); scope.cancel() }
    }

}
