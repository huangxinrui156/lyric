package com.lyric.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyric.R

/** Mirrors design/v1.0/tokens.md. Unspecified legacy artwork is isolated in Pending below. */
object LyricTokens {
    data class Palette(
        val background: Color, val card: Color, val stroke: Color, val ink: Color,
        val secondary: Color, val muted: Color, val vermilion: Color, val moss: Color,
        val skeleton: Color, val primaryFill: Color, val primaryText: Color,
    )
    val Day = Palette(
        Color(0xFFF4EFE6), Color(0xFFFBF8F2), Color(0xFFE2DBCE), Color(0xFF2B2A27),
        Color(0xFF8A8478), Color(0xFFB3AC9F), Color(0xFFB5563A), Color(0xFF5E6B4E),
        Color(0xFFE9E2D6), Color(0xFF2B2A27), Color(0xFFF4EFE6),
    )
    val Night = Palette(
        Color(0xFF16171A), Color(0xFF1E1F23), Color(0xFF2A2B2F), Color(0xFFD0CABE),
        Color(0xFF7D786E), Color(0xFF5A574F), Color(0xFFE08A6E), Color(0xFFA3B38A),
        Color(0xFF2A2B2F), Color(0xFFD0CABE), Color(0xFF16171A),
    )
    fun colors(night: Boolean) = if (night) Night else Day
    val strokeWidth = 1.dp
    const val nightGrainAlpha = .03f
    const val emptyShelfHeightFraction = .4f

