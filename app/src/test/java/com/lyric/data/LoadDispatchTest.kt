package com.lyric.data

import android.app.Application
import android.content.*
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LoadDispatchTest {
    class Provider : ContentProvider() {
        lateinit var file: File
        lateinit var uiThread: Thread
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                assertNotSame(uiThread, Thread.currentThread()); addRow(arrayOf("large.txt"))
            }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            assertNotSame(uiThread, Thread.currentThread())
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }

    @Test fun blockedImportSourceLeavesUiDispatcherResponsiveAndCacheReopens() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val provider = Robolectric.buildContentProvider(Provider::class.java).create("lyric.load.probe").get()
        provider.uiThread = withContext(ui) { Thread.currentThread() }
        provider.file = File(app.cacheDir, "load.txt").apply { writeText("Reading a paragraph.\n".repeat(10000)) }
        val store = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) { File(app.filesDir, "load-dispatch.preferences_pb") })
        val repo = LibraryRepository(app, db, store)
        try {
            val importing = async(ui) { repo.importUris(listOf(Uri.parse("content://lyric.load.probe/book")), grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            try {
                assertTrue(withContext(Dispatchers.IO) { provider.started.await(3, TimeUnit.SECONDS) })
                // Source is still blocked, while the UI can service scroll/input work.
                assertEquals("scroll", withTimeout(1000) { withContext(ui) { "scroll" } })
            } finally { provider.release.countDown() }
            importing.await()
            assertEquals(1, repo.imports.value.added)
            val row = repo.dao.all().single()
            val book = withContext(ui) { LibraryRepository(app, db, store).load(row.id).second }
            assertTrue(book.chapters.size > 1)
            assertEquals("Reading a paragraph.", book.chapters.first().text.lineSequence().first())
        } finally { provider.release.countDown(); scope.cancel(); db.close(); ui.close() }
    }
}
