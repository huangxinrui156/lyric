package com.lyric.core

import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipFile
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** EPUB 2/3 text spine. External resources and active content are never loaded. */
class EpubParser(private val tempDirectory: File? = null) : BookParser {
    override val extensions = setOf("epub")

    override fun parse(source: ContentSource, encoding: String?, chapterRegex: String?): Book {
        val owned = source !is FileContentSource
        val archive = if (source is FileContentSource) source.file else
            ImportTrace.stage("EPUB/复制随机访问文件") {
                File.createTempFile("import-epub-", ".tmp", tempDirectory).also { file ->
                    try {
                        source.open().use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                ImportProgress.check()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 200L * 1024 * 1024) { "文件超过 200MB" }
                                output.write(buffer, 0, count)
                            }
                        } }
                    } catch (e: Exception) { file.delete(); throw e }
                }
            }
        try {
            ImportTrace.stage("EPUB/ZIP") { checkArchive(archive) }
            ImportTrace.stage("EPUB/图片书检测") { ImageEpub.detect(archive, source.displayName) }?.let { return it }
            var first: Exception? = null
            // ZIP central-directory sizes support STORED descriptors and ZIP64. Legacy names
            // are retried against the actual OPF references, rather than accepting mojibake.
            for (charset in listOf("UTF-8", "GBK", "Cp437")) {
                try {
                    val parent = tempDirectory ?: File(requireNotNull(System.getProperty("java.io.tmpdir")))
                    val directory = java.nio.file.Files.createTempDirectory(parent.toPath(), "epub-unpack-").toFile()
                    try {
                        val files = ImportTrace.stage("EPUB/ZIP($charset)") { readArchive(archive, charset, directory) }
                        return parseFiles(source.displayName, files)
                    } finally { directory.deleteRecursively() }
                } catch (e: Exception) {
                    e.rethrowImportAbort()
                    if (first == null) first = e else first.addSuppressed(e)
                }
            }
            throw requireNotNull(first)
        } finally { if (owned) archive.delete() }
    }

    private fun readArchive(file: File, charset: String, directory: File): Map<String, File> {
        val files = mutableMapOf<String, File>()
        var total = 0L
        ZipFile(file, Charset.forName(charset)).use { zip ->
            val entries = zip.entries()
            var count = 0
            val buffer = ByteArray(65536)
            while (entries.hasMoreElements()) {
                ImportProgress.check()
                if (++count > MAX_ARCHIVE_ENTRIES) throw ArchiveLimitException()
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                require(entry.size <= 32L * 1024 * 1024) { "EPUB 条目过大：${entry.name}" }
                val name = EpubPaths.archive(entry.name)
                require(!files.containsKey(name)) { "EPUB 存在重复条目：$name" }
                // Numeric filenames cannot traverse directories; only the map keeps EPUB paths.
                val target = File(directory, count.toString())
                zip.getInputStream(entry).use { input -> target.outputStream().use { output ->
                    var size = 0L
                    while (true) {
                        ImportProgress.check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n; total += n
                        require(size <= 32L * 1024 * 1024 && total <= 160L * 1024 * 1024) { "EPUB 解压内容过大" }
                        output.write(buffer, 0, n)
                    }
                } }
                files[name] = target
            }
        }
        return files
    }

    /** XML local names are independent of the publisher's chosen namespace prefixes. */
    private fun Element.local(name: String): Elements = Elements(
        getAllElements().filter { it.tagName().substringAfter(':').equals(name, true) })
    private fun Element.childrenLocal(name: String): List<Element> = children().filter {
        it.tagName().substringAfter(':').equals(name, true)
    }

    private fun parseFiles(displayName: String, files: Map<String, File>): Book {
        fun document(path: String, xml: Boolean = false) = ImportTrace.stage("EPUB/文档 $path") {
            // Jsoup detects BOM/XML/meta charsets and tolerates broken XHTML and HTML entities.
            // It never fetches external DTDs, entities, stylesheets or other network resources.
            ImportProgress.check()
            (files[path] ?: error("EPUB 缺少 $path")).inputStream().use {
                Jsoup.parse(it, null, "", if (xml) Parser.xmlParser() else Parser.htmlParser())
            }
        }
        fun xml(path: String) = document(path, true)
        val root = ImportTrace.stage("EPUB/container.xml") {
            val roots = xml("META-INF/container.xml").local("rootfile")
            (roots.firstOrNull { it.attr("media-type") == "application/oebps-package+xml" } ?: roots.firstOrNull())
                ?.attr("full-path")?.takeIf { it.isNotBlank() } ?: error("EPUB 缺少出版物描述")
        }
        val packagePath = EpubPaths.resolve("", root)
        val opf = ImportTrace.stage("EPUB/OPF") { xml(packagePath) }
        fun resolve(href: String) = EpubPaths.resolve(packagePath, href)
        val manifest = opf.local("manifest").flatMap { it.childrenLocal("item") }.associateBy { it.attr("id") }
        val obfuscatedFonts = EpubEncryption.inspect(files["META-INF/encryption.xml"]?.readText(),
            EpubEncryption.fontPaths(manifest.values, ::resolve))
        val spine = ImportTrace.stage("EPUB/spine") {
            opf.local("spine").flatMap { it.childrenLocal("itemref") }.map { ref ->
                manifest[ref.attr("idref")] ?: error("EPUB spine 引用了不存在的条目：${ref.attr("idref")}")
            }
        }
        val anchors = mutableMapOf<String, Locator>()
        val ruleCache = HashMap<String, List<EpubLayout.Rule>>()
        val inlineSheets = mutableListOf<Pair<String, String>>()
        val chapters =
            spine.mapIndexed { index, item ->
                ImportProgress.tick("解析章节", index + 1, spine.size)
                val path = resolve(item.attr("href"))
                val html = document(path)
                fun relative(href: String) = EpubPaths.resolve(path, href)
                val chapterCss = html.select("link[rel=stylesheet]").joinToString("\n") {
                    importAttempt { files[relative(it.attr("href"))]?.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
                }
                val inline = html.select("style").joinToString("\n") { it.data().ifBlank { it.html() } }
                if (inline.isNotBlank()) inlineSheets += path to inline
                val chapterRules = ruleCache.getOrPut(chapterCss + "\n" + inline) { EpubLayout.rules(chapterCss + "\n" + inline) }
                val extracted = EpubLayout.extract(html.body(), chapterRules, ::relative) { id, offset ->
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
        val chapterByHref = HashMap<String, Int>().also { m -> chapters.forEach { m.putIfAbsent(it.href, it.index) } }
        fun locate(path: String, href: String): Locator? {
            val full = importAttempt { EpubPaths.resolve(path, href) }.getOrNull() ?: return null
            val fragment = importAttempt { EpubPaths.decode(href.substringAfter('#', "")) }.getOrNull() ?: return null
            return anchors["$full#$fragment"]
                ?: chapterByHref[full]?.let { Locator(it) }
        }
        val toc = mutableListOf<TocEntry>()
        val nav = manifest.values.firstOrNull { "nav" in it.attr("properties").split(Regex("\\s+")) }
        if (nav != null) {
            val path = resolve(nav.attr("href"))
            val doc = if (files.containsKey(path)) document(path) else null
            val navElement =
                doc?.local("nav")?.firstOrNull { it.attr("epub:type").contains("toc") }
                    ?: doc?.local("nav")?.firstOrNull()
            navElement?.select("a[href]")?.forEach { a ->
                locate(path, a.attr("href"))?.let {
                    toc += TocEntry(a.text(), it.chapterIndex, it.charOffset)
                }
            }
        }
        if (toc.isEmpty()) {
            val ncx = manifest.values.firstOrNull { it.attr("media-type").contains("ncx") }
            if (ncx != null && files.containsKey(resolve(ncx.attr("href")))) {
                val path = resolve(ncx.attr("href"))
                xml(path).local("navPoint").forEach { point ->
                    val href = point.local("content").firstOrNull()?.attr("src").orEmpty()
                    locate(path, href)?.let {
                        toc +=
                            TocEntry(
                                point.local("navLabel").firstOrNull()?.text().orEmpty(),
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
                .joinToString("\n") { it.readText(Charsets.UTF_8) }
        val lineHeight =
            Regex("line-height\\s*:\\s*([12](?:\\.\\d+)?)\\s*[;\\}]", RegexOption.IGNORE_CASE)
                .find(css)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
        val fonts = mutableMapOf<String, String>()
        val fontSheets = files.filterKeys { it.endsWith(".css") }.map { it.key to it.value.readText(Charsets.UTF_8) } +
            inlineSheets
        fontSheets.forEach { (cssPath, sheet) ->
            Regex("@font-face\\s*\\{([^\\}]+)\\}", RegexOption.IGNORE_CASE).findAll(sheet).forEach { face ->
                val props = EpubLayout.declarations(face.groupValues[1])
                val family = props["font-family"]?.trim()?.trim('\'', '"')
                val src = Regex("url\\(['\"]?([^)'\"]+)").find(props["src"].orEmpty())?.groupValues?.get(1)
                if (family != null && src != null) {
                    val resolved = importAttempt { EpubPaths.resolve(cssPath, src) }.getOrNull()
                    if (resolved != null && files.containsKey(resolved) && resolved !in obfuscatedFonts) fonts[family] = resolved
                }
            }
        }
        val imagePaths = manifest.values.filter { it.attr("media-type").startsWith("image/") }.mapNotNull { importAttempt { resolve(it.attr("href")) }.getOrNull() }.toSet()
        val resources = files.filterKeys { path ->
            path in imagePaths || path in fonts.values || path.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")
        }.mapValues {
            ImportProgress.check()
            // Keep parsed-v1 JSON compatible; at most one resource's raw bytes is resident.
            java.util.Base64.getEncoder().encodeToString(it.value.readBytes())
        }
        return Book(
            opf.local("title").firstOrNull()?.text()?.ifBlank { null }
                ?: displayName.substringBeforeLast('.'),
            opf.local("creator").firstOrNull()?.text()?.ifBlank { null } ?: "佚名",
            chapters,
            toc.ifEmpty { chapters.map { TocEntry(it.title, it.index) } },
            resources = resources,
            coverResource = (manifest.values.firstOrNull { "cover-image" in it.attr("properties").split(Regex("\\s+")) }
                ?: opf.local("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let(manifest::get))
                ?.let { importAttempt { resolve(it.attr("href")) }.getOrNull() }?.takeIf { it in resources },
            embeddedFonts = fonts,
            suggestedStyle =
                BookStyle(
                    !css.contains("sans-serif"),
                    lineHeight,
                    if (css.contains("text-indent")) !css.contains("text-indent: 0") else null,
                ),
        )
    }

}
