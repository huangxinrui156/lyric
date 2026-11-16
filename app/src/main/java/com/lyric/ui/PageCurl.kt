package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.hypot

/** Two entire reader surfaces, including paper, safe areas, chapter heading and status row. */
internal class CurlFrame(val direction: Int, val topCorner: Boolean, touch: Offset, automatic: Boolean) {
    var current by mutableStateOf<Bitmap?>(null)
    var target by mutableStateOf<Bitmap?>(null)
    var touch by mutableStateOf(touch)
    var release by mutableStateOf<Boolean?>(if (automatic) true else null)
    var finishing = false
}

internal fun curlShouldComplete(direction: Int, x: Float, width: Float, velocityX: Float): Boolean {
    val velocity = -direction * velocityX
    if (abs(velocityX) > 650f) return velocity > 0f
    val distance = if (direction > 0) width - x else x
    return distance > width * .42f
}

@Composable
internal fun PageCurl(frame: CurlFrame, paper: Color, modifier: Modifier, style: com.lyric.core.CurlStyle? = null, finished: (Boolean) -> Unit) {
    val animated = remember(frame) { Animatable(frame.touch, Offset.VectorConverter) }
    var settling by remember(frame) { mutableStateOf(false) }
    val latestFinished by rememberUpdatedState(finished)
    LaunchedEffect(frame, frame.release, frame.target) {
        val commit = frame.release ?: return@LaunchedEffect
        val target = frame.target ?: return@LaunchedEffect
        animated.snapTo(frame.touch)
        settling = true
        val w = target.width.toFloat()
        val h = target.height.toFloat()
        val end = Offset(
            if (commit) { if (frame.direction > 0) -w else w * 2 }
            else { if (frame.direction > 0) w - .5f else .5f },
            if (frame.topCorner) .5f else h - .5f,
        )
        animated.animateTo(end, LyricTokens.Motion.medium())
        frame.finishing = true
        latestFinished(commit)
    }
    val drawing = remember { CurlDrawing() }
    Canvas(modifier) {
        val current = frame.current ?: return@Canvas
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            drawing.bounds.set(0f, 0f, size.width, size.height)
            if (frame.target == null) native.drawBitmap(current, null, drawing.bounds, null)
            else drawPageCurl(native, current, frame.target!!, size.width, size.height,
                if (settling) animated.value else frame.touch, frame.direction, frame.topCorner, paper.toArgb(), style, density, drawing)
        }
    }
}

/** Classic corner curl: two quadratic Beziers join the touch point to the page edges.
 * The back is the original sheet reflected about the perpendicular bisector of corner/touch. */
internal class CurlGeometry(width: Float, height: Float, touch: Offset, direction: Int, top: Boolean) {
    val cut = Path()
    val back = Path()
    val reflection = Matrix()
    val midpoint = PointF()
    val normal = PointF()
    val corner = PointF()
    val finger = PointF()
    private val a = PointF(); private val f = PointF(); private val g = PointF()
    private val e = PointF(); private val h = PointF(); private val c = PointF()
    private val j = PointF(); private val b = PointF(); private val k = PointF()
    private val d = PointF(); private val i = PointF()
    private val mirror = Matrix()
    private val matrixValues = FloatArray(9)

    init { update(width, height, touch, direction, top) }

    fun update(width: Float, height: Float, touch: Offset, direction: Int, top: Boolean) {
        cut.rewind(); back.rewind()
        corner.set(if (direction > 0) width else 0f, if (top) 0f else height)
        val x = (if (direction > 0) touch.x else width - touch.x).coerceIn(-width, width - .5f)
        val y = touch.y.coerceIn(.5f, height - .5f)
        a.set(x, y)
        f.set(width, corner.y)
        g.set((a.x + f.x) / 2, (a.y + f.y) / 2)
        e.set(g.x - (f.y - g.y) * (f.y - g.y) / (f.x - g.x), f.y)
        h.set(f.x, g.y - (f.x - g.x) * (f.x - g.x) / (f.y - g.y))
        c.set(e.x - (f.x - e.x) / 2, f.y)
        j.set(f.x, h.y - (f.y - h.y) / 2)
        fun intersection(a1: PointF, a2: PointF, b1: PointF, b2: PointF, out: PointF) {
            val dx = a2.x - a1.x; val dy = a2.y - a1.y
            val bx = b2.x - b1.x; val by = b2.y - b1.y
            val denominator = dx * by - dy * bx
            if (abs(denominator) < .0001f) { out.set(a2); return }
            val t = ((b1.x - a1.x) * by - (b1.y - a1.y) * bx) / denominator
            out.set(a1.x + t * dx, a1.y + t * dy)
        }
        intersection(a, e, c, j, b)
        intersection(a, h, c, j, k)
        d.set((c.x + 2 * e.x + b.x) / 4, (c.y + 2 * e.y + b.y) / 4)
        i.set((j.x + 2 * h.x + k.x) / 4, (j.y + 2 * h.y + k.y) / 4)
        cut.moveTo(c.x, c.y); cut.quadTo(e.x, e.y, b.x, b.y)
        cut.lineTo(a.x, a.y); cut.lineTo(k.x, k.y); cut.quadTo(h.x, h.y, j.x, j.y)
        cut.lineTo(f.x, f.y); cut.close()
        back.moveTo(d.x, d.y); back.lineTo(b.x, b.y); back.lineTo(a.x, a.y)
        back.lineTo(k.x, k.y); back.lineTo(i.x, i.y); back.close()
        if (direction < 0) {
            matrixValues.fill(0f)
            matrixValues[0] = -1f; matrixValues[2] = width; matrixValues[4] = 1f; matrixValues[8] = 1f
            mirror.setValues(matrixValues)
            cut.transform(mirror); back.transform(mirror)
        }
        finger.set(if (direction > 0) x else width - x, y)
        midpoint.set((corner.x + finger.x) / 2, (corner.y + finger.y) / 2)
        val dx = corner.x - finger.x; val dy = corner.y - finger.y
        val length = hypot(dx, dy)
        normal.set(dx / length, dy / length)
        val nx = normal.x; val ny = normal.y
        val distance = nx * midpoint.x + ny * midpoint.y
        matrixValues[0] = 1 - 2 * nx * nx; matrixValues[1] = -2 * nx * ny; matrixValues[2] = 2 * nx * distance
        matrixValues[3] = -2 * nx * ny; matrixValues[4] = 1 - 2 * ny * ny; matrixValues[5] = 2 * ny * distance
        matrixValues[6] = 0f; matrixValues[7] = 0f; matrixValues[8] = 1f
        reflection.setValues(matrixValues)
    }
}

