package com.lyric.core

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal const val MAX_ARCHIVE_ENTRIES = 10_000
internal class ProtectedBookException : FriendlyImportException("这本书有版权保护，无法导入")
internal class ArchiveLimitException : FriendlyImportException("压缩包文件条目过多（最多 10,000 条）")

/** Retry only malformed input/legacy encodings, never cancellation, deadlines or policy failures. */
internal fun Throwable.rethrowImportAbort() {
    if (this is CancellationException || this is TimeoutException || this is FriendlyImportException) throw this
    cause?.takeIf { it !== this }?.rethrowImportAbort()
}
internal inline fun <T> importAttempt(block: () -> T): Result<T> = try { Result.success(block()) }
catch (e: Exception) { e.rethrowImportAbort(); Result.failure(e) }

/** Read central flags before ZipFile (which can reject encryption before exposing entries).
 * Includes directories, supports ZIP64, and never reads compressed payloads into memory. */
internal fun checkArchive(file: File) {
    RandomAccessFile(file, "r").use { input ->
        fun u16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun u64(): Long = u32() or (u32() shl 32)
        val tailStart = (input.length() - 65557).coerceAtLeast(0)
        var end = input.length() - 22
        var found = false
        while (end >= tailStart) {
            ImportProgress.check()
            input.seek(end)
            if (u32() == 0x06054b50L) {
                input.seek(end + 20)
                if (end + 22 + u16() == input.length()) { found = true; break }
            }
            end--
        }
        require(found) { "压缩包目录已损坏" }
        input.seek(end + 10)
        var count = u16().toLong()
        input.seek(end + 16)
        var offset = u32()
        if (count == 0xffffL || offset == 0xffffffffL) {
            input.seek(end - 20)
            require(u32() == 0x07064b50L) { "ZIP64 目录已损坏" }
            u32()
            val zip64 = u64()
            input.seek(zip64)
            require(u32() == 0x06064b50L) { "ZIP64 目录已损坏" }
            input.seek(zip64 + 32)
            count = u64()
            u64()
            offset = u64()
        }
        if (count > MAX_ARCHIVE_ENTRIES) throw ArchiveLimitException()
        require(count >= 0 && offset >= 0) { "压缩包目录已损坏" }
        input.seek(offset)
        repeat(count.toInt()) {
            ImportProgress.check()
            val start = input.filePointer
            require(u32() == 0x02014b50L) { "压缩包目录已损坏" }
            input.seek(start + 8)
            if (u16() and 0x41 != 0) throw ProtectedBookException()
            input.seek(start + 28)
            val length = u16() + u16() + u16()
            input.seek(start + 46 + length)
        }
    }
}

/** Only the two standardized font obfuscation algorithms are safe to ignore. */
internal object EpubEncryption {
    fun fontPaths(manifest: Collection<org.jsoup.nodes.Element>, resolve: (String) -> String): Set<String> =
        manifest.filter {
            it.attr("media-type").contains("font", true) ||
                it.attr("href").substringBefore('?').substringAfterLast('.').lowercase() in setOf("ttf", "otf", "woff", "woff2")
        }.map { resolve(it.attr("href")) }.toSet()

    fun inspect(xml: String?, fonts: Set<String>): Set<String> {
        if (xml == null) return emptySet()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val ignored = mutableSetOf<String>()
        doc.getAllElements().filter { it.tagName().substringAfter(':') == "EncryptedData" }.forEach { data ->
            ImportProgress.check()
            val elements = data.getAllElements()
            val algorithm = elements.firstOrNull { it.tagName().substringAfter(':') == "EncryptionMethod" }?.attr("Algorithm")
            val uri = elements.firstOrNull { it.tagName().substringAfter(':') == "CipherReference" }?.attr("URI")
            val path = uri?.let { EpubPaths.resolve("", it) }
            if (algorithm !in setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC") || path !in fonts)
                throw ProtectedBookException()
            ignored += requireNotNull(path)
        }
        return ignored
    }
}
