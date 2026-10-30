package com.lyric.core

/** SAF providers often report a generic MIME type or omit the display-name suffix. */
object BookFormats {
    val pickerMimeTypes = arrayOf("text/plain", "application/epub+zip", "application/octet-stream")

    fun extension(name: String): String? = name.substringAfterLast('.', "").lowercase()
        .takeIf { it == "txt" || it == "epub" }

    fun detect(prefix: ByteArray, displayName: String, mimeType: String?, uriName: String = ""): String {
        // All EPUBs are ZIP containers. The parser then validates container.xml and the spine;
        // an arbitrary ZIP is never silently imported as text.
        if (prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
            prefix[2] == 3.toByte() && prefix[3] == 4.toByte()) return "epub"
        return extension(displayName) ?: extension(uriName) ?: when (mimeType?.substringBefore(';')) {
            "application/epub+zip" -> "epub"
            "text/plain" -> "txt"
            else -> error("无法识别文件格式，仅支持 TXT / EPUB")
        }
    }
}
