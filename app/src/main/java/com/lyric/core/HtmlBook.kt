package com.lyric.core

import org.jsoup.Jsoup

/** Shared HTML → Book assembly for DOCX and MOBI/AZW3, reusing the EPUB text layout. */
object HtmlBook {
    class Part(val title: String?, val html: String)

    fun build(
        title: String, author: String, parts: List<Part>, images: Map<String, ByteArray>,
        cover: String? = null, toc: (Map<String, Locator>) -> List<TocEntry> = { emptyList() },
    ): Book {
        val anchors = HashMap<String, Locator>()
        val chapters = parts.mapIndexed { index, part ->
            ImportProgress.tick("解析章节", index + 1, parts.size)
            val doc = Jsoup.parse(part.html)
            val extracted = EpubLayout.extract(doc.body(), "", { it }) { id, offset -> anchors.putIfAbsent(id, Locator(index, offset)) }
            Chapter(index, part.title?.trim()?.ifBlank { null }
                ?: doc.selectFirst("h1,h2,h3")?.text()?.trim()?.take(40)?.ifBlank { null } ?: "第 ${index + 1} 章",
                extracted.text, "part$index", styles = extracted.styles)
        }.filter { it.text.isNotBlank() }.mapIndexed { i, c -> c.copy(index = i) }
        require(chapters.isNotEmpty()) { "没有可阅读的正文" }
        val resources = images.mapValues { java.util.Base64.getEncoder().encodeToString(it.value) }
        return Book(title, author.ifBlank { "佚名" }, chapters,
            toc(anchors).filter { it.chapterIndex in chapters.indices }.ifEmpty { chapters.map { TocEntry(it.title, it.index) } },
            resources = resources, coverResource = cover?.takeIf { it in resources })
    }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
