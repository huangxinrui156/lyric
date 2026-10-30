package com.lyric.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class BackupData(
    val version: Int = 1,
    val books: List<ShelfBook>,
    val progress: List<ProgressRow>,
    val bookmarks: List<BookmarkRow>,
    val sessions: List<ReadingSession>,
    val finished: List<FinishedBook>,
    val settings: SettingsSnapshot = SettingsSnapshot(),
)

data class BackupPreview(val data: BackupData, val fonts: Map<String, ByteArray>, val matched: Int) {
    val missing get() = data.books.size - matched
}

/** Originals and URI grants are never exported. Hash IDs reconnect imported files on any device. */
class BackupManager(private val context: Context, private val db: LibraryDatabase, private val settings: SettingsStore) {
    private val dao = db.library()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    val filename get() = "lyric-backup-${LocalDate.now()}.zip"

    suspend fun export(uri: Uri, includeFonts: Boolean) = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { export(it, includeFonts) }
    }

    suspend fun export(output: OutputStream, includeFonts: Boolean) = withContext(Dispatchers.IO) {
        val snapshot = settings.snapshot()
        val data = db.withTransaction {
            BackupData(books = dao.all().map { it.copy(uri = "", coverPath = null) },
                progress = dao.allProgress(), bookmarks = dao.allBookmarks(), sessions = dao.sessions(),
                finished = dao.finished(), settings = snapshot)
        }
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("database.json"))
            val database = json.encodeToJsonElement(data).jsonObject.filterKeys { it != "settings" }
            zip.write(JsonObject(database).toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("settings.json"))
            zip.write(json.encodeToString(SettingsSnapshot.serializer(), snapshot).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            if (includeFonts) File(context.filesDir, "fonts").listFiles()?.filter { it.isFile }?.forEach { file ->
                zip.putNextEntry(ZipEntry("fonts/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    suspend fun preview(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        requireNotNull(context.contentResolver.openInputStream(uri)).use { preview(it) }
    }

    suspend fun preview(input: InputStream): BackupPreview = withContext(Dispatchers.IO) {
        var raw: String? = null
        var rawSettings: String? = null
        val fonts = linkedMapOf<String, ByteArray>()
        val names = hashSetOf<String>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                require(names.size < 1000 && names.add(entry.name)) { "备份条目过多或重复" }
                val name = entry.name
                require(name == "database.json" || name == "settings.json" || Regex("fonts/[A-Za-z0-9_.-]+\\.(ttf|otf)", RegexOption.IGNORE_CASE).matches(name)) { "无效备份路径" }
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n = zip.read(buf)
                while (n != -1) {
                    total += n
                    require(total <= 128L * 1024 * 1024 && out.size() + n <= 32 * 1024 * 1024) { "备份过大" }
                    out.write(buf, 0, n)
                    n = zip.read(buf)
                }
                if (name == "database.json") raw = out.toString("UTF-8")
                else if (name == "settings.json") rawSettings = out.toString("UTF-8")
                else fonts[name.substringAfter('/')] = out.toByteArray()
                entry = zip.nextEntry
            }
        }
        val database = requireNotNull(raw) { "缺少数据库 JSON" }
        require(rawSettings != null || "settings" in json.parseToJsonElement(database).jsonObject) { "缺少设置 JSON" }
        val decoded = json.decodeFromString<BackupData>(database)
        val data = rawSettings?.let { decoded.copy(settings = json.decodeFromString<SettingsSnapshot>(it)) } ?: decoded
        require(data.version == 1) { "不支持的备份版本" }
        settings.validate(data.settings)
        require(data.books.map { it.id }.distinct().size == data.books.size) { "书籍哈希重复" }
        require(data.books.all { Regex("[a-f0-9]{64}").matches(it.id) }) { "书籍哈希无效" }
        require(data.sessions.all { it.durationSec >= 0 && it.chars >= 0 && it.endAt >= it.startAt }) { "阅读记录无效" }
        require(data.bookmarks.map { it.id }.distinct().size == data.bookmarks.size) { "书签编号重复" }
        val current = dao.all().filter { !it.invalid && readable(it) }.map { it.id }.toSet()
        BackupPreview(data, fonts, data.books.count { it.id in current })
    }

    private fun readable(book: ShelfBook) = runCatching {
        UriSource(context, Uri.parse(book.uri), book.name).open().use { }
    }.isSuccess

    suspend fun restore(preview: BackupPreview) = withContext(Dispatchers.IO) {
        val data = preview.data
        settings.validate(data.settings)
        // Validate every font before touching saved data. Paths are always locally generated.
        val fontDir = File(context.filesDir, "fonts").apply { mkdirs() }
        val staged = mutableListOf<Pair<File, String>>()
        try {
            preview.fonts.forEach { (name, bytes) ->
                val file = File.createTempFile("restore-", ".${name.substringAfterLast('.')}", context.cacheDir)
                staged += file to name
                file.writeBytes(bytes)
                android.graphics.Typeface.createFromFile(file)
            }
            val fontNames = mutableMapOf<String, String>()
            staged.forEach { (file, name) ->
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                val target = File(fontDir, "$hash.${name.substringAfterLast('.')}")
                file.copyTo(target, overwrite = true)
                fontNames[name] = target.absolutePath
            }
            val strings = data.settings.strings.mapValues { (_, raw) ->
                val fields = json.parseToJsonElement(raw).jsonObject.toMutableMap()
                fields["font"]?.jsonPrimitive?.content?.takeIf { it.startsWith('/') }?.let { path ->
                    fields["font"] = JsonPrimitive(fontNames[File(path).name] ?: "serif")
                }
                JsonObject(fields).toString()
            }
            val bookmarks = dao.allBookmarks()
            val current = dao.all().associateBy { it.id }
            db.withTransaction {
                data.books.forEach { saved ->
                    val local = current[saved.id]
                    if (local == null) dao.insert(saved.copy(uri = "", invalid = true, coverPath = null))
                    else if (saved.lastRead > local.lastRead) dao.update(local.copy(lastRead = saved.lastRead,
                        percent = saved.percent, chapterTitle = saved.chapterTitle, excerpt = saved.excerpt))
                }
                data.progress.forEach { p ->
                    if (dao.book(p.bookId) != null && (dao.progress(p.bookId)?.updatedAt ?: -1) < p.updatedAt) dao.saveProgress(p)
                }
                val keys = bookmarks.map { Triple(it.bookId, it.createdAt, "${it.chapterIndex}:${it.excerpt}") }.toMutableSet()
                data.bookmarks.forEach { m ->
                    if (keys.add(Triple(m.bookId, m.createdAt, "${m.chapterIndex}:${m.excerpt}")))
                        dao.addBookmark(m.copy(id = 0))
                }
                data.sessions.forEach {
                    dao.addSession(it)
                    dao.extendSession(it.id, it.endAt, it.durationSec, it.chars)
                }
                data.finished.forEach { dao.finish(it) }
            }
            settings.restore(data.settings.copy(strings = strings))
        } finally { staged.forEach { it.first.delete() } }
    }
}
