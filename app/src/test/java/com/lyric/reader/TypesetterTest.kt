package com.lyric.reader

import android.app.Application
import android.graphics.*
import android.text.Spanned
import android.text.style.*
import com.lyric.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypesetterTest {
    private fun layout(c: Chapter, settings: ReaderSettings = ReaderSettings(), at: Int = 0, width: Int = 320, height: Int = 480) =
        Typesetter.layout(c.text, at, width, height, 1f, 1f, settings, Typeface.SERIF, c)

    @Test fun repaginationRetainsCanonicalAnchorAndHasNoTrailingBlankPage() {
        val text = "雨落之前，字句之间有呼吸。The reader keeps the same sentence.\n".repeat(100)
        val c = Chapter(0, "test", text)
        val anchor = 2371
        for (mode in PageMode.entries) for (size in listOf(12f, 18f, 36f)) for (width in listOf(220, 640)) {
            val result = layout(c, ReaderSettings(fontSize = size, pageMode = mode), anchor, width)
            assertTrue(result.pages.isNotEmpty())
            assertTrue(result.pages.all { it.start < it.end })
            assertTrue(result.pages.any { anchor - result.sourceStart in it.start until it.end })
            assertEquals(text.substring(result.sourceStart, result.sourceEnd), result.plainText)
        }
    }
    @Test fun stylesHaveRealSpansAndBookCssCanBeDisabled() {
        val c = Chapter(0, "styles", "标题\n粗斜体\n漢字\n\uFFFC\n\uFFFC\n", styles = listOf(
            TextStyleRange(0, 3, bold = true, sizeEm = 1.8f, align = "center"),
            TextStyleRange(3, 6, bold = true, italic = true),
            TextStyleRange(7, 9, ruby = "かんじ"),
            TextStyleRange(10, 11, rule = true),
            TextStyleRange(12, 13, resource = "missing.png"),
            TextStyleRange(3, 7, sizeEm = 1.3f, align = "right", indentEm = 0f, bookStyle = true)))
        val custom = layout(c, ReaderSettings(useBookStyle = true))
        val standard = layout(c)
        val text = custom.layout.text as Spanned
        assertEquals(2, text.getSpans(0, text.length, RelativeSizeSpan::class.java).size)
        assertEquals(1, (standard.layout.text as Spanned).getSpans(0, text.length, RelativeSizeSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, RubySpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, RuleSpan::class.java).size)
        assertEquals(1, text.getSpans(0, text.length, BookImageSpan::class.java).size)
        assertTrue(text.getSpans(3, 6, LeadingMarginSpan::class.java).isEmpty()) // text-indent:0 overrides default
        assertEquals(c.text, custom.plainText)
        custom.layout.draw(Canvas(Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)))
    }
    @Test fun largeImagesFitPageAndRubyReservesSpaceAboveBaseText() {
        val paint = Paint().apply { textSize = 20f }
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val span = BookImageSpan("image", bitmap, 320, 400)
        val fm = Paint.FontMetricsInt()
        assertEquals(200, span.getSize(paint, "\uFFFC", 0, 1, fm))
        assertEquals(400, -fm.ascent)
        val ruby = RubySpan("かんじ")
        val plainAscent = paint.fontMetricsInt.ascent
        ruby.getSize(paint, "漢字", 0, 2, fm)
        assertTrue(fm.ascent < plainAscent)
    }
    @Test fun svgDecodesOfflineAndEmbeddedFontIsAppliedOnlyWithBookStyle() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val encode = java.util.Base64.getEncoder()
        val font = java.io.File("src/main/res/font/noto_serif_sc.otf").readBytes()
        val chapter = Chapter(0, "font", "书中字体", styles = listOf(TextStyleRange(0, 4, fontFamily = "BookFont", bookStyle = true)))
        val book = Book("assets", chapters = listOf(chapter), resources = mapOf(
            "font.otf" to encode.encodeToString(font),
            "p.svg" to encode.encodeToString("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><rect width="200" height="100" fill="red"/></svg>""".toByteArray())),
            embeddedFonts = mapOf("BookFont" to "font.otf"))
        val assets = BookAssets(context, book)
        val image = assets.image("p.svg", 320, 480)!!
        assertEquals(320, image.width)
        assertEquals(160, image.height)
        assertNotNull(assets.font("bookfont"))
        fun rendered(enabled: Boolean) = Typesetter.layout(chapter.text, 0, 320, 480, 1f, 1f,
            ReaderSettings(useBookStyle = enabled), Typeface.SERIF, chapter, assets).layout.text as Spanned
        val enabled = rendered(true)
        val disabled = rendered(false)
        assertTrue(enabled.getSpans(0, 4, MetricAffectingSpan::class.java).any { it.javaClass.simpleName == "BookTypefaceSpan" })
        assertFalse(disabled.getSpans(0, 4, MetricAffectingSpan::class.java).any { it.javaClass.simpleName == "BookTypefaceSpan" })
        assertNull(assets.font("missing"))
    }


    @Test fun blankLinesCollapseToParagraphGapWithoutShiftingLocators() {
        val settings = ReaderSettings(paragraphSpace = 0.5f, indent = false, fontSize = 18f, lineHeight = 1.7f)
        val loose = "第一段。\n\n\n第二段在这里。"
        val tight = "第一段。\n第二段在这里。"
        val looseChapter = Chapter(0, "段距", loose)
        val tightChapter = Chapter(0, "段距", tight)
        val withBlanks = layout(looseChapter, settings)
        val compact = layout(tightChapter, settings)
        assertEquals(loose, withBlanks.plainText)
        val at = loose.indexOf("第二")
        assertTrue(withBlanks.pages.any { at - withBlanks.sourceStart in it.start until it.end })
        assertEquals(loose[at], withBlanks.plainText[at])
        // Collapsed blank lines should not add a full extra line of height.
        val extra = withBlanks.layout.height - compact.layout.height
        assertTrue("blank lines added ${extra}px", extra in 0..4)
    }

    @Test fun chapterTitleHasNoIndentMediumSizeAndGapBeforeBody() {
        val title = "第一章 晨光"
        val text = "$title\n\n窗边有风经过。"
        val chapter = Chapter(0, title, text)
        val settings = ReaderSettings(paragraphSpace = 0.5f, indent = true, fontSize = 18f, lineHeight = 1.7f)
        val result = layout(chapter, settings)
        val spanned = result.layout.text as Spanned
        val titleEnd = title.length
        assertEquals(0, spanned.getSpans(0, titleEnd, android.text.style.LeadingMarginSpan::class.java).size)
        val bodyStart = text.indexOf("窗边")
        assertTrue(spanned.getSpans(bodyStart, bodyStart + 2, android.text.style.LeadingMarginSpan::class.java).isNotEmpty())
        val titleLineHeight = result.layout.getLineBottom(0) - result.layout.getLineTop(0)
        val bodyLine = (0 until result.layout.lineCount).first { result.layout.getLineStart(it) >= bodyStart }
        val bodyLineHeight = result.layout.getLineBottom(bodyLine) - result.layout.getLineTop(bodyLine)
        // 20sp title plus 24dp gap is distinctly taller than a body line (18sp × 1.7 plus 0.5em).
        assertTrue("title line $titleLineHeight vs body $bodyLineHeight", titleLineHeight > bodyLineHeight)
        assertTrue("expected 24dp gap in the title line, was $titleLineHeight", titleLineHeight >= 24 + 20)
        assertEquals(text, result.plainText)
    }
}
