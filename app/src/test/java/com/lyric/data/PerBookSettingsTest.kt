package com.lyric.data

import android.app.Application
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import com.lyric.core.tinyPng
import androidx.room.Room
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.ContentType
import com.lyric.core.PageMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PerBookSettingsTest {
    @Test
    fun directionPageModeSpreadAndTrimSurviveProcessKillAndNewBooksUseGlobalDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "per-book-${System.nanoTime()}.preferences_pb")
        val firstJob = SupervisorJob()
        try {
            val first = SettingsStore(
                context,
                PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO)) { file },
            )
            val before = first.settings("read", ContentType.COMIC).first()
            assertEquals(PageMode.SLIDE, before.pageMode)
            assertEquals("rtl", before.comicDirection)
            assertNull(before.comicSpread)
            assertFalse(before.comicTrim)

            first.update(
                SettingsScope.BOOK,
                "read",
                before,
                before.copy(
                    pageMode = PageMode.SCROLL,
                    comicDirection = "ltr",
                    comicSpread = true,
                    comicTrim = true,
                ),
                ContentType.COMIC,
            )
            val global = first.global.first()
            first.update(
                SettingsScope.GLOBAL,
                "",
                global,
                global.copy(
                    pageMode = PageMode.CURL,
                    comicDirection = "vertical",
                    comicSpread = false,
                    comicTrim = false,
                ),
            )

            val saved = first.settings("read", ContentType.COMIC).first()
            assertEquals(PageMode.SCROLL, saved.pageMode)
            assertEquals("ltr", saved.comicDirection)
            assertEquals(true, saved.comicSpread)
            assertTrue(saved.comicTrim)

            val fresh = first.settings("new", ContentType.COMIC).first()
            assertEquals(PageMode.CURL, fresh.pageMode)
            assertEquals("vertical", fresh.comicDirection)
            assertEquals(false, fresh.comicSpread)
            assertFalse(fresh.comicTrim)
        } finally {
            firstJob.cancelAndJoin()
        }

        val secondJob = SupervisorJob()
        try {
            val reopened = SettingsStore(
                context,
                PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO)) { file },
            )
            val restored = reopened.settings("read", ContentType.COMIC).first()
            assertEquals(PageMode.SCROLL, restored.pageMode)
            assertEquals("ltr", restored.comicDirection)
            assertEquals(true, restored.comicSpread)
            assertTrue(restored.comicTrim)

            val anotherNew = reopened.settings("another", ContentType.TEXT).first()
            assertEquals(PageMode.CURL, anotherNew.pageMode)
            assertEquals("vertical", anotherNew.comicDirection)
            assertEquals(false, anotherNew.comicSpread)
            assertFalse(anotherNew.comicTrim)
        } finally {
            secondJob.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun changingAComicDoesNotLeakIntoTypeDefaultsOrTheNextImportedComic() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(
            context,
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.filesDir, "type-defaults-${System.nanoTime()}.preferences_pb")
            },
        )
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        fun cbz(name: String, pixel: Int): File {
            val file = File(context.cacheDir, name)
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("1.png"))
                zip.write(tinyPng(pixel, pixel))
                zip.closeEntry()
            }
            return file
        }
        try {
            val repo = LibraryRepository(context, db, store)
            repo.importUris(listOf(Uri.fromFile(cbz("first.cbz", 4))))
            val first = repo.dao.all().single()
            val before = store.settings(first.id, ContentType.COMIC).first()
            assertEquals("rtl", before.comicDirection)
            assertFalse(before.comicTrim)
            val snapBefore = store.snapshot()

            store.update(
                SettingsScope.BOOK,
                first.id,
                before,
                before.copy(comicDirection = "ltr", comicTrim = true, comicSpread = true),
                ContentType.COMIC,
            )
            val snapAfter = store.snapshot()
            assertEquals(snapBefore.strings["global"], snapAfter.strings["global"])
            assertEquals(snapBefore.strings["type_TEXT"], snapAfter.strings["type_TEXT"])
            assertEquals(snapBefore.strings["type_COMIC"], snapAfter.strings["type_COMIC"])
            assertTrue(snapAfter.strings.containsKey("book_${first.id}"))

            repo.importUris(listOf(Uri.fromFile(cbz("second.cbz", 8))))
            val second = repo.dao.all().single { it.id != first.id }
            val next = store.settings(second.id, ContentType.COMIC).first()
            assertEquals("rtl", next.comicDirection)
            assertFalse(next.comicTrim)
            assertNull(next.comicSpread)
            assertEquals("ltr", store.settings(first.id, ContentType.COMIC).first().comicDirection)

            val comicType = store.settings("unused-for-type", ContentType.COMIC).first()
            store.update(
                SettingsScope.TYPE,
                "",
                comicType,
                comicType.copy(comicDirection = "vertical"),
                ContentType.COMIC,
            )
            repo.importUris(listOf(Uri.fromFile(cbz("third.cbz", 12))))
            val third = repo.dao.all().single { it.id != first.id && it.id != second.id }
            assertEquals("vertical", store.settings(third.id, ContentType.COMIC).first().comicDirection)
            assertEquals("ltr", store.settings(first.id, ContentType.COMIC).first().comicDirection)
        } finally {
            db.close()
            scope.cancel()
        }
    }
}
