package com.lyric.reader

import java.io.File
import java.util.zip.InflaterInputStream

/** Deliberately bounded, read-only PDF structural parser. Never interprets scripts or external actions. */
object PdfOutline {
    data class Entry(val title: String, val page: Int, val level: Int)
    private data class Ref(val id: Int, val generation: Int)
    private data class Name(val value: String)
    private data class Obj(val value: Any?, val stream: ByteArray? = null)
    private const val LIMIT = 32 * 1024 * 1024
    private val latin = Charsets.ISO_8859_1

    fun read(file: File): List<Entry> = runCatching {
        // Skip image/content streams using their lengths. Large scanned books do not need to be
        // loaded into memory just to find a few structural dictionaries.
        parse(structure(file))
    }.getOrDefault(emptyList())

    private fun structure(file: File): ByteArray = java.io.RandomAccessFile(file, "r").use { input ->
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray(latin))
        val objectPattern = Regex("(\\d+)\\s+(\\d+)\\s+obj\\b")
        val streamEnd = Regex("endstream")
        val objectEnd = Regex("endobj")
        fun window(at: Long, length: Int): String {
            input.seek(at)
            val data = ByteArray(minOf(length.toLong(), input.length() - at).toInt().coerceAtLeast(0))
            input.readFully(data); return String(data, latin)
        }
        fun find(pattern: Regex, from: Long): Pair<Long, MatchResult>? {
            var at = from
            while (at < input.length()) {
                val chunk = window(at, 65536)
                val match = pattern.find(chunk)
                if (match != null) return (at + match.range.first) to match
                if (chunk.length < 65536) return null
                at += 65536 - 128
            }
            return null
        }
        require(window(0, 8).startsWith("%PDF-"))
        var at = 0L; var count = 0
        val lengths = mutableMapOf<Ref, Int>()
        while (true) {
            val (start, match) = find(objectPattern, at) ?: break
            require(++count <= 200_000)
            val bodyAt = start + match.value.length
            var capacity = 8192
            var text: String
            var lexer: Lexer
            var value: Any?
            while (true) {
                text = window(bodyAt, capacity); lexer = Lexer(text)
                val parsed = runCatching { lexer.value().also { lexer.skip() } }
                if (parsed.isSuccess && (lexer.pos + 32 < text.length || bodyAt + text.length == input.length())) {
                    value = parsed.getOrNull(); break
                }
                capacity *= 2; require(capacity <= 2 * 1024 * 1024)
            }
            val ref = Ref(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            if (value is Int) lengths[ref] = value
            var serialized = text.substring(0, lexer.pos)
            var end = bodyAt + lexer.pos
            if (text.startsWith("stream", lexer.pos)) {
                var streamStart = end + 6
                val eol = window(streamStart, 2)
                if (eol.startsWith("\r\n")) streamStart += 2 else if (eol.startsWith("\n") || eol.startsWith("\r")) streamStart++
                val d = value as? Map<*, *> ?: error("Stream dictionary")
                val length = (d["Length"] as? Int) ?: lengths[d["Length"]]
                val streamStop = if (length != null && length >= 0) streamStart + length
                    else find(streamEnd, streamStart)?.first ?: error("Stream end")
                require(streamStop in streamStart..input.length())
                if ((d["Type"] as? Name)?.value == "ObjStm") {
                    require(streamStop - streamStart <= LIMIT)
                    serialized += "stream\n" + window(streamStart, (streamStop - streamStart).toInt()) + "\nendstream\n"
                } else serialized = "null\n" // No content/font/image decoding for an outline.
                end = streamStop
            }
            at = (find(objectEnd, end)?.first ?: error("Object end")) + 6
            val record = "${match.value}\n$serialized\nendobj\n".toByteArray(latin)
            require(out.size().toLong() + record.size <= LIMIT)
            out.write(record)
        }
        out.toByteArray()
    }

    fun parse(bytes: ByteArray): List<Entry> = runCatching { Parser(bytes).outline() }.getOrDefault(emptyList())

