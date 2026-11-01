package com.lyric.core

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * Image-only / fixed-layout EPUBs (Kindle/Calibre comic exports, svg-wrapped pages,
 * rendition:layout pre-paginated) become comics: page images in spine order, read
 * straight out of the EPUB ZIP like a CBZ. Streams entries; nothing large is held in memory.
 */
object ImageEpub {
    data class Analysis(val prePaginated: Boolean, val direction: String?, val pages: Int, val imagePages: Int)

    /** Returns a COMIC [Book] when the EPUB is a comic, otherwise null (text EPUB). */
    fun detect(file: File, displayName: String): Book? {
        checkArchive(file)
        for (cs in listOf("UTF-8", "GBK", "Cp437")) {
            val result = importAttempt { ZipFile(file, Charset.forName(cs)).use { detect(it, displayName) } }
            result.getOrNull()?.let { return it }
            if (result.isSuccess) return null // parsed fine and is a text book
        }
        return null
    }

    private fun Element.local(name: String) = getAllElements().filter { it.tagName().substringAfter(':').equals(name, true) }

    private fun detect(zip: ZipFile, displayName: String): Book? {
        val entries = HashMap<String, ZipEntry>()
        var count = 0
        zip.entries().asSequence().onEach {
            ImportProgress.check()
            if (++count > MAX_ARCHIVE_ENTRIES) throw ArchiveLimitException()
        }.filter { !it.isDirectory }.forEach { e ->
            importAttempt { EpubPaths.archive(e.name) }.getOrNull()?.let { entries.putIfAbsent(it, e) }
        }
        fun text(path: String, limit: Int = 2 * 1024 * 1024): String? {
            ImportProgress.check()
            val e = entries[path] ?: return null
            if (e.size > limit) {
                require(path != "META-INF/encryption.xml") { "EPUB 加密描述过大" }
                return null
            }
            return zip.getInputStream(e).use {
                val bytes = it.readBounded(limit + 1)
                require(bytes.size <= limit) { "EPUB 条目过大" }
                String(bytes, Charsets.UTF_8)
            }
        }
        val container = Jsoup.parse(text("META-INF/container.xml") ?: error("no container"), "", Parser.xmlParser())
        val root = container.local("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() } ?: error("no opf")
        val opfPath = EpubPaths.resolve("", root)
        val opf = Jsoup.parse(text(opfPath) ?: error("no opf"), "", Parser.xmlParser())
        val manifest = opf.local("manifest").flatMap { m -> m.children().filter { it.tagName().substringAfter(':') == "item" } }
            .associateBy { it.attr("id") }
        EpubEncryption.inspect(text("META-INF/encryption.xml"),
            EpubEncryption.fontPaths(manifest.values) { EpubPaths.resolve(opfPath, it) })
        val spineEl = opf.local("spine").firstOrNull() ?: error("no spine")
        val refs = spineEl.children().filter { it.tagName().substringAfter(':') == "itemref" }
        val metas = opf.local("meta")
        val prePaginated = metas.any { it.attr("property") == "rendition:layout" && it.text().trim() == "pre-paginated" } ||
            metas.any { it.attr("name") == "fixed-layout" && it.attr("content").equals("true", true) } ||
            refs.isNotEmpty() && refs.all { "rendition:layout-pre-paginated" in it.attr("properties") }
        val direction = when (spineEl.attr("page-progression-direction").lowercase()) { "rtl" -> "rtl"; "ltr" -> "ltr"; else -> null }

        val pages = mutableListOf<Pair<String, String>>() // (spine doc path, image path)
        var docs = 0; var imageDocs = 0
        val firstPageOfDoc = HashMap<String, Int>()
        refs.forEach { ref ->
            ImportProgress.check()
            val item = manifest[ref.attr("idref")] ?: return@forEach
            val path = importAttempt { EpubPaths.resolve(opfPath, item.attr("href")) }.getOrNull() ?: return@forEach
            docs++
            if (item.attr("media-type").startsWith("image/")) {
                if (path in entries) { firstPageOfDoc.putIfAbsent(path, pages.size); pages += path to path; imageDocs++ }
                return@forEach
            }
            val html = text(path, 512 * 1024)?.let { Jsoup.parse(it, "", Parser.htmlParser()) } ?: return@forEach
            val body = html.body()
            val images = body.getAllElements().mapNotNull { el ->
                when (el.tagName().substringAfter(':').lowercase()) {
                    "img" -> el.attr("src")
                    "image" -> el.attr("xlink:href").ifBlank { el.attr("href") }
                    else -> null
                }?.takeIf { it.isNotBlank() }
            }.mapNotNull { importAttempt { EpubPaths.resolve(path, it) }.getOrNull() }.filter { it in entries }.distinct()
            val words = body.text().replace(Regex("\\s+"), "").length
            if (images.isEmpty()) { if (words == 0) docs-- ; return@forEach } // blank spacer pages don't count
            // Essentially one picture: at most two images and no more than a caption's worth of text.
            if (images.size <= 2 && words <= 40) {
                imageDocs++
                firstPageOfDoc.putIfAbsent(path, pages.size)
                images.forEach { pages += path to it }
            }
        }
        if (pages.size < 2 || docs == 0) return null
        val ratio = imageDocs.toFloat() / docs
        if (!(ratio >= 0.9f || (prePaginated && ratio >= 0.6f))) return null

        // Chapters from nav / NCX where they point at a page; otherwise one chapter.
        val marks = sortedMapOf<Int, String>()
        val navItem = manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
        navItem?.let { importAttempt { EpubPaths.resolve(opfPath, it.attr("href")) }.getOrNull() }?.let { navPath ->
            text(navPath)?.let { Jsoup.parse(it, "", Parser.htmlParser()) }?.select("nav a[href]")?.forEach { a ->
                importAttempt { EpubPaths.resolve(navPath, a.attr("href")) }.getOrNull()?.let(firstPageOfDoc::get)
                    ?.let { marks.putIfAbsent(it, a.text().trim().ifBlank { "第 ${marks.size + 1} 话" }) }
            }
        }
        if (marks.isEmpty()) manifest.values.firstOrNull { it.attr("media-type").contains("ncx") }?.let { ncx ->
            val ncxPath = importAttempt { EpubPaths.resolve(opfPath, ncx.attr("href")) }.getOrNull() ?: return@let
            text(ncxPath)?.let { Jsoup.parse(it, "", Parser.xmlParser()) }?.local("navPoint")?.forEach { p ->
                val src = p.local("content").firstOrNull()?.attr("src") ?: return@forEach
                importAttempt { EpubPaths.resolve(ncxPath, src) }.getOrNull()?.let(firstPageOfDoc::get)
                    ?.let { marks.putIfAbsent(it, p.local("text").firstOrNull()?.text()?.trim().orEmpty().ifBlank { "第 ${marks.size + 1} 话" }) }
            }
        }
        if (marks.isEmpty() || marks.firstKey() != 0) marks[0] = marks[0] ?: "正文"
        val starts = marks.keys.toList()
        var done = 0
        val chapters = starts.mapIndexed { i, start ->
            val end = starts.getOrNull(i + 1) ?: pages.size
            Chapter(i, marks.getValue(start), content = pages.subList(start, end).map { (_, image) ->
                ImportProgress.tick("读取图片尺寸", ++done, pages.size)
                val entry = entries.getValue(image)
                val size = importAttempt { zip.getInputStream(entry).use { ImageHeader.size(it) } }.getOrNull()
                ContentUnit.ImagePage(entry.name, size?.first, size?.second)
            })
        }.filter { it.content.isNotEmpty() }.mapIndexed { i, c -> c.copy(index = i) }
        return Book(
            opf.local("title").firstOrNull()?.text()?.ifBlank { null } ?: displayName.substringBeforeLast('.'),
            opf.local("creator").firstOrNull()?.text().orEmpty(),
            chapters, contentType = ContentType.COMIC,
            coverResource = chapters.first().units.first().let { (it as ContentUnit.ImagePage).resource },
            readingDirection = direction,
        )
    }
}
