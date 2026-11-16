package com.lyric.core

import java.net.URLDecoder

/** ZIP names are literal; only URI references are percent-decoded, exactly once. */
internal object EpubPaths {
    fun archive(path: String): String {
        require(!path.startsWith('/') && !path.startsWith('\\')) { "EPUB 路径无效" }
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach {
            when (it) {
                "", "." -> Unit
                ".." -> { require(parts.isNotEmpty()) { "EPUB 路径无效" }; parts.removeLast() }
                else -> parts.addLast(it)
            }
        }
        return parts.joinToString("/")
    }

    fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    fun resolve(baseFile: String, href: String): String {
        require(!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(href)) { "EPUB 不支持外部资源" }
        val path = decode(href.substringBefore('#').substringBefore('?'))
        if (path.isEmpty()) return baseFile
        val dir = baseFile.substringBeforeLast('/', "")
        return archive(if (dir.isEmpty()) path else "$dir/$path")
    }
}
