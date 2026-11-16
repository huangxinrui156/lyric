package com.lyric.export

import com.lyric.ui.theme.LyricTokens
import androidx.compose.ui.graphics.toArgb

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.lyric.data.HighlightRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Worker-only layout/rendering. The same measured layouts are drawn without a second layout pass. */
object NoteExport {
    /** [quoteOnly] = 无笔记版式 (design/v0.9/src/export-card-quote.svg); otherwise 带笔记版式 (export-card.svg). */
    data class CardLayout(val quote: StaticLayout, val note: StaticLayout?, val quoteTop: Int, val divider: Int?,
                          val quoteOnly: Boolean = note == null, val quoteX: Int = if (note == null) 168 else 140,
                          val barX: Int = if (note == null) 120 else 96, val attribution: StaticLayout? = null)
    private fun date(time: Long) = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
    private fun paint(size: Float, color: Int, font: Typeface = Typeface.create("serif", Typeface.NORMAL)) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color; typeface = font
    }
    private fun layout(text: String, size: Float, width: Int, color: Int, maxLines: Int = Int.MAX_VALUE, font: Typeface = Typeface.create("serif", Typeface.NORMAL)) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint(size, color, font), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setLineSpacing(0f, 1.45f).setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END).build()

    // Shrink ordinary long quotes; bound pathological text with an explicit ellipsis at a readable size.
    private fun fit(text: String, size: Float, width: Int, height: Int, color: Int, font: Typeface): StaticLayout {
        var sp = size
        while (sp >= 24f) {
            val result = layout(text, sp, width, color, font = font)
            if (result.height <= height) return result
            sp -= 2f
        }
        var lines = (height / 36).coerceAtLeast(1)
        while (true) {
            val result = layout(text, 24f, width, color, lines, font)
            if (result.height <= height || lines == 1) return result
            lines--
        }
    }
    fun attributionText(h: HighlightRow, author: String) =
        "—— " + (if (author.isNotBlank()) author.trim() else "") + "《${h.bookTitle.ifBlank { "未名" }}》"

    fun cardLayout(h: HighlightRow, night: Boolean = false, font: Typeface = Typeface.create("serif", Typeface.NORMAL), author: String = ""): CardLayout {
        val ink = LyricTokens.colors(night).ink.toArgb()
        val muted = LyricTokens.colors(night).secondary.toArgb()
        val hasNote = h.note.isNotBlank()
        if (!hasNote) {
            val q = fit(h.text, 72f, 1080 - 168 - 120, 860, ink, font)
            val attr = layout(attributionText(h, author), 34f, 1080 - 168 - 120, muted, 2, font)
            val block = q.height + 50 + attr.height
            val top = ((1440 - block) / 2 - 30).coerceAtLeast(150)
            return CardLayout(q, null, top, null, attribution = attr)
        }
        val q = fit(h.text, 64f, 844, 460, ink, font)
        val top = minOf(300, 620 - q.height)
        val divider = maxOf(640, top + q.height + 60)
        val n = fit(h.note, 40f, 888, 1140 - (divider + 130), ink, font)
        return CardLayout(q, n, top, divider)
    }
    fun render(h: HighlightRow, author: String, night: Boolean, font: Typeface = Typeface.create("serif", Typeface.NORMAL)): Bitmap {
        val bg = LyricTokens.colors(night).background.toArgb()
        val muted = LyricTokens.colors(night).secondary.toArgb()
        val accent = LyricTokens.colors(night).vermilion.toArgb()
        val divider = (if (night) LyricTokens.Night.stroke else LyricTokens.Pending.exportDayDivider).toArgb()
        val l = cardLayout(h, night, font, author)
        val bitmap = Bitmap.createBitmap(1080, 1440, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(bg)
        fun draw(layout: StaticLayout, x: Float, y: Float) {
            canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
        }
        canvas.drawRect(l.barX.toFloat(), l.quoteTop.toFloat(), l.barX + 4f, (l.quoteTop + maxOf(200, l.quote.height)).toFloat(), Paint().apply { color = accent })
        draw(l.quote, l.quoteX.toFloat(), l.quoteTop.toFloat())
        if (l.quoteOnly) {
            l.attribution?.let { draw(it, l.quoteX.toFloat(), l.quoteTop + maxOf(200, l.quote.height) + 50f) }
            canvas.drawText("lyric", 540f, 1320f, paint(34f, muted, font).apply { textAlign = Paint.Align.CENTER })
            return bitmap
        }
        l.divider?.let { y ->
            canvas.drawRect(96f, y.toFloat(), 984f, y + 2f, Paint().apply { color = divider })
            draw(layout("我的笔记", 36f, 888, muted, font = font), 96f, y + 50f)
            draw(l.note!!, 96f, y + 130f)
        }
        draw(layout("《${h.bookTitle.ifBlank { "未名" }}》", 34f, 750, muted, 1, font), 96f, 1195f)
        draw(layout(listOf(author.trim(), date(h.createdAt)).filter { it.isNotBlank() }.joinToString("  ·  "), 34f, 750, muted, 1, font), 96f, 1250f)
        canvas.drawText("lyric", 984f, 1285f, paint(34f, muted, font).apply { textAlign = Paint.Align.RIGHT })
        return bitmap
    }
    private fun escape(s: String) = s.replace("\\", "\\\\").replace(Regex("([`*_{}\\[\\]<>#!|])"), "\\\\$1")
    fun markdown(notes: List<HighlightRow>, authors: Map<String, String>): String = buildString {
        notes.groupBy { it.bookId }.forEach { (id, list) ->
            append("# ").append(escape(list.first().bookTitle.ifBlank { "未名" }).replace('\n', ' '))
            authors[id]?.takeIf { it.isNotBlank() }?.let { append(" — ").append(escape(it).replace('\n', ' ')) }
            append("\n\n")
            list.sortedWith(compareBy({ it.chapterIndex }, { it.start }, { it.createdAt })).forEach { h ->
                h.text.lines().forEach { append("> ").append(escape(it)).append('\n') }
                if (h.note.isNotBlank()) append('\n').append(escape(h.note)).append('\n')
                append('\n').append(escape(h.chapterTitle)).append(" · ").append(date(h.createdAt)).append("\n\n---\n\n")
            }
        }
    }
    suspend fun imageFile(context: Context, h: HighlightRow, author: String, night: Boolean): File = withContext(Dispatchers.IO) {
        val file = newFile(context, ".png")
        val font = androidx.core.content.res.ResourcesCompat.getFont(context, com.lyric.R.font.noto_serif_sc) ?: Typeface.SERIF
        val bitmap = render(h, author, night, font)
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        file
    }
    suspend fun markdownFile(context: Context, notes: List<HighlightRow>, authors: Map<String, String>): File = withContext(Dispatchers.IO) {
        newFile(context, ".md").apply { writeText(markdown(notes, authors)) }
    }
    private fun newFile(context: Context, suffix: String): File {
        val dir = File(context.cacheDir, "note_exports").apply { mkdirs() }
        // Leave recently shared files alive for receiving apps; prune only old exports.
        dir.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 7 * 86_400_000L }?.forEach { it.delete() }
        return File.createTempFile("lyric-", suffix, dir)
    }
    suspend fun share(context: Context, file: File, mime: String, title: String = "导出笔记") {
        val uri = withContext(Dispatchers.IO) { FileProvider.getUriForFile(context, "${context.packageName}.exports", file) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Lyric", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title))
    }
}
