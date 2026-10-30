package com.lyric.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import kotlin.math.sin

internal class CurlFrame(val bitmap: Bitmap, val direction: Int)

/** The destination is the live reader underneath; the old page curls away in either direction. */
@Composable
internal fun PageCurl(frame: CurlFrame, ready: Boolean, paper: Color, modifier: Modifier, finished: () -> Unit) {
    val progress = remember(frame) { Animatable(0f) }
    val image = remember(frame) { frame.bitmap.asImageBitmap() }
    LaunchedEffect(frame, ready) {
        if (ready) {
            progress.animateTo(1f, tween(420))
            finished()
        }
    }
    Canvas(modifier) { drawCurl(image, progress.value, frame.direction, paper) }
}

/** Curved clipping edge, reflected page back, paper shading and cast shadow. No 3D View layer. */
internal fun DrawScope.drawCurl(image: ImageBitmap, progress: Float, direction: Int, paper: Color) {
    val p = progress.coerceIn(0f, 1f)
    if (p <= 0f) { drawImage(image); return }
    if (p >= 1f) return
    val w = size.width
    val h = size.height
    val forward = direction > 0
    val sign = if (forward) 1f else -1f
    val edge = if (forward) w * (1f - p) else w * p
    val bend = w * .12f * sin(Math.PI.toFloat() * p)
    val back = bend * 1.7f
    val fixedEdge = if (forward) 0f else w
    val front = Path().apply {
        moveTo(fixedEdge, 0f)
        lineTo(edge, 0f)
        quadraticBezierTo(edge - sign * bend, h * .48f, edge, h)
        lineTo(fixedEdge, h)
        close()
    }
    val flap = Path().apply {
        moveTo(edge, 0f)
        quadraticBezierTo(edge - sign * bend, h * .48f, edge, h)
        quadraticBezierTo(edge + sign * back, h * .65f, edge + sign * back * .55f, h * .18f)
        quadraticBezierTo(edge + sign * back * .35f, h * .04f, edge, 0f)
        close()
    }
    // Shadow belongs on the exposed destination page.
    val shadowStart = edge - sign * bend
    val shadowEnd = edge + sign * (back + w * .06f)
    drawRect(Brush.horizontalGradient(
        listOf(Color.Black.copy(alpha = .26f), Color.Transparent),
        startX = shadowStart, endX = shadowEnd,
    ), topLeft = Offset(minOf(shadowStart, shadowEnd), 0f),
        size = androidx.compose.ui.geometry.Size(kotlin.math.abs(shadowEnd - shadowStart), h))
    clipPath(front) { drawImage(image) }
    drawPath(flap, paper)
    clipPath(flap) {
        // Faint mirrored print on the reverse of the sheet.
        scale(-1f, 1f, pivot = Offset(edge, h / 2)) { drawImage(image, alpha = .12f) }
        drawRect(Brush.horizontalGradient(
            listOf(Color.Black.copy(alpha = .18f), Color.White.copy(alpha = .18f), Color.Transparent),
            startX = edge - sign * bend, endX = edge + sign * back,
        ))
    }
    drawPath(flap, paper.copy(alpha = .5f), style = Stroke(width = 1f))
}
