package com.lyric.core

import java.io.File
import java.util.zip.ZipFile

/** SAF providers often report a generic MIME type or omit the display-name suffix. */
object BookFormats {
    val pickerMimeTypes = arrayOf("text/plain", "application/epub+zip", "application/octet-stream",
        "application/zip", "application/x-zip", "application/x-zip-compressed", "application/x-epub+zip",
        "application/vnd.comicbook+zip", "application/x-cbz")

    fun extension(name: String): String? = when (val e = name.substringAfterLast('.', "").lowercase()) {
        "txt", "epub", "cbz" -> e
        else -> null
    }

    fun detect(prefix: ByteArray, displayName: String, mimeType: String?, uriName: String = ""): String {
        // All EPUBs/CBZs are ZIP containers; see [zipKind] for the content-based split.
        if (prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
            prefix[2] == 3.toByte() && prefix[3] == 4.toByte()) return "epub"
        return extension(displayName) ?: extension(uriName) ?: when (mimeType?.substringBefore(';')) {
            "application/epub+zip" -> "epub"
            "application/vnd.comicbook+zip", "application/x-cbz" -> "cbz"
            "text/plain" -> "txt"
            else -> error("无法识别文件格式，仅支持 TXT / EPUB / CBZ / ZIP 漫画")
        }
    }

    /** A ZIP is an EPUB when it has container.xml; a ZIP of images is a comic. */
    fun zipKind(file: File): String = runCatching {
        ZipFile(file, Charsets.ISO_8859_1).use { zip ->
            if (zip.getEntry("META-INF/container.xml") != null) return "epub"
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            if (names.any { it.endsWith(".opf", true) || it.endsWith(".xhtml", true) }) "epub"
            else if (names.any(ComicParser::isImage)) "cbz" else "epub"
        }
    }.getOrDefault("epub")
}
