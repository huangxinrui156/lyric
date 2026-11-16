package com.lyric.data

import android.app.Application
import com.lyric.core.BookFormats
import android.content.Intent
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
class OpenWithImportTest {
    private fun env(block: suspend (LibraryRepository) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val settings = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) {
            File(app.filesDir, "open-with-${System.nanoTime()}.preferences_pb")
        })
        try {
            block(LibraryRepository(app, db, settings))
        } finally {
            db.close()
            scope.cancel()
        }
    }

    private fun txt(app: Application, name: String) =
        File(app.cacheDir, name).apply { writeText("第一章 $name\n\n正文内容。".repeat(40)) }

    @Test
    fun viewOfAnAlreadyImportedBookDoesNotDuplicateAndStillOpens() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = txt(app, "already.txt")
        val uri = Uri.fromFile(file)
        repo.importUris(listOf(uri))
        val first = repo.imports.value.openBookId
        assertNotNull(first)
        assertEquals(1, repo.books.first().size)
        repo.consumeOpenBook()
        assertNull(repo.imports.value.openBookId)

        val inbound = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain")
        assertTrue(inbound.isBookOpenIntent())
        repo.importUris(inbound.bookUris(), grantFlags = inbound.flags)
        val status = repo.imports.value
        assertEquals(0, status.added)
        assertEquals(1, status.duplicates)
        assertEquals(0, status.failures)
        assertEquals(first, status.openBookId)
        assertEquals(1, repo.books.first().size)
        assertEquals(first, repo.consumeOpenBook())
        assertNull(repo.imports.value.openBookId)
    }

    @Test
    fun consumeOpenBookReturnsTheIdOnce() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        repo.importUris(listOf(Uri.fromFile(txt(app, "once.txt"))))
        val id = repo.consumeOpenBook()
        assertNotNull(id)
        assertNull(repo.consumeOpenBook())
        assertEquals(id, repo.books.first().single().id)
    }

    @Test
    fun unsupportedBinaryIsRejectedWithOneLineMessageAndNotImported() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "share.bin").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3, 0, 0xFF.toByte(), 0, 0x10, 0, 0, 7, 8, 9, 0))
        }
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertEquals(BookFormats.UNSUPPORTED_OPEN_WITH, status.message)
        assertTrue(status.errors.isEmpty())
        assertEquals(0, status.added)
        assertEquals(1, status.failures)
        assertNull(status.openBookId)
        assertTrue(repo.dao.all().isEmpty())
    }

    @Test
    fun namelessDecodableTextStillImportsThroughOpenWith() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "share").apply { writeText("第一章 开始\n\n正文内容。".repeat(20)) }
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertEquals(1, status.added)
        assertEquals(0, status.failures)
        assertNotNull(status.openBookId)
        assertEquals("txt", repo.dao.all().single().extension)
    }

    @Test
    fun docxOpenedFromTheSystemIsNotImported() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "share").apply {
            java.util.zip.ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("word/document.xml"))
                zip.write("<w:document/>".toByteArray())
                zip.closeEntry()
            }
        }
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertEquals(BookFormats.UNSUPPORTED_OPEN_WITH, status.message)
        assertTrue(repo.dao.all().isEmpty())
        assertNull(status.openBookId)
    }
}
