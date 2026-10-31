package com.lyric.core

import java.io.ByteArrayOutputStream

/** KF8 (AZW3) index tables and skeleton/fragment reassembly, following Calibre's mobi8 reader. */
object Kf8 {
    class Entry(val ident: String, val tags: Map<Int, List<Int>>)
    class Index(val entries: List<Entry>, val cncx: Map<Int, String>)

    private fun ByteArray.u16(o: Int) = if (o + 2 > size) 0 else ((this[o].toInt() and 255) shl 8) or (this[o + 1].toInt() and 255)
    private fun ByteArray.u32(o: Int) = if (o + 4 > size) 0 else (u16(o) shl 16) or u16(o + 2)
    private fun ByteArray.ascii(o: Int, n: Int) = if (o + n > size) "" else String(this, o, n, Charsets.ISO_8859_1)

    /** Forward variable-width integer: the last byte has its high bit set. Returns (consumed, value). */
    fun decint(data: ByteArray, at: Int): Pair<Int, Int> {
        var value = 0; var i = at
        while (i < data.size) {
            val b = data[i++].toInt() and 255
            value = (value shl 7) or (b and 0x7f)
            if (b and 0x80 != 0) break
        }
        return (i - at) to value
    }

    /** [record] returns record n relative to the KF8 base; null for out-of-range. */
    fun readIndex(first: Int, record: (Int) -> ByteArray?): Index? {
        if (first <= 0 || first == -1) return null
        val head = record(first) ?: return null
        if (head.ascii(0, 4) != "INDX") return null
        val headerLength = head.u32(4)
        val count = head.u32(24)
        val cncxCount = head.u32(52)
        if (head.ascii(headerLength, 4) != "TAGX") return null
        val tagxLength = head.u32(headerLength + 4)
        val controlBytes = head.u32(headerLength + 8)
        val tagx = mutableListOf<IntArray>()
        var p = headerLength + 12
        while (p + 4 <= headerLength + tagxLength) {
            tagx += intArrayOf(head[p].toInt() and 255, head[p + 1].toInt() and 255, head[p + 2].toInt() and 255, head[p + 3].toInt() and 255)
            p += 4
        }
        val cncx = HashMap<Int, String>()
        for (c in 0 until cncxCount) {
            val rec = record(first + count + 1 + c) ?: break
            var pos = 0
            while (pos < rec.size) {
                val (used, len) = decint(rec, pos)
                if (used == 0 || pos + used + len > rec.size) break
                cncx[c * 0x10000 + pos] = String(rec, pos + used, len, Charsets.UTF_8)
                pos += used + len
            }
        }
        val entries = mutableListOf<Entry>()
        for (r in 1..count) {
            val rec = record(first + r) ?: break
            if (rec.ascii(0, 4) != "INDX") break
            val idxt = rec.u32(20); val n = rec.u32(24)
            if (rec.ascii(idxt, 4) != "IDXT") break
            val offsets = IntArray(n) { rec.u16(idxt + 4 + it * 2) }
            for (i in 0 until n) {
                val start = offsets[i]
                val end = if (i + 1 < n) offsets[i + 1] else idxt
                if (start >= rec.size || end > rec.size || start >= end) continue
                val identLength = rec[start].toInt() and 255
                val ident = rec.ascii(start + 1, identLength)
                val data = rec.copyOfRange(start + 1 + identLength, end)
                entries += Entry(ident, tagMap(controlBytes, tagx, data))
            }
        }
        return Index(entries, cncx)
    }

    fun tagMap(controlByteCount: Int, tagx: List<IntArray>, data: ByteArray): Map<Int, List<Int>> {
        var cb = 0
        var p = controlByteCount
        class P(val tag: Int, val count: Int?, val bytes: Int?, val perEntry: Int)
        val pending = mutableListOf<P>()
        for ((tag, perEntry, mask0, end) in tagx.map { it.toList() }) {
            if (end == 1) { cb++; continue }
            if (cb >= controlByteCount || cb >= data.size) break
            var mask = mask0
            var value = (data[cb].toInt() and 255) and mask
            if (value == 0) continue
            if (value == mask) {
                if (Integer.bitCount(mask) > 1) { val (u, v) = decint(data, p); p += u; pending += P(tag, null, v, perEntry) }
                else pending += P(tag, 1, null, perEntry)
            } else {
                while (mask and 1 == 0) { mask = mask shr 1; value = value shr 1 }
                pending += P(tag, value, null, perEntry)
            }
        }
        val out = HashMap<Int, List<Int>>()
        for (t in pending) {
            val values = mutableListOf<Int>()
            if (t.count != null) repeat(t.count * t.perEntry) {
                if (p < data.size) { val (u, v) = decint(data, p); p += u; values += v }
            } else {
                var consumed = 0
                while (consumed < t.bytes!! && p < data.size) { val (u, v) = decint(data, p); p += u; consumed += u; values += v }
            }
            out[t.tag] = values
        }
        return out
    }

