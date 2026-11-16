package com.lyric.core

import org.junit.Assert.*
import org.junit.Test

class ImportProgressTest {
    @Test fun reportsProgressAndTimesOutWithClearMessage() {
        val seen = mutableListOf<String>()
        ImportProgress.run(60_000, { false }, { s, d, t -> seen += "$s $d/$t" }) { ImportProgress.tick("解析章节", 3, 10) }
        assertEquals(listOf("解析章节 3/10"), seen)
        val e = assertThrows(java.util.concurrent.TimeoutException::class.java) {
            ImportProgress.run(0, { false }, { _, _, _ -> }) { Thread.sleep(2); ImportProgress.tick("解析章节", 5, 10) }
        }
        assertTrue(e.message!!.contains("5 / 10"))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            ImportProgress.run(60_000, { true }, { _, _, _ -> }) { ImportProgress.tick("x", 1, 1) }
        }
        ImportProgress.tick("outside", 1, 1) // no scope: no-op
    }

    @Test fun compiledSelectorsMatchLikeJsoup() {
        val doc = org.jsoup.Jsoup.parse("<body><p class='a'>x</p><div>y</div></body>")
        val rules = EpubLayout.rules("p.a { font-weight: bold } div { text-align: center } @font-face { font-family: z }")
        val r = EpubLayout.extract(doc.body(), rules, { it }) { _, _ -> }
        assertTrue(r.styles.any { it.bookStyle && it.bold })
        assertTrue(r.styles.any { it.bookStyle && it.align == "center" })
    }
}
