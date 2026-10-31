package com.lyric.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.lyric.data.ShelfSection
import kotlin.math.sin

internal fun reducedShelfMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

@Stable
internal class ShelfTransitionState(initial: ShelfSection) {
    var from by mutableStateOf(initial)
        private set
    var target by mutableStateOf(initial)
        private set
    var running by mutableStateOf(false)
        private set
    var reduced by mutableStateOf(false)
        private set
    var fraction by mutableFloatStateOf(1f)
    fun start(next: ShelfSection, reduceMotion: Boolean): Boolean {
        if (running || next == target) return false
        from = target
        target = next
        reduced = reduceMotion
        fraction = 0f
        running = true // Set synchronously: a second tap cannot enqueue another animation.
        return true
    }
    fun finish() { fraction = 1f; from = target; running = false }
}

internal data class ShelfTransitionFrame(
    val outgoing: Float, val incoming: Float,
    val outgoingY: Float, val incomingY: Float,
    val drawing: Float,
)

internal fun shelfTransitionFrame(fraction: Float, toComic: Boolean, reduced: Boolean): ShelfTransitionFrame {
    val t = fraction.coerceIn(0f, 1f)
    if (reduced) return ShelfTransitionFrame(1f - t, t, 0f, 0f, 0f)
    val ms = t * 700f
    val outgoing = (1f - ms / if (toComic) 250f else 200f).coerceIn(0f, 1f)
    val incoming = ((ms - if (toComic) 500f else 450f) / if (toComic) 200f else 250f).coerceIn(0f, 1f)
    return ShelfTransitionFrame(outgoing, incoming,
        if (toComic) 0f else 48f * (1f - outgoing),
        if (toComic) 48f * (1f - incoming) else 0f,
        if (toComic) t else 1f - t)
}

@Composable
internal fun ShelfTransition(state: ShelfTransitionState, content: @Composable (ShelfSection) -> Unit) {
    LaunchedEffect(state.running, state.target) {
        if (state.running) {
            // A frame clock intentionally bypasses Compose's duration-scale multiplier so the
            // accessibility fallback still crossfades for 200ms when the system scale is zero.
            val duration = if (state.reduced) 200_000_000L else 700_000_000L
            val start = withFrameNanos { it }
            do {
                state.fraction = withFrameNanos { ((it - start).toDouble() / duration).toFloat().coerceIn(0f, 1f) }
            } while (state.fraction < 1f)
            state.finish()
        }
    }
    Box(Modifier.fillMaxSize().then(if (state.running) Modifier.background(Paper) else Modifier)) {
        // Stable section keys keep the destination grid alive when the animation ends.
        val sections = if (state.running) listOf(state.from, state.target) else listOf(state.target)
        sections.forEach { section ->
            key(section) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val frame = shelfTransitionFrame(state.fraction, state.target == ShelfSection.COMIC, state.reduced)
                    val incoming = section == state.target
                    alpha = if (!state.running) 1f else if (incoming) frame.incoming else frame.outgoing
                    translationY = if (!state.running) 0f else (if (incoming) frame.incomingY else frame.outgoingY).dp.toPx()
                }) { content(section) }
            }
        }
        if (state.running && !state.reduced) ShelfMorph { if (state.target == ShelfSection.COMIC) state.fraction else 1f - state.fraction }
    }
}

/** Matching vertices morph the book outline and its page rules into three comic panels. */
private val bookLines = listOf(
    listOf(-90f to -45f, -45f to -60f, 0f to -35f, 45f to -60f, 90f to -45f,
        90f to 55f, 45f to 40f, 0f to 65f, -45f to 40f, -90f to 55f, -90f to -45f),
    listOf(0f to -35f, 0f to 65f),
    listOf(-72f to -23f, -42f to -32f, -16f to -18f),
    listOf(-72f to -2f, -42f to -11f, -16f to 3f),
    listOf(16f to -18f, 42f to -32f, 72f to -23f),
    listOf(16f to 3f, 42f to -11f, 72f to -2f),
)
private val panelLines = listOf(
    listOf(-90f to -60f, -45f to -60f, 0f to -60f, 45f to -60f, 90f to -60f,
        90f to 60f, 45f to 60f, 0f to 60f, -45f to 60f, -90f to 60f, -90f to -60f),
    listOf(10f to -60f, 10f to 60f),
    listOf(-90f to 0f, -40f to 0f, 10f to 0f),
    listOf(-90f to 0f, -40f to 0f, 10f to 0f),
    listOf(10f to -60f, 50f to -60f, 90f to -60f),
    listOf(10f to 60f, 50f to 60f, 90f to 60f),
)

@Composable
private fun ShelfMorph(time: () -> Float) {
    val paths = remember { List(bookLines.size) { Path() } }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    val figure = remember { List(2) { Path() } }
    Canvas(Modifier.fillMaxSize()) {
        val ms = time().coerceIn(0f, 1f) * 700f
        val morph = ((ms - 250f) / 250f).coerceIn(0f, 1f)
        val reveal = (ms / 250f).coerceIn(0f, 1f)
        val alpha = (1f - (ms - 500f) / 200f).coerceIn(0f, 1f)
        val unit = 1.dp.toPx()
        val jitter = sin(ms * .055f) * .45f * unit
        val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        paths.forEachIndexed { i, path ->
            path.reset()
            bookLines[i].forEachIndexed { j, (x, y) ->
                val (tx, ty) = panelLines[i][j]
                val px = (x + (tx - x) * morph) * unit
                val py = (y + (ty - y) * morph) * unit
                if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
        }
        val lengths = paths.map { measure.setPath(it, false); measure.length }
        var remaining = lengths.sum() * reveal
        translate(size.width / 2f + jitter, size.height / 2f - jitter) {
            paths.forEachIndexed { i, path ->
                measure.setPath(path, false)
                segment.reset()
                measure.getSegment(0f, remaining.coerceIn(0f, lengths[i]), segment)
                drawPath(segment, Ink, alpha = alpha, style = stroke)
                remaining -= lengths[i]
            }
            // Two pen strokes: head/body/legs, then arms. They appear during the morph.
            val person = ((morph - .45f) / .55f).coerceIn(0f, 1f)
            figure[0].apply {
                reset(); moveTo(50f * unit, -10f * unit)
                cubicTo(30f * unit, -10f * unit, 30f * unit, -36f * unit, 50f * unit, -36f * unit)
                cubicTo(70f * unit, -36f * unit, 70f * unit, -10f * unit, 50f * unit, -10f * unit)
                lineTo(50f * unit, 15f * unit); lineTo(34f * unit, 38f * unit)
                lineTo(50f * unit, 15f * unit); lineTo(68f * unit, 36f * unit)
            }
            figure[1].apply { reset(); moveTo(30f * unit, 2f * unit); lineTo(50f * unit, -3f * unit); lineTo(72f * unit, -10f * unit) }
            figure.forEach { path ->
                measure.setPath(path, false); segment.reset()
                measure.getSegment(0f, measure.length * person, segment)
                drawPath(segment, Ink, alpha = alpha, style = stroke)
            }
        }
    }
}
