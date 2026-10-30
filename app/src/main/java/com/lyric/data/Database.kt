package com.lyric.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
)

@Entity(
    tableName = "progress",
    foreignKeys =
        [
            ForeignKey(
                entity = ShelfBook::class,
                parentColumns = ["id"],
                childColumns = ["bookId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class ProgressRow(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val page: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "bookmarks",
    indices = [Index("bookId")],
    foreignKeys =
        [
            ForeignKey(
                entity = ShelfBook::class,
                parentColumns = ["id"],
                childColumns = ["bookId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class BookmarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val charOffset: Int,
    val page: Int = 0,
    val excerpt: String,
    val chapterTitle: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM books ORDER BY lastRead DESC, addedAt DESC")
    fun observeBooks(): Flow<List<ShelfBook>>

    @Query("SELECT * FROM books") suspend fun all(): List<ShelfBook>

    @Query("SELECT * FROM books WHERE id = :id") suspend fun book(id: String): ShelfBook?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(book: ShelfBook)

    @Update suspend fun update(book: ShelfBook)

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

    @Query("DELETE FROM bookmarks WHERE id = :id") suspend fun deleteBookmark(id: Long)
}

@Database(
    entities = [ShelfBook::class, ProgressRow::class, BookmarkRow::class],
    version = 1,
    exportSchema = false,
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}
