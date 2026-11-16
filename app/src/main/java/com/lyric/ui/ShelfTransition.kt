package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

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

/**
 * Total line-art switch length; retune here. Phase marks below are fractions of it
 * (at 2000ms: 0–650 draw book · 650–850 hold · 850–1450 morph to panels + figures + horizon ·
 * 1450–1650 hold · 1650–2000 line art fades, grid rises). Reduce-motion: 200ms crossfade.
 */
const val SHELF_SWITCH_MS = LyricTokens.Pending.shelfSwitchMs
private const val BOOK_END = LyricTokens.Pending.shelfBookEnd
private const val MORPH_START = LyricTokens.Pending.shelfMorphStart
private const val MORPH_END = LyricTokens.Pending.shelfMorphEnd
private const val FADE_START = LyricTokens.Pending.shelfFadeStart

private fun phase(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)

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
    /** A tap during the animation jumps to the end state; repeated taps are no-ops. */
    fun skip() { if (running) finish() }
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
    val m = if (toComic) t else 1f - t
    val comic = phase(m, FADE_START, 1f)
    val book = 1f - phase(m, 0f, BOOK_END)
    val outgoing = if (toComic) book else comic
    val incoming = if (toComic) comic else book
    // The arriving grid rises from 12dp below into place.
    return ShelfTransitionFrame(outgoing, incoming, 0f, LyricTokens.Pending.shelfRise.value * (1f - incoming), m)
}

@Composable
internal fun ShelfTransition(state: ShelfTransitionState, content: @Composable (ShelfSection) -> Unit) {
    LaunchedEffect(state.running, state.target) {
        if (state.running) {
            // A frame clock intentionally bypasses Compose's duration-scale multiplier so the
            // accessibility fallback still crossfades for 200ms when the system scale is zero.
            val duration = if (state.reduced) LyricTokens.Motion.mediumMs * 1_000_000L else SHELF_SWITCH_MS * 1_000_000L
            val start = withFrameNanos { it }
            do {
                val linear = withFrameNanos { ((it - start).toDouble() / duration).toFloat().coerceIn(0f, 1f) }
                state.fraction = LyricTokens.Motion.easeOut.transform(linear)
            } while (linear < 1f && state.running)
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

/**
 * Hand jitter (美术设计 0.6.2): straight segments are cut into ~10dp pieces whose vertices move
 * ±0.8dp along the segment normal; curve control points move ±0.5dp. [unit] = art units per dp.
 * Fixed seed, built once, so every frame draws the same wobble.
 */
internal fun jitteredSvgPath(d: String, unit: Float, rnd: java.util.Random): Path {
    fun r(maxDp: Float) = (rnd.nextFloat() * 2f - 1f) * maxDp * unit
    val path = Path()
    var cx = 0f; var cy = 0f; var sx = 0f; var sy = 0f
    var lastCtrlX = 0f; var lastCtrlY = 0f
    fun line(x: Float, y: Float) {
        val dx = x - cx; val dy = y - cy
        val len = kotlin.math.hypot(dx, dy)
        val n = maxOf(1, (len / (10f * unit)).toInt())
        val nx = if (len > 0f) -dy / len else 0f; val ny = if (len > 0f) dx / len else 0f
        for (i in 1..n) {
            val t = i.toFloat() / n
            val o = if (i == n) 0f else r(.8f)
            path.lineTo(cx + dx * t + nx * o, cy + dy * t + ny * o)
        }
        cx = x; cy = y
    }
    for (node in androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes()) {
        when (node) {
            is androidx.compose.ui.graphics.vector.PathNode.MoveTo -> { cx = node.x; cy = node.y; sx = cx; sy = cy; path.moveTo(cx, cy) }
            is androidx.compose.ui.graphics.vector.PathNode.RelativeMoveTo -> { cx += node.dx; cy += node.dy; sx = cx; sy = cy; path.moveTo(cx, cy) }
            is androidx.compose.ui.graphics.vector.PathNode.LineTo -> line(node.x, node.y)
            is androidx.compose.ui.graphics.vector.PathNode.RelativeLineTo -> line(cx + node.dx, cy + node.dy)
            is androidx.compose.ui.graphics.vector.PathNode.QuadTo -> {
                lastCtrlX = node.x1 + r(.5f); lastCtrlY = node.y1 + r(.5f)
                path.quadraticTo(lastCtrlX, lastCtrlY, node.x2, node.y2); cx = node.x2; cy = node.y2
            }
            is androidx.compose.ui.graphics.vector.PathNode.ReflectiveQuadTo -> {
                lastCtrlX = 2 * cx - lastCtrlX + r(.5f); lastCtrlY = 2 * cy - lastCtrlY + r(.5f)
                path.quadraticTo(lastCtrlX, lastCtrlY, node.x, node.y); cx = node.x; cy = node.y
            }
            is androidx.compose.ui.graphics.vector.PathNode.RelativeArcTo -> {
                // Small circles (heads): keep exact geometry.
                val ex = cx + node.arcStartDx; val ey = cy + node.arcStartDy
                val tmp = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
                    "M$cx $cy a${node.horizontalEllipseRadius} ${node.verticalEllipseRadius} ${node.theta} ${if (node.isMoreThanHalf) 1 else 0} ${if (node.isPositiveArc) 1 else 0} ${node.arcStartDx} ${node.arcStartDy}").toPath()
                path.addPath(tmp); path.moveTo(ex, ey); cx = ex; cy = ey
            }
            is androidx.compose.ui.graphics.vector.PathNode.Close -> { line(sx, sy); path.close() }
            else -> {}
        }
    }
    return path
}

@Composable
private fun ShelfMorph(time: () -> Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * density
    // Art units per dp at the 64%-of-width scale; jitter is built once with a fixed seed.
    val unit = density / (screenW * .64f / 240f)
    val book = remember(unit) { val rnd = java.util.Random(0x1A7E); bookSvg.map { jitteredSvgPath(it, unit, rnd) } }
    val panels = remember(unit) { val rnd = java.util.Random(0x2B2A); panelSvg.map { jitteredSvgPath(it, unit, rnd) } }
    val figures = remember(unit) { val rnd = java.util.Random(0x3C3B); figureSvg.map { jitteredSvgPath(it, unit, rnd) } }
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    val night = MaterialTheme.colorScheme.background.luminance() < .5f
    val ink = if (night) LyricTokens.Night.ink else LyricTokens.Day.ink
    Canvas(Modifier.fillMaxSize()) {
        val t = time().coerceIn(0f, 1f)
        val fade = 1f - phase(t, FADE_START, 1f)
        val morph = phase(t, MORPH_START, MORPH_END)
        // 美术设计: art 64% of screen width, centred horizontally, its centre at 42% of the height.
        val scale = size.width * .64f / 240f
        // Stroke stays 2dp on screen regardless of the art scale.
        val stroke = Stroke(LyricTokens.Pending.indicatorStroke.toPx() / scale, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
        translate((size.width - 240f * scale) / 2f, size.height * .42f - 90f * scale) {
            scale(scale, scale, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                // Morph: the book fades while the panel frames stroke in, then figures and horizon.
                draw(book, phase(t, 0f, BOOK_END), (1f - phase(morph, 0f, .35f)) * fade)
                draw(panels, phase(morph, 0f, .65f), fade)
                draw(figures, phase(morph, .55f, 1f), fade)
            }
        }
    }
}
