package com.lyric.core

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Kf8Test {
    private fun w16(a: ByteArray, o: Int, v: Int) { a[o] = (v shr 8).toByte(); a[o + 1] = v.toByte() }
    private fun w32(a: ByteArray, o: Int, v: Int) { w16(a, o, v ushr 16); w16(a, o + 2, v and 0xffff) }
    private fun varint(v: Int): ByteArray {
        val groups = mutableListOf<Int>(); var x = v
        do { groups.add(0, x and 0x7f); x = x ushr 7 } while (x != 0)
        return ByteArray(groups.size) { (groups[it] or if (it == groups.lastIndex) 0x80 else 0).toByte() }
    }

    private fun palmDb(records: List<ByteArray>): ByteArray {
        val header = ByteArray(78 + records.size * 8 + 2)
        "BOOKMOBI".toByteArray().copyInto(header, 60); w16(header, 76, records.size)
        var off = header.size
        records.forEachIndexed { i, r -> w32(header, 78 + i * 8, off); off += r.size }
        return ByteArrayOutputStream().apply { write(header); records.forEach(::write) }.toByteArray()
    }

    private fun rec0(textLength: Int, compression: Int = 1, version: Int = 8, skel: Int = -1, frag: Int = -1, ncx: Int = -1): ByteArray {
        val r = ByteArray(16 + 264)
        w16(r, 0, compression); w32(r, 4, textLength); w16(r, 8, 1); w16(r, 10, 4096)
        "MOBI".toByteArray().copyInto(r, 16); w32(r, 20, 264); w32(r, 28, 65001); w32(r, 36, version); w32(r, 108, -1)
        w32(r, 0xC0, -1); w32(r, 0xF4, ncx); w32(r, 0xF8, frag); w32(r, 0xFC, skel)
        return r
    }

    /** INDX header + one INDX entry record. tags: (tag, valuesPerEntry, mask). */
    private fun index(tags: List<Triple<Int, Int, Int>>, entries: List<Pair<String, ByteArray>>, cncx: Int = 0): List<ByteArray> {
        val tagx = ByteArrayOutputStream().apply {
            write("TAGX".toByteArray()); write(ByteArray(8))
            tags.forEach { write(byteArrayOf(it.first.toByte(), it.second.toByte(), it.third.toByte(), 0)) }
            write(byteArrayOf(0, 0, 0, 1))
        }.toByteArray()
        w32(tagx, 4, tagx.size); w32(tagx, 8, 1)
        val head = ByteArray(56) + tagx
        "INDX".toByteArray().copyInto(head); w32(head, 4, 56); w32(head, 24, 1); w32(head, 52, cncx)
        val body = ByteArrayOutputStream(); val offsets = mutableListOf<Int>()
        body.write(ByteArray(28))
        entries.forEach { (ident, data) -> offsets += body.size(); body.write(ident.length); body.write(ident.toByteArray()); body.write(data) }
        val idxt = body.size()
        body.write("IDXT".toByteArray()); offsets.forEach { body.write(it shr 8); body.write(it and 255) }
        val rec = body.toByteArray()
        "INDX".toByteArray().copyInto(rec); w32(rec, 20, idxt); w32(rec, 24, entries.size)
        return listOf(head, rec)
    }

    private fun kf8Book(): Pair<ByteArray, String> {
        val a = "A".repeat(210); val b = "B".repeat(210); val c = "C".repeat(210)
        val s1 = "<html><body></body></html>"
        val f1 = "<p>$b</p>"; val f2 = "<p>$a</p>"
        val s2 = s1; val f3 = "<h1>第二章</h1><p>$c</p>"
        val text = (s1 + f1 + f2 + s2 + f3).toByteArray()
        val s2pos = (s1 + f1 + f2).toByteArray().size
        val f3pos = s2pos + s2.length
        fun vals(vararg v: Int) = v.fold(ByteArray(0)) { acc, x -> acc + varint(x) }
        val skel = index(listOf(Triple(1, 1, 1), Triple(6, 2, 2)), listOf(
            "SKEL0000000" to byteArrayOf(3) + vals(2, 0, s1.length),
            "SKEL0000001" to byteArrayOf(3) + vals(1, s2pos, s2.length)))
        // Both fragments insert after "<html><body>": the second must end up first.
        val frag = index(listOf(Triple(6, 2, 1)), listOf(
            "12" to byteArrayOf(1) + vals(0, f1.length), "12" to byteArrayOf(1) + vals(0, f2.length),
            "${s2pos + 12}" to byteArrayOf(1) + vals(0, f3.toByteArray().size)))
        val label = "目录第二章".toByteArray()
        val ncx = index(listOf(Triple(1, 1, 1), Triple(3, 1, 2)), listOf("0" to byteArrayOf(3) + vals(f3pos, 0)), cncx = 1)
        val cncxRec = (varint(label.size)) + label
        val records = listOf(rec0(text.size, skel = 2, frag = 4, ncx = 6), text) + skel + frag + ncx + listOf(cncxRec)
        return palmDb(records) to c
    }

    @Test fun kf8FragmentsReassembledInSkeletonOrder() {
        val (bytes, _) = kf8Book()
        val book = MobiParser().parseBytes(bytes, "样书.azw3")
        assertEquals(2, book.chapters.size)
        val first = book.chapters[0].text
        assertTrue(first.indexOf("AAA") in 0 until first.indexOf("BBB"))
        assertTrue(book.chapters[1].text.contains("CCC"))
        assertEquals(listOf("目录第二章"), book.toc.map { it.title })
        assertEquals(1, book.toc[0].chapterIndex)
    }

    @Test fun huffCdicShowsFriendlyMessage() {
        val bytes = palmDb(listOf(rec0(3, compression = 17480, version = 6), "abc".toByteArray()))
        val e = runCatching { MobiParser().parseBytes(bytes, "a.mobi") }.exceptionOrNull()
        assertEquals("暂不支持这种压缩格式", e?.message)
        // The import pipeline keeps it verbatim (no "[阶段] 类名:" prefix).
        val wrapped = runCatching { ImportTrace.stage("解析书籍") { MobiParser().parseBytes(bytes, "a.mobi") } }.exceptionOrNull()
        assertEquals("暂不支持这种压缩格式", wrapped?.message)
    }
}
