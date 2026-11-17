package com.lyric.data

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FirstOpenImportTest {
    private fun env(block: suspend (LibraryRepository) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val settings = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) {
            File(app.filesDir, "first-open-${System.nanoTime()}.preferences_pb")
        })
        try {
            val repo = LibraryRepository(app, db, settings)
            assertTrue(repo.books.first().isEmpty())
            assertFalse(settings.isSeeded())
            block(repo)
        } finally {
            db.close()
            scope.cancel()
        }
    }

    private fun txt(app: Application, name: String) =
        File(app.cacheDir, name).apply { writeText("第一章 $name\n\n正文内容。".repeat(40)) }

    @Test
    fun singleFileImportSetsOpenBookIdAndConsumeClearsIt() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = txt(app, "first-open.txt")
        repo.importUris(listOf(Uri.fromFile(file)))
        val status = repo.imports.value
        assertEquals(1, status.added)
        assertEquals(0, status.failures)
        assertFalse(status.running)
        val id = status.openBookId
        assertNotNull(id)
        assertEquals(id, repo.books.first().single().id)
        repo.consumeOpenBook()
        assertNull(repo.imports.value.openBookId)
        assertEquals(1, repo.books.first().size)
    }

    @Test
    fun twoFileImportDoesNotAutoOpen() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        repo.importUris(listOf(Uri.fromFile(txt(app, "a.txt")), Uri.fromFile(txt(app, "b.txt"))))
        assertEquals(2, repo.imports.value.added)
        assertNull(repo.imports.value.openBookId)
    }
}
