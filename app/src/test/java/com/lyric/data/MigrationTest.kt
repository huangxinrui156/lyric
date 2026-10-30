package com.lyric.data

import android.app.Application
import android.content.ContentValues
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MigrationTest {
    @Test fun realV1SchemaMigratesPreservingBooksProgressAndBookmarks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-${System.nanoTime()}.db"
        val schema = Json.parseToJsonElement(File("schemas/com.lyric.data.LibraryDatabase/1.json").readText()).jsonObject["database"]!!.jsonObject
        // Equivalent to MigrationTestHelper: create from the exported v1 SQL, then let
        // Room open/migrate/validate the file against the generated v3 schema.
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema["entities"]!!.jsonArray.forEach { element ->
                        val entity = element.jsonObject
                        db.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", entity["tableName"]!!.jsonPrimitive.content))
                        entity["indices"]!!.jsonArray.forEach { index -> db.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", entity["tableName"]!!.jsonPrimitive.content)) }
                    }
                    schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        val old = helper.writableDatabase
        old.execSQL("INSERT INTO books (id,uri,name,title,author,extension,contentType,totalChars,chapterCount,encoding,addedAt,lastRead,invalid,excerpt,chapterTitle,percent) VALUES ('hash','file:///book.txt','book.txt','原书','作者','txt','TEXT',100,1,'UTF-8',1,2,0,'摘录','章',1.0)")
        old.execSQL("INSERT INTO progress VALUES ('hash',0,42,3,123)")
        old.execSQL("INSERT INTO bookmarks (id,bookId,chapterIndex,charOffset,page,excerpt,chapterTitle,createdAt) VALUES (7,'hash',0,12,1,'保留这句话','章',456)")
        helper.close()
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(4, db.openHelper.writableDatabase.version)
            val dao = db.library()
            assertEquals("原书", dao.book("hash")!!.title)
            assertEquals(42, dao.progress("hash")!!.charOffset)
            assertEquals(7L, dao.allBookmarks().single().id)
            assertEquals("原书", dao.allBookmarks().single().bookTitle)
            dao.addSession(ReadingSession("s", "hash", 1000, 21000, 20, 30))
            assertEquals(20L, dao.dailyStats().single().durationSec)
            assertEquals(1, dao.observeTotals().first().finished)
            dao.remove("hash")
            assertNull(dao.progress("hash"))
            assertEquals(20L, dao.sessions().single().durationSec)
            assertEquals(1, dao.finished().size)
            assertTrue(dao.observeAllBookmarks().first().single().unavailable)
            assertEquals("保留这句话", dao.allBookmarks().single().excerpt)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun realV2SchemaMigratesAndRollingSessionsUpsertWithoutDoubleCounting() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-v2-${System.nanoTime()}.db"
        val schema = Json.parseToJsonElement(File("schemas/com.lyric.data.LibraryDatabase/2.json").readText()).jsonObject["database"]!!.jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema["entities"]!!.jsonArray.forEach { element ->
                        val e = element.jsonObject
                        db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
                        e["indices"]!!.jsonArray.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content)) }
                    }
                    schema["views"]!!.jsonArray.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{VIEW_NAME}", it.jsonObject["viewName"]!!.jsonPrimitive.content)) }
                    schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase.apply {
            execSQL("INSERT INTO books (id,uri,name,title,author,extension,contentType,totalChars,chapterCount,encoding,addedAt,lastRead,invalid,excerpt,chapterTitle,percent) VALUES ('epub','file:///old.epub','old.epub','原书','作者','epub','TEXT',100,1,'UTF-8',1,2,0,'摘录','章',0.4)")
            execSQL("INSERT INTO progress VALUES ('epub',0,42,3,123)")
            execSQL("INSERT INTO bookmarks (id,bookId,chapterIndex,charOffset,page,excerpt,chapterTitle,createdAt,bookTitle) VALUES (7,'epub',0,12,1,'保留这句话','章',456,'原书')")
            execSQL("INSERT INTO reading_sessions VALUES ('session','epub',1000,16000,15,20)")
            execSQL("INSERT INTO finished_books VALUES ('old','已读',1000)")
        }
        helper.close()
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(4, db.openHelper.writableDatabase.version)
            val dao = db.library()
            assertEquals(0, dao.book("epub")!!.contentRevision)
            assertEquals(0, dao.progress("epub")!!.contentRevision)
            assertEquals(42, dao.progress("epub")!!.charOffset)
            assertEquals(123L, dao.progress("epub")!!.updatedAt)
            assertEquals(0, dao.allBookmarks().single().contentRevision)
            assertEquals(7L, dao.allBookmarks().single().id)
            dao.checkpointSession(ReadingSession("session", "epub", 1000, 31000, 30, 50))
            dao.checkpointSession(ReadingSession("session", "epub", 1000, 35000, 34, 60))
            assertEquals(1, dao.sessions().size)
            assertEquals(34L, dao.dailyStats().single().durationSec)
            assertEquals(1, dao.finished().size)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun realV3SchemaMigratesToV4WithNotesAndHighlights() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-v3-${System.nanoTime()}.db"
        val schema = Json.parseToJsonElement(File("schemas/com.lyric.data.LibraryDatabase/3.json").readText()).jsonObject["database"]!!.jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema["entities"]!!.jsonArray.forEach { element ->
                        val e = element.jsonObject
                        db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content))
                        e["indices"]!!.jsonArray.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content)) }
                    }
                    schema["views"]!!.jsonArray.forEach { db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\u0024{VIEW_NAME}", it.jsonObject["viewName"]!!.jsonPrimitive.content)) }
                    schema["setupQueries"]!!.jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase.apply {
            execSQL("INSERT INTO books (id,uri,name,title,author,extension,contentType,totalChars,chapterCount,encoding,addedAt,lastRead,invalid,excerpt,chapterTitle,percent) VALUES ('epub','file:///old.epub','old.epub','原书','作者','epub','TEXT',100,1,'UTF-8',1,2,0,'摘录','章',0.4)")
            execSQL("INSERT INTO progress (bookId,chapterIndex,charOffset,page,updatedAt) VALUES ('epub',0,42,3,123)")
            execSQL("INSERT INTO bookmarks (id,bookId,chapterIndex,charOffset,page,excerpt,chapterTitle,createdAt,bookTitle) VALUES (7,'epub',0,12,1,'保留这句话','章',456,'原书')")
            execSQL("INSERT INTO reading_sessions VALUES ('session','epub',1000,16000,15,20)")
            execSQL("INSERT INTO finished_books VALUES ('old','已读',1000)")
        }
        helper.close()
        val db = Room.databaseBuilder(context, LibraryDatabase::class.java, name)
            .addMigrations(MIGRATION_3_4).allowMainThreadQueries().build()
        try {
            assertEquals(4, db.openHelper.writableDatabase.version)
            val dao = db.library()
            val mark = dao.allBookmarks().single()
            assertEquals("保留这句话", mark.excerpt); assertEquals("", mark.note)
            dao.bookmarkNote(7, "开头这句很好")
            assertEquals("开头这句很好", dao.allBookmarks().single().note)
            val id = dao.addHighlight(HighlightRow(bookId = "epub", chapterIndex = 0, start = 3, end = 8, text = "窗外的雨"))
            dao.highlightNote(id, "想起高中")
            assertEquals("想起高中", dao.highlights("epub").first().single().note)
            assertEquals(42, dao.progress("epub")!!.charOffset)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close(); context.deleteDatabase(name) }
    }

}
