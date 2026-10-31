package com.lyric.core

import java.io.ByteArrayOutputStream
import java.io.File

class DrmProtectedException : FriendlyImportException("这本书有版权保护，无法导入")

/** Non-DRM Kindle books: MOBI6 (PalmDOC) and KF8/AZW3. HUFF/CDIC compression is rejected clearly. */
class MobiParser : BookParser {
    override val extensions = setOf("mobi", "azw3", "azw", "prc")

    override fun parse(source: ContentSource, encoding: String?, chapterRegex: String?): Book {
        val bytes = ImportTrace.stage("MOBI/读取文件") {
            if (source is FileContentSource) {
                require(source.file.length() <= 300L * 1024 * 1024) { "文件超过 300MB" }
                source.file.readBytes()
            } else source.open().use { it.readBytes() }
        }
        return parseBytes(bytes, source.displayName)
    }

    private class Db(val data: ByteArray) {
        val type = String(data, 60, 8, Charsets.ISO_8859_1)
        val offsets: IntArray = run {
            val n = u16(76)
            require(n in 1..65535 && data.size >= 78 + n * 8) { "PalmDB 记录表损坏" }
            IntArray(n) { u32(78 + it * 8) }
        }
        fun u16(o: Int) = ((data[o].toInt() and 255) shl 8) or (data[o + 1].toInt() and 255)
        fun u32(o: Int) = (u16(o) shl 16) or u16(o + 2)
        fun record(i: Int): ByteArray {
            require(i in offsets.indices) { "记录 $i 不存在" }
            val start = offsets[i]; val end = if (i + 1 < offsets.size) offsets[i + 1] else data.size
            require(start in 0..end && end <= data.size) { "记录 $i 越界" }
            return data.copyOfRange(start, end)
        }
    }

    private fun ByteArray.u16(o: Int) = if (o + 2 > size) 0 else ((this[o].toInt() and 255) shl 8) or (this[o + 1].toInt() and 255)
    private fun ByteArray.u32(o: Int) = if (o + 4 > size) 0 else (u16(o) shl 16) or u16(o + 2)

