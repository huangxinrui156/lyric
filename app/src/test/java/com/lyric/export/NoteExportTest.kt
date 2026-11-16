package com.lyric.export

import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.lyric.data.HighlightRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoteExportTest {
    private fun note(text: String = "人生如逆旅，\n我亦是行人。", body: String = "") = HighlightRow(
        bookId = "b", chapterIndex = 1, start = 0, end = text.length, text = text, note = body,
        bookTitle = "临江仙", chapterTitle = "送钱穆父", createdAt = 1793448000000L)
    @Test fun emptyNoteHidesAllNoteFurniture() {
        listOf("", " \n ").forEach {
            val layout = NoteExport.cardLayout(note(body = it))
            assertNull(layout.note); assertNull(layout.divider)
            assertTrue(layout.quoteOnly); assertEquals(168, layout.quoteX); assertEquals(120, layout.barX)
            assertEquals(72f, layout.quote.paint.textSize)
        }
        val withNote = NoteExport.cardLayout(note(body = "站台上的风。"))
        assertNotNull(withNote.note); assertEquals(640, withNote.divider)
        assertFalse(withNote.quoteOnly); assertEquals(64f, withNote.quote.paint.textSize)
        assertEquals(40f, withNote.note!!.paint.textSize)
    }
    @Test fun longQuoteAndNoteNeverOverlapFooterOrLeaveTheirBounds() {
        for (body in listOf("", "我的笔记。".repeat(600))) {
            val l = NoteExport.cardLayout(note("长长的旅途，走过山河。".repeat(800), body))
            assertTrue(l.quoteTop >= 0)
            assertTrue(l.quoteTop + l.quote.height < 1195)
            assertTrue(l.quote.paint.textSize < 64f)
            val width = if (l.quoteOnly) 792.1f else 844.1f
            for (i in 0 until l.quote.lineCount) assertTrue(l.quote.getLineRight(i) <= width)
            l.attribution?.let { assertTrue(l.quoteTop + maxOf(200, l.quote.height) + 50 + it.height < 1280) }
            l.note?.let { assertTrue(l.divider!! + 130 + it.height <= 1140) }
        }
    }
    @Test fun dimensionsAndExactPalettes() {
        for ((night, color) in listOf(false to "#F4EFE6", true to "#16171A")) {
            val b = NoteExport.render(note(), "苏轼", night)
            assertEquals(1080, b.width); assertEquals(1440, b.height)
            assertEquals(Color.parseColor(color), b.getPixel(0, 0)); b.recycle()
        }
    }
    @Test fun quoteOnlyAttributionNamesAuthorAndBook() {
        assertEquals("—— 苏轼《临江仙》", NoteExport.attributionText(note(), "苏轼"))
        assertEquals("—— 《临江仙》", NoteExport.attributionText(note(), " "))
    }
    @Test fun markdownGroupsBooksAndPreservesMultilineQuoteNoteAndMetadata() {
        val a = note("first\nsecond", "my note").copy(bookId = "a", bookTitle = "A")
        val b = note("other").copy(bookId = "b", bookTitle = "B")
        val c = a.copy(start = 10, text = "later", note = "")
        val md = NoteExport.markdown(listOf(c, b, a), mapOf("a" to "Author", "b" to "Writer"))
        assertEquals(1, Regex("# A — Author").findAll(md).count())
        assertTrue(md.contains("# B — Writer"))
        assertTrue(md.contains("> first\n> second\n\nmy note\n"))
        assertTrue(md.indexOf("> first") < md.indexOf("> later"))
        assertTrue(md.contains("送钱穆父 · 2026.10.31"))
        assertEquals("", NoteExport.markdown(emptyList(), emptyMap()))
    }
    @Test fun fileProviderOnlySharesCacheExportWithMimeAndReadGrant() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = NoteExport.markdownFile(context, listOf(note()), emptyMap())
        assertEquals("note_exports", file.parentFile!!.name)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
        assertEquals("content", uri.scheme)
        assertEquals(file.readText(), context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
    }
}
