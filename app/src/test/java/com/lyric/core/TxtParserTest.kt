package com.lyric.core

import kotlin.system.measureNanoTime
import org.junit.Assert.*
import org.junit.Test

class TxtParserTest {
    private fun parse(text: String, regex: String? = null) =
        TxtParser().parse(BytesSource(text.toByteArray()), chapterRegex = regex, encoding = null)

    @Test
    fun chineseAndEnglishHeadingsPreserveEveryCharacter() {
        val text = "书名\n作者\n\n第一卷 初见\n风来了。\n第十二章 归途\n雨停了。\nChapter IV Evening\nGood night.\n"
        val b = parse(text)
        assertEquals(
            listOf("序言", "第一卷 初见", "第十二章 归途", "Chapter IV Evening"),
            b.chapters.map { it.title },
        )
        assertEquals(text, b.chapters.joinToString("") { it.text })
    }

    @Test
    fun customRuleAndInvalidRule() {
        val b = parse("序\n◆ 清晨\n正文\n◆ 黄昏\n正文", "^◆ .+$")
        assertEquals(3, b.chapters.size)
        assertThrows(IllegalArgumentException::class.java) { parse("正文", "[") }
    }

    @Test
    fun fallbackSplitsAt10kWithoutBreakingSurrogates() {
        val text = "字".repeat(9999) + "🌿" + "文".repeat(15000)
        val b = parse(text)
        assertEquals(3, b.chapters.size)
        assertEquals(9999, b.chapters.first().text.length)
        assertEquals(text, b.chapters.joinToString("") { it.text })
        assertFalse(
            b.chapters.any { it.text.first().isLowSurrogate() || it.text.last().isHighSurrogate() }
        )
    }

    @Test
    fun longLinesAreNotHeadings() {
        val b = parse("第一章" + "字".repeat(20000))
        assertEquals("第 1 节", b.chapters.first().title)
    }

    @Test
    fun tenMegabyteParseUnderBudgetOnHost() {
        val content = buildString {
            repeat(360) {
                append("第${it + 1}章 测试\n")
                append("在山里，时间是被树影丈量的。\n".repeat(900))
            }
        }
        val bytes = content.toByteArray()
        assertTrue(bytes.size >= 10 * 1024 * 1024)
        parse("第一章 热身\n正文")
        var result: Book? = null
        val elapsed =
            measureNanoTime { result = TxtParser().parse(BytesSource(bytes), null, null) } /
                1_000_000.0
        println(
            "TXT benchmark: ${bytes.size} bytes, $elapsed ms, ${result!!.chapters.size} chapters (host JVM)"
        )
        assertEquals(360, result!!.chapters.size)
        assertTrue("10MB host parse took $elapsed ms", elapsed < 1500)
    }
}
