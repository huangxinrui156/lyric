package com.lyric.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.*
import com.lyric.reader.ReaderCanvas
import com.lyric.ui.ImportDocuments
import com.lyric.ui.ImportFilter
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowContentResolver

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealEpubImportTest {
    class Provider : ContentProvider() {
        lateinit var file: File
        var metadata = 0
        var denied = false
        var mime: String? = "application/x-zip-compressed"
        override fun onCreate() = true
        override fun getType(uri: Uri) = mime
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = when (metadata) {
            0 -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(null, null)) }
            1 -> MatrixCursor(arrayOf("unexpected_column")).apply { addRow(arrayOf("unused")) }
            2 -> throw UnsupportedOperationException("OEM metadata query unavailable")
            else -> null
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("provider denied read")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }

    class TreeProvider : ContentProvider() {
        lateinit var first: File
        lateinit var second: File
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): MatrixCursor {
            if (uri.lastPathSegment == "children") {
                val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                return MatrixCursor(columns).apply {
                    if (DocumentsContract.getDocumentId(uri) == "root") {
                        addRow(arrayOf("book1", "first.epub", "application/zip"))
                        addRow(arrayOf("nested", "子文件夹", DocumentsContract.Document.MIME_TYPE_DIR))
                        addRow(arrayOf("image", "ignored.png", "image/png"))
                    } else {
                        addRow(arrayOf("book2", null, "application/octet-stream"))
                        addRow(arrayOf("root", "cycle", DocumentsContract.Document.MIME_TYPE_DIR))
                    }
                }
            }
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(null, null)) }
        }
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(
            if (DocumentsContract.getDocumentId(uri) == "book1") first else second, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    }

    @Implements(android.content.ContentResolver::class)
    class TransientOnlyResolver : ShadowContentResolver() {
        @Implementation
        override fun takePersistableUriPermission(uri: Uri, flags: Int) {
            throw SecurityException("persistable flag was not granted")
        }
    }

    private fun settings(context: Application, scope: CoroutineScope) = SettingsStore(context,
        PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "real-${System.nanoTime()}.preferences_pb") })

    @Test fun realAndAdversarialEpubsGoFromOpaqueProviderUriThroughRoomIntoReaderAndSurviveRevocation() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        val store = settings(context, scope)
        val fixtures = listOf(
            "real/gutenberg-11-epub2.epub" to "com.android.externalstorage.documents/document/primary%3ABooks%2Falice.epub",
            "real/gutenberg-84-images.epub" to "com.coloros.filemanager/FileProvider/books/frankenstein",
            "real/gutenberg-23950-zh.epub" to "com.oplus.filemanager/FileProvider/books/sanguo",
            "real/standard-austen.epub" to "com.android.providers.downloads.documents/document/msf%3A42",
            "stored-descriptor.epub" to "com.android.providers.media.documents/document/file%3A84",
            "prefixed-gbk.epub" to "com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2Fbook.epub",
            "prefixed-cp437.epub" to "lyric.real.documents/document/opaque-id",
            "zip64.epub" to "lyric.zip64.documents/document/opaque-id",
        )
        val providers = mutableMapOf<String, Provider>()
        try {
            val repo = LibraryRepository(context, db, store)
            for ((index, pair) in fixtures.withIndex()) {
                val (fixture, address) = pair
                val uri = Uri.parse("content://$address")
                val provider = providers.getOrPut(uri.authority!!) {
                    Robolectric.buildContentProvider(Provider::class.java).create(uri.authority!!).get()
                }
                provider.file = File(context.cacheDir, "real-source-$index").apply {
                    writeBytes(RealEpubImportTest::class.java.getResourceAsStream("/epub/$fixture")!!.use { it.readBytes() })
                }
                provider.metadata = index % 4
                provider.mime = if (index % 2 == 0) null else "application/x-zip-compressed"
                provider.denied = false
                repo.importUris(listOf(uri), grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
                assertEquals("$fixture ${repo.imports.value}", 0, repo.imports.value.failures)
                val row = repo.dao.all().first { it.uri == uri.toString() }
                assertEquals("epub", row.extension)
                val book = repo.load(row.id, force = true).second
                assertTrue(book.chapters.isNotEmpty())
                assertTrue(book.totalChars > 0)
                if (fixture.contains("23950")) assertEquals("三國志演義", book.title)
                if (fixture.contains("84-images") || fixture.contains("standard")) {
                    assertTrue(book.resources.isNotEmpty())
                    assertNotNull(row.coverPath)
                    assertTrue(File(row.coverPath!!).length() > 0)
                }
                // Open a text-bearing chapter in the actual reader view, including image/font handling.
                val chapter = book.chapters.first { it.text.length > 100 || it == book.chapters.last() }
                val view = ReaderCanvas(context)
                var visible: Locator? = null
                view.onVisibleLocator = { visible = it }
                view.layout(0, 0, 360, 640)
                view.bind(book, Locator(chapter.index), ReaderSettings(), 0xff222222.toInt(), 0xffeeeeee.toInt(), "")
                val deadline = System.nanoTime() + 15_000_000_000L
                while (visible == null && System.nanoTime() < deadline) {
                    main.scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
                }
                assertNotNull("$fixture reader typesetting", visible)
                val output = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(output))
                assertTrue("$fixture reader must draw content", (0 until 360 step 3).any { x ->
                    (0 until 640 step 3).any { y -> output.getPixel(x, y) != 0xffeeeeee.toInt() }
                })
                repo.save(row.id, book, Locator(chapter.index, 7))
                // Drop parsed caches and repository memory, then revoke transient URI access.
                repo.clearCaches()
                provider.denied = true
                val reopened = LibraryRepository(context, db, store).load(row.id, force = true).second
                assertEquals(book.title, reopened.title)
                assertEquals(book.chapters[chapter.index].text, reopened.chapters[chapter.index].text)
                assertEquals(7, repo.dao.progress(row.id)!!.charOffset)
                provider.file.delete()
            }
            assertEquals(8, db.library().all().size)
        } finally { db.close(); scope.cancel(); Dispatchers.resetMain() }
    }

    @Test
    @Config(shadows = [TransientOnlyResolver::class])
    fun persistableSecurityExceptionDoesNotAbortImportOrBreakReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val provider = Robolectric.buildContentProvider(Provider::class.java).create("lyric.transient.documents").get()
            provider.file = File(context.cacheDir, "transient-original").apply {
                writeBytes(RealEpubImportTest::class.java.getResourceAsStream("/epub/epub3-basic.epub")!!.use { it.readBytes() })
            }
            val store = settings(context, scope)
            val repo = LibraryRepository(context, db, store)
            repo.importUris(listOf(Uri.parse("content://lyric.transient.documents/document/1")),
                grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            assertEquals(repo.imports.value.toString(), 1, repo.imports.value.added)
            val row = repo.dao.all().single()
            provider.denied = true
            repo.clearCaches()
            assertTrue(LibraryRepository(context, db, store).load(row.id, force = true).second.totalChars > 0)
        } finally { db.close(); scope.cancel() }
    }

    @Test fun treeScanRecursesWithoutPersistentGrantHandlesNullNamesAndSkipsImagesAndCycles() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val provider = Robolectric.buildContentProvider(TreeProvider::class.java).create("lyric.tree.documents").get()
            provider.first = File(context.cacheDir, "tree-first").apply {
                writeBytes(RealEpubImportTest::class.java.getResourceAsStream("/epub/epub3-basic.epub")!!.readBytes())
            }
            provider.second = File(context.cacheDir, "tree-second").apply {
                writeBytes(RealEpubImportTest::class.java.getResourceAsStream("/epub/stored-descriptor.epub")!!.readBytes())
            }
            val repo = LibraryRepository(context, db, settings(context, scope))
            repo.importUris(listOf(Uri.parse("content://lyric.tree.documents/tree/root")), folder = true,
                grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertEquals(repo.imports.value.toString(), 2, repo.imports.value.added)
            assertEquals(0, repo.imports.value.failures)
            assertEquals(2, repo.imports.value.total)
            for (row in repo.dao.all()) assertTrue(repo.load(row.id, force = true).second.totalChars > 0)
        } finally { db.close(); scope.cancel() }
    }

    @Test fun pickerUsesUnfilteredOpenDocumentAndPreservesReturnedGrantFlags() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val picker = ImportDocuments()
        val intent = picker.createIntent(context, ImportFilter.ALL)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        val uri = Uri.parse("content://com.oplus.filemanager/FileProvider/42")
        val result = picker.parseResult(android.app.Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        assertEquals(listOf(uri), result.uris)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, result.flags)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, ImportDocuments(true).createIntent(context, ImportFilter.ALL).action)
        assertTrue(picker.parseResult(android.app.Activity.RESULT_CANCELED, null).uris.isEmpty())
    }

    @Test fun failedReadAndRoomFailureExposeStageClassAndMessageAndBatchContinues() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val provider = Robolectric.buildContentProvider(Provider::class.java).create("lyric.failure.documents").get()
            provider.file = File(context.cacheDir, "broken-epub").apply { writeBytes(byteArrayOf(0x50, 0x4b, 3, 4)) }
            val uri = Uri.parse("content://lyric.failure.documents/document/42")
            val repo = LibraryRepository(context, db, settings(context, scope))
            provider.denied = true
            repo.importUris(listOf(uri), grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
            assertTrue(repo.imports.value.errors.single().contains("[ContentResolver/读取并复制] SecurityException: provider denied read"))
            provider.denied = false
            val good = File(context.cacheDir, "good-book.txt").apply { writeText("测试书名\n第1章\n正文内容".repeat(10)) }
            repo.importUris(listOf(uri, Uri.fromFile(good)))
            assertEquals(1, repo.imports.value.added)
            assertEquals(1, repo.imports.value.failures)
            assertTrue(repo.imports.value.errors.single().contains("EPUB/ZIP"))
            // An insert trigger reproduces a real SQLite constraint failure, off the main thread.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_insert BEFORE INSERT ON books BEGIN SELECT RAISE(ABORT, 'fixture constraint'); END")
            good.appendText("new hash")
            repo.importUris(listOf(Uri.fromFile(good)))
            val error = repo.imports.value.errors.single()
            assertTrue(error, error.contains("[Room/插入书架]"))
            assertTrue(error, error.contains("SQLiteConstraintException") && error.contains("fixture constraint"))
            assertFalse(repo.imports.value.running)
        } finally { db.close(); scope.cancel() }
    }
}
