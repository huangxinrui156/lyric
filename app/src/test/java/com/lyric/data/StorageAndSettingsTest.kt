package com.lyric.data

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.ReaderSettings
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class StorageAndSettingsTest {
    @Test fun comicLayoutOverridesPersistOnlyForTheirBook() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val data = PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "layout-${System.nanoTime()}.preferences_pb") }
        try {
            val store = SettingsStore(context, data)
            val before = store.settings("a", com.lyric.core.ContentType.COMIC).first()
            assertNull(before.comicSpread); assertFalse(before.comicOffset); assertFalse(before.comicTrim)
            store.update(SettingsScope.BOOK, "a", before, before.copy(comicSpread = false, comicOffset = true, comicTrim = true), com.lyric.core.ContentType.COMIC)
            val reopened = SettingsStore(context, data).settings("a", com.lyric.core.ContentType.COMIC).first()
            assertEquals(false, reopened.comicSpread); assertTrue(reopened.comicOffset); assertTrue(reopened.comicTrim)
            val other = store.settings("b", com.lyric.core.ContentType.COMIC).first()
            assertNull(other.comicSpread); assertFalse(other.comicOffset); assertFalse(other.comicTrim)
        } finally { scope.cancel() }
    }
    @Test fun clearingCachesRebuildsFromSourceAndPreservesOriginalFontsCoversAndReadingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "test.preferences_pb") })
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val original = File(context.cacheDir, "original.txt").apply { writeText("第一章 清晨\n\n" + "书页间的光。".repeat(40)) }
        val font = File(context.filesDir, "fonts/custom.ttf").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64)) }
        val cover = File(context.filesDir, "covers/cover.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(32)) }
        try {
            val repository = LibraryRepository(context, db, store)
            repository.importUris(listOf(Uri.fromFile(original)))
            val row = db.library().all().single()
            db.library().update(row.copy(coverPath = cover.absolutePath))
            repository.load(row.id) // populate both memory and disk caches
            db.library().saveProgress(ProgressRow(row.id, 0, 10))
            db.library().addBookmark(BookmarkRow(bookId = row.id, chapterIndex = 0, charOffset = 10, excerpt = "光", chapterTitle = "章"))
            File(context.cacheDir, "import-work.tmp").writeBytes(ByteArray(100))
            val inspector = StorageInspector(context, repository)
            assertEquals(64L, inspector.inspect().fonts)
            assertEquals(32L, inspector.inspect().covers)
            val before = inspector.inspect().cache
            assertTrue(before > 100)
            inspector.clearCache()
            assertTrue(inspector.inspect().cache < before)
            assertTrue(File(context.filesDir, "parsed-v1").listFiles().orEmpty().isEmpty())
            assertFalse(File(context.cacheDir, "import-work.tmp").exists())
            assertTrue(original.isFile); assertTrue(font.isFile); assertTrue(cover.isFile)
            assertEquals(10, db.library().progress(row.id)!!.charOffset)
            assertEquals(1, db.library().allBookmarks().size)
            // Changing the source proves that the memory cache was also cleared.
            original.writeText("第一章 清晨\n\n" + "重建后的文字。".repeat(40))
            assertTrue(repository.load(row.id).second.chapters.any { it.text.contains("重建后的文字") })
            assertTrue(inspector.inspect().cache > 0)
        } finally { db.close(); scope.cancel() }
    }

    @Test fun globalEditorReadsOnlyGlobalLayerAndResetRemovesOnlyBookOverrides() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "inherit-${System.nanoTime()}.preferences_pb") })
        try {
            val defaults = ReaderSettings()
            store.update(SettingsScope.GLOBAL, "", defaults, defaults.copy(fontSize = 22f))
            store.update(SettingsScope.TYPE, "", defaults, defaults.copy(lineHeight = 2.1f))
            store.update(SettingsScope.BOOK, "a", defaults, defaults.copy(fontSize = 30f))
            store.update(SettingsScope.BOOK, "b", defaults, defaults.copy(margin = 40f))
            assertEquals(22f, store.global.first().fontSize)
            assertEquals(1.9f, store.global.first().lineHeight)
            assertEquals(30f, store.settings("a").first().fontSize)
            assertEquals(2, store.ownBookCount.first())
            store.resetBookInheritance()
            assertEquals(0, store.ownBookCount.first())
            assertEquals(22f, store.settings("a").first().fontSize)
            assertEquals(2.1f, store.settings("a").first().lineHeight)
            assertEquals(20f, store.settings("b").first().margin)
        } finally { scope.cancel() }
    }
    @Test fun v031DefaultsApplyOnlyToUnsetFieldsAndPreserveExplicitOldPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "defaults-${System.nanoTime()}.preferences_pb")
        })
        try {
            val fresh = store.settings("new").first()
            assertEquals(17f, fresh.fontSize)
            assertEquals(20f, fresh.margin)
            assertEquals(.3f, fresh.paragraphSpace)
            assertTrue(fresh.volumeKeys)
            store.restore(SettingsSnapshot(strings = mapOf("global" to
                """{"fontSize":18.0,"margin":28.0,"paragraphSpace":0.6,"volumeKeys":false}""")))
            val old = store.settings("old").first()
            assertEquals(18f, old.fontSize)
            assertEquals(28f, old.margin)
            assertEquals(.6f, old.paragraphSpace)
            assertFalse(old.volumeKeys)
        } finally { scope.cancel() }
    }

    @Test fun shelfSectionSurvivesStoreRecreationAndBackupRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "section-${System.nanoTime()}.preferences_pb")
        val firstJob = SupervisorJob()
        val first = SettingsStore(context, PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO)) { file })
        assertEquals(ShelfSection.BOOK, first.shelfSection.first())
        first.shelfSection(ShelfSection.COMIC)
        first.shelfSort(2, ShelfSection.BOOK)
        first.shelfSort(1, ShelfSection.COMIC)
        val snapshot = first.snapshot()
        first.validate(snapshot)
        firstJob.cancelAndJoin()
        val secondJob = SupervisorJob()
        try {
            val reopened = SettingsStore(context, PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO)) { file })
            assertEquals(ShelfSection.COMIC, reopened.shelfSection.first())
            assertEquals(2, reopened.shelfSort.first())
            assertEquals(1, reopened.comicShelfSort.first())
            reopened.shelfSection(ShelfSection.BOOK)
            reopened.restore(snapshot)
            assertEquals(ShelfSection.COMIC, reopened.shelfSection.first())
            reopened.restore(SettingsSnapshot()) // old backups without a section remain valid
            assertEquals(ShelfSection.BOOK, reopened.shelfSection.first())
        } finally { secondJob.cancelAndJoin(); file.delete() }
    }

}
