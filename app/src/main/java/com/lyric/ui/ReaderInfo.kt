package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import android.content.*
import android.os.BatteryManager
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun readerClockAndBattery(): Pair<String, String> {
    val context = LocalContext.current
    var time by remember { mutableStateOf("") }
    var battery by remember { mutableStateOf("—") }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    battery = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "—"
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        receiver.onReceive(context, androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return time to battery
}

@Composable
fun PaperSkeleton(paper: Color, ink: Color, margin: Float = LyricTokens.Reading.default.marginDp) {
    val color = LyricTokens.colors(paper.luminance() < .2f).skeleton
    BoxWithConstraints(Modifier.fillMaxSize().background(paper).padding(horizontal = margin.dp, vertical = LyricTokens.Space.xxl), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        val rows = ((maxHeight + LyricTokens.Space.sm) / (LyricTokens.Space.md + LyricTokens.Space.sm)).toInt().coerceAtLeast(1)
        Column(Modifier.widthIn(max = LyricTokens.Reading.bodyMaxWidth).fillMaxSize().loadingBreathe(), verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.sm)) {
        repeat(rows) { row ->
            Box(Modifier.fillMaxWidth(if (row == rows - 1) .6f else 1f).height(LyricTokens.Space.md)
                .background(color, RoundedCornerShape(LyricTokens.Radius.skeleton)))
        }
        }
    }
}

/** Preserve visible movement near the beginning of a long book instead of rounding to 0.0%. */
internal fun readingPercentLabel(fraction: Float): String {
    val percent = fraction.coerceIn(0f, 1f) * 100.0
    val decimals = if (percent > 0 && percent < .1)
        kotlin.math.ceil(-kotlin.math.log10(percent) - 1e-6).toInt().coerceIn(2, 8)
    else 1
    return String.format(Locale.getDefault(), "%.${decimals}f%%", percent)
}

/** 1-based chapter label shown while whole-book length is still being filled. */
internal fun readingChapterPlaceholder(chapterIndex: Int, chapterCount: Int = Int.MAX_VALUE): String {
    val last = (chapterCount - 1).coerceAtLeast(0)
    return "第 ${chapterIndex.coerceIn(0, last) + 1} 章"
}

internal fun readingProgressText(
    lengthsReady: Boolean,
    chapterIndex: Int,
    fraction: Float,
    chapterCount: Int = Int.MAX_VALUE,
): String = if (lengthsReady) readingPercentLabel(fraction) else readingChapterPlaceholder(chapterIndex, chapterCount)

/** Unknown progress uses the UI-palette secondary ink; the computed percent keeps the reading-theme meta color. */
internal fun readingProgressColor(lengthsReady: Boolean, night: Boolean, percentColor: Color): Color =
    if (lengthsReady) percentColor else LyricTokens.colors(night).secondary

@Composable
internal fun ReaderProgressLabel(
    lengthsReady: Boolean,
    chapterIndex: Int,
    fraction: Float,
    night: Boolean,
    percentColor: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    chapterCount: Int = Int.MAX_VALUE,
) {
    Crossfade(
        targetState = lengthsReady,
        animationSpec = LyricTokens.Motion.short(),
        label = "reading-progress",
        modifier = modifier.testTag("reading-progress"),
    ) { ready ->
        Text(
            readingProgressText(ready, chapterIndex, fraction, chapterCount),
            color = readingProgressColor(ready, night, percentColor),
            style = style,
        )
    }
}
