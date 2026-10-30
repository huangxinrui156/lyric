package com.lyric.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import com.lyric.core.ReaderSettings
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.lyric.R
import kotlin.random.Random

val Paper = Color(0xFFF4EFE6)
val Ink = Color(0xFF2B2A27)
val Moss = Color(0xFF5E6B4E)
val Vermilion = Color(0xFFB5563A)
val Stone = Color(0xFF8C8478)
val Linen = Color(0xFFE2DACB)
val CardPaper = Color(0xFFEAE3D6)
val LightColors =
    lightColorScheme(
        primary = Moss,
        onPrimary = Paper,
        primaryContainer = Color(0xFFDDE3D0),
        onPrimaryContainer = Color(0xFF1C2414),
        secondary = Color(0xFF8A6A4F),
        onSecondary = Color.White,
        tertiary = Vermilion,
        onTertiary = Color.White,
        background = Paper,
        onBackground = Ink,
        surface = Color(0xFFF7F3EB),
        onSurface = Ink,
        surfaceVariant = CardPaper,
        onSurfaceVariant = Stone,
        outline = Color(0xFFD2C9B8),
        outlineVariant = Linen,
    )
val DarkColors =
    darkColorScheme(
        primary = Color(0xFFA9B796),
        onPrimary = Color(0xFF223015),
        secondary = Color(0xFFC9A98C),
        tertiary = Color(0xFFE08A6E),
        background = Color(0xFF16171A),
        onBackground = Color(0xFFB8B2A6),
        surface = Color(0xFF1E1F23),
        onSurface = Color(0xFFD0CABE),
        surfaceVariant = Color(0xFF2A2B2F),
        onSurfaceVariant = Color(0xFF8E897F),
        outline = Color(0xFF45444A),
    )

val AccentPresets = listOf(Moss, Vermilion, Color(0xFF8A6A4F), Color(0xFF626D83), Color(0xFF7E667D), Color(0xFF73766C))
val AccentNames = listOf("苔绿", "朱红", "枯茶", "黛蓝", "灰紫", "石色")

@Composable
fun LyricTheme(prefs: ReaderSettings = ReaderSettings(), content: @Composable () -> Unit) {
    val dark = when (prefs.appTheme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val accent = AccentPresets[prefs.accentColor.coerceIn(0, 5)]
    val primary by animateColorAsState(if (dark) androidx.compose.ui.graphics.lerp(accent, Paper, .42f) else accent, tween(400), label = "accent")
    val base = if (dark) DarkColors else LightColors
    val bg by animateColorAsState(base.background, tween(400), label = "background")
    val surface by animateColorAsState(base.surface, tween(400), label = "surface")
    val text by animateColorAsState(base.onBackground, tween(400), label = "text")
    MaterialTheme(
        colorScheme = base.copy(primary = primary, background = bg, surface = surface, onBackground = text, onSurface = text),
        shapes =
            Shapes(
                small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            ),
        content = content,
    )
}

fun Modifier.paperGrain() = drawWithCache {
    val random = Random(17)
    val count = (size.width * size.height / 140f).toInt().coerceAtMost(18000)
    val points =
        List(count) { Offset(random.nextFloat() * size.width, random.nextFloat() * size.height) }
    onDrawWithContent {
        drawContent()
        drawPoints(points, PointMode.Points, Color.Black.copy(alpha = .045f), strokeWidth = .7f)
    }
}

val Serif = FontFamily(Font(R.font.noto_serif_sc))
