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
import com.lyric.core.ImportProgress
import com.lyric.core.Locator
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ImportRecoveryTest {
    class Documents : ContentProvider() {
        lateinit var file: File
        var revoked = false
        var missing = false
        var cancelScan = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val opened = mutableListOf<String>()
        override fun onCreate() = true
        override fun getType(uri: Uri) = "text/plain"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): MatrixCursor {
            if (cancelScan) throw CancellationException("scan cancelled")
            return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("${uri.lastPathSegment}.txt")) }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (revoked) throw SecurityException("permission revoked")
            if (missing) throw FileNotFoundException("provider cleaned file")
            val name = uri.lastPathSegment!!
            opened += name
            if (name == "timeout") ImportProgress.run(-1, { false }, { _, _, _ -> }) { ImportProgress.check() }
            if (name == "cancel") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    }

    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private fun uri(name: String) = Uri.parse("content://lyric.recovery/document/$name")
    private fun env(block: suspend (Application, LibraryRepository, Documents) -> Unit) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(app, LibraryDatabase::class.java).build()
        val provider = Robolectric.buildContentProvider(Documents::class.java).create("lyric.recovery").get()
        provider.file = File(app.cacheDir, "original.txt").apply { writeText("第一章 开始\n\n" + "正文内容。".repeat(200)) }
        try {
            val settings = SettingsStore(app, PreferenceDataStoreFactory.create(scope = scope) {
                File(app.filesDir, "recovery-${System.nanoTime()}.preferences_pb")
            })
            block(app, LibraryRepository(app, db, settings), provider)
        } finally { provider.release.countDown(); db.close(); scope.cancel(); provider.file.delete() }
    }

    @Test fun revokedGrantMarksInvalidAndRepickingKeepsExactProgress() = env { app, repo, provider ->
        repo.importUris(listOf(uri("original")), grantFlags = flags)
        val row = repo.dao.all().single()
        val book = repo.load(row.id).second
        repo.save(row.id, book, Locator(0, 37, 2))
        val progress = repo.dao.progress(row.id)
        val savedRow = repo.dao.book(row.id)!!
        app.contentResolver.releasePersistableUriPermission(uri("original"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        provider.revoked = true
        repo.checkSources()
        assertTrue(repo.dao.book(row.id)!!.invalid)
        try { repo.load(row.id); fail("cached content must not hide revoked access") }
        catch (e: IllegalStateException) { assertEquals("文件访问已失效，重新选择即可恢复进度", e.message) }
        provider.revoked = false
        repo.importUris(listOf(uri("repicked")), grantFlags = flags)
        assertEquals(1, repo.dao.all().size)
        assertEquals(1, repo.imports.value.duplicates)
        val restored = repo.dao.book(row.id)!!
        assertFalse(restored.invalid)
        assertEquals(uri("repicked").toString(), restored.uri)
        assertEquals(progress, repo.dao.progress(row.id))
        assertEquals(savedRow.percent, restored.percent)
        assertEquals(savedRow.lastRead, restored.lastRead)
        assertEquals(book, repo.load(row.id).second)
    }

    @Test fun cleanedSourceShowsRecoveryCopyEvenBeforeShelfRefresh() = env { _, repo, provider ->
        repo.importUris(listOf(uri("original")), grantFlags = flags)
        val row = repo.dao.all().single()
        provider.missing = true
        try { repo.load(row.id); fail("missing source must fail") }
        catch (e: IllegalStateException) { assertEquals("文件访问已失效，重新选择即可恢复进度", e.message) }
        assertTrue(repo.dao.book(row.id)!!.invalid)
    }

    @Test fun privateCopyStillReadsAfterExternalGrantAndFileDisappear() = env { _, repo, provider ->
        repo.importUris(listOf(uri("temporary")), grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val row = repo.dao.all().single()
        provider.revoked = true
        provider.file.delete()
        repo.checkSources()
        assertFalse(repo.dao.book(row.id)!!.invalid)
        assertTrue(repo.load(row.id, force = true).second.totalChars > 0)
    }

    @Test fun timeoutKeepsBatchCountsAndHumanReasonAndContinues() = env { app, repo, _ ->
        repo.importUris(listOf(uri("original"), uri("timeout"), uri("duplicate")), grantFlags = flags)
        val status = repo.imports.value
        assertFalse(status.running)
        assertEquals(3, status.done)
        assertEquals("导入 1 · 跳过 1 · 失败 1", status.summary)
        assertEquals(status.summary + "\ntimeout.txt：文件太大或结构异常，超时已停止", status.message)
        assertEquals(listOf("timeout.txt：文件太大或结构异常，超时已停止"), status.errors)
        assertEquals(1, repo.dao.all().size)
        assertFalse(app.cacheDir.listFiles()!!.any { it.name.startsWith("import-") })
        assertFalse(app.contentResolver.persistedUriPermissions.any { it.uri == uri("timeout") })
    }

    @Test fun cancelKeepsCompletedCountsAndSkipsUnstartedFiles() = env { app, repo, provider ->
        coroutineScope {
            val job = launch(Dispatchers.IO) {
                repo.importUris(listOf(uri("original"), uri("duplicate"), uri("cancel"), uri("unstarted")), grantFlags = flags)
            }
            try {
                assertTrue(provider.entered.await(10, TimeUnit.SECONDS))
                repo.cancelImport()
            } finally { provider.release.countDown() }
            job.join()
            assertTrue(job.isCancelled)
        }
        val status = repo.imports.value
        assertFalse(status.running)
        assertEquals("导入 1 · 跳过 2 · 失败 1", status.summary)
        assertEquals(1, status.duplicates)
        assertEquals(1, status.skipped)
        assertEquals(status.summary + "\ncancel.txt：导入已取消", status.message)
        assertFalse(provider.opened.contains("unstarted"))
        assertEquals(1, repo.dao.all().size)
        assertFalse(app.cacheDir.listFiles()!!.any { it.name.startsWith("import-") })
        assertEquals(listOf(uri("duplicate")), app.contentResolver.persistedUriPermissions.map { it.uri })
    }

    @Test fun cancelDuringFolderScanStillHasSummaryAndFolderContext() = env { _, repo, provider ->
        provider.cancelScan = true
        try {
            repo.importUris(listOf(DocumentsContract.buildTreeDocumentUri("lyric.recovery", "primary:Books")), folder = true, grantFlags = flags)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            val status = repo.imports.value
            assertFalse(status.running)
            assertTrue(status.folder)
            assertEquals("/Books", status.source)
            assertEquals("导入 0 · 跳过 0 · 失败 0\n导入已取消", status.message)
        }
    }

    private fun protectedEpub(file: File, images: Boolean) {
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
                "OPS/book.opf" to "<package><manifest><item id='p' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='p'/></spine></package>",
                "OPS/a.xhtml" to if (images) "<img src='p.png'/>" else "<p>受保护的正文</p>",
                "OPS/p.png" to "image",
                "META-INF/encryption.xml" to "<encryption><EncryptedData><EncryptionMethod Algorithm='http://www.w3.org/2001/04/xmlenc#aes128-cbc'/><CipherData><CipherReference URI='OPS/a.xhtml'/></CipherData></EncryptedData></encryption>",
            )
            entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
    }

    @Test fun drmTextImageEpubAndEncryptedZipLeaveNoRowsOrPartialFiles() = env { app, repo, _ ->
        val baseline = app.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(app.filesDir).path }.toSet()
        for (kind in listOf("text.epub", "images.epub", "encrypted.cbz", "protected.mobi")) {
            val file = File(app.cacheDir, kind)
            if (kind.endsWith("mobi")) {
                // PalmDB with one record carrying PalmDOC's nonzero encryption field.
                val bytes = ByteArray(104)
                "BOOKMOBI".toByteArray().copyInto(bytes, 60)
                bytes[77] = 1; bytes[81] = 88; bytes[101] = 2
                file.writeBytes(bytes)
            } else {
                protectedEpub(file, kind == "images.epub")
                if (kind.endsWith("cbz")) {
                    val bytes = file.readBytes()
                    for (i in 0 until bytes.size - 10) if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte()) {
                        val flag = when {
                            bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() -> i + 8
                            bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte() -> i + 6
                            else -> continue
                        }
                        bytes[flag] = (bytes[flag].toInt() or 1).toByte()
                    }
                    file.writeBytes(bytes)
                }
            }
            repo.importUris(listOf(Uri.fromFile(file)))
            assertEquals(kind, listOf("$kind：这本书有版权保护，无法导入"), repo.imports.value.errors)
            assertEquals("导入 0 · 跳过 0 · 失败 1", repo.imports.value.summary)
            assertTrue(repo.dao.all().isEmpty())
            assertEquals(baseline, app.filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(app.filesDir).path }.toSet())
            assertFalse(app.cacheDir.listFiles()!!.any { it.name.startsWith("import-") || it.name.startsWith("epub-") && it.isDirectory })
            file.delete()
        }
    }
}
