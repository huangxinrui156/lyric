package com.lyric.core

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** Deliberately small, inert CSS subset. No network, scripts, floats, tables or columns. */
object EpubLayout {
    data class Rule(val selector: String, val declarations: Map<String, String>, val priority: Int) {
        /** Compiled once; Element.is(String) re-parses the selector on every call (O(elements x rules)). */
        val evaluator: org.jsoup.select.Evaluator? by lazy(LazyThreadSafetyMode.NONE) {
            runCatching { org.jsoup.select.QueryParser.parse(selector) }.getOrNull()
        }
    }
    fun declarations(css: String): Map<String, String> = css.split(';').mapNotNull {
        val colon = it.indexOf(':')
        if (colon < 0) null else it.take(colon).trim().lowercase() to
            it.drop(colon + 1).trim().removeSuffix("!important").trim()
    }.toMap()

    fun rules(css: String): List<Rule> = Regex("([^\\{\\}]+)\\{([^\\{\\}]*)\\}")
        .findAll(css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), ""))
        .filter { !it.groupValues[1].trim().startsWith('@') }
        .flatMap { match -> match.groupValues[1].split(',').map { selector ->
            val s = selector.trim()
            Rule(s, declarations(match.groupValues[2]),
                s.count { it == '#' } * 100 + s.count { it == '.' || it == '[' } * 10 +
                    Regex("[a-zA-Z][\\w-]*").findAll(s).count())
        } }.sortedBy { it.priority }.toList()

    fun em(value: String?): Float? {
        val raw = value?.trim()?.lowercase() ?: return null
        val n = Regex("^-?\\d*\\.?\\d+").find(raw)?.value?.toFloatOrNull() ?: return null
        return when {
            raw.endsWith('%') -> n / 100
            raw.endsWith("px") || raw.endsWith("pt") -> n / 16
            else -> n
        }.coerceIn(-4f, 6f)
    }

    data class Result(val text: String, val styles: List<TextStyleRange>, val legacyOffsets: List<OffsetMapping>)
    fun extract(body: Element, css: String, resolve: (String) -> String,
                anchor: (String, Int) -> Unit): Result = extract(body, rules(css), resolve, anchor)

    fun extract(body: Element, rules: List<Rule>, resolve: (String) -> String,
                anchor: (String, Int) -> Unit): Result {
        val root = body.root()
        val out = StringBuilder()
        val styles = mutableListOf<TextStyleRange>()
        val currentRanges = java.util.IdentityHashMap<Node, Pair<Int, Int>>()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    val start = out.length
                    out.append(node.text())
                    currentRanges[node] = start to out.length
                }
                is Element -> {
                    val tag = node.normalName()
                    if (tag in setOf("script", "style", "noscript", "rt", "rp")) return
                    val block = tag in setOf("p", "div", "section", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "br", "hr", "center", "figure")
                    if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    val start = out.length
                    if (node.id().isNotEmpty()) anchor(node.id(), start)
                    val authored = mutableMapOf<String, String>()
                    rules.forEach { rule ->
                        val ev = rule.evaluator
                        if (ev != null && runCatching { ev.matches(root, node) }.getOrDefault(false)) authored.putAll(rule.declarations)
                    }
                    authored.putAll(declarations(node.attr("style")))
                    when (tag) {
                        "img", "image" -> out.append('\uFFFC')
                        "hr" -> out.append('\uFFFC')
                        else -> node.childNodes().forEach(::walk)
                    }
                    if (block && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                    val end = out.length
                    currentRanges[node] = start to end
                    if (end > start) {
                        val heading = tag.removePrefix("h").toIntOrNull()?.takeIf { tag.startsWith('h') && it in 1..6 }
                        styles += TextStyleRange(start, end,
                            bold = tag in setOf("b", "strong") || heading != null,
                            italic = tag in setOf("i", "em"),
                            sizeEm = heading?.let { (2.1f - it * .18f).coerceAtLeast(1f) },
                            align = if (tag == "center") "center" else node.attr("align").ifBlank { null },
                            resource = if (tag == "img" || tag == "image") runCatching {
                                resolve(node.attr("src").ifBlank { node.attr("xlink:href") })
                            }.getOrNull() else null,
                            ruby = if (tag == "ruby") node.select("rt").joinToString(" ") { it.text() }.ifBlank { null } else null,
                            rule = tag == "hr")
                        if (authored.isNotEmpty()) styles += TextStyleRange(start, end,
                            bold = authored["font-weight"]?.let { it == "bold" || (it.toIntOrNull() ?: 0) >= 600 } ?: false,
                            italic = authored["font-style"] in setOf("italic", "oblique"),
                            sizeEm = em(authored["font-size"])?.coerceIn(.5f, 3f),
                            align = authored["text-align"], indentEm = em(authored["text-indent"]),
                            fontFamily = authored["font-family"]?.substringBefore(',')?.trim()?.trim('\'', '"'),
                            bookStyle = true)
                    }
                }
            }
        }
        walk(body)
        // Reproduce v0.2 text boundaries without modifying the DOM, then map its leaf nodes.
        // This preserves old progress/bookmarks even if all old parsed caches were cleared.
        val legacy = StringBuilder()
        val mapping = mutableListOf<OffsetMapping>()
        fun addMapping(node: Node, start: Int, end: Int) {
            var parent: Node? = node
            while (parent != null && !currentRanges.containsKey(parent)) parent = parent.parentNode()
            val range = parent?.let { currentRanges[it] } ?: (0 to 0)
            val a = if (parent === node) range.first else range.second
            mapping += OffsetMapping(start, end, a, range.second)
        }
        fun legacyWalk(node: Node) {
            when (node) {
                is TextNode -> {
                    val start = legacy.length
                    legacy.append(node.text())
                    addMapping(node, start, legacy.length)
                }
                is Element -> {
                    val tag = node.normalName()
                    if (tag in setOf("script", "style", "noscript")) return
                    val block = tag in setOf("p", "div", "section", "h1", "h2", "h3", "li", "blockquote", "br", "hr")
                    if (block && legacy.isNotEmpty() && legacy.last() != '\n') legacy.append('\n')
                    if (tag == "img") {
                        val start = legacy.length
                        legacy.append("［插图：${node.attr("alt").ifBlank { "图片" }}］")
                        addMapping(node, start, legacy.length)
                    }
                    node.childNodes().forEach(::legacyWalk)
                    if (block && legacy.isNotEmpty() && legacy.last() != '\n') legacy.append('\n')
                }
            }
        }
        legacyWalk(body)
        return Result(out.toString().ifBlank { "本节无文字内容。" }, styles, mapping)
    }
}
