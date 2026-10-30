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
}
