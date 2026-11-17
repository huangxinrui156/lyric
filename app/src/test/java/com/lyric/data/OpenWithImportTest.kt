package com.lyric.data

import android.app.Application
import com.lyric.core.BookFormats
import com.lyric.core.tinyPng
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
        assertNull(repo.imports.value.openBookId)
        val first = repo.books.first().single().id
        assertEquals(1, repo.books.first().size)

        val inbound = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain")
        assertTrue(inbound.isBookOpenIntent())
        repo.importUris(inbound.bookUris(), grantFlags = inbound.flags, openWith = true)
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
        repo.importUris(listOf(Uri.fromFile(txt(app, "once.txt"))), openWith = true)
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
    fun docxOpenedFromTheSystemIsImported() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "share").apply {
            java.util.zip.ZipOutputStream(outputStream()).use { zip ->
                fun put(name: String, text: String) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                put("[Content_Types].xml", "<Types/>")
                put("word/document.xml",
                    """<w:document xmlns:w="w"><w:body><w:p><w:r><w:t>第一章 开始</w:t></w:r></w:p><w:p><w:r><w:t>正文内容。</w:t></w:r></w:p></w:body></w:document>""")
                put("docProps/core.xml", """<cp:coreProperties xmlns:cp="c" xmlns:dc="d"><dc:title>样例</dc:title></cp:coreProperties>""")
            }
        }
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertEquals(status.toString(), 1, status.added)
        assertEquals(0, status.failures)
        assertNotEquals(BookFormats.UNSUPPORTED_OPEN_WITH, status.message)
        assertEquals("docx", repo.dao.all().single().extension)
        assertNotNull(status.openBookId)
    }

    @Test
    fun mobiHeaderIsRecognizedAndNotRejectedAsUnsupported() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "share").apply {
            writeBytes(ByteArray(80).also { bytes -> "BOOKMOBI".toByteArray().copyInto(bytes, 60) })
        }
        assertEquals("mobi", BookFormats.sniffOpenWith(file))
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertNotEquals(BookFormats.UNSUPPORTED_OPEN_WITH, status.message)
    }

    @Test
    fun azw3OpenedFromTheSystemIsRecognizedByName() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "book.azw3").apply {
            writeBytes(ByteArray(80).also { bytes -> "BOOKMOBI".toByteArray().copyInto(bytes, 60) })
        }
        assertEquals("azw3", BookFormats.sniffOpenWith(file, displayName = file.name))
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertNotEquals(BookFormats.UNSUPPORTED_OPEN_WITH, status.message)
        // A header-only file may fail to parse, but it must not be rejected as an unsupported type.
    }

    @Test
    fun imageOpenedFromTheSystemIsRejectedEvenAsOctetStream() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "page.png").apply { writeBytes(tinyPng(4, 4)) }
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertEquals(status.toString(), 0, status.added)
        assertEquals(1, status.failures)
        assertEquals(BookFormats.UNSUPPORTED_OPEN_WITH, status.message)
        assertTrue(repo.dao.all().isEmpty())
    }

    @Test
    fun inAppImportStillPacksASingleImageAsAComic() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "page.png").apply { writeBytes(tinyPng(4, 4)) }
        repo.importUris(listOf(Uri.fromFile(file)), openWith = false)
        val status = repo.imports.value
        assertEquals(status.toString(), 1, status.added)
        assertEquals(0, status.failures)
        assertEquals("cbz", repo.dao.all().single().extension)
        assertNull(status.openBookId)
    }

    @Test
    fun zipWithoutImagesOrABookFileIsRejectedAsUnsupported() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.cacheDir, "notes.zip")
        java.util.zip.ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("README.md"))
            zip.write("# notes".toByteArray())
            zip.closeEntry()
        }
        repo.importUris(listOf(Uri.fromFile(file)), openWith = true)
        val status = repo.imports.value
        assertEquals(status.toString(), 0, status.added)
        assertEquals(1, status.failures)
        assertEquals(BookFormats.UNSUPPORTED_OPEN_WITH, status.message)
        assertTrue(repo.dao.all().isEmpty())
    }
}
