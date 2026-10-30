package com.lyric.core

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Test

class BytesSource(private val bytes: ByteArray, override val displayName: String = "测试.txt") :
    ContentSource {
    override fun open() = ByteArrayInputStream(bytes)
}

class EncodingTest {
    @Test
    fun utf8WithAndWithoutBom() {
        val text = "第一章 山间来信\n天地之间，风吹过竹林。🌿"
        assertEquals("UTF-8", Encoding.detect(text.toByteArray()))
        assertEquals(
            text,
            Encoding.decode(
                    byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()
                )
                .first,
        )
    }

    @Test
    fun gbkAndGb18030RoundTrip() {
        val text = "第一章 归途\n这是一个关于春天的故事，窗外有树影，桌上有一杯温茶。".repeat(100)
        val gbk = text.toByteArray(Charset.forName("GBK"))
        assertEquals("GB18030", Encoding.detect(gbk))
        assertEquals(text, Encoding.decode(gbk).first)
        val extended = text + "𠀀🌿"
        assertEquals(
            extended,
            Encoding.decode(extended.toByteArray(Charset.forName("GB18030"))).first,
        )
    }

    @Test
    fun utf16BothByteOrdersAndBomlessAscii() {
        val text = "第一章 雨落之前\n在山里，时间是被树影丈量的。"
        listOf(
                "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
                "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            )
            .forEach { (charset, bom) ->
                val bytes = bom + text.toByteArray(Charset.forName(charset))
                assertEquals(charset, Encoding.detect(bytes))
                assertEquals(text, Encoding.decode(bytes).first)
                assertEquals(
                    text,
                    Encoding.decode(text.toByteArray(Charset.forName(charset))).first,
                )
            }
        assertEquals(
            "UTF-16LE",
            Encoding.detect("Chapter 1\nA quiet local reader.".toByteArray(Charsets.UTF_16LE)),
        )
    }

    @Test
    fun big5TraditionalChinese() {
        val text = "第一章 山間來信\n這是關於春天的故事，窗外有樹影，桌上有一杯溫茶。我們在安靜的午後讀書，等待遠方的朋友。".repeat(40)
        val bytes = text.toByteArray(Charset.forName("Big5"))
        assertEquals("Big5", Encoding.detect(bytes))
        assertEquals(text, Encoding.decode(bytes).first)
    }

    @Test
    fun overrideAndCanonicalNewlines() {
        val bytes = "春天\r\n山间\r来信".toByteArray(Charset.forName("GBK"))
        val (decoded, encoding) = Encoding.decode(bytes, "GBK")
        assertEquals("春天\n山间\n来信", decoded)
        assertEquals("GBK", encoding)
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals("", Encoding.decode(byteArrayOf()).first)
    }
}
