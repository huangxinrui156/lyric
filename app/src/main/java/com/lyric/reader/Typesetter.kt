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
import android.text.style.*
import com.lyric.core.Chapter
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
        chapter: Chapter? = null,
        assets: BookAssets? = null,
        /** Page margin each side; standalone images are drawn across it, edge to edge. */
        bleed: Int = 0,
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
            val authoredIndent = if (settings.useBookStyle) chapter?.styles
                ?.filter { it.bookStyle && it.indentEm != null && it.start <= from + start && it.end > from + start }
                ?.minByOrNull { it.end - it.start }?.indentEm else null
            val structural = span[start] == '\uFFFC' || chapter?.styles?.any {
                !it.bookStyle && (it.sizeEm != null || it.align == "center") && it.start <= from + start && it.end > from + start
            } == true
            val indentEm = if (span[start] == '\uFFFC') 0f else authoredIndent ?: if (settings.indent && !structural) 2f else 0f
            if (indentEm != 0f && (start > 0 || from == 0 || text.getOrNull(from - 1) == '\n'))
                span.setSpan(
                    LeadingMarginSpan.Standard((size * indentEm).toInt(), 0),
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
        chapter?.styles?.filter { it.end > from && it.start < to && (!it.bookStyle || settings.useBookStyle) }
            ?.sortedByDescending { it.end - it.start }?.forEach { style ->
                val a = (style.start - from).coerceAtLeast(0)
                val b = (style.end - from).coerceAtMost(span.length)
                fun apply(value: Any, end: Int = b) { if (end > a) span.setSpan(value, a, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                if (style.bold || style.italic) apply(StyleSpan(
                    (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)))
                val overriddenSize = !style.bookStyle && settings.useBookStyle && chapter.styles.any {
                    it.bookStyle && it.start == style.start && it.end == style.end && it.sizeEm != null
                }
                if (!overriddenSize) style.sizeEm?.let { apply(RelativeSizeSpan(it)) }
                style.align?.let { alignment -> apply(AlignmentSpan.Standard(when (alignment) {
                    "center" -> Layout.Alignment.ALIGN_CENTER
                    "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                })) }
                if (settings.useBookStyle) style.fontFamily?.let { family ->
                    val face = assets?.font(family)
                    if (face != null) apply(BookTypefaceSpan(face)) else apply(TypefaceSpan(family))
                }
                style.resource?.let { path ->
                    // Standalone = the image is alone on its line (paragraph breaks or chapter edges around it).
                    val alone = (a == 0 || span[a - 1] == '\n') && (minOf(a + 1, b) >= span.length || span[minOf(a + 1, b)] == '\n')
                    val extra = if (alone) bleed else 0
                    apply(BookImageSpan(path, assets?.image(path, width + 2 * extra, height), width, (height * .85f).toInt(), extra), minOf(a + 1, b))
                }
                if (style.rule) apply(RuleSpan(width), minOf(a + 1, b))
                style.ruby?.let { apply(RubySpan(it)) }
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
            if (span.isNotEmpty() && layout.getLineStart(firstLine) >= span.length) break
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

    private class BookTypefaceSpan(private val face: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.typeface = Typeface.create(face, tp.typeface?.style ?: Typeface.NORMAL) }
        override fun updateMeasureState(tp: TextPaint) = updateDrawState(tp)
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
            val actual = maxOf(height, original)
            fm.descent = (fm.descent.toFloat() / original * actual).roundToInt()
            fm.ascent = fm.descent - actual
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
