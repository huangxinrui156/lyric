package com.lyric.data

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.Locator
import com.lyric.core.clamp
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
class ContinueReadingTest {
    private fun env(block: suspend (LibraryRepository) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val settings = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) {
            File(app.filesDir, "continue-${System.nanoTime()}.preferences_pb")
        })
        try {
            block(LibraryRepository(app, db, settings))
        } finally {
            db.close()
            scope.cancel()
        }
    }

    @Test
    fun resumeLocatorUsesTheSavedPositionWhenOpeningFromTheShelf() {
        val saved = ProgressRow("book", chapterIndex = 2, charOffset = 42, page = 3, contentRevision = 3)
        assertEquals(Locator(2, 42, 3), resumeLocator(null, saved))
        assertEquals(Locator(0, 8, 1), resumeLocator(Locator(0, 8, 1), saved))
        assertEquals(Locator(), resumeLocator(null, null))
    }

    @Test
    fun savedProgressReopensAtTheExactLocator() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "continue.txt").apply {
            writeText("第一章 开头\n\n" + "正文甲。".repeat(80) + "\n\n第二章 继续\n\n" + "正文乙。".repeat(80))
        }
        repo.importUris(listOf(Uri.fromFile(file)))
        val id = repo.books.first().single().id
        val book = repo.load(id).second
        assertTrue(book.chapters.size >= 2)
        val at = book.clamp(Locator(1, 24, 0))
        repo.save(id, book, at)
        val saved = repo.dao.progress(id)
        assertNotNull(saved)
        val resumed = resumeLocator(null, saved)
        assertEquals(at.chapterIndex, resumed.chapterIndex)
        assertEquals(at.charOffset, resumed.charOffset)
        assertEquals(at.page, resumed.page)
        val row = repo.dao.book(id)!!
        assertTrue(row.lastRead > 0)
    }
}
