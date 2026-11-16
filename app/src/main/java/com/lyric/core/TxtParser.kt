package com.lyric.core

class TxtParser : BookParser {
    override val extensions = setOf("txt")

    companion object {
        val DEFAULT_RULE =
            "^\\s*(?:第[零〇一二三四五六七八九十百千万两0-9０-９]+[章卷回节部篇].{0,70}|Chapter\\s+[0-9IVXLCDM]+.{0,70})\\s*$"
        const val FALLBACK_SIZE = 10_000

        fun rule(pattern: String?): Regex =
            Regex(pattern?.takeIf { it.isNotBlank() } ?: DEFAULT_RULE, RegexOption.IGNORE_CASE)
    }

    override fun parse(source: ContentSource, encoding: String?, chapterRegex: String?): Book {
        val (text, charset) = source.open().use { Encoding.decode(it.readBytes(), encoding) }
        require(text.isNotBlank()) { "文件为空，未找到可阅读的文字" }
        val regex = rule(chapterRegex)
        val headings = mutableListOf<Pair<Int, String>>()
        var start = 0
        // No split/lines allocation on a 10MB file; only short lines are regex candidates.
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (end - start in 1..160) {
                val line = text.substring(start, end).trim()
                if (regex.matches(line)) headings += start to line
            }
            start = end + 1
        }
        val chapters = mutableListOf<Chapter>()
        fun add(title: String, from: Int, to: Int) {
            if (to > from) chapters += Chapter(chapters.size, title, text.substring(from, to))
        }
        if (headings.isEmpty()) {
            var from = 0
            while (from < text.length) {
                var end = minOf(from + FALLBACK_SIZE, text.length)
                if (end < text.length && text[end].isLowSurrogate()) end--
                add("第 ${chapters.size + 1} 节", from, end)
                from = end
            }
        } else {
            if (headings.first().first > 0) add("序言", 0, headings.first().first)
            headings.forEachIndexed { i, h ->
                add(h.second, h.first, headings.getOrNull(i + 1)?.first ?: text.length)
            }
        }
        return Book(
            source.displayName.substringBeforeLast('.'),
            chapters = chapters,
            encoding = charset,
        )
    }
}