    object Fonts {
        val song = FontFamily(Font(R.font.noto_serif_sc))
        val hei = FontFamily.Default
        const val songKey = "serif"
        const val heiKey = "sans-serif"
        const val songLabel = "宋体"
        const val heiLabel = "黑体"
    }
    object Type {
        val title = TextStyle(fontFamily = Fonts.hei, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
        val subtitle = TextStyle(fontFamily = Fonts.hei, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
        val body = TextStyle(fontFamily = Fonts.hei, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
        val auxiliary = TextStyle(fontFamily = Fonts.hei, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
        val material = Typography(
            displayLarge = title, displayMedium = title, displaySmall = title,
            headlineLarge = title, headlineMedium = title, headlineSmall = title,
            titleLarge = title, titleMedium = subtitle, titleSmall = subtitle,
            bodyLarge = body, bodyMedium = body, bodySmall = auxiliary,
            labelLarge = body, labelMedium = auxiliary, labelSmall = auxiliary,
        )
    }
    object Radius {
        val card = 12.dp
        val button = 10.dp
        val cover = 4.dp
        val skeleton = 2.dp
        val pill = RoundedCornerShape(percent = 50)
        val cardShape = RoundedCornerShape(card)
        val buttonShape = RoundedCornerShape(button)
        val material = Shapes(
            extraSmall = RoundedCornerShape(cover), small = RoundedCornerShape(button),
            medium = RoundedCornerShape(card), large = RoundedCornerShape(card),
            extraLarge = RoundedCornerShape(button),
        )
    }
    object Space {
        val xs = 4.dp
        val sm = 8.dp
        val md = 12.dp
        val lg = 16.dp
        val xl = 24.dp
        val xxl = 32.dp
    }
    object Page {
        const val tabletSmallestWidthDp = 600
        val phoneMargin = 16.dp
        val tabletMargin = 24.dp
        fun margin(smallestWidthDp: Int) = if (smallestWidthDp >= tabletSmallestWidthDp) tabletMargin else phoneMargin
        val margin @Composable get() = margin(LocalConfiguration.current.smallestScreenWidthDp)
    }
    object Reading {
        data class Preset(val fontSizeSp: Float, val lineHeight: Float, val marginDp: Float, val paragraphEm: Float) {
            val fontSize get() = fontSizeSp.sp
            val margin get() = marginDp.dp
        }
        val compact = Preset(16f, 1.5f, 16f, .4f)
        val comfortable = Preset(18f, 1.7f, 24f, .5f)
        val loose = Preset(20f, 1.9f, 32f, .6f)
        val default = comfortable
        const val bodyMaxWidthDp = 640f
        val bodyMaxWidth = bodyMaxWidthDp.dp
    }
    object Motion {
        const val shortMs = 150
        const val mediumMs = 200
        const val longMs = 300
        const val instantMs = 0
        val easeOut = CubicBezierEasing(0f, 0f, .2f, 1f)
        fun <T> short(): TweenSpec<T> = duration(shortMs)
        fun <T> medium(): TweenSpec<T> = duration(mediumMs)
        fun <T> long(): TweenSpec<T> = duration(longMs)
        fun <T> duration(ms: Int): TweenSpec<T> = tween(durationMillis = ms, easing = easeOut)
    }
    object Skeleton {
        const val skipMs = 300L
        const val minimumMs = 300L
        const val fadeMs = Motion.shortMs
        const val breatheMinAlpha = .6f
        const val breatheMaxAlpha = 1f
        // Requested 0.6 → 1.0 rise over 1.2 seconds, then reverse; no shimmer.
        const val breatheLegMs = 1200
        const val breatheCycleMs = breatheLegMs * 2
    }

    /** pending 美术设计: preserve product artwork/geometry not specified by the 1.0 tables.
     * These are not additions to the approved spacing or UI type scales. */
    object Pending {
        val accentNames = listOf("苔绿", "朱红", "枯茶", "黛蓝", "灰紫", "石色")
        val accentPresets = listOf(Day.moss, Day.vermilion, Color(0xFF8A6A4F), Color(0xFF626D83), Color(0xFF7E667D), Color(0xFF73766C))
        fun accent(index: Int, night: Boolean): Color {
            val i = index.coerceIn(0, accentPresets.lastIndex)
            return when {
                !night -> accentPresets[i]
                i == 0 -> Night.moss
                i == 1 -> Night.vermilion
                else -> lerp(accentPresets[i], Day.background, .42f)
            }
        }
        // Existing HEAD uses 2000ms, not 1400ms. Preserve phase proportions and reversible art.
        const val shelfSwitchMs = 2000L
        const val shelfBookEnd = 650f / 2000f
        const val shelfMorphStart = 850f / 2000f
        const val shelfMorphEnd = 1450f / 2000f
        const val shelfFadeStart = 1650f / 2000f
        val shelfRise = 12.dp
        const val comicHintMs = 2400L
        const val comicHintFlashMs = 1200L
        val comicBackground = Color(0xFF111111)
        val comicBar = Color(0xE0111111)
        val comicInk = Color(0xFFD0CABE)
        val comicMeta = Color(0xFF8E897F)
        val comicTrack = Color(0xFF3A3A3E)
        val comicErrorPanel = Color(0xFF1A1A1D)
        val comicOutline = Color(0xFF45444A)
        val overlayBlack = Color.Black
        val overlayWhite = Color.White
        const val opaqueArgb = 0xFF000000L
        const val rgbMask = 0xFFFFFFL
        val curlBackColors = intArrayOf(0x18000000, 0x38FFFFFF, 0x68000000)
        const val curlFallbackOutline = 0x33000000
        val coverColors = listOf(0xFF5E6B4E, 0xFF8A6A4F, 0xFF3E4A52, 0xFFB5563A, 0xFF9A9178, 0xFF6F5B6E)
        val missingCover = Color(0xFFA9B39A)
        val exportDayDivider = Color(0xFFD9D1C3)
        val highlightSwatches = listOf(0xB5563AL, 0x5E6B4EL, 0xC08A2EL, 0x4F6D8AL)

        // Reading content palettes are persisted choices, independent of Compose chrome.
        object ReaderContent {
            const val paperBackground = 0xFFF4EFE6L
            const val paperInk = 0xFF2B2A27L
            const val paperMeta = 0xFF9C9384L
            const val beigeBackground = 0xFFE8E1CFL
            const val beigeInk = 0xFF3A362FL
            const val mossBackground = 0xFFC9D3C0L
            const val mossInk = 0xFF2F3529L
            const val mossMeta = 0xFF7A8470L
            const val nightBackground = 0xFF16171AL
            const val nightInk = 0xFFB8B2A6L
            const val nightMeta = 0xFF5C5A55L
            const val greenBackground = 0xFF182820L
            const val greenInk = 0xFFB8C3B1L
            const val greenMeta = 0xFF7A897AL
            const val blackBackground = 0xFF000000L
        }

        // Component dimensions, not spacing: retain touch targets, image ratios, viewport caps.
        val indicatorStroke = 2.dp
        val progressStroke = 3.dp
        val statusDot = 5.dp
        val dashLength = 6.dp
        val heatmapCell = 9.dp
        val sliderThumb = 14.dp
        val smallIcon = 18.dp
        val mediumIcon = 20.dp
        val toolbarIcon = 22.dp
        val navigationIcon = 23.dp
        val smallCoverWidth = 28.dp
        val switchWidth = 30.dp
        val compactButtonHeight = 34.dp
        val smallControl = 36.dp
        val searchHeight = 40.dp
        val goalRing = 42.dp
        val buttonHeight = 44.dp
        val touchTarget = 48.dp
        val toolbarHeight = 56.dp
        val settingsLabelWidth = 58.dp
        val continueCoverWidth = 64.dp
        val settingValueWidth = 68.dp
        val listCoverHeight = 78.dp
        val continueCoverHeight = 96.dp
        val focusDial = 240.dp
        val drawerMaxWidth = 360.dp
        val dialogMaxHeight = 360.dp
        val outlineMaxHeight = 480.dp
        val settingsMaxHeight = 680.dp
        val readerToolbarElevation = 2.dp
        val readerSheetElevation = 6.dp
        val focusTime = TextStyle(fontFamily = Fonts.song, fontSize = 52.sp, lineHeight = 60.sp)
    }
}
