package com.lyric.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Full pipeline on a user-supplied huge EPUB (not committed). Set LYRIC_PERF_EPUB to run. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LargeRealEpubImportTest {
    @Test fun hugeEpubImportsWithInBookProgressAndReopens() = runBlocking {
        val path = System.getenv("LYRIC_PERF_EPUB"); assumeTrue(path != null && File(path).isFile)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val provider = Robolectric.buildContentProvider(RealEpubImportTest.Provider::class.java).create("com.coloros.filemanager").get()
            provider.file = File(path!!); provider.mime = "application/epub+zip"; provider.metadata = 3
            val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "big.preferences_pb") })
            val repo = LibraryRepository(context, db, store)
            val details = java.util.Collections.synchronizedList(mutableListOf<String>())
            val watcher = scope.launch { repo.imports.collect { if (it.detail.isNotBlank()) details += it.detail } }
            val t0 = System.nanoTime()
            repo.importUris(listOf(Uri.parse("content://com.coloros.filemanager/FileProvider/jianlai")), grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val importMs = (System.nanoTime() - t0) / 1_000_000
            watcher.cancel()
            assertEquals(repo.imports.value.toString(), 1, repo.imports.value.added)
            val row = repo.dao.all().single()
            val t1 = System.nanoTime()
            repo.clearCaches()
            val book = LibraryRepository(context, db, store).load(row.id).second
            val reopenMs = (System.nanoTime() - t1) / 1_000_000
            println("PERF import=${importMs}ms reopen(reparse)=${reopenMs}ms chapters=${book.chapters.size} chars=${book.totalChars} progressUpdates=${details.size} last=${details.lastOrNull()}")
            assertTrue(details.any { it.startsWith("解析章节") })
            assertTrue(book.chapters.size > 1000)
        } finally { db.close(); scope.cancel() }
    }
}
