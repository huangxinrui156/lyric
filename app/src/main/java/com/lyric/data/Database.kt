package com.lyric.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Serializable
@Entity(tableName = "books")
data class ShelfBook(
    @PrimaryKey val id: String,
    val uri: String,
    val name: String,
    val title: String,
    val author: String,
    val extension: String,
    val contentType: String = "TEXT",
    val totalChars: Long,
    val chapterCount: Int,
    val encoding: String,
    val encodingOverride: String? = null,
    val chapterRegex: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val lastRead: Long = 0,
    val invalid: Boolean = false,
    val excerpt: String = "",
    val chapterTitle: String = "未读",
    val percent: Float = 0f,
    val coverPath: String? = null,
    @ColumnInfo(defaultValue = "0") val contentRevision: Int = 0,
    @ColumnInfo(defaultValue = "'BOOK'") val section: String = if (contentType == "COMIC") "COMIC" else "BOOK",
)

/** No foreign key since v5: removing a book from the shelf keeps where the reader was. */
@Entity(tableName = "progress")
@Serializable
data class ProgressRow(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val page: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val contentRevision: Int = 0,
)

@Serializable
@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val page: Int = 0,
    val excerpt: String,
    val chapterTitle: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val bookTitle: String = "",
    @ColumnInfo(defaultValue = "0") val contentRevision: Int = 0,
    @ColumnInfo(defaultValue = "''") val note: String = "",
)

/** Highlights anchor to chapter-relative UTF-16 offsets, so re-pagination never moves them. */
@Serializable
@Entity(tableName = "highlights", indices = [Index("bookId")])
data class HighlightRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val note: String = "",
    val chapterTitle: String = "",
    val bookTitle: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val color: Int = 0,
)

@Serializable
@Entity(tableName = "reading_sessions", indices = [Index("startAt"), Index("bookId")])
data class ReadingSession(
    @PrimaryKey val id: String,
    val bookId: String,
    val startAt: Long,
    val endAt: Long,
    val durationSec: Long,
    val chars: Long,
)

@Serializable
@Entity(tableName = "finished_books")
data class FinishedBook(@PrimaryKey val bookId: String, val title: String, val finishedAt: Long)

/** Pomodoro-style focus blocks from the 案头 page; endAt=0 means still running. */
@Serializable
@Entity(tableName = "focus_sessions", indices = [Index("startAt"), Index("bookId")])
data class FocusSession(
    @PrimaryKey val id: String,
    val bookId: String,
    val bookTitle: String = "",
    val startAt: Long,
    val endAt: Long = 0,
    val durationSec: Long = 0,
    val plannedMin: Int = 25,
    val completed: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pausedMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val pausedAt: Long = 0,
)

@DatabaseView(viewName = "daily_stats", value = "SELECT date(startAt / 1000, 'unixepoch', 'localtime') AS day, SUM(durationSec) AS durationSec, SUM(chars) AS chars FROM reading_sessions GROUP BY day")
data class DailyStat(val day: String, val durationSec: Long, val chars: Long)

