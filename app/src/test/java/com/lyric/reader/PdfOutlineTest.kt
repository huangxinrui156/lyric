package com.lyric.reader

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

class PdfOutlineTest {
    private val latin = Charsets.ISO_8859_1
    /** Real small PDF fixtures generated here, with page tree, content streams, trailer and xref. */
    private fun fixture(extra: Map<Int, String> = emptyMap(), catalog: String = "", compressed: Boolean = false): ByteArray {
        val objects = linkedMapOf(
            1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R $catalog >>",
            // Object-number order intentionally differs from page-tree order.
            2 to "<< /Type /Pages /Kids [5 0 R 3 0 R] /Count 2 >>",
            3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>",
            4 to "<< /Length 0 >>\nstream\n\nendstream",
            5 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Contents 4 0 R >>",
            6 to "<< /Type /Outlines /First 7 0 R /Last 9 0 R /Count 3 >>",
            7 to "<< /Title (Part \\(one\\)) /Parent 6 0 R /Dest [5 0 R /Fit] /First 8 0 R /Next 9 0 R >>",
            8 to "<< /Title <FEFF7B2C4E8C7AE0> /Parent 7 0 R /A << /S /GoTo /D [3 0 R /XYZ null null 0] >> >>",
            9 to "<< /Title (Last) /Parent 6 0 R /Dest [3 0 R /Fit] >>",
        ).apply { putAll(extra) }
        if (compressed) {
            val ids = listOf(6, 7, 8, 9)
            val body = StringBuilder(); val header = StringBuilder()
            ids.forEach { id -> header.append("$id ${body.toString().toByteArray(latin).size} "); body.append(objects.remove(id)).append('\n') }
            val plain = (header.toString() + body).toByteArray(latin)
            val zipped = ByteArrayOutputStream().also { DeflaterOutputStream(it).use { z -> z.write(plain) } }.toByteArray()
            objects[10] = "<< /Type /ObjStm /N 4 /First ${header.length} /Filter /FlateDecode /Length ${zipped.size} >>\nstream\n${String(zipped, latin)}\nendstream"
        }
        val out = ByteArrayOutputStream()
        fun write(s: String) { out.write(s.toByteArray(latin)) }
        write("%PDF-1.5\n")
        val offsets = mutableMapOf<Int, Int>()
        objects.forEach { (id, body) -> offsets[id] = out.size(); write("$id 0 obj\n$body\nendobj\n") }
        val start = out.size(); val size = objects.keys.max() + 1
        write("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) write(if (id in offsets) "%010d 00000 n \n".format(offsets[id]) else "0000000000 00000 f \n")
        write("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n")
        return out.toByteArray()
    }
    @Test fun fileReaderSkipsLargeImageStreamsAndReadsCompressedStructure() {
        for (compressed in listOf(false, true)) {
            val file = java.io.File.createTempFile("outline-", ".pdf")
            try {
                file.writeBytes(fixture(compressed = compressed))
                java.io.RandomAccessFile(file, "rw").use { f ->
                    f.seek(f.length())
                    f.write("11 0 obj\n<< /Type /XObject /Subtype /Image /Length 40000000 >>\nstream\n".toByteArray(latin))
                    val end = f.filePointer + 40_000_000
                    f.setLength(end); f.seek(end)
                    f.write("\nendstream\nendobj\n".toByteArray(latin))
                }
                assertEquals(PdfOutline.parse(fixture()), PdfOutline.read(file))
            } finally { file.delete() }
        }
    }
    @Test fun explicitAndActionDestinationsNestedAndUnicode() {
        val result = PdfOutline.parse(fixture())
        assertEquals(listOf("Part (one)", "第二章", "Last"), result.map { it.title })
        assertEquals(listOf(0, 1, 1), result.map { it.page })
        assertEquals(listOf(0, 1, 0), result.map { it.level })
    }
    @Test fun compressedObjectStreams() {
        assertEquals(PdfOutline.parse(fixture()), PdfOutline.parse(fixture(compressed = true)))
        assertEquals(3, PdfOutline.parse(fixture(compressed = true)).size)
    }
    @Test fun namedDestinationsNameTreeAndLegacyDictionary() {
        val extra = mapOf(
            7 to "<< /Title (Named) /Dest (target) /Next 9 0 R >>",
            9 to "<< /Title (Legacy) /A << /S /GoTo /D /old >> >>",
            11 to "<< /Kids [12 0 R] >>",
            12 to "<< /Names [(target) 13 0 R] >>",
            13 to "<< /D [3 0 R /Fit] >>",
        )
        val result = PdfOutline.parse(fixture(extra, "/Names << /Dests 11 0 R >> /Dests << /old [5 0 R /Fit] >>"))
        assertEquals(listOf(1, 0), result.map { it.page })
    }
    @Test fun cyclesInvalidDestinationsAndExternalActionsDoNotHangOrNavigate() {
        val bytes = fixture(mapOf(
            7 to "<< /Title (Cycle) /Dest [5 0 R /Fit] /First 7 0 R /Next 9 0 R >>",
            9 to "<< /Title (External) /A << /S /GoToR /D [1 /Fit] /F (external.pdf) >> /Next 9 0 R >>",
        ))
        assertEquals(listOf(PdfOutline.Entry("Cycle", 0, 0)), PdfOutline.parse(bytes))
        assertTrue(PdfOutline.parse("not a PDF".toByteArray()).isEmpty())
        assertTrue(PdfOutline.parse(fixture(mapOf(6 to "<< >>"))).isEmpty())
        assertTrue(PdfOutline.parse(fixture().copyOf(40)).isEmpty())
    }
}