    private class Lexer(val text: String, var pos: Int = 0) {
        fun skip() {
            while (pos < text.length) {
                if (text[pos].isWhitespace() || text[pos] == '\u0000') pos++
                else if (text[pos] == '%') { while (pos < text.length && text[pos] !in "\r\n") pos++ }
                else break
            }
        }
        fun word(): String {
            skip(); val start = pos
            while (pos < text.length && !text[pos].isWhitespace() && text[pos] !in "()<>[]{}/%\u0000") pos++
            return text.substring(start, pos)
        }
        fun value(depth: Int = 0): Any? {
            require(depth < 64); skip(); if (pos >= text.length) return null
            return when (text[pos]) {
                '/' -> { pos++; Name(word().replace(Regex("#([0-9a-fA-F]{2})")) { it.groupValues[1].toInt(16).toChar().toString() }) }
                '(' -> {
                    pos++; var nesting = 1; val out = StringBuilder()
                    while (pos < text.length && nesting > 0) {
                        val c = text[pos++]
                        when (c) {
                            '\\' -> if (pos < text.length) {
                                val e = text[pos++]
                                when (e) {
                                    'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t')
                                    'b' -> out.append('\b'); 'f' -> out.append('\u000c')
                                    '\r' -> { if (pos < text.length && text[pos] == '\n') pos++ }
                                    '\n' -> Unit
                                    in '0'..'7' -> {
                                        var oct = "$e"; repeat(2) { if (pos < text.length && text[pos] in '0'..'7') oct += text[pos++] }
                                        out.append(oct.toInt(8).toChar())
                                    }
                                    else -> out.append(e)
                                }
                            }
                            '(' -> { nesting++; out.append(c) }
                            ')' -> { nesting--; if (nesting > 0) out.append(c) }
                            else -> out.append(c)
                        }
                    }
                    require(nesting == 0)
                    decode(out.toString().toByteArray(latin))
                }
                '[' -> { pos++; val out = mutableListOf<Any?>(); skip()
                    while (pos < text.length && text[pos] != ']') { val before = pos; out += value(depth + 1); require(pos > before); require(out.size < 200_000); skip() }
                    require(pos < text.length && text[pos] == ']'); pos++; out
                }
                '<' -> if (text.startsWith("<<", pos)) {
                    pos += 2; val out = linkedMapOf<String, Any?>(); skip()
                    while (pos < text.length && !text.startsWith(">>", pos)) {
                        val key = value(depth + 1) as? Name ?: error("Invalid dictionary")
                        out[key.value] = value(depth + 1); skip(); require(out.size < 200_000)
                    }
                    require(text.startsWith(">>", pos)); pos += 2; out
                } else {
                    pos++; val end = text.indexOf('>', pos).also { require(it >= pos) }
                    val hex = text.substring(pos, end).filterNot { it.isWhitespace() }.let { if (it.length % 2 == 0) it else it + "0" }
                    pos = end + 1; decode(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
                }
                else -> {
                    val token = word(); require(token.isNotEmpty())
                    val n = token.toIntOrNull()
                    if (n != null) {
                        val saved = pos; val second = word().toIntOrNull(); val r = if (second != null) word() else ""
                        if (r == "R") Ref(n, second!!) else { pos = saved; n }
                    } else token.toDoubleOrNull() ?: token
                }
            }
        }
    }
    private fun decode(b: ByteArray): String = when {
        b.size >= 2 && b[0] == 0xfe.toByte() && b[1] == 0xff.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
        b.size >= 3 && b[0] == 0xef.toByte() && b[1] == 0xbb.toByte() && b[2] == 0xbf.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
        else -> String(b, latin)
    }
    private class Parser(bytes: ByteArray) {
        private val objects = linkedMapOf<Ref, Obj>()
        private val source = String(bytes, latin)
        private fun dict(value: Any?): Map<*, *> = resolve(value) as? Map<*, *> ?: emptyMap<Any, Any>()
        private fun resolve(value: Any?, depth: Int = 0): Any? = if (value is Ref && depth < 32) resolve(objects[value]?.value, depth + 1) else value
        init {
            require(bytes.size <= LIMIT && source.startsWith("%PDF-"))
            val pattern = Regex("(?m)(\\d+)\\s+(\\d+)\\s+obj\\b")
            var at = 0
            while (at < source.length) {
                val match = pattern.find(source, at) ?: break
                val lexer = Lexer(source, match.range.last + 1)
                val value = lexer.value(); lexer.skip()
                var stream: ByteArray? = null
                if (source.startsWith("stream", lexer.pos)) {
                    var start = lexer.pos + 6
                    if (source.getOrNull(start) == '\r') start++
                    if (source.getOrNull(start) == '\n') start++
                    val length = (value as? Map<*, *>)?.get("Length") as? Int
                    val end = if (length != null && length >= 0 && start.toLong() + length <= bytes.size) start + length
                        else source.indexOf("endstream", start).also { require(it >= start) }
                    stream = bytes.copyOfRange(start, end)
                    lexer.pos = source.indexOf("endstream", end).let { if (it < 0) end else it + 9 }
                }
                objects[Ref(match.groupValues[1].toInt(), match.groupValues[2].toInt())] = Obj(value, stream)
                at = source.indexOf("endobj", lexer.pos).also { require(it >= lexer.pos) } + 6
                require(objects.size <= 200_000)
            }
            // PDF 1.5 object streams (usually Flate): expand only structural objects, capped globally.
            var expanded = 0
            objects.values.toList().forEach { obj ->
                val d = obj.value as? Map<*, *> ?: return@forEach
                if ((d["Type"] as? Name)?.value != "ObjStm") return@forEach
                val raw = obj.stream ?: return@forEach
                val filter = resolve(d["Filter"])
                val filters = if (filter is List<*>) filter else listOfNotNull(filter)
                var data = raw
                filters.forEach { f ->
                    require((f as? Name)?.value in listOf("FlateDecode", "Fl"))
                    data = InflaterInputStream(data.inputStream()).use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= LIMIT)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(data.size <= LIMIT)
                }
                expanded += data.size; require(expanded <= LIMIT)
                val first = resolve(d["First"]) as? Int ?: error("First")
                val count = resolve(d["N"]) as? Int ?: error("N")
                require(first in 0..data.size && count in 0..200_000)
                val text = String(data, latin); val header = Lexer(text)
                repeat(count) {
                    val id = header.word().toInt(); val offset = header.word().toInt()
                    require(offset >= 0 && first.toLong() + offset < data.size)
                    objects.putIfAbsent(Ref(id, 0), Obj(Lexer(text, first + offset).value()))
                }
            }
        }
        fun outline(): List<Entry> {
            val catalog = objects.values.lastOrNull { (dict(it.value)["Type"] as? Name)?.value == "Catalog" }?.value ?: return emptyList()
            val root = dict(catalog)
            val pageIndices = mutableMapOf<Ref, Int>(); val pageSeen = mutableSetOf<Any>(); var pageCount = 0
            fun pages(value: Any?, depth: Int) {
                if (value == null || depth > 64 || !pageSeen.add(value)) return
                val d = dict(value)
                if ((d["Type"] as? Name)?.value == "Page") { if (value is Ref) pageIndices[value] = pageCount; pageCount++ }
                else (resolve(d["Kids"]) as? List<*>)?.forEach { pages(it, depth + 1) }
            }
            pages(root["Pages"], 0)
            val named = mutableMapOf<String, Any?>()
            dict(root["Dests"]).forEach { (k, v) -> named[k.toString()] = v }
            val nameSeen = mutableSetOf<Any>()
            fun names(value: Any?, depth: Int) {
                if (value == null || depth > 64 || !nameSeen.add(value)) return
                val d = dict(value); val list = resolve(d["Names"]) as? List<*>
                list?.chunked(2)?.filter { it.size == 2 }?.forEach { named[it[0].toString()] = it[1] }
                (resolve(d["Kids"]) as? List<*>)?.forEach { names(it, depth + 1) }
            }
            names(dict(root["Names"])["Dests"], 0)
            fun destination(value: Any?, depth: Int = 0): Int? {
                if (depth > 32) return null
                return when (val v = resolve(value)) {
                    is Name -> destination(named[v.value], depth + 1)
                    is String -> destination(named[v], depth + 1)
                    is Map<*, *> -> destination(v["D"], depth + 1)
                    is List<*> -> (pageIndices[v.firstOrNull()] ?: (v.firstOrNull() as? Int))?.takeIf { it in 0 until pageCount }
                    else -> null
                }
            }
            val out = mutableListOf<Entry>(); val seen = mutableSetOf<Any>()
            fun walk(first: Any?, level: Int) {
                if (level > 32) return
                var item = first
                while (item != null && seen.add(item) && seen.size <= 10_000) {
                    val d = dict(item); val action = dict(d["A"])
                    val dest = d["Dest"] ?: if ((action["S"] as? Name)?.value == "GoTo") action["D"] else null
                    val page = destination(dest)
                    if (page != null) out += Entry((resolve(d["Title"]) as? String).orEmpty().ifBlank { "第 ${page + 1} 页" }, page, level)
                    walk(d["First"], level + 1); item = d["Next"]
                }
            }
            walk(dict(root["Outlines"])["First"], 0)
            return out
        }
    }
}
