package com.lyric.reader

import android.content.Context
import android.graphics.*
import android.text.style.ReplacementSpan
import com.lyric.core.Book
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Created and used by the pagination worker, never decodes an image in onDraw. */
class BookAssets(private val context: Context, private val book: Book) {
    private val fonts = mutableMapOf<String, Typeface?>()
    private val images = object : LinkedHashMap<String, Bitmap>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }
    fun font(family: String): Typeface? = fonts.getOrPut(family) {
        runCatching {
            val bytes = Base64.getDecoder().decode(book.resources[book.embeddedFonts.entries.firstOrNull { it.key.equals(family, true) }?.value] ?: return@getOrPut null)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val file = File(context.cacheDir, "epub-fonts/$hash.ttf")
            if (!file.exists()) {
                file.parentFile!!.mkdirs()
                file.writeBytes(bytes)
            }
            Typeface.createFromFile(file)
        }.getOrNull()
    }
    fun image(path: String, width: Int, height: Int): Bitmap? {
        val key = "$path:$width:$height"
        images[key]?.let { return it }
        return runCatching {
            val bytes = Base64.getDecoder().decode(book.resources[path] ?: return null)
            if (path.substringAfterLast('.').equals("svg", true)) {
                val svg = com.caverock.androidsvg.SVG.getFromInputStream(bytes.inputStream())
                val viewBox = svg.documentViewBox
                val w = svg.documentWidth.takeIf { it > 0 } ?: viewBox?.width() ?: width.toFloat()
                val h = svg.documentHeight.takeIf { it > 0 } ?: viewBox?.height() ?: height.toFloat()
                val ratio = minOf(width / w, height / h)
                val bitmap = Bitmap.createBitmap((w * ratio).toInt().coerceAtLeast(1),
                    (h * ratio).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                svg.setDocumentWidth("100%")
                svg.setDocumentHeight("100%")
                svg.renderToCanvas(Canvas(bitmap))
                images[key] = bitmap
                return bitmap
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.also { images[key] = it }
        }.getOrNull()
    }
}

class BookImageSpan(val resource: String, private val bitmap: Bitmap?, width: Int, height: Int) : ReplacementSpan() {
    private val targetWidth: Int
    private val targetHeight: Int
    init {
        val ratio = bitmap?.let { minOf(width.toFloat() / it.width, height.toFloat() / it.height) } ?: 1f
        targetWidth = bitmap?.let { (it.width * ratio).toInt().coerceAtLeast(1) } ?: width
        targetHeight = bitmap?.let { (it.height * ratio).toInt().coerceAtLeast(1) } ?: 48
    }
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.apply { ascent = -targetHeight; top = ascent; descent = 0; bottom = 0 }
        return targetWidth
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        if (bitmap != null) canvas.drawBitmap(bitmap, null, RectF(x, (y - targetHeight).toFloat(), x + targetWidth, y.toFloat()), paint)
        else canvas.drawText("［图片不可用］", x, y.toFloat(), paint)
    }
}

class RuleSpan(private val width: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?) = width
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val oldAlpha = paint.alpha
        paint.alpha = 90
        canvas.drawRect(x, y - paint.textSize / 3, x + width, y - paint.textSize / 3 + 1, paint)
        paint.alpha = oldAlpha
    }
}

class RubySpan(private val reading: String) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        paint.getFontMetricsInt(fm)
        fm?.apply { ascent -= (paint.textSize * .65f).toInt(); top = ascent }
        return maxOf(paint.measureText(text, start, end), Paint(paint).apply { textSize *= .5f }.measureText(reading)).toInt()
    }
    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val width = getSize(paint, text, start, end, null).toFloat()
        val rubyPaint = Paint(paint).apply { textSize *= .5f }
        canvas.drawText(reading, x + (width - rubyPaint.measureText(reading)) / 2,
            y + paint.fontMetrics.ascent - paint.textSize * .12f, rubyPaint)
        canvas.drawText(text, start, end, x + (width - paint.measureText(text, start, end)) / 2, y.toFloat(), paint)
    }
}