    fun parseBytes(data: ByteArray, displayName: String): Book {
        require(data.size > 78) { "文件过短，不是有效的 MOBI/AZW3" }
        val db = ImportTrace.stage("MOBI/读取 PalmDB") { Db(data) }
        require(db.type == "BOOKMOBI" || db.type == "TEXtREAd") { "不是 MOBI/AZW3 文件（类型 ${db.type.trim()}）" }
        var base = 0
        var rec0 = db.record(0)
        var exth = exth(rec0)
        // Prefer the KF8 half of a combined file: it carries the richer markup.
        exth[121]?.let { b -> val boundary = b.u32(0); if (boundary in 1 until db.offsets.size - 1) runCatching {
            val r = db.record(boundary + 1)
            if (String(r, 16, 4, Charsets.ISO_8859_1) == "MOBI") { base = boundary + 1; rec0 = r; exth = exth(r) }
        } }
        val compression = rec0.u16(0)
        val textRecords = rec0.u16(8)
        val encryption = rec0.u16(12)
        if (encryption != 0) throw DrmProtectedException()
        val isMobi = rec0.size >= 20 && String(rec0, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        val headerLength = if (isMobi) rec0.u32(20) else 0
        val charset = if (isMobi && rec0.u32(28) == 65001) Charsets.UTF_8 else charset("windows-1252")
        val version = if (isMobi) rec0.u32(36) else 0
        val firstImage = if (isMobi) rec0.u32(108).let { if (it == -1 || it == 0xFFFFFFFF.toInt()) -1 else it } else -1
        val extraFlags = if (isMobi && headerLength >= 0xE4) rec0.u16(16 + 0xE2) else 0
        val title = exth[503]?.toString(charset)?.ifBlank { null }
            ?: if (isMobi) runCatching { String(rec0, rec0.u32(84), rec0.u32(88), charset) }.getOrNull()?.ifBlank { null } else null
        val author = exth[100]?.toString(charset).orEmpty()
        val raw = ImportTrace.stage("MOBI/解压正文") {
            val out = ByteArrayOutputStream()
            for (i in 1..textRecords) {
                if (i % 50 == 0) ImportProgress.tick("解压正文", i, textRecords)
                val record = db.record(base + i)
                val body = record.copyOf(record.size - trailingSize(record, extraFlags))
                when (compression) {
                    1 -> out.write(body)
                    2 -> out.write(palmDoc(body))
                    17480 -> error("不支持的压缩格式（HUFF/CDIC）")
                    else -> error("未知的压缩方式 $compression")
                }
            }
            out.toByteArray()
        }
        val images = LinkedHashMap<String, ByteArray>()
        val imageIndex = HashMap<Int, String>()
        if (firstImage > 0) ImportTrace.stage("MOBI/读取图片") {
            var n = 1
            var r = base + firstImage
            while (r < db.offsets.size) {
                val rec = runCatching { db.record(r) }.getOrNull() ?: break
                val ext = BookFormats.imageExtension(rec.copyOf(minOf(12, rec.size)))
                if (ext != null) { val path = "images/%05d.$ext".format(n); images[path] = rec; imageIndex[n] = path }
                n++; r++
            }
        }
        val cover = exth[201]?.u32(0)?.let { imageIndex[it + 1] } ?: imageIndex[1]
        val text = ImportTrace.stage("MOBI/解码文本") { String(raw, charset) }
        val kf8 = version >= 8
        val parts = ImportTrace.stage("MOBI/拆分章节") { if (kf8) splitKf8(text, imageIndex) else splitMobi6(raw, charset, imageIndex) }
        val anchors = parts.second
        return ImportTrace.stage("MOBI/生成章节") {
            HtmlBook.build(title?.trim() ?: displayName.substringBeforeLast('.'), author.trim(), parts.first, images, cover) { found ->
                anchors.mapNotNull { (label, id) -> found[id]?.let { TocEntry(label, it.chapterIndex, it.charOffset) } }
            }
        }
    }

    private fun exth(rec0: ByteArray): Map<Int, ByteArray> {
        if (rec0.size < 24 || String(rec0, 16, 4, Charsets.ISO_8859_1) != "MOBI") return emptyMap()
        if (rec0.u32(0x80) and 0x40 == 0) return emptyMap()
        val start = 16 + rec0.u32(20)
        if (start + 12 > rec0.size || String(rec0, start, 4, Charsets.ISO_8859_1) != "EXTH") return emptyMap()
        val out = HashMap<Int, ByteArray>()
        var p = start + 12
        repeat(rec0.u32(start + 8)) {
            if (p + 8 > rec0.size) return out
            val type = rec0.u32(p); val len = rec0.u32(p + 4)
            if (len < 8 || p + len > rec0.size) return out
            out.putIfAbsent(type, rec0.copyOfRange(p + 8, p + len))
            p += len
        }
        return out
    }

    /** MOBI6: chapters at mbp:pagebreak; filepos links become anchors so the in-book TOC works. */
    private fun splitMobi6(raw: ByteArray, charset: java.nio.charset.Charset, images: Map<Int, String>): Pair<List<HtmlBook.Part>, List<Pair<String, String>>> {
        var html = String(raw, charset)
        val latin = String(raw, Charsets.ISO_8859_1)
        val positions = Regex("filepos=[\"']?0*(\\d+)", RegexOption.IGNORE_CASE).findAll(latin).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in raw.indices }.toSortedSet()
        if (positions.isNotEmpty()) {
            // Insert anchors at byte offsets (descending so earlier offsets stay valid).
            val out = ByteArrayOutputStream()
            var last = 0
            for (pos in positions) { out.write(raw, last, pos - last); out.write("<a id=\"fp$pos\"></a>".toByteArray(charset)); last = pos }
            out.write(raw, last, raw.size - last)
            html = String(out.toByteArray(), charset)
        }
        html = fixImages(html, images)
        val toc = Regex("<a[^>]*filepos=[\"']?0*(\\d+)[\"']?[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).map { org.jsoup.Jsoup.parse(it.groupValues[2]).text().trim() to "fp${it.groupValues[1].toInt()}" }
            .filter { it.first.isNotBlank() }.distinctBy { it.second }.toList()
        val pieces = html.split(Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
        return merge(pieces) to toc
    }

    private fun splitKf8(text: String, images: Map<Int, String>): Pair<List<HtmlBook.Part>, List<Pair<String, String>>> {
        val html = fixImages(text, images)
        val pieces = html.split(Regex("(?=<\\?xml)|(?=<html[\\s>])", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
        return merge(pieces) to emptyList()
    }

    private fun merge(pieces: List<String>): List<HtmlBook.Part> {
        val parts = mutableListOf<HtmlBook.Part>()
        val pending = StringBuilder()
        for (p in pieces) {
            pending.append(p)
            if (org.jsoup.Jsoup.parse(pending.toString()).text().length >= 200) { parts += HtmlBook.Part(null, pending.toString()); pending.clear() }
        }
        if (pending.isNotBlank()) parts += HtmlBook.Part(null, pending.toString())
        return parts.ifEmpty { listOf(HtmlBook.Part(null, pieces.joinToString(""))) }
    }

    private fun fixImages(html: String, images: Map<Int, String>): String {
        var s = Regex("recindex=[\"']?0*(\\d+)[\"']?", RegexOption.IGNORE_CASE).replace(html) { m ->
            images[m.groupValues[1].toInt()]?.let { "src=\"$it\"" } ?: m.value }
        s = Regex("kindle:embed:([0-9A-Va-v]{4})(\\?[^\"']*)?", RegexOption.IGNORE_CASE).replace(s) { m ->
            images[m.groupValues[1].uppercase().toInt(32)]?.let { it } ?: m.value }
        return s
    }

    companion object {
        /** PalmDOC LZ77 (MOBI compression type 2). */
        fun palmDoc(input: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(input.size * 2)
            val buf = ByteArray(8192 * 2)
            var size = 0
            fun put(b: Int) { if (size == buf.size) error("PalmDOC 记录过大"); buf[size++] = b.toByte() }
            var i = 0
            while (i < input.size) {
                val c = input[i++].toInt() and 255
                when {
                    c in 1..8 -> repeat(c) { if (i < input.size) put(input[i++].toInt()) }
                    c <= 0x7f -> put(c)
                    c >= 0xc0 -> { put(' '.code); put(c xor 0x80) }
                    else -> {
                        if (i >= input.size) break
                        val pair = (c shl 8) or (input[i++].toInt() and 255)
                        val distance = (pair shr 3) and 0x7ff
                        val length = (pair and 7) + 3
                        require(distance in 1..size) { "PalmDOC 数据损坏" }
                        repeat(length) { put(buf[size - distance].toInt()) }
                    }
                }
            }
            out.write(buf, 0, size)
            return out.toByteArray()
        }

        fun trailingSize(record: ByteArray, flags: Int): Int {
            var num = 0
            var f = flags shr 1
            while (f != 0) {
                if (f and 1 != 0) {
                    var ptr = record.size - num; var bitpos = 0; var result = 0
                    while (ptr > 0) {
                        val v = record[ptr - 1].toInt() and 255
                        result = result or ((v and 0x7f) shl bitpos); bitpos += 7; ptr--
                        if (v and 0x80 != 0 || bitpos >= 28) break
                    }
                    num += result
                }
                f = f shr 1
            }
            if (flags and 1 != 0 && record.size - num - 1 >= 0) num += (record[record.size - num - 1].toInt() and 3) + 1
            return num.coerceIn(0, record.size)
        }
    }
}