internal class CurlDrawing {
    val bounds = RectF()
    val geometry = CurlGeometry(1f, 1f, Offset.Zero, 1, false)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val shadowColors = IntArray(3)
    val shadowStops = FloatArray(3)
    val backColors = LyricTokens.Pending.curlBackColors.copyOf()
    val backStops = floatArrayOf(0f, .55f, 1f)
}

internal fun drawPageCurl(canvas: AndroidCanvas, current: Bitmap, target: Bitmap, width: Float, height: Float,
    touch: Offset, direction: Int, top: Boolean, paper: Int,
    style: com.lyric.core.CurlStyle? = null, density: Float = 1f,
    drawing: CurlDrawing = CurlDrawing(),
) {
    fun a(alpha: Float) = (alpha.coerceIn(0f, 1f) * 255).toInt() shl 24
    val cast = style?.castAlpha ?: .33f
    val dim = style?.dimAlpha ?: .10f
    val bounds = drawing.bounds.apply { set(0f, 0f, width, height) }
    val geometry = drawing.geometry.apply { update(width, height, touch, direction, top) }
    val paint = drawing.paint.apply { shader = null; alpha = 255; this.style = Paint.Style.FILL }
    canvas.save()
    canvas.clipRect(bounds)
    canvas.drawBitmap(target, null, bounds, paint)
    // Shadow cast by the lifted sheet on the revealed page.
    val m = geometry.midpoint; val n = geometry.normal
    val shadowWidth = if (style != null) 24 * density else (width * .075f).coerceIn(12f, 72f)
    val dimWidth = if (style != null) 16 * density else shadowWidth
    // Cast shadow: strongest at the fold, fading over 24dp onto the revealed page; under-page dim 16dp.
    drawing.shadowColors[0] = a(dim * .4f); drawing.shadowColors[1] = a(maxOf(cast, dim)); drawing.shadowColors[2] = 0
    drawing.shadowStops[0] = 0f; drawing.shadowStops[1] = dimWidth / (dimWidth + shadowWidth); drawing.shadowStops[2] = 1f
    paint.shader = LinearGradient(m.x - n.x * dimWidth, m.y - n.y * dimWidth,
        m.x + n.x * shadowWidth, m.y + n.y * shadowWidth,
        drawing.shadowColors, drawing.shadowStops, Shader.TileMode.CLAMP)
    canvas.save(); canvas.clipPath(geometry.cut); canvas.drawRect(bounds, paint); canvas.restore()
    paint.shader = null
    canvas.save(); canvas.clipOutPath(geometry.cut)
    canvas.drawBitmap(current, null, bounds, paint); canvas.restore()
    // Paper underside, with reflected print and a highlight/shadow across the fold.
    canvas.save(); canvas.clipPath(geometry.back)
    canvas.drawColor(style?.back ?: paper)
    canvas.save(); canvas.concat(geometry.reflection)
    paint.alpha = ((style?.showThrough ?: .18f) * 255).toInt(); canvas.drawBitmap(current, null, bounds, paint); paint.alpha = 255
    canvas.restore()
    paint.shader = LinearGradient(m.x - n.x * shadowWidth * 2, m.y - n.y * shadowWidth * 2,
        m.x + n.x * shadowWidth / 2, m.y + n.y * shadowWidth / 2,
        drawing.backColors, drawing.backStops, Shader.TileMode.CLAMP)
    canvas.drawRect(bounds, paint); canvas.restore()
    paint.shader = null
    paint.color = if (style != null) a(style.highlightAlpha) or LyricTokens.Pending.rgbMask.toInt() else LyricTokens.Pending.curlFallbackOutline
    paint.style = Paint.Style.STROKE; paint.strokeWidth = if (style != null) 1 * density else .8f
    canvas.drawPath(geometry.back, paint)
    canvas.restore()
}
