package com.lyric.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.Log
import androidx.room.withTransaction
import com.lyric.core.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.decodeFromStream
import com.lyric.core.ImportProgress
import com.lyric.core.ContentType
import com.lyric.core.ComicParser
import com.lyric.core.ImageEpub
import com.lyric.reader.ComicPages

class UriSource(
    private val context: Context,
    private val uri: Uri,
    override val displayName: String,
) : ContentSource {
    override fun open(): InputStream =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
        else context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
}

private class FileSource(override val file: File, override val displayName: String) : FileContentSource {
    override fun open() = file.inputStream()
}

internal data class ImportOneResult(val added: Boolean, val id: String)

data class ImportStatus(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val duplicates: Int = 0,
    val failures: Int = 0,
    val message: String = "",
    val errors: List<String> = emptyList(),
    /** In-book progress for the file currently being imported, e.g. "解析章节 120 / 1248". */
    val detail: String = "",
    val detailFraction: Float = 0f,
    val batchId: Long = 0,
    /** PDFs that look scanned (no fonts): the shelf asks once whether to move them to 画架. */
    val scannedPdfs: List<Pair<String, String>> = emptyList(),
    val folder: Boolean = false,
    val source: String = "",
    /** Files not started when the batch was stopped; duplicates keep their original meaning. */
    val skipped: Int = 0,
    /** Set only for a successful open-with single-file import, so the reader can open that book. */
    val openBookId: String? = null,
) {
    val summary: String get() = "导入 $added · 跳过 ${duplicates + skipped} · 失败 $failures"
    internal fun completed(): ImportStatus = copy(running = false, detail = "", detailFraction = 0f,
        message = summary + (errors.lastOrNull()?.let { "\n$it" } ?: ""))
}

const val FILE_ACCESS_EXPIRED_MESSAGE = "文件访问已失效，重新选择即可恢复进度"
private class SourceAccessException(cause: Throwable) : IllegalStateException(FILE_ACCESS_EXPIRED_MESSAGE, cause)

data class SearchHit(
    val chapterIndex: Int,
    val charOffset: Int,
    val chapterTitle: String,
    val context: String,
)

