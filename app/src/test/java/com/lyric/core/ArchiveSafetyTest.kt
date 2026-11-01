package com.lyric.core

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ArchiveSafetyTest {
    private fun epub(encryption: String? = null, imageBook: Boolean = false): MutableMap<String, ByteArray> {
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfile full-path='OPS/book.opf'/></container>",
            "OPS/book.opf" to """<package><metadata><title>安全测试</title></metadata><manifest>
                <item id='a' href='a.xhtml' media-type='application/xhtml+xml'/>
                <item id='b' href='b.xhtml' media-type='application/xhtml+xml'/>
                <item id='font' href='font.otf' media-type='font/otf'/>
                <item id='pic' href='p.png' media-type='image/png'/>
                </manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>""",
            "OPS/a.xhtml" to if (imageBook) "<img src='p.png'/>" else "<html><head><link rel='stylesheet' href='style.css'/></head><body><p>${"正文内容。".repeat(40)}</p></body></html>",
            "OPS/b.xhtml" to if (imageBook) "<img src='p.png'/>" else "<p>第二章</p>",
            "OPS/style.css" to "@font-face {font-family:BookFont;src:url('font.otf')} p {font-family:BookFont}",
            "OPS/font.otf" to "obfuscated font bytes",
        ).mapValues { it.value.toByteArray() }.toMutableMap()
        entries["OPS/p.png"] = byteArrayOf(0x89.toByte(), 'P'.code.toByte()) + ByteArray(22)
        encryption?.let { entries["META-INF/encryption.xml"] = it.toByteArray() }
        return entries
    }
    private fun encryption(path: String, algorithm: String) = """<encryption xmlns:e='http://www.w3.org/2001/04/xmlenc#'>
        <e:EncryptedData><e:EncryptionMethod Algorithm='$algorithm'/><e:CipherData><e:CipherReference URI='$path'/></e:CipherData></e:EncryptedData></encryption>"""
    private fun archive(entries: Map<String, ByteArray>): File = File.createTempFile("safety-", ".epub").apply {
        ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }
    private fun source(file: File) = object : FileContentSource {
        override val file = file
        override val displayName = "safety.epub"
        override fun open() = file.inputStream()
    }

    @Test fun fontObfuscationFallsBackButPlainFontsAndTextRemainReadable() {
        for (algorithm in listOf(null, "http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")) {
            val file = archive(epub(algorithm?.let { encryption("OPS/font.otf", it) }))
            try {
                val book = EpubParser().parse(source(file))
                assertTrue(book.chapters[0].text.contains("正文内容"))
                assertEquals(algorithm == null, "BookFont" in book.embeddedFonts)
                assertEquals(algorithm == null, "OPS/font.otf" in book.resources)
                assertTrue("OPS/p.png" in book.resources)
            } finally { file.delete() }
            val comic = archive(epub(algorithm?.let { encryption("OPS/font.otf", it) }, imageBook = true))
            try { assertEquals(ContentType.COMIC, EpubParser().parse(source(comic)).contentType) }
            finally { comic.delete() }
        }
    }

    @Test fun encryptedContentAndZipEntriesHaveFriendlyErrorsForTextAndComics() {
        for (images in listOf(false, true)) {
            for (algorithm in listOf("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "http://www.idpf.org/2008/embedding")) {
                val file = archive(epub(encryption("OPS/a.xhtml", algorithm), images))
                try {
                    val error = assertThrows(ProtectedBookException::class.java) { EpubParser().parse(source(file)) }
                    assertEquals("该书受 DRM 保护，无法打开", error.message)
                    assertThrows(ProtectedBookException::class.java) { ImageEpub.detect(file, "comic.epub") }
                } finally { file.delete() }
            }
        }
        for (isEpub in listOf(false, true)) {
            val file = archive(if (isEpub) epub() else mapOf("page.png" to ByteArray(24)))
            val bytes = file.readBytes()
            // Set the encryption bit in local AND central headers, as password-protected ZIPs do.
            for (i in 0 until bytes.size - 10) {
                if (bytes[i] == 0x50.toByte() && bytes[i+1] == 0x4b.toByte()) {
                    val flag = when { bytes[i+2] == 1.toByte() && bytes[i+3] == 2.toByte() -> i+8
                        bytes[i+2] == 3.toByte() && bytes[i+3] == 4.toByte() -> i+6
                        else -> continue }
                    bytes[flag] = (bytes[flag].toInt() or 1).toByte()
                }
            }
            file.writeBytes(bytes)
            try {
                assertEquals("该书受 DRM 保护，无法打开", assertThrows(ProtectedBookException::class.java) {
                    if (isEpub) EpubParser().parse(source(file)) else ComicParser().parse(source(file))
                }.message)
                assertThrows(ProtectedBookException::class.java) { BookFormats.zipKind(file) }
            } finally { file.delete() }
        }
    }

    @Test fun extractionUsesPrivateFilesAndPreservesResourceJsonAndCleansUp() {
        val entries = epub()
        val resource = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        repeat(4) { entries["OPS/p$it.png"] = resource }
        val file = archive(entries)
        val directory = java.nio.file.Files.createTempDirectory("epub-extract-test").toFile()
        var sawExtractedFiles = false
        try {
            val book = ImportProgress.run(60_000, { false }, { stage, _, _ ->
                if (stage == "解析章节") {
                    val extracted = directory.listFiles()!!.single().listFiles()!!
                    assertEquals(entries.size, extracted.size)
                    assertTrue(extracted.all { it.isFile })
                    sawExtractedFiles = true
                }
            }) { EpubParser(directory).parse(source(file)) }
            assertTrue(sawExtractedFiles)
            assertArrayEquals(resource, Base64.getDecoder().decode(book.resources["OPS/p3.png"]))
            assertEquals(book, Json.decodeFromString(Book.serializer(), Json.encodeToString(Book.serializer(), book)))
            assertTrue(directory.listFiles()!!.isEmpty())
            entries.remove("OPS/b.xhtml")
            val broken = archive(entries)
            try { assertThrows(Exception::class.java) { EpubParser(directory).parse(source(broken)) } }
            finally { broken.delete() }
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { file.delete(); directory.deleteRecursively() }
    }

    @Test fun cancellationAndDeadlineEscapeDetectionAndUnzipWithoutCharsetRetries() {
        val file = archive(epub(imageBook = true))
        try {
            assertThrows(CancellationException::class.java) {
                ImportProgress.run(60_000, { true }, { _, _, _ -> }) { ImageEpub.detect(file, "image.epub") }
            }
            assertThrows(TimeoutException::class.java) {
                ImportProgress.run(-1, { false }, { _, _, _ -> }) { ImageEpub.detect(file, "image.epub") }
            }
            var reads = 0
            // Cancel during the spine scan, after both container and OPF have been read.
            assertThrows(CancellationException::class.java) {
                ImportProgress.run(60_000, { ++reads > 25 }, { _, _, _ -> }) { ImageEpub.detect(file, "image.epub") }
            }
            assertEquals(26, reads)
        } finally { file.delete() }
        val directory = java.nio.file.Files.createTempDirectory("cancel-extract").toFile()
        val large = epub().apply { this["large.bin"] = ByteArray(2 * 1024 * 1024) }
        val text = archive(large)
        var stoppedInUnzip = false
        try {
            assertThrows(CancellationException::class.java) {
                ImportProgress.run(60_000, {
                    val partial = directory.listFiles()?.firstOrNull()?.listFiles()?.any { it.length() >= 65536 } == true
                    if (partial) stoppedInUnzip = true
                    partial
                }, { _, _, _ -> }) { EpubParser(directory).parse(source(text)) }
            }
            assertTrue(stoppedInUnzip)
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { text.delete(); directory.deleteRecursively() }
    }

    @Test fun cbzAndImageEpubRejectMoreThanTenThousandEntriesIncludingDirectories() {
        val entries = (0..MAX_ARCHIVE_ENTRIES).associate { "dir$it/" to ByteArray(0) }
        val file = archive(entries)
        try {
            assertThrows(ArchiveLimitException::class.java) { ComicParser().parse(source(file)) }
            assertThrows(ArchiveLimitException::class.java) { ImageEpub.detect(file, "images.epub") }
            assertThrows(ArchiveLimitException::class.java) { EpubParser().parse(source(file)) }
        } finally { file.delete() }
    }
    @Test fun zip64CentralDirectoryRemainsReadable() {
        val file = archive(epub())
        try {
            val bytes = file.readBytes()
            val end = bytes.size - 22
            val eocd = java.nio.ByteBuffer.wrap(bytes, end, 22).slice().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val count = eocd.getShort(10).toLong() and 0xffff
            val directorySize = eocd.getInt(12).toLong() and 0xffffffffL
            val directoryOffset = eocd.getInt(16).toLong() and 0xffffffffL
            val zip64 = java.nio.ByteBuffer.allocate(76).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            zip64.putInt(0x06064b50).putLong(44).putShort(45).putShort(45).putInt(0).putInt(0)
                .putLong(count).putLong(count).putLong(directorySize).putLong(directoryOffset)
                .putInt(0x07064b50).putInt(0).putLong(end.toLong()).putInt(1)
            eocd.putShort(8, -1).putShort(10, -1).putInt(12, -1).putInt(16, -1)
            file.writeBytes(bytes.copyOfRange(0, end) + zip64.array() + bytes.copyOfRange(end, bytes.size))
            assertEquals("安全测试", EpubParser().parse(source(file)).title)
        } finally { file.delete() }
    }

    @Test fun epubComicLimitsDoNotChangeDocxClassification() {
        val entries = (0..MAX_ARCHIVE_ENTRIES).associate { "dir$it/" to ByteArray(0) }.toMutableMap()
        entries["word/document.xml"] = "<document/>".toByteArray()
        val file = archive(entries)
        try { assertEquals("docx", BookFormats.zipKind(file)) }
        finally { file.delete() }
    }

}
