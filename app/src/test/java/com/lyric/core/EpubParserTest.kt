package com.lyric.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EpubParserTest {
    private fun zip(entries: Map<String, String>): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                ZipOutputStream(out).use { z ->
                    entries.forEach { (path, text) ->
                        z.putNextEntry(ZipEntry(path))
                        z.write(text.toByteArray())
                        z.closeEntry()
                    }
                }
            }
            .toByteArray()

    private fun fixture(nav: Boolean): Map<String, String> =
        mapOf(
            "META-INF/container.xml" to
                """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to
                """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>雨落之前</dc:title><dc:creator>林间</dc:creator></metadata><manifest><item id="b" href="second.xhtml" media-type="application/xhtml+xml"/><item id="a" href="first.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
            "OPS/first.xhtml" to
                """<html><body><h1>清晨</h1><p>第一段文字。</p><p id="leaf">叶子在风中。</p><script>禁止脚本</script></body></html>""",
            "OPS/second.xhtml" to """<html><body><h1>黄昏</h1><p>雨停了。</p></body></html>""",
            "OPS/nav.xhtml" to
                if (nav)
                    """<html><body><nav epub:type="toc"><ol><li><a href="first.xhtml">清晨目录</a></li><li><a href="first.xhtml#leaf">一片叶子</a></li><li><a href="second.xhtml">黄昏目录</a></li></ol></nav></body></html>"""
                else "<html/>",
            "OPS/toc.ncx" to
                """<ncx><navMap><navPoint><navLabel><text>清晨 NCX</text></navLabel><content src="first.xhtml#leaf"/></navPoint><navPoint><navLabel><text>黄昏 NCX</text></navLabel><content src="second.xhtml"/></navPoint></navMap></ncx>""",
        )

    @Test
    fun epub3NavSpineMetadataAnchorsAndScriptRemoval() {
        val b = EpubParser().parse(BytesSource(zip(fixture(true)), "测试.epub"), null, null)
        assertEquals("雨落之前", b.title)
        assertEquals("林间", b.author)
        assertEquals(listOf("清晨", "黄昏"), b.chapters.map { it.title })
        assertEquals(3, b.toc.size)
        assertEquals("一片叶子", b.toc[1].title)
        assertTrue(b.chapters[0].text.drop(b.toc[1].charOffset).startsWith("叶子"))
        assertFalse(b.chapters[0].text.contains("禁止脚本"))
    }

    @Test
    fun epub2NcxFallback() {
        val b = EpubParser().parse(BytesSource(zip(fixture(false)), "测试.epub"), null, null)
        assertEquals(listOf("清晨 NCX", "黄昏 NCX"), b.toc.map { it.title })
        assertEquals(1, b.toc.last().chapterIndex)
    }

    @Test
    fun brokenAndTraversalArchivesFailClearly() {
        assertThrows(Exception::class.java) {
            EpubParser().parse(BytesSource(zip(mapOf("../evil" to "x")), "测试.epub"), null, null)
        }
        assertThrows(Exception::class.java) {
            EpubParser().parse(BytesSource(zip(emptyMap()), "测试.epub"), null, null)
        }
    }
    @Test fun layoutSubsetPreservesOffsetsRubyImagesCssAndFonts() {
        val entries = fixture(true).toMutableMap()
        entries["OPS/first.xhtml"] = """<html><head><link rel="stylesheet" href="styles/book.css"/></head><body><h1>大标题</h1><p class="quote" id="leaf" style="text-align:right">前文<strong>粗体</strong><em>斜体</em><ruby>漢字<rt>かんじ</rt><rp>(</rp></ruby>后文</p><center>居中</center><hr/><p><img src="images/p.png" alt="插图"/></p></body></html>"""
        entries["OPS/styles/book.css"] = """@font-face {font-family:'BookFont';src:url('../fonts/book.ttf')} body {font-family:BookFont} p {text-indent:2em} .quote {text-align:center; font-size:120%; font-style:italic} h1 {font-size:2em} """
        entries["OPS/fonts/book.ttf"] = "fixture font bytes"
        entries["OPS/images/p.png"] = "fixture image bytes"
        val b = EpubParser().parse(BytesSource(zip(entries), "styles.epub"), null, null)
        val c = b.chapters[0]
        assertFalse(c.text.contains("かんじ"))
        assertFalse(c.text.contains("插图"))
        assertTrue(c.text.contains("漢字"))
        assertEquals("漢字", c.styles.first { it.ruby != null }.let { c.text.substring(it.start, it.end) })
        assertEquals("かんじ", c.styles.first { it.ruby != null }.ruby)
        assertTrue(c.styles.any { it.bold && c.text.substring(it.start, it.end) == "粗体" })
        assertTrue(c.styles.any { it.italic && !it.bookStyle })
        assertTrue(c.styles.any { it.sizeEm != null && it.sizeEm > 1 })
        assertTrue(c.styles.any { it.align == "center" && !it.bookStyle })
        assertTrue(c.styles.any { it.align == "right" && it.bookStyle })
        assertTrue(c.styles.any { it.indentEm == 2f && it.bookStyle })
        assertTrue(c.styles.any { it.rule })
        val image = c.styles.first { it.resource != null }
        assertEquals('\uFFFC', c.text[image.start])
        assertEquals("OPS/images/p.png", image.resource)
        assertEquals("OPS/fonts/book.ttf", b.embeddedFonts["BookFont"])
        assertEquals("fixture font bytes", String(java.util.Base64.getDecoder().decode(b.resources[b.embeddedFonts["BookFont"]])))
        val json = kotlinx.serialization.json.Json
        assertEquals(b, json.decodeFromString(Book.serializer(), json.encodeToString(Book.serializer(), b)))
        assertTrue(c.text.drop(b.toc[1].charOffset).startsWith("前文"))
    }

    @Test fun twentyGeneratedSpinesCoverMarkupVariantsWithoutEmptyChapters() {
        val bodies = listOf("<h1>标题</h1><p>正文</p>", "<center><b>中心</b></center>",
            "<p style='text-indent:0;text-align:right'>右侧</p>", "<p><ruby>空<rt>そら</rt></ruby></p>",
            "<hr/>", "<p><img src='missing.jpg'/></p>", "<div><i>嵌套<b>粗斜体</b></i></div>",
            "<body><script>bad()</script></body>", "<p>😀<br/>换行</p>", "<h6>小标题</h6>")
        repeat(20) { variant ->
            val entries = fixture(true).toMutableMap()
            entries["OPS/first.xhtml"] = "<html><body>" + bodies[variant % bodies.size] + "</body></html>"
            val book = EpubParser().parse(BytesSource(zip(entries), "sample-$variant.epub"), null, null)
            assertTrue(book.chapters.all { it.text.isNotBlank() })
            book.chapters.forEach { c -> c.styles.forEach { style ->
                assertTrue(style.start >= 0 && style.end <= c.text.length && style.start < style.end)
            } }
        }
    }

}