private const val PARSE_TIMEOUT_MS = 180_000L

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class LibraryRepository(
    private val context: Context,
    private val db: LibraryDatabase,
    val settings: SettingsStore,
) {
    val dao = db.library()
    val books = dao.observeBooks()
    val imports = MutableStateFlow(ImportStatus())
    private val registry = ParserRegistry(listOf(TxtParser(), EpubParser(context.cacheDir), ComicParser(),
        com.lyric.core.DocxParser(context.cacheDir), com.lyric.core.MobiParser(), com.lyric.reader.PdfParser()))
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = File(context.filesDir, "parsed-v1")
    // A FileProvider may grant only transient access. Retain a private source in that case,
    // so clearing the parsed cache or restarting the process cannot break the imported book.
    private val sources = File(context.filesDir, "sources-v1")
    private fun source(row: ShelfBook): ContentSource = File(sources, row.id).takeIf { it.isFile }
        ?.let { FileSource(it, row.name) } ?: UriSource(context, Uri.parse(row.uri), row.name)
    /** Private CBZ copy for comic page decoding (always kept at import). */
    fun comicFile(id: String): File = File(sources, id)
    private val importLock = Mutex()
    private val parseLock = Mutex()
    private val fillScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val incremental = mutableMapOf<String, java.lang.ref.WeakReference<Book>>()
    private val fills = mutableMapOf<String, Job>()
    private fun parts(row: ShelfBook) = File(cacheFile(row).path + ".parts")
    suspend fun startBackground(book: Book) = withContext(Dispatchers.IO) {
        parseLock.withLock {
            val key = incremental.entries.firstOrNull { it.value.get() === book }?.key ?: return@withLock
            startFill(key, book)
        }
    }
    private fun startFill(key: String, book: Book) {
        if (fills[key]?.isActive == true || (book.progressReady && book.chapters.none { it.pending })) return
        fills[key] = fillScope.launch {
            try {
                book.deferred?.complete()
                EpubChapterCache.publishManifest(File(cache, "$key.parts"), book)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { Log.w(ImportTrace.TAG, "EPUB background fill failed; demand reads remain retryable", e) }
        }
    }
    private val memory = BookMemoryCache((Runtime.getRuntime().maxMemory() / 16).coerceAtMost(32L * 1024 * 1024))

    /** Called under importLock, after rows are durable, including cancelled/failed batches. */
    private suspend fun releaseUnusedGrants() {
        val references = dao.all().map { Uri.parse(it.uri) }
        context.contentResolver.persistedUriPermissions.toList().forEach { grant ->
            if (!grantIsReferenced(grant.uri, references)) {
                val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                try { context.contentResolver.releasePersistableUriPermission(grant.uri, flags) }
                catch (e: SecurityException) { Log.i(ImportTrace.TAG, "授权已失效", e) }
            }
        }
    }

    private fun cacheFile(row: ShelfBook): File {
        val options = "layout-v03:${row.encodingOverride.orEmpty()}\u0000${row.chapterRegex.orEmpty()}"
        val fingerprint =
            MessageDigest.getInstance("SHA-256").digest(options.toByteArray()).take(8).joinToString(
                ""
            ) {
                "%02x".format(it)
            }
        return File(cache, "${row.id}-$fingerprint.json")
    }

    fun persist(uri: Uri, grantFlags: Int? = null): Boolean {
        if (uri.scheme == "file") return true
        return try {
            if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) return true
            if (grantFlags != null && (grantFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0 ||
                    grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0)) {
                Log.i(ImportTrace.TAG, "授权为临时读取，将保留私有源副本 authority=${uri.authority}")
                false
            } else {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                Log.i(ImportTrace.TAG, "已保留读取授权 authority=${uri.authority}")
                true
            }
        } catch (e: SecurityException) {
            Log.i(ImportTrace.TAG, "持久授权未授予，继续使用临时读取授权", e)
            false
        } catch (e: Exception) {
            Log.e(ImportTrace.TAG, "持久授权失败，继续尝试读取", e)
            false
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return File(requireNotNull(uri.path)).name
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) cursor.getLong(sizeColumn) else "unknown"
                    Log.i(ImportTrace.TAG, "元数据 authority=${uri.authority} size=$size")
                    if (nameColumn >= 0 && !cursor.isNull(nameColumn)) cursor.getString(nameColumn)?.takeIf { it.isNotBlank() } else null
                } ?: "未命名"
        } catch (e: Exception) {
            Log.e(ImportTrace.TAG, "元数据不可用，继续读取 authority=${uri.authority}", e)
            "未命名"
        }
    }

    @Volatile private var importJob: kotlinx.coroutines.Job? = null
    fun cancelImport() { importJob?.cancel(CancellationException("导入已取消")) }
    fun dismissScanned(id: String) { imports.value = imports.value.copy(scannedPdfs = imports.value.scannedPdfs.filterNot { it.first == id }) }
    @Synchronized fun consumeOpenBook(): String? {
        val id = imports.value.openBookId
        if (id != null) imports.value = imports.value.copy(openBookId = null)
        return id
    }

    /** Clear a completed batch's user-facing message so the snackbar is shown only once. */
    @Synchronized fun consumeImportMessage(batchId: Long) {
        val current = imports.value
        if (!current.running && current.batchId == batchId && current.message.isNotBlank()) {
            imports.value = current.copy(message = "")
        }
    }

    suspend fun importUris(uris: List<Uri>, folder: Boolean = false, grantFlags: Int? = null, openWith: Boolean = false) =
        withContext(Dispatchers.IO) {
            importLock.withLock {
                importJob = currentCoroutineContext()[kotlinx.coroutines.Job]
                val batchId = System.nanoTime()
                imports.value = ImportStatus(running = true, message = "正在扫描本地文件…", batchId = batchId, folder = folder,
                    source = if (folder) uris.firstOrNull()?.lastPathSegment?.substringAfter(':')?.let { "/$it" }.orEmpty() else "")
                var activeFile: String? = null
                try {
                    Log.i(ImportTrace.TAG, "开始导入 count=${uris.size} tree=$folder flags=$grantFlags")
                    val files = mutableListOf<Uri>()
                    val retained = mutableMapOf<Uri, Boolean>()
                    val comicFolders = mutableListOf<Pair<String, List<Pair<String, Uri>>>>()
                    if (folder) {
                        uris.forEach { uri ->
                            val durable = persist(uri, grantFlags)
                            val before = files.size
                            val images = mutableListOf<Pair<String, Uri>>()
                            val paths = mutableMapOf<String, String>()
                            ImportTrace.stage("扫描文件夹") {
                                val queue = ArrayDeque<String>()
                                queue.add(DocumentsContract.getTreeDocumentId(uri))
                                paths[DocumentsContract.getTreeDocumentId(uri)] = ""
                                val visited = mutableSetOf<String>()
                                while (queue.isNotEmpty()) {
                                    currentCoroutineContext().ensureActive()
                                    val directory = queue.removeFirst()
                                    if (!visited.add(directory)) continue
                                    require(visited.size <= 10_000) { "文件夹层级或数量过多" }
                                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, directory)
                                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                                    val cursor = context.contentResolver.query(children, columns, null, null, null)
                                        ?: error("文件夹提供者没有返回扫描结果")
                                    cursor.use { c ->
                                        val idCol = c.getColumnIndexOrThrow(columns[0])
                                        val nameCol = c.getColumnIndex(columns[1])
                                        val mimeCol = c.getColumnIndex(columns[2])
                                        while (c.moveToNext()) {
                                            currentCoroutineContext().ensureActive()
                                            val childId = c.getString(idCol) ?: continue
                                            val name = if (nameCol >= 0) c.getString(nameCol) else null
                                            val mime = if (mimeCol >= 0) c.getString(mimeCol) else null
                                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                                queue.add(childId)
                                                paths[childId] = paths[directory].orEmpty() + name.orEmpty().ifBlank { childId.takeLast(12) } + "/"
                                            } else if (name != null && ComicParser.isImage(name)) {
                                                images += (paths[directory].orEmpty() + name) to
                                                    DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                                            } else if (BookFormats.extension(name.orEmpty()) != null ||
                                                mime in BookFormats.pickerMimeTypes || mime == null || name.isNullOrBlank()) {
                                                val child = DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                                                files += child
                                                retained[child] = durable
                                            }
                                        }
                                    }
                                }
                            }
                            // A folder holding images but no books is one comic (sub-folders = chapters).
                            if (files.size == before && images.isNotEmpty())
                                comicFolders += (runCatching { displayName(uri) }.getOrNull()?.takeIf { it != "未知文件" }
                                    ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "漫画") to images
                        }
                    } else {
                        uris.forEach { retained[it] = persist(it, grantFlags) }
                        files += uris
                    }
                    var status = imports.value.copy(total = files.size + comicFolders.size)
                    imports.value = status
                    var lastBookId: String? = null
                    var lastBookCount = 0
                    comicFolders.forEach { (title, images) ->
                        currentCoroutineContext().ensureActive()
                        activeFile = title
                        status = status.copy(message = title, detail = "", detailFraction = 0f); imports.value = status
                        val result = runCatching {
                            val packed = ImportTrace.stage("打包图片文件夹") { packImages(images) }
                            try { importOne(Uri.fromFile(packed), "$title.cbz", retainSource = true) } finally { packed.delete() }
                        }
                        result.exceptionOrNull()?.let {
                            if (it is CancellationException) throw it
                            Log.e(ImportTrace.TAG, "$title：${it.importReason()}", it)
                        }
                        status = imports.value.copy(done = status.done + 1,
                            added = status.added + if (result.getOrNull()?.added == true) 1 else 0,
                            duplicates = status.duplicates + if (result.getOrNull()?.added == false) 1 else 0,
                            failures = status.failures + if (result.isFailure) 1 else 0,
                            errors = status.errors + listOfNotNull(result.exceptionOrNull()?.let { "$title：${it.importReason()}" }))
                        result.getOrNull()?.let { lastBookId = it.id; lastBookCount += 1 }
                        imports.value = status
                        activeFile = null
                    }
                    files.forEach { uri ->
                        currentCoroutineContext().ensureActive()
                        val name = runCatching { displayName(uri) }.getOrDefault("未知文件")
                        activeFile = name
                        status = status.copy(message = name, detail = "", detailFraction = 0f)
                        imports.value = status
                        val result = runCatching { importOne(uri, name, retainSource = retained[uri] == false, openWith = openWith) }
                        result.exceptionOrNull()?.let {
                            if (it is CancellationException) throw it
                            Log.e(ImportTrace.TAG, "$name：${it.importReason()}", it)
                        }
                        status =
                            imports.value.copy(
                                done = status.done + 1,
                                added = status.added + if (result.getOrNull()?.added == true) 1 else 0,
                                duplicates =
                                    status.duplicates + if (result.getOrNull()?.added == false) 1 else 0,
                                failures = status.failures + if (result.isFailure) 1 else 0,
                                message = result.exceptionOrNull()?.importReason() ?: name,
                                errors = status.errors + listOfNotNull(result.exceptionOrNull()?.let { "$name：${it.importReason()}" }),
                            )
                        result.getOrNull()?.let { lastBookId = it.id; lastBookCount += 1 }
                        imports.value = status
                        activeFile = null
                    }
                    val openBookId = lastBookId.takeIf {
                        openWith && !folder && files.size == 1 && comicFolders.isEmpty() &&
                            status.failures == 0 && lastBookCount == 1 && status.scannedPdfs.isEmpty()
                    }
                    val done = status.completed().copy(openBookId = openBookId)
                    imports.value = if (openWith && done.added == 0 && done.duplicates == 0 && done.failures > 0 &&
                        done.errors.all { it.substringAfter("：", it) == BookFormats.UNSUPPORTED_OPEN_WITH }) {
                        done.copy(message = BookFormats.UNSUPPORTED_OPEN_WITH, errors = emptyList())
                    } else done
                } catch (e: CancellationException) {
                    val status = imports.value
                    val interrupted = if (activeFile != null) 1 else 0
                    imports.value = status.copy(done = status.done + interrupted,
                        failures = status.failures + interrupted,
                        skipped = status.skipped + (status.total - status.done - interrupted).coerceAtLeast(0),
                        errors = status.errors + (activeFile?.let { "$it：" }.orEmpty() + e.importReason())).completed()
                    throw e
                } catch (e: Exception) {
                    imports.value =
                        imports.value.copy(failures = imports.value.failures + 1,
                            errors = imports.value.errors + e.importReason()).completed()
                    Log.e(ImportTrace.TAG, "导入终止", e)
                } finally {
                    withContext(NonCancellable) { releaseUnusedGrants() }
                    importJob = null
                }
            }
        }

    private suspend fun importOne(uri: Uri, name: String, retainSource: Boolean = false, openWith: Boolean = false): ImportOneResult {
        val temp = ImportTrace.stage("创建导入缓存") { File.createTempFile("import-", ".tmp", context.cacheDir) }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            ImportTrace.stage("ContentResolver/读取并复制") {
                UriSource(context, uri, name).open().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var size = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= 300L * 1024 * 1024) { "文件超过 300MB" }
                            if (count > 0) {
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
            // Recognize the bytes before asking Android to inspect bounds. This also accepts
            // opaque SAF filenames/MIME types without probing every EPUB or text as a bitmap.
            val prefix = temp.inputStream().use { it.readBounded(68) }
            val imageExtension = BookFormats.imageExtension(prefix)
            // In-app 「+」 still packs a single image as a one-page CBZ. The system open-with
            // path must not: Lyric is no longer an image viewer, including octet-stream.
            if (imageExtension != null && !openWith) {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(temp.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片已损坏或无法解码" }
                val packed = packImages(listOf("1.$imageExtension" to Uri.fromFile(temp)))
                try {
                    return importOne(Uri.fromFile(packed), "${name.substringBeforeLast('.')}.cbz", retainSource = true)
                } finally { packed.delete() }
            }
            val extension = ImportTrace.stage("识别格式") {
                if (openWith) BookFormats.sniffOpenWith(
                    temp, prefix, name,
                    runCatching { context.contentResolver.getType(uri) }.getOrNull(),
                    uri.lastPathSegment.orEmpty(),
                ) ?: throw FriendlyImportException(BookFormats.UNSUPPORTED_OPEN_WITH)
                else BookFormats.detect(
                    prefix, name,
                    runCatching { context.contentResolver.getType(uri) }.getOrNull(),
                    uri.lastPathSegment.orEmpty(),
                ).let { if (it == "epub" || it == "cbz") BookFormats.zipKind(temp) else it }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it) }
            // Comics are read page by page with random access, so they always keep a private copy.
            var keepCopy = retainSource || extension == "cbz" || extension == "pdf"
            fun retain() = ImportTrace.stage("保存临时授权源文件") {
                if (keepCopy) {
                    check(sources.isDirectory || sources.mkdirs()) { "无法创建源文件目录" }
                    val target = File(sources, id)
                    val staging = File(sources, "$id.tmp")
                    try { temp.copyTo(staging, overwrite = true); check(staging.renameTo(target)) { "无法保存源文件" } }
                    finally { staging.delete() }
                }
            }
            ImportTrace.stage("Room/查询重复") { dao.book(id) }?.let { old ->
                if (old.contentType == ContentType.COMIC.name) keepCopy = true
                retain()
                // Re-import can repair a moved or revoked original without discarding its progress.
                ImportTrace.stage("Room/更新重复书籍") {
                    dao.update(old.copy(uri = uri.toString(), name = name, extension = extension, invalid = false))
                }
                return ImportOneResult(added = false, id = id)
            }
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            var lastEmit = 0L
            val parsed = ImportTrace.stage("解析书籍") {
                ImportProgress.run(PARSE_TIMEOUT_MS, { job?.isActive == false }, { stage, done, total ->
                    val now = System.nanoTime()
                    if (done == total || now - lastEmit > 100_000_000L) {
                        lastEmit = now
                        imports.value = imports.value.copy(detail = "$stage $done / $total",
                            detailFraction = if (total > 0) done.toFloat() / total else 0f)
                    }
                }) {
                    registry
                        .parser(extension)
                        .parse(FileSource(temp, name))
                }
            }
            // Image EPUBs are read page by page out of the ZIP, like CBZ.
            if (parsed.contentType == ContentType.COMIC) keepCopy = true
            imports.value = imports.value.copy(detail = "写入缓存…", detailFraction = 1f)
            val cover = ImportTrace.stage("保存封面") {
                if (extension == "pdf") {
                    val directory = File(context.filesDir, "covers").apply { check(isDirectory || mkdirs()) }
                    File(directory, "$id.jpg").apply { com.lyric.reader.PdfPageSource.thumbnail(temp, 720)?.let { writeBytes(it) } }
                        .takeIf { it.length() > 0 }?.absolutePath
                } else if (parsed.contentType == ContentType.COMIC) parsed.coverResource?.let { entry ->
                    val directory = File(context.filesDir, "covers").apply { check(isDirectory || mkdirs()) }
                    File(directory, "$id.${entry.substringAfterLast('.', "jpg").lowercase()}").apply {
                        ComicPages.thumbnail(temp, entry, 720)?.let { writeBytes(it) }
                    }.takeIf { it.length() > 0 }?.absolutePath
                } else
                parsed.coverResource?.let { path -> parsed.resources[path]?.let { encoded ->
                    val directory = File(context.filesDir, "covers").apply { check(isDirectory || mkdirs()) }
                    File(directory, "$id.${path.substringAfterLast('.', "jpg")}").apply {
                        writeBytes(java.util.Base64.getDecoder().decode(encoded))
                    }.absolutePath
                } }
            }
            val row =
                ShelfBook(
                    id,
                    uri.toString(),
                    name,
                    parsed.title,
                    parsed.author,
                    extension,
                    contentType = parsed.contentType.name,
                    totalChars = parsed.totalChars,
                    chapterCount = parsed.chapters.size,
                    encoding = parsed.encoding,
                    excerpt = parsed.chapters.first().text.take(80),
                    contentRevision = 4,
                    coverPath = cover,
                ).let { if (extension == "pdf") it.copy(section = ShelfSection.BOOK.name) else it }
            try {
                retain()
                ImportTrace.stage("写入解析缓存") { parseLock.withLock { writeCache(row, parsed) } }
                ImportTrace.stage("Room/插入书架") {
                    // 0.5.1: a removed book's progress survives; re-import puts it back on the shelf where it was.
                    val kept = dao.progress(id)
                    dao.insert(kept?.let {
                        val at = parsed.clamp(com.lyric.core.Locator(it.chapterIndex, it.charOffset, it.page))
                        row.copy(lastRead = it.updatedAt, percent = parsed.percent(at),
                            chapterTitle = parsed.chapters[at.chapterIndex].title, excerpt = parsed.excerpt(at, 80))
                    } ?: row)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    cacheFile(row).delete()
                    File(cacheFile(row).path + ".tmp").delete()
                    File(sources, id).delete()
                    cover?.let { File(it).delete() }
                }
                throw e
            }
            if (extension == "pdf" && com.lyric.reader.PdfPageSource.looksScanned(File(sources, id)))
                imports.value = imports.value.copy(scannedPdfs = imports.value.scannedPdfs + (id to parsed.title))
            Log.i(ImportTrace.TAG, "导入完成 id=$id chapters=${parsed.chapters.size} chars=${parsed.totalChars}")
            return ImportOneResult(added = true, id = id)
        } finally {
            temp.delete()
        }
    }

    /** Copies folder images into an uncompressed ZIP (images are already compressed). */
    private suspend fun packImages(images: List<Pair<String, Uri>>): File {
        val out = File.createTempFile("comic-folder-", ".cbz", context.cacheDir)
        try {
            java.util.zip.ZipOutputStream(out.outputStream().buffered(1 shl 16)).use { zip ->
                zip.setLevel(0)
                val seen = mutableSetOf<String>()
                images.sortedWith(compareBy(com.lyric.core.NaturalOrder) { it.first }).forEachIndexed { i, (path, uri) ->
                    currentCoroutineContext().ensureActive()
                    ImportProgress.tick("打包图片", i + 1, images.size)
                    val name = if (seen.add(path)) path else "${i}_$path"
                    zip.putNextEntry(java.util.zip.ZipEntry(name).apply { time = 315532800000L })
                    (context.contentResolver.openInputStream(uri) ?: error("无法读取 $path")).use { it.copyTo(zip, 1 shl 16) }
                    zip.closeEntry()
                }
            }
            return out
        } catch (e: Throwable) { out.delete(); throw e }
    }

    private suspend fun redetectComic(row: ShelfBook): Pair<ShelfBook, Book>? {
        val kept = File(sources, row.id)
        val file = if (kept.isFile) kept else File.createTempFile("redetect-", ".epub", context.cacheDir).also { t ->
            source(row).open().use { input -> t.outputStream().use { input.copyTo(it, 1 shl 16) } }
        }
        try {
            val book = runCatching { ImageEpub.detect(file, row.name) }.getOrNull()
            if (book == null) { dao.update(row.copy(contentRevision = 4)); return null }
            check(sources.isDirectory || sources.mkdirs()) { "无法创建源文件目录" }
            if (file != kept) { file.copyTo(File(sources, "${row.id}.tmp"), true); check(File(sources, "${row.id}.tmp").renameTo(kept)) }
            val cover = book.coverResource?.let { entry ->
                val directory = File(context.filesDir, "covers").apply { check(isDirectory || mkdirs()) }
                File(directory, "${row.id}.${entry.substringAfterLast('.', "jpg").lowercase()}").apply {
                    ComicPages.thumbnail(kept, entry, 720)?.let { writeBytes(it) }
                }.takeIf { it.length() > 0 }?.absolutePath
            } ?: row.coverPath
            val updated = row.copy(contentType = ContentType.COMIC.name, section = ShelfSection.COMIC.name, contentRevision = 4, totalChars = book.totalChars,
                chapterCount = book.chapters.size, excerpt = "", coverPath = cover)
            writeCache(updated, book)
            // Text offsets mean nothing in a comic: restart at the first page, keep timestamps.
            dao.progress(row.id)?.let { dao.saveProgress(it.copy(chapterIndex = 0, charOffset = 0, page = 0, contentRevision = 3)) }
            dao.update(updated)
            memory[cacheFile(updated).name] = book
            dao.invalid(row.id, false)
            Log.i(ImportTrace.TAG, "图片 EPUB 重新识别为漫画 id=${row.id} pages=${book.chapters.sumOf { it.content.size }}")
            return updated to book
        } finally { if (file != kept) file.delete() }
    }

    private fun writeCache(row: ShelfBook, book: Book) {
        check(cache.isDirectory || cache.mkdirs()) { "无法创建解析缓存目录" }
        if (row.extension == "epub" && row.contentRevision >= 4 && book.contentType == ContentType.TEXT) {
            EpubChapterCache.seed(parts(row), book)
            cacheFile(row).delete()
            memory.remove(cacheFile(row).name)
            return
        }
        val file = cacheFile(row)
        val tmp = File(file.path + ".tmp")
        // Stream instead of building a ~50 MB String (x2 bytes) for large web novels.
        tmp.outputStream().buffered(1 shl 16).use { json.encodeToStream(Book.serializer(), book, it) }
        check(tmp.renameTo(file)) { "无法写入解析缓存" }
        memory.remove(file.name)
    }

    /** Shelf metadata must never perform reader upgrades or rewrite reading anchors. */
    suspend fun comicSummary(id: String): Pair<Book, ProgressRow?> = withContext(Dispatchers.IO) {
        parseLock.withLock {
            val row = dao.book(id) ?: error("书籍已移出收藏")
            require(row.contentType == ContentType.COMIC.name)
            val key = cacheFile(row).name
            val book = memory[key] ?: runCatching {
                cacheFile(row).inputStream().buffered(1 shl 16).use { json.decodeFromStream(Book.serializer(), it) }
            }.getOrNull() ?: registry.parser(row.extension).parse(source(row)).also { writeCache(row, it) }
            memory[key] = book
            book to dao.progress(id)
        }
    }

    suspend fun load(id: String, force: Boolean = false, requested: Locator? = null): Pair<ShelfBook, Book> =
        withContext(Dispatchers.IO) {
            parseLock.withLock {
                var row = dao.book(id) ?: error("书籍已移出书架")
                if (com.lyric.reader.PerfLog.enabled) {
                    val size = (source(row) as? FileContentSource)?.file?.length() ?: runCatching {
                        context.contentResolver.openAssetFileDescriptor(Uri.parse(row.uri), "r")?.use { it.length }
                    }.getOrNull() ?: -1L
                    com.lyric.reader.PerfLog.source(row.extension, size)
                    com.lyric.reader.PerfLog.stage("parse_toc_start")
                }
                val oldProgress = dao.progress(id)
                val oldMarks = if (row.extension == "epub") dao.bookmarks(id).first() else emptyList()
                val upgrade = row.extension == "epub" && (row.contentRevision < 3 ||
                    (oldProgress != null && oldProgress.contentRevision < 3) || oldMarks.any { it.contentRevision < 3 })
                try {
                    // Even with a parsed cache, check the source so revoked/missing files are
                    // explicit.
                    try { ImportTrace.stage("打开阅读源") { source(row).open().use {} } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { throw SourceAccessException(e) }
                    // Books imported before 0.4.0-p0.3 as text may be image-only EPUBs: re-detect once.
                    if (row.extension == "epub" && row.contentType == ContentType.TEXT.name && row.contentRevision < 4)
                        redetectComic(row)?.let { return@withLock it }
                    val key = cacheFile(row).name
                    if (force) {
                        fills.remove(key)?.cancelAndJoin()
                        incremental.remove(key)
                        parts(row).deleteRecursively()
                    }
                    val useIncremental = row.extension == "epub" && row.contentType == ContentType.TEXT.name && !upgrade && !force
                    val partial = if (useIncremental && !force) incremental[key]?.get() else null
                    val parsed =
                        if (!force && !upgrade)
                            partial ?: memory[key]
                                ?: runCatching {
                                        if (useIncremental && (File(parts(row), "files.json").isFile || File(parts(row), "book.json").isFile)) error("Use chapter cache")
                                        cacheFile(row).inputStream().buffered(1 shl 16).use {
                                            json.decodeFromStream(Book.serializer(), it)
                                        }
                                    }
                                    .getOrNull()
                        else null
                    val book =
                        parsed
                            ?: if (useIncremental) {
                                val directory = parts(row)
                                try { EpubParser(context.cacheDir).openIncremental(source(row), directory,
                                    (requested ?: oldProgress?.let { Locator(it.chapterIndex, it.charOffset) } ?: Locator()).chapterIndex) }
                                catch (e: Exception) { directory.deleteRecursively(); throw e }
                            } else registry
                                .parser(row.extension)
                                .parse(
                                    source(row),
                                    row.encodingOverride,
                                    row.chapterRegex,
                                )
                                .also { writeCache(row, it) }
                    com.lyric.reader.PerfLog.stage("parse_toc_ready")
                    if (upgrade) {
                        val upgraded = row.copy(contentRevision = 3, totalChars = book.totalChars,
                            chapterCount = book.chapters.size)
                        fun translate(chapter: Int, offset: Int): Locator {
                            val c = chapter.coerceIn(0, book.chapters.lastIndex)
                            return book.clamp(Locator(c, book.chapters[c].upgradeLegacyOffset(offset)))
                        }
                        db.withTransaction {
                            dao.progress(id)?.takeIf { it.contentRevision < 3 }?.let { old ->
                                val at = translate(old.chapterIndex, old.charOffset)
                                // Preserve timestamps so backup conflict resolution is unchanged.
                                dao.saveProgress(old.copy(chapterIndex = at.chapterIndex, charOffset = at.charOffset, contentRevision = 3))
                            }
                            dao.bookmarks(id).first().filter { it.contentRevision < 3 }.forEach { old ->
                                val at = translate(old.chapterIndex, old.charOffset)
                                dao.updateBookmark(old.copy(chapterIndex = at.chapterIndex, charOffset = at.charOffset, contentRevision = 3))
                            }
                            dao.update(upgraded)
                        }
                        row = upgraded
                    }
                    if (book.deferred != null) {
                        incremental[key] = java.lang.ref.WeakReference(book)
                        book.ensureChapter((requested ?: oldProgress?.let { Locator(it.chapterIndex, it.charOffset) } ?: Locator()).chapterIndex)
                    } else memory[key] = book
                    dao.invalid(id, false)
                    row to book
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    dao.invalid(id, true)
                    if (e is SourceAccessException) throw e
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
                    runCatching { source(row).open().use {} }
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
                        source(candidate),
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
                dao.saveProgress(ProgressRow(id, loc.chapterIndex, loc.charOffset, contentRevision = 3))
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
                    dao.updateBookmark(
                        mark.copy(
                            chapterIndex = m.chapterIndex,
                            charOffset = m.charOffset,
                            chapterTitle = parsed.chapters[m.chapterIndex].title,
                            contentRevision = 3,
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
                    dao.saveProgress(ProgressRow(id, loc.chapterIndex, loc.charOffset, loc.page, contentRevision = 3))
                    if (book.progressReady && book.percent(loc) >= .99f) dao.finish(FinishedBook(id, book.title, System.currentTimeMillis()))
                    if (book.progressReady) dao.reading(
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
            importLock.withLock {
                parseLock.withLock {
                    fills.keys.filter { it.startsWith("$id-") }.forEach { key ->
                        fills.remove(key)?.cancelAndJoin(); incremental.remove(key)
                    }
                    dao.remove(id)
                    File(sources, id).delete()
                    cache
                        .listFiles()
                        ?.filter { it.name.startsWith("$id-") }
                        ?.forEach {
                            it.deleteRecursively()
                            memory.remove(it.name)
                        }
                }
                releaseUnusedGrants()
            }
        }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearCaches() = withContext(Dispatchers.IO) {
        importLock.withLock {
            parseLock.withLock {
                val live = incremental.values.mapNotNull { it.get() }
                fills.values.forEach { it.cancelAndJoin() }
                // Keep the currently open Book usable after its backing cache is removed.
                live.forEach { it.deferred?.complete(paced = false) }
                fills.clear()
                incremental.clear()
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
    }

    fun search(book: Book, query: String): Flow<SearchHit> =
        flow {
                if (query.isBlank()) return@flow
                for (i in book.chapters.indices) {
                    val chapter = book.ensureChapter(i)
                    yield()
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