data class ReadingTotals(val durationSec: Long, val finished: Int)
data class BookmarkSummary(
    @Embedded val mark: BookmarkRow,
    val title: String,
    val unavailable: Boolean,
)
data class DayBook(val bookId: String, val title: String, val durationSec: Long)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM daily_stats WHERE day >= :from AND day <= :to ORDER BY day")
    fun observeDailyStats(from: String, to: String): Flow<List<DailyStat>>

    @Query("SELECT COALESCE(SUM(durationSec),0) AS durationSec, (SELECT COUNT(*) FROM finished_books) AS finished FROM reading_sessions")
    fun observeTotals(): Flow<ReadingTotals>

    @Query("SELECT bookmarks.*, COALESCE(books.title, NULLIF(bookmarks.bookTitle, ''), '已移除的书籍') AS title, CASE WHEN books.id IS NULL OR books.invalid = 1 THEN 1 ELSE 0 END AS unavailable FROM bookmarks LEFT JOIN books ON books.id = bookmarks.bookId ORDER BY bookmarks.createdAt DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkSummary>>

    @Query("SELECT reading_sessions.bookId, COALESCE(books.title, finished_books.title, '已移除的书籍') AS title, SUM(durationSec) AS durationSec FROM reading_sessions LEFT JOIN books ON books.id = reading_sessions.bookId LEFT JOIN finished_books ON finished_books.bookId = reading_sessions.bookId WHERE startAt >= :from AND startAt < :to GROUP BY reading_sessions.bookId ORDER BY durationSec DESC")
    suspend fun dayBooks(from: Long, to: Long): List<DayBook>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addSession(session: ReadingSession)
    @Upsert suspend fun checkpointSession(session: ReadingSession)
    @Query("UPDATE reading_sessions SET endAt = :endAt, durationSec = :seconds, chars = :chars WHERE id = :id AND endAt < :endAt")
    suspend fun extendSession(id: String, endAt: Long, seconds: Long, chars: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun finish(book: FinishedBook)
    @Query("SELECT * FROM reading_sessions") suspend fun sessions(): List<ReadingSession>
    @Query("SELECT * FROM finished_books") suspend fun finished(): List<FinishedBook>
    @Query("SELECT * FROM progress") suspend fun allProgress(): List<ProgressRow>
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC") suspend fun allBookmarks(): List<BookmarkRow>
    @Query("SELECT * FROM daily_stats ORDER BY day") suspend fun dailyStats(): List<DailyStat>

    @Query("SELECT * FROM books ORDER BY lastRead DESC, addedAt DESC")
    fun observeBooks(): Flow<List<ShelfBook>>

    @Query("SELECT * FROM books") suspend fun all(): List<ShelfBook>

    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): ShelfBook?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(book: ShelfBook)

    @Update suspend fun update(book: ShelfBook)

    @Query("UPDATE books SET section = :section WHERE id = :id")
    suspend fun moveToSection(id: String, section: String)

    @Query("DELETE FROM books WHERE id = :id") suspend fun remove(id: String)

    @Query("UPDATE books SET invalid = :invalid WHERE id = :id")
    suspend fun invalid(id: String, invalid: Boolean)

    @Query("SELECT * FROM progress WHERE bookId = :id")
    suspend fun progress(id: String): ProgressRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(progress: ProgressRow)

    @Query(
        "UPDATE books SET lastRead = :time, percent = :percent, chapterTitle = :chapter, excerpt = :excerpt WHERE id = :id"
    )
    suspend fun reading(id: String, time: Long, percent: Float, chapter: String, excerpt: String)

    @Query("SELECT * FROM bookmarks WHERE bookId = :id ORDER BY createdAt DESC")
    fun bookmarks(id: String): Flow<List<BookmarkRow>>

    @Insert suspend fun addBookmark(bookmark: BookmarkRow)
    @Update suspend fun updateBookmark(bookmark: BookmarkRow)
    @Query("SELECT * FROM bookmarks WHERE id = :id") suspend fun bookmark(id: Long): BookmarkRow?

    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
    @Query("UPDATE bookmarks SET note = :note WHERE id = :id") suspend fun bookmarkNote(id: Long, note: String)

    @Query("SELECT * FROM highlights WHERE bookId = :id ORDER BY chapterIndex, start")
    fun highlights(id: String): Flow<List<HighlightRow>>
    @Query("SELECT * FROM highlights") suspend fun allHighlights(): List<HighlightRow>
    @Insert suspend fun addHighlight(row: HighlightRow): Long
    @Query("UPDATE highlights SET note = :note WHERE id = :id") suspend fun highlightNote(id: Long, note: String)
    @Query("DELETE FROM highlights WHERE id = :id") suspend fun deleteHighlight(id: Long)
    @Query("UPDATE highlights SET start = :start, `end` = :end, text = :text WHERE id = :id") suspend fun highlightRange(id: Long, start: Int, end: Int, text: String)
    @Query("UPDATE highlights SET color = :color WHERE id = :id") suspend fun highlightColor(id: Long, color: Int)

    @Query("SELECT * FROM highlights ORDER BY createdAt DESC")
    fun observeAllHighlights(): Flow<List<HighlightRow>>

    @Query("SELECT COUNT(*) FROM highlights WHERE createdAt >= :from AND createdAt < :to")
    fun observeHighlightCount(from: Long, to: Long): Flow<Int>

    @Query("""
        SELECT COALESCE(SUM(
            CASE WHEN books.contentType = 'COMIC' THEN reading_sessions.chars
                 ELSE reading_sessions.chars / 400 END
        ), 0) FROM reading_sessions
        LEFT JOIN books ON books.id = reading_sessions.bookId
        WHERE reading_sessions.startAt >= :from AND reading_sessions.startAt < :to
    """)
    fun observeApproxPages(from: Long, to: Long): Flow<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFocus(session: FocusSession)
    @Query("SELECT * FROM focus_sessions WHERE endAt = 0 LIMIT 1") suspend fun activeFocus(): FocusSession?
    @Query("SELECT * FROM focus_sessions ORDER BY startAt DESC") suspend fun allFocus(): List<FocusSession>
    @Query("SELECT COALESCE(SUM(durationSec), 0) FROM focus_sessions WHERE completed = 1 AND startAt >= :from AND startAt < :to")
    fun observeFocusSeconds(from: Long, to: Long): Flow<Long>
}

