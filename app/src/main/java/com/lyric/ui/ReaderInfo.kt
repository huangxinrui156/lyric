package com.lyric.ui

import android.content.*
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
fun PaperSkeleton(paper: Color, ink: Color, margin: Float = 20f) {
    Column(Modifier.fillMaxSize().background(paper).padding(horizontal = margin.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        repeat(30) { row ->
            Box(Modifier.fillMaxWidth(if (row % 4 == 3) .75f else 1f).height(7.dp)
                .background(ink.copy(alpha = .065f), RoundedCornerShape(3.dp)))
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
