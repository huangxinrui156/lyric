package com.lyric.core

import java.net.URLDecoder
import java.nio.file.Paths
import java.util.zip.ZipInputStream
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** EPUB 2/3 text spine. External resources and active content are never loaded. */
class EpubParser : BookParser {
    override val extensions = setOf("epub")

    override fun parse(source: ContentSource, encoding: String?, chapterRegex: String?): Book {
        val files = mutableMapOf<String, ByteArray>()
        var total = 0L
        source.open().use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    require(files.size < 10_000) { "EPUB 文件条目过多" }
                    if (!entry.isDirectory) {
                        val bytes = zip.readBounded(32 * 1024 * 1024 + 1)
                        total += bytes.size
                        require(bytes.size <= 32 * 1024 * 1024 && total <= 160 * 1024 * 1024) {
                            "EPUB 解压内容过大"
                        }
                        files[normalize(entry.name)] = bytes
                    }
                    entry = zip.nextEntry
                }
            }
        }
        fun xml(path: String) =
            Jsoup.parse(
                String(files[path] ?: error("EPUB 缺少 $path"), Charsets.UTF_8),
                "",
                Parser.xmlParser(),
            )
        val root =
            xml("META-INF/container.xml").selectFirst("rootfile")?.attr("full-path")
                ?: error("EPUB 缺少出版物描述")
        val opf = xml(root)
        val base = root.substringBeforeLast('/', "")
        fun resolve(href: String) =
            normalize((if (base.isEmpty()) "" else "$base/") + href.substringBefore('#'))
        val manifest = opf.select("manifest > item").associateBy { it.attr("id") }
        val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }
        val anchors = mutableMapOf<String, Locator>()
        val chapters =
            spine.mapIndexed { index, item ->
                val path = resolve(item.attr("href"))
                val html =
                    Jsoup.parse(String(files[path] ?: error("EPUB 缺少章节 $path"), Charsets.UTF_8))
                val dir = path.substringBeforeLast('/', "")
                fun relative(href: String) = normalize((if (dir.isEmpty()) "" else "$dir/") + href.substringBefore('#'))
                val chapterCss = html.select("link[rel=stylesheet]").joinToString("\n") {
                    files[relative(it.attr("href"))]?.toString(Charsets.UTF_8).orEmpty()
                } + "\n" + html.select("style").joinToString("\n") { it.data().ifBlank { it.html() } }
                val extracted = EpubLayout.extract(html.body(), chapterCss, ::relative) { id, offset ->
                    anchors["$path#$id"] = Locator(index, offset)
                }
                val text = extracted.text
                Chapter(
                    index,
                    html.selectFirst("h1,h2,h3,title")?.text()?.ifBlank { null }
                        ?: "第 ${index + 1} 章",
                    text,
                    path,
                    styles = extracted.styles,
                ).also { it.legacyOffsets = extracted.legacyOffsets }
            }
        require(chapters.isNotEmpty()) { "EPUB 没有可阅读的章节" }
        fun locate(path: String, href: String): Locator? {
            val dir = path.substringBeforeLast('/', "")
            val full = normalize((if (dir.isEmpty()) "" else "$dir/") + href.substringBefore('#'))
            val fragment = href.substringAfter('#', "")
            return anchors["$full#$fragment"]
                ?: chapters.indexOfFirst { it.href == full }.takeIf { it >= 0 }?.let { Locator(it) }
        }
        val toc = mutableListOf<TocEntry>()
        val nav = manifest.values.firstOrNull { "nav" in it.attr("properties").split(' ') }
        if (nav != null) {
            val path = resolve(nav.attr("href"))
            val doc = xml(path)
            val navElement =
                doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") }
                    ?: doc.selectFirst("nav")
            navElement?.select("a[href]")?.forEach { a ->
                locate(path, a.attr("href"))?.let {
                    toc += TocEntry(a.text(), it.chapterIndex, it.charOffset)
                }
            }
        }
        if (toc.isEmpty()) {
            val ncx = manifest.values.firstOrNull { it.attr("media-type").contains("ncx") }
            if (ncx != null) {
                val path = resolve(ncx.attr("href"))
                xml(path).select("navPoint").forEach { point ->
                    val href = point.selectFirst("content")?.attr("src").orEmpty()
                    locate(path, href)?.let {
                        toc +=
                            TocEntry(
                                point.selectFirst("navLabel")?.text().orEmpty(),
                                it.chapterIndex,
                                it.charOffset,
                            )
                    }
                }
            }
        }
        val css =
            files
                .filterKeys { it.endsWith(".css") }
                .values
                .take(10)
                .joinToString("\n") { String(it, Charsets.UTF_8) }
        val lineHeight =
            Regex("line-height\\s*:\\s*([12](?:\\.\\d+)?)\\s*[;}]", RegexOption.IGNORE_CASE)
                .find(css)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
        val fonts = mutableMapOf<String, String>()
        val fontSheets = files.filterKeys { it.endsWith(".css") }.map { it.key to it.value.toString(Charsets.UTF_8) } +
            spine.map { item ->
                val path = resolve(item.attr("href"))
                path to Jsoup.parse(files.getValue(path).toString(Charsets.UTF_8)).select("style")
                    .joinToString("\n") { it.data().ifBlank { it.html() } }
            }
        fontSheets.forEach { (cssPath, sheet) ->
            Regex("@font-face\\s*\\{([^}]+)}", RegexOption.IGNORE_CASE).findAll(sheet).forEach { face ->
                val props = EpubLayout.declarations(face.groupValues[1])
                val family = props["font-family"]?.trim()?.trim('\'', '"')
                val src = Regex("url\\(['\"]?([^)'\"]+)").find(props["src"].orEmpty())?.groupValues?.get(1)
                if (family != null && src != null) {
                    val resolved = normalize(cssPath.substringBeforeLast('/', "").let { if (it.isEmpty()) src else "$it/$src" })
                    if (files.containsKey(resolved)) fonts[family] = resolved
                }
            }
        }
        val imagePaths = manifest.values.filter { it.attr("media-type").startsWith("image/") }.map { resolve(it.attr("href")) }.toSet()
        val resources = files.filterKeys { path ->
            path in imagePaths || path in fonts.values || path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")
        }.mapValues { java.util.Base64.getEncoder().encodeToString(it.value) }
        return Book(
            opf.getElementsByTag("dc:title").firstOrNull()?.text()?.ifBlank { null }
                ?: source.displayName.substringBeforeLast('.'),
            opf.getElementsByTag("dc:creator").firstOrNull()?.text()?.ifBlank { null } ?: "佚名",
            chapters,
            toc.ifEmpty { chapters.map { TocEntry(it.title, it.index) } },
            resources = resources,
            embeddedFonts = fonts,
            suggestedStyle =
                BookStyle(
                    !css.contains("sans-serif"),
                    lineHeight,
                    if (css.contains("text-indent")) !css.contains("text-indent: 0") else null,
                ),
        )
    }

    private fun normalize(path: String): String {
        val decoded = URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
        val normalized = Paths.get(decoded).normalize().toString().replace('\\', '/')
        require(!normalized.startsWith("../") && !normalized.startsWith('/')) { "EPUB 路径无效" }
        return normalized
    }
}