    class Assembled(val files: List<ByteArray>, val toc: List<Pair<String, String>>)

    /**
     * Rebuilds the HTML files: each skeleton gets its fragments spliced in at their insert positions.
     * NCX positions (raw text offsets) become `<a id="ncxN">` anchors inside the owning fragment.
     */
    fun assemble(text: ByteArray, skel: Index, frag: Index, ncx: Index?): Assembled {
        val marks = ncx?.entries?.mapIndexedNotNull { i, e ->
            val pos = e.tags[1]?.firstOrNull() ?: return@mapIndexedNotNull null
            val label = e.tags[3]?.firstOrNull()?.let { ncx.cncx[it] } ?: return@mapIndexedNotNull null
            Triple(pos, "ncx$i", label)
        }.orEmpty()
        val placed = HashSet<String>()
        val files = mutableListOf<ByteArray>()
        var fragPtr = 0
        for (s in skel.entries) {
            val fragCount = s.tags[1]?.firstOrNull() ?: 0
            val skelPos = s.tags[6]?.getOrNull(0) ?: continue
            val skelLen = s.tags[6]?.getOrNull(1) ?: continue
            if (skelPos < 0 || skelPos + skelLen > text.size) continue
            var skeleton = text.copyOfRange(skelPos, skelPos + skelLen)
            var base = skelPos + skelLen
            repeat(fragCount) {
                val f = frag.entries.getOrNull(fragPtr++) ?: return@repeat
                val length = f.tags[6]?.getOrNull(1) ?: return@repeat
                val insert = ((f.ident.trim().toIntOrNull() ?: skelPos) - skelPos).coerceIn(0, skeleton.size)
                val end = (base + length).coerceAtMost(text.size)
                var part = text.copyOfRange(base.coerceAtMost(end), end)
                // Anchors for NCX targets inside this fragment, at the next tag start.
                marks.filter { it.first in base until end && it.second !in placed }.sortedByDescending { it.first }.forEach { (pos, id, _) ->
                    var at = pos - base
                    while (at < part.size && part[at] != '<'.code.toByte()) at++
                    part = part.copyOfRange(0, at) + "<a id=\"$id\"></a>".toByteArray() + part.copyOfRange(at, part.size)
                    placed += id
                }
                skeleton = skeleton.copyOfRange(0, insert) + part + skeleton.copyOfRange(insert, skeleton.size)
                base = end
            }
            // NCX targets pointing into the skeleton itself land at the file start.
            marks.filter { it.first in skelPos until skelPos + skelLen && it.second !in placed }.forEach { (_, id, _) ->
                val body = String(skeleton, Charsets.ISO_8859_1).indexOf("<body", ignoreCase = true).let { b ->
                    if (b < 0) 0 else String(skeleton, Charsets.ISO_8859_1).indexOf('>', b) + 1 }
                skeleton = skeleton.copyOfRange(0, body) + "<a id=\"$id\"></a>".toByteArray() + skeleton.copyOfRange(body, skeleton.size)
                placed += id
            }
            files += skeleton
        }
        return Assembled(files, marks.filter { it.second in placed }.map { it.third to it.second })
    }

    /** First flow of the FDST table is the HTML text; the rest are CSS/SVG flows. */
    fun htmlFlowEnd(fdst: ByteArray?, total: Int): Int {
        if (fdst == null || fdst.ascii(0, 4) != "FDST") return total
        val count = fdst.u32(8)
        return if (count >= 1) fdst.u32(16).coerceIn(0, total) else total
    }

    @Suppress("unused") private fun concat(parts: List<ByteArray>) = ByteArrayOutputStream().apply { parts.forEach(::write) }.toByteArray()
}
