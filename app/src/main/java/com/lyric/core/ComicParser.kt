package com.lyric.core

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile

/**
 * CBZ / ZIP of images. Pages are referenced by ZIP entry name and decoded lazily by the reader;
 * nothing but a 64 KB header per image is read at import, so 500+ page archives stay fast and small.
 */
class ComicParser : BookParser {
    override val extensions = setOf("cbz", "comic")

    override fun parse(source: ContentSource, encoding: String?, chapterRegex: String?): Book {
        val file = (source as? FileContentSource)?.file ?: error("漫画需要本地副本")
        var first: Exception? = null
        for (charset in listOf("UTF-8", "GBK", "Cp437")) {
            try { return parse(file, source.displayName, Charset.forName(charset)) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (first == null) first = e else first.addSuppressed(e)
            }
        }
        throw requireNotNull(first)
    }

    private fun parse(file: File, displayName: String, charset: Charset): Book = ZipFile(file, charset).use { zip ->
        val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }
            .filter { isImage(it) && !hidden(it) }.toList()
        require(names.isNotEmpty()) { "压缩包里没有可识别的图片（支持 jpg/png/webp/gif/bmp）" }
        val sorted = names.sortedWith(NaturalOrder)
        val groups = group(sorted)
        var done = 0
        val chapters = groups.mapIndexed { index, (title, entries) ->
            Chapter(index, title, content = entries.map { name ->
                ImportProgress.tick("读取图片尺寸", ++done, sorted.size)
                val size = runCatching { zip.getInputStream(zip.getEntry(name)).use { ImageHeader.size(it) } }.getOrNull()
                ContentUnit.ImagePage(name, size?.first, size?.second)
            })
        }
        Book(displayName.substringBeforeLast('.').ifBlank { "漫画" }, "", chapters,
            contentType = ContentType.COMIC, coverResource = sorted.first())
    }

    companion object {
        private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExt
        private fun hidden(name: String) = name.split('/').any { it.startsWith(".") || it == "__MACOSX" }

        /** Sub-folders become chapters; otherwise a shared filename prefix (e.g. "ch01_003") does. */
        fun group(sorted: List<String>): List<Pair<String, List<String>>> {
            val byDir = sorted.groupBy { it.substringBeforeLast('/', "") }
            if (byDir.size > 1) return byDir.entries.sortedWith(compareBy(NaturalOrder) { it.key })
                .map { (dir, list) -> dir.substringAfterLast('/').ifBlank { "正文" } to list }
            val prefix = Regex("^(.*?(?:ch|chapter|第|vol|v|c)[ _.-]?\\d+)[ _.\\-]+\\d+", RegexOption.IGNORE_CASE)
            val byPrefix = sorted.groupBy { prefix.find(it.substringAfterLast('/'))?.groupValues?.get(1) }
            if (byPrefix.size > 1 && null !in byPrefix.keys) return byPrefix.entries.map { it.key!! to it.value }
            return listOf("正文" to sorted)
        }
    }
}

/** Natural ordering: "2.jpg" before "10.jpg", case-insensitive, digit runs compared numerically. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ei; j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** Width/height from the first bytes of PNG / JPEG / GIF / WebP / BMP without decoding. */
object ImageHeader {
    fun size(input: InputStream): Pair<Int, Int>? = size(input.readBounded(65536))
    fun size(b: ByteArray): Pair<Int, Int>? {
        fun u8(i: Int) = b[i].toInt() and 0xff
        fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun be32(i: Int) = (be16(i) shl 16) or be16(i + 2)
        fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
        if (b.size >= 24 && u8(0) == 0x89 && b[1] == 'P'.code.toByte()) return be32(16) to be32(20)
        if (b.size >= 10 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte()) return le16(6) to le16(8)
        if (b.size >= 26 && b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte()) return le32(18) to kotlin.math.abs(le32(22))
        if (b.size >= 30 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP") {
            return when (String(b, 12, 4, Charsets.US_ASCII)) {
                "VP8 " -> (le16(26) and 0x3fff) to (le16(28) and 0x3fff)
                "VP8L" -> { val v = le32(21); ((v and 0x3fff) + 1) to (((v shr 14) and 0x3fff) + 1) }
                "VP8X" -> ((u8(24) or (u8(25) shl 8) or (u8(26) shl 16)) + 1) to ((u8(27) or (u8(28) shl 8) or (u8(29) shl 16)) + 1)
                else -> null
            }
        }
        if (b.size >= 4 && u8(0) == 0xff && u8(1) == 0xd8) {
            var i = 2
            while (i + 9 < b.size) {
                if (u8(i) != 0xff) { i++; continue }
                val marker = u8(i + 1)
                if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) { i += 2; continue }
                val len = be16(i + 2)
                if (marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc)
                    return be16(i + 7) to be16(i + 5)
                i += 2 + len
            }
        }
        return null
    }
}