@Database(
    entities = [ShelfBook::class, ProgressRow::class, BookmarkRow::class, ReadingSession::class, FinishedBook::class, HighlightRow::class, FocusSession::class],
    views = [DailyStat::class],
    version = 8,
    exportSchema = true,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

/** The v1 tables are retained; only bookmarks is rebuilt to retain excerpts after shelf deletion. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_sessions (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, startAt INTEGER NOT NULL, endAt INTEGER NOT NULL, durationSec INTEGER NOT NULL, chars INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_startAt ON reading_sessions(startAt)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_sessions_bookId ON reading_sessions(bookId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS finished_books (bookId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, finishedAt INTEGER NOT NULL)")
        db.execSQL("INSERT OR IGNORE INTO finished_books SELECT id, title, lastRead FROM books WHERE percent >= 0.99")
        db.execSQL("CREATE TABLE bookmarks_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, bookId TEXT NOT NULL, chapterIndex INTEGER NOT NULL, charOffset INTEGER NOT NULL, page INTEGER NOT NULL, excerpt TEXT NOT NULL, chapterTitle TEXT NOT NULL, createdAt INTEGER NOT NULL, bookTitle TEXT NOT NULL DEFAULT '')")
        db.execSQL("INSERT INTO bookmarks_new SELECT bookmarks.*, COALESCE(books.title, '') FROM bookmarks LEFT JOIN books ON books.id = bookmarks.bookId")
        db.execSQL("DROP TABLE bookmarks")
        db.execSQL("ALTER TABLE bookmarks_new RENAME TO bookmarks")
        db.execSQL("CREATE INDEX index_bookmarks_bookId ON bookmarks(bookId)")
        db.execSQL("CREATE VIEW `daily_stats` AS SELECT date(startAt / 1000, 'unixepoch', 'localtime') AS day, SUM(durationSec) AS durationSec, SUM(chars) AS chars FROM reading_sessions GROUP BY day")
    }
}

/** Marks books whose stored EPUB anchors still use the v0.2 plain-text representation. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN contentRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE progress ADD COLUMN contentRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN contentRevision INTEGER NOT NULL DEFAULT 0")
    }
}

/** v0.5: bookmark notes and chapter-anchored highlights (old 摘录 bookmarks migrate on first open). */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bookmarks ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE IF NOT EXISTS `highlights` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `start` INTEGER NOT NULL, `end` INTEGER NOT NULL, `text` TEXT NOT NULL, `note` TEXT NOT NULL, `chapterTitle` TEXT NOT NULL, `bookTitle` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_highlights_bookId` ON `highlights` (`bookId`)")
    }
}

/** v0.5.1: progress outlives shelf removal (drops the ON DELETE CASCADE foreign key). */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `progress_new` (`bookId` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `charOffset` INTEGER NOT NULL, `page` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `contentRevision` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`bookId`))")
        db.execSQL("INSERT INTO progress_new SELECT bookId, chapterIndex, charOffset, page, updatedAt, contentRevision FROM progress")
        db.execSQL("DROP TABLE progress")
        db.execSQL("ALTER TABLE progress_new RENAME TO progress")
    }
}

/** v0.5.2: per-highlight colour (0 = theme vermilion). Offsets are unchanged. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE highlights ADD COLUMN color INTEGER NOT NULL DEFAULT 0")
    }
}

/** v0.6: shelf placement is independent of the reader format; all reading data stays untouched. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN section TEXT NOT NULL DEFAULT 'BOOK'")
        db.execSQL("UPDATE books SET section = 'COMIC' WHERE contentType = 'COMIC'")
    }
}

enum class ShelfSection(val label: String) {
    BOOK("书架"), COMIC("画架");
    fun other() = if (this == BOOK) COMIC else BOOK
}

/** v0.8: focus sessions for 案头 · 专注阅读. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `focus_sessions` (`id` TEXT NOT NULL, `bookId` TEXT NOT NULL, `bookTitle` TEXT NOT NULL, `startAt` INTEGER NOT NULL, `endAt` INTEGER NOT NULL, `durationSec` INTEGER NOT NULL, `plannedMin` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `pausedMs` INTEGER NOT NULL DEFAULT 0, `pausedAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_startAt` ON `focus_sessions` (`startAt`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_focus_sessions_bookId` ON `focus_sessions` (`bookId`)")
    }
}

