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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.scale
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

/** [fraction] is already eased; m runs 0→700ms toward 画架 and 700→0 when switching back. */
internal fun shelfTransitionFrame(fraction: Float, toComic: Boolean, reduced: Boolean): ShelfTransitionFrame {
    val t = fraction.coerceIn(0f, 1f)
    if (reduced) return ShelfTransitionFrame(1f - t, t, 0f, 0f, 0f)
    val m = (if (toComic) t else 1f - t) * 700f
    val comic = ((m - 500f) / 200f).coerceIn(0f, 1f)
    val book = 1f - (m / 250f).coerceIn(0f, 1f)
    val outgoing = if (toComic) book else comic
    val incoming = if (toComic) comic else book
    // The arriving grid rises from 12dp below into place.
    return ShelfTransitionFrame(outgoing, incoming, 0f, 12f * (1f - incoming), m / 700f)
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
                val linear = withFrameNanos { ((it - start).toDouble() / duration).toFloat().coerceIn(0f, 1f) }
                state.fraction = if (state.reduced) linear else androidx.compose.animation.core.FastOutSlowInEasing.transform(linear)
            } while (linear < 1f)
            state.finish()
        }
    }
    Box(Modifier.fillMaxSize().then(if (state.running) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)) {
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

/** design/v0.6/src/anim.html, 240×180 viewBox: frame ① open book, frame ② three panels / figures / horizon. */
private val bookSvg = listOf(
    "M20 40 Q70 25 118 42 L118 150 Q70 133 20 148 Z",
    "M122 42 Q170 25 220 40 L220 148 Q170 133 122 150 Z",
    "M118 42 L122 42 M120 42 L120 150",
    "M35 62 Q70 54 105 63 M35 82 Q70 74 105 83 M137 63 Q172 54 205 62",
)
private val panelSvg = listOf(
    "M20 30 L108 32 L106 96 L19 94 Z",
    "M118 31 L222 29 L221 96 L117 97 Z",
    "M19 106 L221 104 L222 160 L20 162 Z",
)
private val figureSvg = listOf(
    "M55 70 m-9 0 a9 9 0 1 0 18 0 a9 9 0 1 0 -18 0 M55 79 L55 92",
    "M165 62 m-8 0 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0 M165 70 L165 88 M150 80 L180 78",
    "M40 140 Q90 120 130 142 T205 136",
)

private fun svgPath(d: String): Path = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toPath()

@Composable
private fun ShelfMorph(time: () -> Float) {
    val book = remember { bookSvg.map(::svgPath) }
    val panels = remember { panelSvg.map(::svgPath) }
    val figures = remember { figureSvg.map(::svgPath) }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    val night = MaterialTheme.colorScheme.background.luminance() < .5f
    val ink = if (night) Color(0xFFD0CABE) else Color(0xFF2B2A27)
    Canvas(Modifier.fillMaxSize()) {
        val ms = time().coerceIn(0f, 1f) * 700f
        val fade = 1f - ((ms - 500f) / 200f).coerceIn(0f, 1f)
        // Scale the 240×180 art to fit 72% of the width (and 40% of the height), centred.
        val scale = minOf(size.width * .72f / 240f, size.height * .4f / 180f)
        // Stroke stays 2dp on screen regardless of the art scale.
        val stroke = Stroke(2.dp.toPx() / scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun draw(paths: List<Path>, progress: Float, alpha: Float) {
            if (progress <= 0f || alpha <= 0f) return
            paths.forEach { p ->
                measure.setPath(p, false)
                // Dash offset 1 → 0: reveal each contour from its start.
                segment.reset()
                measure.getSegment(0f, measure.length * progress, segment, true)
                drawPath(segment, ink, alpha = alpha, style = stroke)
            }
        }
        translate((size.width - 240f * scale) / 2f, (size.height - 180f * scale) / 2f) {
            scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                draw(book, (ms / 250f).coerceIn(0f, 1f), (1f - ((ms - 250f) / 250f).coerceIn(0f, 1f)) * fade)
                draw(panels, ((ms - 250f) / 150f).coerceIn(0f, 1f), fade)
                draw(figures, ((ms - 380f) / 120f).coerceIn(0f, 1f), fade)
            }
        }
    }
}
