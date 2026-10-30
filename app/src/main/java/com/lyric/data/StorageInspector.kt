package com.lyric.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StorageUsage(val cache: Long = 0, val fonts: Long = 0, val covers: Long = 0)

/** Only app-generated cache paths are candidates. URI sources and covers are always protected,
 * even when an imported file happens to live in an app cache directory. */
internal object CacheFiles {
    fun protectedPaths(books: List<ShelfBook>): Set<String> = books.flatMap { book ->
        val uri = Uri.parse(book.uri)
        listOfNotNull(if (uri.scheme == "file") uri.path else null, book.coverPath)
    }.map { File(it).canonicalPath }.toSet()

    fun temporary(context: Context) = context.cacheDir.listFiles().orEmpty().filter {
        it.isFile && Regex("(import-.*\\.tmp|font-.*\\.ttf|restore-.*\\.(ttf|otf))", RegexOption.IGNORE_CASE).matches(it.name)
    }

    fun size(file: File, protected: Set<String> = emptySet()): Long {
        if (Files.isSymbolicLink(file.toPath()) || !file.exists() || file.canonicalPath in protected) return 0
        return if (file.isDirectory) file.listFiles().orEmpty().sumOf { size(it, protected) } else file.length()
    }

    fun clear(file: File, protected: Set<String>) {
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalPath in protected) return
        if (file.isDirectory) file.listFiles().orEmpty().forEach { clear(it, protected) }
        file.delete() // A directory containing a protected original remains in place.
    }
}

class StorageInspector(private val context: Context, private val repository: LibraryRepository) {
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun inspect(): StorageUsage = withContext(Dispatchers.IO) {
        val books = repository.dao.all()
        val protected = CacheFiles.protectedPaths(books)
        val covers = books.mapNotNull { it.coverPath }.distinct().map { File(it) }.filter { file ->
            file.isFile && listOf(context.filesDir, context.cacheDir).any {
                file.canonicalPath.startsWith(it.canonicalPath + File.separator)
            }
        }
        val cachePaths = listOfNotNull(File(context.filesDir, "parsed-v1"),
            coil.Coil.imageLoader(context).diskCache?.directory?.toFile()) + CacheFiles.temporary(context)
        StorageUsage(cachePaths.distinctBy { it.canonicalPath }.sumOf { CacheFiles.size(it, protected) },
            CacheFiles.size(File(context.filesDir, "fonts")), covers.sumOf { it.length() })
    }

    suspend fun clearCache() = repository.clearCaches()
}
