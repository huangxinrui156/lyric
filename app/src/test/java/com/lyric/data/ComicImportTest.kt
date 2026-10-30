package com.lyric.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.*
import com.lyric.reader.ComicArchive
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicImportTest {
    /** Image folder: root has 第1话/ and 第2话/ sub-folders, each with images named 1, 2, 10. */
    class ImageTree : ContentProvider() {
        lateinit var dir: File
        override fun onCreate() = true
        override fun getType(uri: Uri) = "image/png"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): MatrixCursor {
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (uri.lastPathSegment == "children") return MatrixCursor(columns).apply {
                val id = DocumentsContract.getDocumentId(uri)
                File(dir, if (id == "root") "" else id).listFiles()!!.forEach { f ->
                    val child = if (id == "root") f.name else "$id/${f.name}"
                    addRow(arrayOf(child, f.name, if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "image/png"))
                }
            }
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("我的漫画")) }
        }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(File(dir, DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    }

    private fun env(block: suspend (Application, LibraryRepository) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "c${System.nanoTime()}.preferences_pb") })
            block(context, LibraryRepository(context, db, store))
        } finally { db.close(); scope.cancel() }
    }

    @Test fun cbzFromOpaqueProviderImportsAsComicWithCoverAndDecodablePages() = env { context, repo ->
        val provider = Robolectric.buildContentProvider(RealEpubImportTest.Provider::class.java).create("com.coloros.filemanager").get()
        provider.file = cbz(listOf("vol1/10.png" to tinyPng(60, 80), "vol1/2.png" to tinyPng(60, 80), "vol2/1.png" to tinyPng(90, 120)))
        provider.mime = "application/zip"; provider.metadata = 3 // no name at all
        val uri = Uri.parse("content://com.coloros.filemanager/FileProvider/x")
        repo.importUris(listOf(uri), grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(repo.imports.value.toString(), 1, repo.imports.value.added)
        val row = repo.dao.all().single()
        assertEquals("cbz", row.extension); assertEquals("COMIC", row.contentType)
        assertTrue(File(row.coverPath!!).length() > 0)
        assertTrue("private copy kept for random access", repo.comicFile(row.id).isFile)
        provider.denied = true; repo.clearCaches()
        val book = repo.load(row.id, force = true).second
        assertEquals(listOf(2, 1), book.chapters.map { it.units.size })
        assertEquals("vol1/2.png", (book.chapters[0].units[0] as ContentUnit.ImagePage).resource)
        ComicArchive(repo.comicFile(row.id), "vol1/2.png").use { a ->
            val bmp = a.page("vol2/1.png", 1080)
            assertEquals(90, bmp.width); assertEquals(120, bmp.height)
            val e = runCatching { a.page("missing.png", 100) }.exceptionOrNull()
            assertTrue(e!!.message!!.contains("找不到"))
        }
        repo.save(row.id, book, Locator(1, 0, 0))
        assertEquals(1, repo.dao.progress(row.id)!!.chapterIndex)
        assertEquals(2f / 3, repo.dao.all().single().percent, 1e-4f)
    }

    @Test fun imageFolderBecomesOneComicWithSubfolderChapters() = env { context, repo ->
        val dir = File(context.cacheDir, "tree-${System.nanoTime()}").apply { mkdirs() }
        listOf("第1话", "第2话").forEach { c -> File(dir, c).mkdirs(); listOf("1", "2", "10").forEach { File(dir, "$c/$it.png").writeBytes(tinyPng(20, 30)) } }
        val provider = Robolectric.buildContentProvider(ImageTree::class.java).create("lyric.images").get()
        provider.dir = dir
        val tree = DocumentsContract.buildTreeDocumentUri("lyric.images", "root")
        repo.importUris(listOf(tree), folder = true, grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(repo.imports.value.toString(), 1, repo.imports.value.added)
        val row = repo.dao.all().single()
        assertEquals("COMIC", row.contentType)
        val book = repo.load(row.id).second
        assertEquals(listOf("第1话", "第2话"), book.chapters.map { it.title })
        assertEquals(listOf("第1话/1.png", "第1话/2.png", "第1话/10.png"),
            book.chapters[0].units.map { (it as ContentUnit.ImagePage).resource })
        // Same folder again is a duplicate (deterministic packing), not a second book.
        repo.importUris(listOf(tree), folder = true, grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(1, repo.imports.value.duplicates)
    }

    @Test fun imageEpubImportsAsComicAndOldTextRowIsRedetected() = env { context, repo ->
        val pages = (1..5).map { """<div><svg xmlns="http://www.w3.org/2000/svg"><image xlink:href="../Images/$it.png"/></svg></div>""" }
        val src = ImageEpubTest().epub("", """page-progression-direction="rtl"""", pages)
        val file = File(context.filesDir, "再见绘梨.epub").also { src.copyTo(it, true) }
        repo.importUris(listOf(Uri.fromFile(file)))
        val row = repo.dao.all().first { it.title == "再见绘梨" }
        assertEquals("COMIC", row.contentType)
        assertTrue(repo.comicFile(row.id).isFile)
        val (_, book) = repo.load(row.id)
        assertEquals(ContentType.COMIC, book.contentType)
        assertEquals("rtl", book.readingDirection)
        // A copy imported by an older build as TEXT: re-detected on open, without a re-import.
        repo.dao.update(row.copy(contentType = "TEXT", contentRevision = 3))
        repo.comicFile(row.id).delete()
        val (fixed, again) = repo.load(row.id, force = false)
        assertEquals("COMIC", fixed.contentType)
        assertEquals("COMIC", repo.dao.book(row.id)!!.contentType)
        assertEquals(5, again.chapters.sumOf { it.content.size })
        assertTrue(repo.comicFile(row.id).isFile)
        ComicArchive(repo.comicFile(row.id), (again.chapters[0].content[0] as ContentUnit.ImagePage).resource).close()
    }
}
