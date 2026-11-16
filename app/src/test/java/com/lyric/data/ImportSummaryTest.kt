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
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImportSummaryTest {
    class BookTree : ContentProvider() {
        lateinit var dir: File
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): MatrixCursor {
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            if (uri.lastPathSegment == "children") return MatrixCursor(columns).apply {
                val id = DocumentsContract.getDocumentId(uri)
                File(dir, if (id == "root") "" else id).listFiles()!!.forEach { f ->
                    val child = if (id == "root") f.name else "$id/${f.name}"
                    addRow(arrayOf(child, f.name, if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "text/plain"))
                }
            }
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("书籍")) }
        }
        override fun openFile(uri: Uri, mode: String) =
            ParcelFileDescriptor.open(File(dir, DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    }

    private fun env(block: suspend (LibraryRepository) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val settings = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) {
            File(app.filesDir, "import-summary-${System.nanoTime()}.preferences_pb")
        })
        try {
            block(LibraryRepository(app, db, settings))
        } finally {
            db.close()
            scope.cancel()
        }
    }

    private fun txt(file: File, name: String) =
        File(file, name).apply { writeText("第一章 $name\n\n正文内容。".repeat(40)) }

    @Test
    fun multiFileImportStaysOnShelfAndUsesLegacySummary() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val a = File(app.cacheDir, "sum-a.txt").apply { writeText("第一章 A\n\n正文内容。".repeat(40)) }
        val b = File(app.cacheDir, "sum-b.txt").apply { writeText("第一章 B\n\n正文内容。".repeat(40)) }
        repo.importUris(listOf(Uri.fromFile(a), Uri.fromFile(b)))
        val status = repo.imports.value
        assertEquals(2, status.added)
        assertFalse(status.folder)
        assertNull(status.openBookId)
        assertEquals("导入 2 · 跳过 0 · 失败 0", status.summary)
        assertTrue(status.message.startsWith(status.summary))
    }

    @Test
    fun folderImportStaysOnShelfAndUsesLegacySummary() = env { repo ->
        val app = ApplicationProvider.getApplicationContext<Application>()
        val dir = File(app.cacheDir, "tree-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "sum-folder-a.txt").writeText("第一章 A\n\n正文内容。".repeat(40))
        File(dir, "sum-folder-b.txt").writeText("第一章 B\n\n正文内容。".repeat(40))
        val provider = Robolectric.buildContentProvider(BookTree::class.java).create("lyric.summary").get()
        provider.dir = dir
        val tree = DocumentsContract.buildTreeDocumentUri("lyric.summary", "root")
        repo.importUris(listOf(tree), folder = true, grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val status = repo.imports.value
        assertEquals(status.toString(), 2, status.added)
        assertTrue(status.folder)
        assertNull(status.openBookId)
        assertEquals("导入 2 · 跳过 0 · 失败 0", status.summary)
        assertTrue(status.message.startsWith(status.summary))
    }
}
