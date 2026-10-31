package com.lyric.core

import java.io.File
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Word 2007+ (.docx): paragraphs, bold/italic runs, Heading 1/2 → chapters, embedded images. */
class DocxParser(private val tempDirectory: File? = null) : BookParser {
    override val extensions = setOf("docx")

    override fun parse(source: ContentSource, encoding: String?, chapterRegex: String?): Book {
        val owned = source !is FileContentSource
        val file = if (source is FileContentSource) source.file else ImportTrace.stage("DOCX/复制文件") {
            File.createTempFile("import-docx-", ".tmp", tempDirectory).also { f -> source.open().use { i -> f.outputStream().use { i.copyTo(it) } } }
        }
        try {
            return ImportTrace.stage("DOCX/读取压缩包") { ZipFile(file).use { parseZip(it, source.displayName) } }
        } finally { if (owned) file.delete() }
    }

    private fun Element.kids(name: String) = children().filter { it.tagName() == name }
    private fun Element.kid(name: String) = children().firstOrNull { it.tagName() == name }

    fun parseZip(zip: ZipFile, displayName: String): Book {
        fun entry(name: String): ByteArray? = zip.getEntry(name)?.let { e ->
            require(e.size <= 64L * 1024 * 1024) { "DOCX 条目过大：$name" }
            zip.getInputStream(e).use { it.readBytes() }
        }
        fun xml(name: String) = entry(name)?.let { Jsoup.parse(it.inputStream(), "UTF-8", "", Parser.xmlParser()) }
        val document = ImportTrace.stage("DOCX/解析 document.xml") { xml("word/document.xml") ?: error("不是有效的 DOCX：缺少 word/document.xml") }
        val headingLevel = HashMap<String, Int>()
        ImportTrace.stage("DOCX/解析样式") {
            xml("word/styles.xml")?.getElementsByTag("w:style")?.forEach { s ->
                val id = s.attr("w:styleId")
                val name = s.kid("w:name")?.attr("w:val").orEmpty().lowercase().replace(" ", "")
                val outline = s.getElementsByTag("w:outlineLvl").firstOrNull()?.attr("w:val")?.toIntOrNull()
                val level = Regex("^(heading|标题)([1-9])$").find(name)?.groupValues?.get(2)?.toIntOrNull()
                    ?: Regex("^heading([1-9])$", RegexOption.IGNORE_CASE).find(id)?.groupValues?.get(1)?.toIntOrNull()
                    ?: outline?.plus(1)
                if (level != null) headingLevel[id] = level
            }
        }
        val rels = HashMap<String, String>()
        xml("word/_rels/document.xml.rels")?.getElementsByTag("Relationship")?.forEach { r ->
            val target = r.attr("Target")
            if (r.attr("TargetMode") != "External") rels[r.attr("Id")] = if (target.startsWith("/")) target.removePrefix("/") else EpubPaths.resolve("word/document.xml", target)
        }
        val images = HashMap<String, ByteArray>()
        val parts = mutableListOf<HtmlBook.Part>()
        var title: String? = null
        var html = StringBuilder()
        var chars = 0
        var hasHeadings = false
        fun flush() { if (html.isNotBlank()) parts += HtmlBook.Part(title, html.toString()); html = StringBuilder(); chars = 0; title = null }
        val body = document.getElementsByTag("w:body").firstOrNull() ?: error("DOCX 没有正文")
        val paragraphs = body.getElementsByTag("w:p")
        paragraphs.forEachIndexed { pi, p ->
            if (pi % 200 == 0) ImportProgress.tick("解析段落", pi + 1, paragraphs.size)
            val pPr = p.kid("w:pPr")
            val styleId = pPr?.kid("w:pStyle")?.attr("w:val")
            val level = styleId?.let(headingLevel::get) ?: pPr?.kid("w:outlineLvl")?.attr("w:val")?.toIntOrNull()?.plus(1)
            val center = pPr?.kid("w:jc")?.attr("w:val") == "center"
            val inner = StringBuilder()
            p.getElementsByTag("w:r").forEach { r ->
                if (r.parents().any { it.tagName() == "w:del" }) return@forEach
                val rPr = r.kid("w:rPr")
                fun on(tag: String) = rPr?.kid(tag)?.let { it.attr("w:val") !in setOf("0", "false") } == true
                val text = StringBuilder()
                r.children().forEach { c ->
                    when (c.tagName()) {
                        "w:t" -> text.append(HtmlBook.escape(c.wholeText()))
                        "w:tab" -> text.append("　")
                        "w:br", "w:cr" -> text.append("<br/>")
                    }
                }
                r.getElementsByTag("a:blip").forEach { blip ->
                    val path = rels[blip.attr("r:embed")] ?: return@forEach
                    val bytes = images[path] ?: entry(path)?.also { images[path] = it }
                    if (bytes != null && BookFormats.imageExtension(bytes.copyOf(minOf(12, bytes.size))) != null)
                        inner.append("<img src=\"${HtmlBook.escape(path)}\"/>")
                }
                var t = text.toString()
                if (t.isEmpty()) return@forEach
                if (on("w:b")) t = "<b>$t</b>"
                if (on("w:i")) t = "<i>$t</i>"
                inner.append(t)
            }
            val plain = Jsoup.parse(inner.toString()).text()
            if (level != null && level <= 2 && plain.isNotBlank()) {
                hasHeadings = true
                flush(); title = plain.take(60)
                html.append("<h$level>$inner</h$level>")
            } else {
                if (!hasHeadings && chars > 30_000) flush()
                html.append(if (level != null && plain.isNotBlank()) "<h${level.coerceAtMost(6)}>$inner</h${level.coerceAtMost(6)}>"
                    else if (center) "<p align=\"center\">$inner</p>" else "<p>$inner</p>")
                chars += plain.length
            }
        }
        flush()
        val core = xml("docProps/core.xml")
        val docTitle = core?.getElementsByTag("dc:title")?.firstOrNull()?.text()?.ifBlank { null } ?: displayName.substringBeforeLast('.')
        val author = core?.getElementsByTag("dc:creator")?.firstOrNull()?.text().orEmpty()
        return ImportTrace.stage("DOCX/生成章节") { HtmlBook.build(docTitle, author, parts, images) }
    }
}
