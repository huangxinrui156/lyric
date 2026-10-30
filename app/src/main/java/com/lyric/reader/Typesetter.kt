package com.lyric.reader

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import com.lyric.core.ReaderSettings
import kotlin.math.roundToInt

data class TextPage(val start: Int, val end: Int, val top: Int, val bottom: Int)

data class TypesetChapter(
    val layout: StaticLayout,
    val pages: List<TextPage>,
    val sourceStart: Int,
    val sourceEnd: Int,
    val plainText: String,
)

object Typesetter {
    const val WINDOW = 12_000

    // API 35 annotates these API 26 constants with their newer LineBreaker aliases.
    @SuppressLint("WrongConstant")
    fun layout(
        text: String,
        at: Int,
        width: Int,
        height: Int,
        density: Float,
        scaledDensity: Float,
        settings: ReaderSettings,
        font: Typeface,
    ): TypesetChapter {
        var from = at.coerceAtLeast(0) / WINDOW * WINDOW
        if (from > 0 && from < text.length && text[from].isLowSurrogate()) from--
        var to = minOf((at.coerceAtLeast(0) / WINDOW + 1) * WINDOW, text.length)
        if (to < text.length && text[to].isLowSurrogate()) to++
        val span = SpannableString(text.substring(from, to))
        val size = settings.fontSize * scaledDensity
        span.setSpan(
            ExactLineHeight((size * settings.lineHeight).roundToInt()),
            0,
            span.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        var start = 0
        while (start < span.length) {
            val end = span.indexOf('\n', start).let { if (it < 0) span.length else it + 1 }
            if (settings.indent && (start > 0 || from == 0 || text.getOrNull(from - 1) == '\n'))
                span.setSpan(
                    LeadingMarginSpan.Standard((size * 2).toInt(), 0),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            if (settings.paragraphSpace > 0)
                span.setSpan(
                    ParagraphSpace((size * settings.paragraphSpace).toInt()),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            start = end
        }
        val paint =
            TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = font
            }
        val layout =
            StaticLayout.Builder.obtain(span, 0, span.length, paint, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setJustificationMode(
                    if (settings.justify) Layout.JUSTIFICATION_MODE_INTER_WORD
                    else Layout.JUSTIFICATION_MODE_NONE
                )
                .build()
        val pages = mutableListOf<TextPage>()
        var firstLine = 0
        while (firstLine < layout.lineCount) {
            val top = layout.getLineTop(firstLine)
            var last = firstLine
            while (
                last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= height
            ) last++
            pages +=
                TextPage(
                    layout.getLineStart(firstLine),
                    layout.getLineEnd(last),
                    top,
                    layout.getLineBottom(last),
                )
            firstLine = last + 1
        }
        return TypesetChapter(layout, pages, from, to, span.toString())
    }

    /** CSS-style em line height, independent of the font's built-in ascent/descent. */
    private class ExactLineHeight(private val height: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: android.graphics.Paint.FontMetricsInt,
        ) {
            val original = (fm.descent - fm.ascent).coerceAtLeast(1)
            fm.descent = (fm.descent.toFloat() / original * height).roundToInt()
            fm.ascent = fm.descent - height
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }

    private class ParagraphSpace(private val extra: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            v: Int,
            fm: android.graphics.Paint.FontMetricsInt,
        ) {
            if (end > 0 && text.getOrNull(end - 1) == '\n') {
                fm.descent += extra
                fm.bottom += extra
            }
        }
    }
}
