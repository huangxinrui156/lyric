package com.lyric.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.lyric.core.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class UriSource(
    private val context: Context,
    private val uri: Uri,
    override val displayName: String,
) : ContentSource {
    override fun open(): InputStream =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
}

private class FileSource(private val file: File, override val displayName: String) : ContentSource {
    override fun open() = file.inputStream()
}

data class ImportStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val duplicates: Int = 0,
    val failures: Int = 0,
    val message: String = "",
)

data class SearchHit(
    val chapterIndex: Int,
    val charOffset: Int,
    val chapterTitle: String,
    val context: String,
)

class LibraryRepository(
    private val context: Context,
    private val db: LibraryDatabase,
    val settings: SettingsStore,
) {
    val dao = db.library()
    val books = dao.observeBooks()
    val imports = MutableStateFlow(ImportStatus())
    private val registry = ParserRegistry()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = File(context.filesDir, "parsed-v1").apply { mkdirs() }
    private val importLock = Mutex()
    private val parseLock = Mutex()
    private val memory =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, Book>(4, .75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Book>?) =
                    size > 2
            }
        )

    private fun cacheFile(row: ShelfBook): File {
        val options = "${row.encodingOverride.orEmpty()}\u0000${row.chapterRegex.orEmpty()}"
        val fingerprint =
            MessageDigest.getInstance("SHA-256").digest(options.toByteArray()).take(8).joinToString(
                ""
            ) {
                "%02x".format(it)
            }
        return File(cache, "${row.id}-$fingerprint.json")
    }

    fun persist(uri: Uri): Boolean =
        runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                true
            }
            .getOrDefault(false)

    private fun displayName(uri: Uri): String =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).name
        else
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "未命名.txt"

    suspend fun importUris(uris: List<Uri>, folder: Boolean = false) =
        withContext(Dispatchers.IO) {
            importLock.withLock {
                imports.value = ImportStatus(running = true, message = "正在扫描本地文件…")
                try {
                    val files = mutableListOf<Uri>()
                    if (folder) {
                        uris.forEach { uri ->
                            require(persist(uri)) { "无法保留文件夹访问权限，请重新选择" }
                            val queue = ArrayDeque<DocumentFile>()
                            DocumentFile.fromTreeUri(context, uri)?.let(queue::add)
                            while (queue.isNotEmpty()) {
                                currentCoroutineContext().ensureActive()
                                queue.removeFirst().listFiles().forEach { f ->
                                    if (f.isDirectory) queue.add(f)
                                    else if (
                                        f.name?.substringAfterLast('.', "")?.lowercase() in
                                            setOf("txt", "epub")
                                    )
                                        files += f.uri
                                }
                            }
                        }
                    } else {
                        uris.forEach { if (it.scheme != "file") persist(it) }
                        files += uris
                    }
                    var status = ImportStatus(running = true, total = files.size)
                    files.forEach { uri ->
                        currentCoroutineContext().ensureActive()
                        val name = runCatching { displayName(uri) }.getOrDefault("未知文件")
                        status = status.copy(message = name)
                        imports.value = status
                        val result = runCatching { importOne(uri, name) }
                        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                        status =
                            status.copy(
                                done = status.done + 1,
                                added = status.added + if (result.getOrNull() == true) 1 else 0,
                                duplicates =
                                    status.duplicates + if (result.getOrNull() == false) 1 else 0,
                                failures = status.failures + if (result.isFailure) 1 else 0,
                                message = result.exceptionOrNull()?.localizedMessage ?: name,
                            )
                        imports.value = status
                    }
                    imports.value =
                        status.copy(
                            running = false,
                            message =
                                "已导入 ${status.added} 本 · 重复 ${status.duplicates} 本 · 失败 ${status.failures} 本",
                        )
                } catch (e: CancellationException) {
                    imports.value = imports.value.copy(running = false, message = "导入已停止")
                    throw e
                } catch (e: Exception) {
                    imports.value =
                        imports.value.copy(running = false, message = e.localizedMessage ?: "导入失败")
                }
            }
        }

    private suspend fun importOne(uri: Uri, name: String): Boolean {
        registry.parser(name.substringAfterLast('.', "").lowercase())
        val temp = File.createTempFile("import-", ".tmp", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            UriSource(context, uri, name).open().use { input ->
                temp.outputStream().use { output ->
                    val buf = ByteArray(65536)
                    var n = input.read(buf)
                    var size = 0L
                    while (n >= 0) {
                        size += n
                        require(size <= 200L * 1024 * 1024) { "文件超过 200MB" }
                        if (n > 0) {
                            digest.update(buf, 0, n)
                            output.write(buf, 0, n)
                        }
                        n = input.read(buf)
                    }
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            dao.book(id)?.let { old ->
                // Re-import can repair a moved or revoked original without discarding its progress.
                dao.update(old.copy(uri = uri.toString(), name = name, invalid = false))
                return false
            }
            val parsed =
                registry
                    .parser(name.substringAfterLast('.').lowercase())
                    .parse(FileSource(temp, name))
            val row =
                ShelfBook(
                    id,
                    uri.toString(),
                    name,
                    parsed.title,
                    parsed.author,
                    name.substringAfterLast('.').lowercase(),
                    contentType = parsed.contentType.name,
                    totalChars = parsed.totalChars,
                    chapterCount = parsed.chapters.size,
                    encoding = parsed.encoding,
                    excerpt = parsed.chapters.first().text.take(80),
                )
            parseLock.withLock { writeCache(row, parsed) }
            dao.insert(row)
            return true
        } finally {
            temp.delete()
        }
    }

    private fun writeCache(row: ShelfBook, book: Book) {
        val file = cacheFile(row)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(Book.serializer(), book))
        check(tmp.renameTo(file)) { "无法写入解析缓存" }
        memory.remove(file.name)
    }

    suspend fun load(id: String, force: Boolean = false): Pair<ShelfBook, Book> =
        withContext(Dispatchers.IO) {
            parseLock.withLock {
                val row = dao.book(id) ?: error("书籍已移出书架")
                try {
                    // Even with a parsed cache, check the source so revoked/missing files are
                    // explicit.
                    UriSource(context, Uri.parse(row.uri), row.name).open().use {}
                    val key = cacheFile(row).name
                    val parsed =
                        if (!force)
                            memory[key]
                                ?: runCatching {
                                        json.decodeFromString(
                                            Book.serializer(),
                                            cacheFile(row).readText(),
                                        )
                                    }
                                    .getOrNull()
                        else null
                    val book =
                        parsed
                            ?: registry
                                .parser(row.extension)
                                .parse(
                                    UriSource(context, Uri.parse(row.uri), row.name),
                                    row.encodingOverride,
                                    row.chapterRegex,
                                )
                                .also { writeCache(row, it) }
                    memory[key] = book
                    dao.invalid(id, false)
                    row to book
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    dao.invalid(id, true)
                    throw IllegalStateException(
                        "无法阅读：${e.localizedMessage ?: "文件丢失或授权失效"}。请在本地导入中重新选择原文件。",
                        e,
                    )
                }
            }
        }

    suspend fun checkSources() =
        withContext(Dispatchers.IO) {
            dao.all().forEach { row ->
                val valid =
                    runCatching { UriSource(context, Uri.parse(row.uri), row.name).open().use {} }
                        .isSuccess
                if (row.invalid == valid) dao.invalid(row.id, !valid)
            }
        }

    suspend fun reparse(id: String, encoding: String?, regex: String?): Pair<ShelfBook, Book> =
        withContext(Dispatchers.IO) {
            TxtParser.rule(regex) // Reject invalid regex before changing saved preferences.
            val old = dao.book(id) ?: error("书籍不存在")
            val oldBook = load(id).second
            val candidate =
                old.copy(
                    encodingOverride = encoding?.takeUnless { it == "自动" },
                    chapterRegex = regex?.takeIf { it.isNotBlank() },
                )
            val parsed =
                registry
                    .parser(candidate.extension)
                    .parse(
                        UriSource(context, Uri.parse(candidate.uri), candidate.name),
                        candidate.encodingOverride,
                        candidate.chapterRegex,
                    )
            writeCache(candidate, parsed)
            val progress = dao.progress(id)
            // Rules/encoding can change chapter boundaries. Remap the absolute text offset.
            val absolute =
                progress?.let {
                    oldBook.chapters.take(it.chapterIndex).sumOf { c -> c.text.length } +
                        it.charOffset
                } ?: 0
            var remaining = absolute
            var chapter = 0
            while (
                chapter < parsed.chapters.lastIndex &&
                    remaining >= parsed.chapters[chapter].text.length
            ) {
                remaining -= parsed.chapters[chapter].text.length
                chapter++
            }
            val loc = parsed.clamp(Locator(chapter, remaining))
            db.withTransaction {
                dao.update(
                    candidate.copy(
                        encoding = parsed.encoding,
                        chapterCount = parsed.chapters.size,
                        totalChars = parsed.totalChars,
                    )
                )
                dao.saveProgress(ProgressRow(id, loc.chapterIndex, loc.charOffset))
                // Remap bookmarks using their absolute character offset as well.
                val marks = dao.bookmarks(id).first()
                marks.forEach { mark ->
                    var offset =
                        oldBook.chapters.take(mark.chapterIndex).sumOf { it.text.length } +
                            mark.charOffset
                    var ci = 0
                    while (
                        ci < parsed.chapters.lastIndex && offset >= parsed.chapters[ci].text.length
                    ) {
                        offset -= parsed.chapters[ci].text.length
                        ci++
                    }
                    val m = parsed.clamp(Locator(ci, offset))
                    dao.deleteBookmark(mark.id)
                    dao.addBookmark(
                        mark.copy(
                            id = 0,
                            chapterIndex = m.chapterIndex,
                            charOffset = m.charOffset,
                            chapterTitle = parsed.chapters[m.chapterIndex].title,
                        )
                    )
                }
            }
            load(id)
        }

    suspend fun save(id: String, book: Book, locator: Locator) =
        withContext(Dispatchers.IO) {
            val loc = book.clamp(locator)
            val chapter = book.chapters[loc.chapterIndex]
            db.withTransaction {
                if (dao.book(id) != null) {
                    dao.saveProgress(ProgressRow(id, loc.chapterIndex, loc.charOffset, loc.page))
                    if (book.percent(loc) >= .99f) dao.finish(FinishedBook(id, book.title, System.currentTimeMillis()))
                    dao.reading(
                        id,
                        System.currentTimeMillis(),
                        book.percent(loc),
                        chapter.title,
                        book.excerpt(loc),
                    )
                }
            }
        }

    suspend fun remove(id: String) =
        withContext(Dispatchers.IO) {
            dao.remove(id)
            cache
                .listFiles()
                ?.filter { it.name.startsWith("$id-") }
                ?.forEach {
                    it.delete()
                    memory.remove(it.name)
                }
        }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearCaches() = withContext(Dispatchers.IO) {
        parseLock.withLock {
            val protected = CacheFiles.protectedPaths(dao.all())
            cache.listFiles()?.forEach { CacheFiles.clear(it, protected) }
            CacheFiles.temporary(context).forEach { CacheFiles.clear(it, protected) }
            memory.clear()
            val images = coil.Coil.imageLoader(context)
            images.memoryCache?.clear()
            images.diskCache?.let { disk ->
                val directory = disk.directory.toFile()
                if (protected.none { it.startsWith(directory.canonicalPath + File.separator) }) disk.clear()
                else directory.listFiles()?.forEach { CacheFiles.clear(it, protected) }
            }
        }
    }

    fun search(book: Book, query: String): Flow<SearchHit> =
        flow {
                if (query.isBlank()) return@flow
                for (chapter in book.chapters) {
                    var from = 0
                    while (from < chapter.text.length) {
                        currentCoroutineContext().ensureActive()
                        val index = chapter.text.indexOf(query, from, ignoreCase = true)
                        if (index < 0) break
                        emit(
                            SearchHit(
                                chapter.index,
                                index,
                                chapter.title,
                                chapter.text
                                    .substring(
                                        (index - 25).coerceAtLeast(0),
                                        (index + query.length + 50).coerceAtMost(
                                            chapter.text.length
                                        ),
                                    )
                                    .replace('\n', ' '),
                            )
                        )
                        from = index + query.length.coerceAtLeast(1)
                    }
                }
            }
            .flowOn(Dispatchers.Default)

    suspend fun seed() =
        withContext(Dispatchers.IO) {
            if (settings.isSeeded()) return@withContext
            val examples =
                listOf(
                    "山间来信" to "在山里，时间是被树影丈量的。清晨推开窗，雾从竹林缓缓走来。桌上有一杯温茶，一本没有读完的书。",
                    "雨落之前" to "午后的街道安静下来。风把窗帘吹出一个柔软的弧度，远处的天色像一封尚未拆开的信。我们坐在屋檐下，等待第一滴雨。",
                    "草木手记" to "春日的草木有自己的秩序。不必催促一片叶子舒展，也不必为迟来的花着急。沿着小径走过，便能听见季节轻轻翻页。",
                )
            val dir = File(context.filesDir, "examples").apply { mkdirs() }
            examples.forEach { (title, line) ->
                val file = File(dir, "$title.txt")
                file.writeText(
                    buildString {
                        append("$title\nLyric 原创示例 · 可长按移出书架\n\n")
                        repeat(4) { chapter ->
                            append(
                                "第${chapter + 1}章 ${listOf("慢下来", "一页之间", "留白", "归途")[chapter]}\n\n"
                            )
                            repeat(12) {
                                append("$line\n\n")
                                append(
                                    "阅读像一场缓慢的散步。字句之间的空白，留给此刻的呼吸。读到这里，可以轻点屏幕两侧翻页，轻点中央唤出目录、书签与排版。\n\n"
                                )
                            }
                        }
                    }
                )
                importOne(Uri.fromFile(file), file.name)
            }
            settings.seeded()
        }
}
