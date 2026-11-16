package com.lyric.data

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class EpubUpgradeTest {
    @Test fun oldEpubProgressAndDetachedBookmarksRebaseExactlyOnceWithoutLosingExcerptOrTimestamp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val settings = SettingsStore(context, PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, "upgrade-${System.nanoTime()}.preferences_pb")
        })
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        val source = File(context.filesDir, "old.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            mapOf("META-INF/container.xml" to """<container><rootfile full-path="book.opf"/></container>""",
                "book.opf" to """<package><manifest><item id="a" href="a.xhtml"/></manifest><spine><itemref idref="a"/></spine></package>""",
                "a.xhtml" to """<html><body><p>开头<img alt="花" src="p.png"/>中<ruby>漢字<rt>かんじ</rt></ruby>后文</p><p>目标句子。</p></body></html>""")
                .forEach { (path, text) -> zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        try {
            val repo = LibraryRepository(context, db, settings)
            repo.importUris(listOf(Uri.fromFile(source)))
            val dao = db.library()
            val row = dao.all().single()
            val legacy = "开头［插图：花］中漢字かんじ后文\n目标句子。\n"
            val offset = legacy.indexOf("目标句子")
            dao.update(row.copy(contentRevision = 0))
            dao.saveProgress(ProgressRow(row.id, 0, offset, updatedAt = 123))
            dao.addBookmark(BookmarkRow(id = 7, bookId = row.id, chapterIndex = 0, charOffset = offset,
                excerpt = "保留旧摘录全文", chapterTitle = "原章名", createdAt = 456))
            val book = repo.load(row.id).second
            val current = book.chapters[0].text.indexOf("目标句子")
            assertTrue(current < offset)
            assertEquals(current, dao.progress(row.id)!!.charOffset)
            assertEquals(123L, dao.progress(row.id)!!.updatedAt)
            assertEquals(3, dao.progress(row.id)!!.contentRevision)
            val mark = dao.allBookmarks().single()
            assertEquals(current, mark.charOffset)
            assertEquals(current, dao.bookmark(7)!!.charOffset) // navigation resolves the upgraded anchor by ID
            assertEquals(7L, mark.id)
            assertEquals("保留旧摘录全文", mark.excerpt)
            assertEquals(456L, mark.createdAt)
            repo.clearCaches(); repo.load(row.id)
            assertEquals(current, dao.progress(row.id)!!.charOffset)
            assertEquals(current, dao.allBookmarks().single().charOffset)

            // An orphan from an old backup is still migrated after its source is reimported.
            dao.remove(row.id)
            dao.updateBookmark(mark.copy(charOffset = offset, contentRevision = 0))
            repo.importUris(listOf(Uri.fromFile(source)))
            repo.load(row.id)
            assertEquals(current, dao.allBookmarks().single().charOffset)
            assertEquals(3, dao.allBookmarks().single().contentRevision)
            assertEquals(7L, dao.allBookmarks().single().id)
        } finally { db.close(); scope.cancel(); source.delete() }
    }
}
