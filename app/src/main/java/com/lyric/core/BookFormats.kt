package com.lyric.core

import java.io.File
import java.util.zip.ZipFile

/** SAF providers often report a generic MIME type or omit the display-name suffix. */
object BookFormats {
    val pickerMimeTypes = arrayOf("text/plain", "application/epub+zip", "application/octet-stream",
        "application/zip", "application/x-zip", "application/x-zip-compressed", "application/x-epub+zip",
        "application/vnd.comicbook+zip", "application/x-cbz", "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword",
        "application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook", "application/x-mobi8-ebook")

    fun extension(name: String): String? = when (val e = name.substringAfterLast('.', "").lowercase()) {
        "txt", "epub", "cbz", "pdf", "docx", "mobi", "azw3" -> e
        "zip" -> "cbz"
        "azw", "prc" -> "mobi"
        "doc" -> throw FriendlyImportException("请另存为 DOCX 后再导入")
        else -> null
    }

    /** Raster signatures supported by the comic reader, including nameless SAF documents. */
    fun imageExtension(prefix: ByteArray): String? {
        fun starts(vararg bytes: Int) = prefix.size >= bytes.size && bytes.indices.all { (prefix[it].toInt() and 255) == bytes[it] }
        return when {
            starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
            starts(0xff, 0xd8, 0xff) -> "jpg"
            starts(0x47, 0x49, 0x46, 0x38) -> "gif"
            starts(0x42, 0x4d) -> "bmp"
            starts(0x52, 0x49, 0x46, 0x46) && prefix.size >= 12 &&
                prefix.copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII)) -> "webp"
            else -> null
        }
    }

    fun detect(prefix: ByteArray, displayName: String, mimeType: String?, uriName: String = ""): String {
        fun ascii(at: Int, s: String) = prefix.size >= at + s.length && String(prefix, at, s.length, Charsets.ISO_8859_1) == s
        if (ascii(0, "%PDF")) return "pdf"
        if (ascii(60, "BOOKMOBI") || ascii(60, "TEXtREAd"))
            return if (displayName.endsWith(".azw3", true) || uriName.endsWith(".azw3", true)) "azw3" else "mobi"
        if (prefix.size >= 4 && (prefix[0].toInt() and 255) == 0xD0 && (prefix[1].toInt() and 255) == 0xCF &&
            (prefix[2].toInt() and 255) == 0x11 && (prefix[3].toInt() and 255) == 0xE0) throw FriendlyImportException("请另存为 DOCX 后再导入")
        // All EPUBs/CBZs are ZIP containers; see [zipKind] for the content-based split.
        if (prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
            prefix[2] == 3.toByte() && prefix[3] == 4.toByte()) return "epub"
        return extension(displayName) ?: extension(uriName) ?: when (mimeType?.substringBefore(';')) {
            "application/epub+zip" -> "epub"
            "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
            "text/plain" -> "txt"
            "application/pdf" -> "pdf"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/vnd.amazon.mobi8-ebook", "application/x-mobi8-ebook" -> "azw3"
            "application/msword" -> throw FriendlyImportException("请另存为 DOCX 后再导入")
            else -> error("无法识别文件格式，支持 TXT / EPUB / PDF / DOCX / MOBI / AZW3 / CBZ")
        }
    }

    /** A ZIP is an EPUB when it has container.xml; a ZIP of images is a comic. */
    fun zipKind(file: File): String = runCatching {
        ZipFile(file, Charsets.ISO_8859_1).use { zip ->
            if (zip.getEntry("word/document.xml") != null) return "docx"
            if (zip.getEntry("META-INF/container.xml") != null) return "epub"
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            if (names.any { it.endsWith(".opf", true) || it.endsWith(".xhtml", true) }) "epub"
            else if (names.any(ComicParser::isImage)) "cbz" else "epub"
        }
    }.getOrDefault("epub")
}
