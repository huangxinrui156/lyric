package com.lyric.ui

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
import androidx.compose.animation.core.tween
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
internal fun PageCurl(frame: CurlFrame, paper: Color, modifier: Modifier, finished: (Boolean) -> Unit) {
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
        animated.animateTo(end, tween(if (commit) 360 else 240))
        frame.finishing = true
        latestFinished(commit)
    }
    Canvas(modifier) {
        val current = frame.current ?: return@Canvas
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (frame.target == null) native.drawBitmap(current, null, RectF(0f, 0f, size.width, size.height), null)
            else drawPageCurl(native, current, frame.target!!, size.width, size.height,
                if (settling) animated.value else frame.touch, frame.direction, frame.topCorner, paper.toArgb())
        }
    }
}

/** Classic corner curl: two quadratic Beziers join the touch point to the page edges.
 * The back is the original sheet reflected about the perpendicular bisector of corner/touch. */
internal class CurlGeometry(width: Float, height: Float, touch: Offset, direction: Int, top: Boolean) {
    val cut = Path()
    val back = Path()
    val reflection = Matrix()
    val midpoint: PointF
    val normal: PointF
    val corner = PointF(if (direction > 0) width else 0f, if (top) 0f else height)
    val finger: PointF

    init {
        val x = (if (direction > 0) touch.x else width - touch.x).coerceIn(-width, width - .5f)
        val y = touch.y.coerceIn(.5f, height - .5f)
        val a = PointF(x, y)
        val f = PointF(width, corner.y)
        val g = PointF((a.x + f.x) / 2, (a.y + f.y) / 2)
        val e = PointF(g.x - (f.y - g.y) * (f.y - g.y) / (f.x - g.x), f.y)
        val h = PointF(f.x, g.y - (f.x - g.x) * (f.x - g.x) / (f.y - g.y))
        val c = PointF(e.x - (f.x - e.x) / 2, f.y)
        val j = PointF(f.x, h.y - (f.y - h.y) / 2)
        fun intersection(a1: PointF, a2: PointF, b1: PointF, b2: PointF): PointF {
            val dx = a2.x - a1.x; val dy = a2.y - a1.y
            val bx = b2.x - b1.x; val by = b2.y - b1.y
            val denominator = dx * by - dy * bx
            if (abs(denominator) < .0001f) return PointF(a2.x, a2.y)
            val t = ((b1.x - a1.x) * by - (b1.y - a1.y) * bx) / denominator
            return PointF(a1.x + t * dx, a1.y + t * dy)
        }
        val b = intersection(a, e, c, j)
        val k = intersection(a, h, c, j)
        val d = PointF((c.x + 2 * e.x + b.x) / 4, (c.y + 2 * e.y + b.y) / 4)
        val i = PointF((j.x + 2 * h.x + k.x) / 4, (j.y + 2 * h.y + k.y) / 4)
        cut.moveTo(c.x, c.y); cut.quadTo(e.x, e.y, b.x, b.y)
        cut.lineTo(a.x, a.y); cut.lineTo(k.x, k.y); cut.quadTo(h.x, h.y, j.x, j.y)
        cut.lineTo(f.x, f.y); cut.close()
        back.moveTo(d.x, d.y); back.lineTo(b.x, b.y); back.lineTo(a.x, a.y)
        back.lineTo(k.x, k.y); back.lineTo(i.x, i.y); back.close()
        if (direction < 0) {
            val mirror = Matrix().apply { setValues(floatArrayOf(-1f, 0f, width, 0f, 1f, 0f, 0f, 0f, 1f)) }
            cut.transform(mirror); back.transform(mirror)
        }
        finger = PointF(if (direction > 0) x else width - x, y)
        midpoint = PointF((corner.x + finger.x) / 2, (corner.y + finger.y) / 2)
        val dx = corner.x - finger.x; val dy = corner.y - finger.y
        val length = hypot(dx, dy)
        normal = PointF(dx / length, dy / length)
        val nx = normal.x; val ny = normal.y
        val distance = nx * midpoint.x + ny * midpoint.y
        reflection.setValues(floatArrayOf(1 - 2 * nx * nx, -2 * nx * ny, 2 * nx * distance,
            -2 * nx * ny, 1 - 2 * ny * ny, 2 * ny * distance, 0f, 0f, 1f))
    }
}

internal fun drawPageCurl(canvas: AndroidCanvas, current: Bitmap, target: Bitmap, width: Float, height: Float,
    touch: Offset, direction: Int, top: Boolean, paper: Int,
) {
    val bounds = RectF(0f, 0f, width, height)
    val geometry = CurlGeometry(width, height, touch, direction, top)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.save()
    canvas.clipRect(bounds)
    canvas.drawBitmap(target, null, bounds, paint)
    // Shadow cast by the lifted sheet on the revealed page.
    val m = geometry.midpoint; val n = geometry.normal
    val shadowWidth = (width * .075f).coerceIn(12f, 72f)
    paint.shader = LinearGradient(m.x - n.x * shadowWidth, m.y - n.y * shadowWidth,
        m.x + n.x * shadowWidth, m.y + n.y * shadowWidth,
        intArrayOf(0x00000000, 0x55000000, 0x00000000), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
    canvas.save(); canvas.clipPath(geometry.cut); canvas.drawRect(bounds, paint); canvas.restore()
    paint.shader = null
    canvas.save(); canvas.clipOutPath(geometry.cut)
    canvas.drawBitmap(current, null, bounds, paint); canvas.restore()
    // Paper underside, with reflected print and a highlight/shadow across the fold.
    canvas.save(); canvas.clipPath(geometry.back)
    canvas.drawColor(paper)
    canvas.save(); canvas.concat(geometry.reflection)
    paint.alpha = 45; canvas.drawBitmap(current, null, bounds, paint); paint.alpha = 255
    canvas.restore()
    paint.shader = LinearGradient(m.x - n.x * shadowWidth * 2, m.y - n.y * shadowWidth * 2,
        m.x + n.x * shadowWidth / 2, m.y + n.y * shadowWidth / 2,
        intArrayOf(0x18000000, 0x38ffffff, 0x68000000), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
    canvas.drawRect(bounds, paint); canvas.restore()
    paint.shader = null; paint.color = 0x33000000; paint.style = Paint.Style.STROKE; paint.strokeWidth = .8f
    canvas.drawPath(geometry.back, paint)
    canvas.restore()
}
