package com.lyric.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.animateColorAsState
import com.lyric.core.ReaderSettings
import com.lyric.ui.theme.LyricTokens
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import kotlin.random.Random

private val LocalLyricColors = staticCompositionLocalOf { LyricTokens.Day }
val Paper @Composable get() = LocalLyricColors.current.background
val Ink @Composable get() = LocalLyricColors.current.ink
val Moss @Composable get() = LocalLyricColors.current.moss
val Vermilion @Composable get() = LocalLyricColors.current.vermilion
val Stone @Composable get() = LocalLyricColors.current.secondary
val Linen @Composable get() = LocalLyricColors.current.stroke
val CardPaper @Composable get() = LocalLyricColors.current.card

private fun colorScheme(night: Boolean): ColorScheme {
    val p = LyricTokens.colors(night)
    val base = if (night) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.primaryFill, onPrimary = p.primaryText,
        primaryContainer = p.card, onPrimaryContainer = p.ink,
        secondary = p.moss, onSecondary = p.background,
        secondaryContainer = p.card, onSecondaryContainer = p.ink,
        tertiary = p.vermilion, onTertiary = p.background,
        tertiaryContainer = p.card, onTertiaryContainer = p.ink,
        background = p.background, onBackground = p.ink,
        surface = p.card, onSurface = p.ink, surfaceVariant = p.card,
        onSurfaceVariant = p.secondary, surfaceTint = Color.Transparent,
        surfaceBright = p.card, surfaceDim = p.background,
        surfaceContainer = p.card, surfaceContainerHigh = p.card,
        surfaceContainerHighest = p.card, surfaceContainerLow = p.card,
        surfaceContainerLowest = p.background,
        outline = p.stroke, outlineVariant = p.stroke,
        error = p.vermilion, onError = p.background,
        errorContainer = p.card, onErrorContainer = p.vermilion,
        inverseSurface = p.ink, inverseOnSurface = p.background, inversePrimary = p.moss,
        scrim = LyricTokens.Overlay.black,
    )
}
val LightColors = colorScheme(false)
val DarkColors = colorScheme(true)
val AccentPresets = LyricTokens.Accents.presets
val AccentNames = LyricTokens.Accents.names

@Composable
fun LyricTheme(prefs: ReaderSettings = ReaderSettings(), content: @Composable () -> Unit) {
    val dark = when (prefs.appTheme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val p = LyricTokens.colors(dark)
    val accent by animateColorAsState(LyricTokens.Accents.color(prefs.accentColor, dark), LyricTokens.Motion.short(), label = "accent")
    val base = if (dark) DarkColors else LightColors
    val bg by animateColorAsState(p.background, LyricTokens.Motion.short(), label = "background")
    val surface by animateColorAsState(p.card, LyricTokens.Motion.short(), label = "surface")
    val text by animateColorAsState(p.ink, LyricTokens.Motion.short(), label = "text")
    CompositionLocalProvider(LocalLyricColors provides p) {
        MaterialTheme(
            colorScheme = base.copy(secondary = accent, background = bg, surface = surface, onBackground = text, onSurface = text),
            typography = LyricTokens.Type.material,
            shapes = LyricTokens.Radius.material,
            content = content,
        )
    }
}

/** Cached raster grain: no bitmap allocation or grain at all in day mode. */
fun Modifier.paperGrain(dark: Boolean = false): Modifier = if (!dark) this else drawWithCache {
    val w = size.width.toInt().coerceIn(1, 4096)
    val h = size.height.toInt().coerceIn(1, 4096)
    val bitmap = androidx.compose.ui.graphics.ImageBitmap(w, h)
    val random = Random(17)
    val count = (w * h / 140f).toInt().coerceAtMost(18000)
    val points = List(count) { Offset(random.nextFloat() * w, random.nextFloat() * h) }
    androidx.compose.ui.graphics.Canvas(bitmap).drawPoints(
        PointMode.Points, points,
        androidx.compose.ui.graphics.Paint().apply {
            color = LyricTokens.Overlay.white.copy(alpha = LyricTokens.nightGrainAlpha)
            strokeWidth = .7f
        },
    )
    onDrawWithContent {
        drawContent()
        drawImage(bitmap, blendMode = androidx.compose.ui.graphics.BlendMode.Screen)
    }
}

val Serif = LyricTokens.Fonts.song
