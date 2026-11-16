package com.lyric.core

import androidx.compose.ui.graphics.toArgb
import com.lyric.ui.theme.LyricTokens

/** Per-theme colours from design/v0.4-visual.md §6 (selection) and §7 (curl paper back). */
data class SelectionColors(val fill: Int, val handle: Int, val menuBg: Int, val menuInk: Int)
data class CurlStyle(val back: Int, val showThrough: Float, val castAlpha: Float, val dimAlpha: Float, val highlightAlpha: Float)

object ThemePalette {
    private fun argb(rgb: Long, alpha: Float) = ((alpha * 255).toInt() shl 24) or (rgb and 0xFFFFFF).toInt()
    private fun mix(a: Long, b: Long, t: Float): Int {
        fun ch(v: Long, s: Int) = ((v shr s) and 0xff).toInt()
        fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt()
        return (0xff shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
    private fun dark(bg: Long): Boolean {
        val r = (bg shr 16) and 0xff; val g = (bg shr 8) and 0xff; val b = bg and 0xff
        return (0.299 * r + 0.587 * g + 0.114 * b) < 128
    }

    fun selection(theme: ReaderTheme, customBg: Long = theme.bg, customInk: Long = theme.ink, accent: Long = 0xFFB5563A): SelectionColors = when (theme) {
        ReaderTheme.PAPER -> SelectionColors(argb(0xB5563A, .22f), 0xFFB5563A.toInt(), 0xFF2B2A27.toInt(), 0xFFF4EFE6.toInt())
        ReaderTheme.BEIGE -> SelectionColors(argb(0xB5563A, .24f), 0xFFA84E33.toInt(), 0xFF3A362F.toInt(), 0xFFF4EFE6.toInt())
        ReaderTheme.MOSS -> SelectionColors(argb(0xA84E33, .26f), 0xFF9E4A31.toInt(), 0xFF2F3529.toInt(), 0xFFE9EDE3.toInt())
        ReaderTheme.NIGHT -> SelectionColors(argb(0xE08A6E, .28f), 0xFFE08A6E.toInt(), 0xFF2A2B2F.toInt(), 0xFFD0CABE.toInt())
        ReaderTheme.GREEN -> SelectionColors(argb(0xE08A6E, .26f), 0xFFD9826A.toInt(), 0xFF24362C.toInt(), 0xFFD5DDD0.toInt())
        ReaderTheme.BLACK -> SelectionColors(argb(0xE08A6E, .30f), 0xFFE08A6E.toInt(), 0xFF1E1F23.toInt(), 0xFFD0CABE.toInt())
        ReaderTheme.CUSTOM -> SelectionColors(argb(accent, .24f), accent.toInt(), customInk.toInt(), customBg.toInt())
    }

    /** Highlight fill is the accent overlay: 25% day / 30% night. Indices map to 朱/苔/枯茶/黛蓝. */
    fun isNight(theme: ReaderTheme, customBg: Long = theme.bg) = when (theme) {
        ReaderTheme.NIGHT, ReaderTheme.GREEN, ReaderTheme.BLACK -> true
        ReaderTheme.CUSTOM -> dark(customBg)
        else -> false
    }
    /** Marker fill; an active (tapped) highlight gets alpha x1.6 capped at 0.65. */
    fun markerFill(color: Int, night: Boolean, active: Boolean = false): Int {
        var a = if (night) LyricTokens.Accents.highlightNightAlpha else LyricTokens.Accents.highlightDayAlpha
        if (active) a = minOf(a * 1.6f, .65f)
        return LyricTokens.Accents.highlight(color, night).copy(alpha = a).toArgb()
    }
    fun markerLine(color: Int): Int = LyricTokens.Accents.highlight(color, false).copy(alpha = 1f).toArgb()

    /** Opaque swatch RGB for the four persisted highlight indices (朱/苔/枯茶/黛蓝). */
    val highlightColors: List<Long> = LyricTokens.Accents.highlightAccents.map {
        it.day.toArgb().toLong() and 0xFFFFFFL
    }
    fun highlight(color: Int, themeFill: Int): Int =
        if (color <= 0 || color >= highlightColors.size) themeFill
        else LyricTokens.Accents.highlight(color, false).toArgb()

    fun curl(theme: ReaderTheme, customBg: Long = theme.bg, customInk: Long = theme.ink): CurlStyle = when (theme) {
        ReaderTheme.PAPER -> CurlStyle(0xFFECE6DB.toInt(), .10f, .18f, .10f, .20f)
        ReaderTheme.BEIGE -> CurlStyle(0xFFDFD7C3.toInt(), .10f, .20f, .10f, .20f)
        ReaderTheme.MOSS -> CurlStyle(0xFFBFCAB5.toInt(), .09f, .20f, .12f, .20f)
        ReaderTheme.NIGHT -> CurlStyle(0xFF1E1F23.toInt(), .06f, .45f, .25f, .06f)
        ReaderTheme.GREEN -> CurlStyle(0xFF1F3128.toInt(), .06f, .45f, .25f, .06f)
        ReaderTheme.BLACK -> CurlStyle(0xFF0E0E0E.toInt(), .05f, .50f, .30f, .06f)
        ReaderTheme.CUSTOM -> dark(customBg).let { d ->
            CurlStyle(mix(customBg, customInk, .06f), .08f, if (d) .45f else .18f, if (d) .25f else .10f, if (d) .06f else .20f)
        }
    }
}
