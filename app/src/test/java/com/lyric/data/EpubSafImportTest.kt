package com.lyric.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.EpubFixtureTest
import com.lyric.core.Locator
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Exercises ContentResolver query/getType/openInputStream, with no filesystem URI shortcut. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EpubSafImportTest {
    class Documents : ContentProvider() {
        lateinit var file: File
        var name: String? = null
        var mime: String? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(name)) }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun completeEpubsImportFromSafUnderBothMimeTypesAndWithoutDisplayNameExtensions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "saf-${System.nanoTime()}.preferences_pb")
        })
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val provider = Robolectric.buildContentProvider(Documents::class.java).create("lyric.test.documents").get()
        val source = File(context.cacheDir, "saf-original")
        try {
            val repo = LibraryRepository(context, db, store)
            for ((index, fixture) in (EpubFixtureTest.fixtures + "epub3-basic.epub").withIndex()) {
                source.writeBytes(javaClass.getResourceAsStream("/epub/$fixture")!!.use { it.readBytes() })
                provider.file = source
                for (mime in listOf("application/epub+zip", "application/octet-stream", null)) {
                    provider.mime = mime
                    for (name in listOf(fixture, "书籍无扩展名", null)) {
                        provider.name = name
                        val uri = Uri.parse("content://lyric.test.documents/document/$index")
                        repo.importUris(listOf(uri))
                        assertEquals("$fixture / $mime / $name: ${repo.imports.value}", 0, repo.imports.value.failures)
                        assertEquals(1, repo.imports.value.added + repo.imports.value.duplicates)
                        val row = repo.dao.all().first { it.uri == uri.toString() && it.name == (name ?: "未命名") }
                        assertEquals("epub", row.extension)
                        val book = repo.load(row.id, force = true).second
                        assertEquals(row.title, book.title)
                        assertTrue(book.totalChars > 0)
                        repo.save(row.id, book, Locator(0, 7))
                        assertEquals(7, repo.dao.progress(row.id)!!.charOffset)
                    }
                }
            }
            assertEquals(5, repo.dao.all().size)
            assertTrue(source.exists())
        } finally { db.close(); scope.cancel(); source.delete() }
    }
}
