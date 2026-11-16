package com.lyric.perf

import com.lyric.core.Book
import com.lyric.core.EpubParser
import com.lyric.core.FileContentSource
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.Assume.assumeTrue
import org.junit.Test

class LargeEpubPerfTest {
    @Test fun parseLargeEpub() {
        val path = System.getenv("LYRIC_PERF_EPUB") ?: return
        val f = File(path); assumeTrue(f.isFile)
        var t = System.nanoTime()
        val book = EpubParser().parse(object : FileContentSource { override val file = f; override val displayName = f.name; override fun open() = f.inputStream() })
        println("PERF parse chapters=${book.chapters.size} chars=${book.totalChars} styles=${book.chapters.sumOf { it.styles.size }} legacy=${book.chapters.sumOf { it.legacyOffsets.size }} res=${book.resources.values.sumOf { it.length }} ms=${(System.nanoTime() - t) / 1_000_000}")
        t = System.nanoTime()
        val counter = object : java.io.OutputStream() { var n = 0L; override fun write(b: Int) { n++ }; override fun write(b: ByteArray, o: Int, l: Int) { n += l } }
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        Json.encodeToStream(Book.serializer(), book, counter)
        println("PERF json bytes=${counter.n} ms=${(System.nanoTime() - t) / 1_000_000}")
    }
}
