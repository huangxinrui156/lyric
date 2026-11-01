package com.lyric.data

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
class UriGrantCleanupTest {
    class CancelledProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/epub+zip"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?) =
            android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("cancel.epub")) }
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor = throw CancellationException("provider cancelled")
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun update(uri: Uri, v: android.content.ContentValues?, s: String?, a: Array<out String>?) = 0
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    }

    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val flags = read or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private fun env(block: suspend (Application, LibraryRepository) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val store = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) {
                File(context.filesDir, "grant-${System.nanoTime()}.preferences_pb")
            })
            block(context, LibraryRepository(context, db, store))
        } finally { db.close(); scope.cancel() }
    }
    private fun row(id: String, uri: Uri) = ShelfBook(id, uri.toString(), "$id.txt", id, "author", "txt",
        totalChars = 20, chapterCount = 1, encoding = "UTF-8")

    @Test fun sharedTreeAndDocumentGrantsSurviveUntilLastBookIsRemoved() = env { app, repo ->
        val tree = DocumentsContract.buildTreeDocumentUri("lyric.grants", "opaque-root")
        val a = DocumentsContract.buildDocumentUriUsingTree(tree, "opaque-a")
        val b = DocumentsContract.buildDocumentUriUsingTree(tree, "opaque-b")
        assertTrue(repo.persist(tree, flags))
        repo.dao.insert(row("a", a)); repo.dao.insert(row("b", b))
        repo.remove("a")
        assertEquals(listOf(tree), app.contentResolver.persistedUriPermissions.map { it.uri })
        repo.remove("b")
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        val document = Uri.parse("content://lyric.grants/document/shared")
        assertTrue(repo.persist(document, flags))
        repo.dao.insert(row("c", document)); repo.dao.insert(row("d", document))
        repo.remove("c")
        assertEquals(listOf(document), app.contentResolver.persistedUriPermissions.map { it.uri })
        repo.remove("d")
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test fun failedImportsReleaseUnusedGrantButPreserveReferencedTree() = env { app, repo ->
        val provider = Robolectric.buildContentProvider(EpubSafImportTest.Documents::class.java).create("lyric.grants").get()
        val file = File(app.cacheDir, "broken.epub").apply { writeText("not an EPUB") }
        provider.file = file; provider.name = "broken.epub"
        val tree = DocumentsContract.buildTreeDocumentUri("lyric.grants", "root")
        repo.persist(tree, flags)
        repo.dao.insert(row("kept", DocumentsContract.buildDocumentUriUsingTree(tree, "existing")))
        val failed = DocumentsContract.buildDocumentUriUsingTree(tree, "broken")
        repo.importUris(listOf(failed), grantFlags = flags)
        assertEquals(1, repo.imports.value.failures)
        assertEquals(listOf(tree), app.contentResolver.persistedUriPermissions.map { it.uri })
        repo.remove("kept")
        repo.importUris(listOf(failed), grantFlags = flags)
        assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
        file.delete()
    }

    @Test fun cancellationStillReleasesPersistedGrant() = env { app, repo ->
        Robolectric.buildContentProvider(CancelledProvider::class.java).create("lyric.cancelled").get()
        try {
            repo.importUris(listOf(Uri.parse("content://lyric.cancelled/document/book")), grantFlags = flags)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(app.contentResolver.persistedUriPermissions.isEmpty())
            assertFalse(repo.imports.value.running)
        }
    }

    @Test fun opaquePlainDocumentsConservativelyRetainProviderTree() {
        val tree = DocumentsContract.buildTreeDocumentUri("provider", "root")
        assertTrue(grantIsReferenced(tree, listOf(Uri.parse("content://provider/document/opaque"))))
        assertFalse(grantIsReferenced(tree, listOf(Uri.parse("content://elsewhere/document/opaque"))))
    }
}
