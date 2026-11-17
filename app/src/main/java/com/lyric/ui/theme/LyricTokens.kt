package com.lyric.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyric.R

/** Mirrors design/v1.0/tokens.md, including the PR #2 answers appended there. */
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
        /** Saved "monospace" and any unknown built-in key render as 黑体. Custom paths are unchanged. */
        fun resolveKey(name: String) = when {
            name.startsWith('/') -> name
            name == songKey -> songKey
            else -> heiKey
        }
        fun isCustom(name: String) = name.startsWith('/')
    }
    object Type {
        val title = TextStyle(fontFamily = Fonts.hei, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
        /** Lowercase upright Noto Serif wordmark; matches the README banner. */
        val wordmark = title.copy(fontFamily = Fonts.song, fontStyle = FontStyle.Normal)
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
    object Shelf {
        val minColumnWidth = 104.dp
        val compactMinColumnWidth = 72.dp
        val compactColumnGap = Space.sm
        val compactRowGap = Space.md
        val densityIcon = 24.dp
        const val coverAspect = 2f / 3f
        const val compactTitleMaxLines = 2
        const val emptyCopy = "书架还空着"
        const val emptyImportLabel = "导入书籍"
        const val emptyComicCopy = "画架还空着"
        const val emptyComicImportLabel = "导入漫画"
        const val addLabel = "添加"
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
        val presets = listOf(compact, comfortable, loose)
        val presetLabels = listOf("紧凑", "舒适", "宽松")
        const val presetRowLabel = "排版"
        const val moreLabel = "更多"
        const val customLabel = "已自定义"
        val fineTuneLabels = listOf("字号", "行距", "段距", "页边距")
        const val bodyMaxWidthDp = 640f
        val bodyMaxWidth = bodyMaxWidthDp.dp
        const val chapterTitleSp = 20f
        val chapterTitleGap = Space.xl
        val chapterTitleSize = chapterTitleSp.sp
    }
    object Focus {
        const val minMinutes = 5
        const val maxMinutes = 180
        const val stepMinutes = 5
        val presets = listOf(25, 45)
        val chipHeight = 32.dp
        val stepperButton = 40.dp
        val stepperValue = TextStyle(fontFamily = Fonts.hei, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
        const val customLabel = "自定义"
        fun snapMinutes(min: Int): Int {
            val stepped = ((min + stepMinutes / 2) / stepMinutes) * stepMinutes
            return stepped.coerceIn(minMinutes, maxMinutes)
        }
        fun isPreset(min: Int, custom: Boolean) = !custom && min in presets
        fun chipLabel(min: Int, custom: Boolean) = if (custom) "$min 分钟" else if (min in presets) "$min" else "$min 分钟"
    }
    object Motion {
        const val shortMs = 150
        const val mediumMs = 200
        const val longMs = 300
        const val ephemeralSnackbarMs = 4000L
        const val instantMs = 0
        val easeOut = CubicBezierEasing(0f, 0f, .2f, 1f)
        fun <T> short(): TweenSpec<T> = duration(shortMs)
        fun <T> medium(): TweenSpec<T> = duration(mediumMs)
        fun <T> long(): TweenSpec<T> = duration(longMs)
        fun <T> duration(ms: Int): TweenSpec<T> = tween(durationMillis = ms, easing = easeOut)
        // Choreography phase fractions kept; total duration is the approved 300ms.
        const val shelfSwitchMs = 300L
        const val shelfBookEnd = 650f / 2000f
        const val shelfMorphStart = 850f / 2000f
        const val shelfMorphEnd = 1450f / 2000f
        const val shelfFadeStart = 1650f / 2000f
        val shelfRise = 12.dp
    }
    object Skeleton {
        const val skipMs = 300L
        const val minimumMs = 300L
        const val fadeMs = Motion.shortMs
        const val breatheMinAlpha = .6f
        const val breatheMaxAlpha = 1f
        const val breatheLegMs = 1200
        const val breatheCycleMs = breatheLegMs * 2
    }

    data class Accent(val name: String, val day: Color, val night: Color) {
        fun color(isNight: Boolean) = if (isNight) night else day
    }
    object Accents {
        val moss = Accent("苔绿", Day.moss, Night.moss)
        val vermilion = Accent("朱红", Day.vermilion, Night.vermilion)
        val tea = Accent("枯茶", Color(0xFF8A6A4A), Color(0xFFC2A07E))
        val indigo = Accent("黛蓝", Color(0xFF4A5A6B), Color(0xFF8FA3B8))
        val mauve = Accent("灰紫", Color(0xFF6E6278), Color(0xFFA99BB5))
        val stone = Accent("石色", Color(0xFF7A7770), Color(0xFFADA89D))
        val all = listOf(moss, vermilion, tea, indigo, mauve, stone)
        val names = all.map { it.name }
        val presets = all.map { it.day }
        const val highlightDayAlpha = .25f
        const val highlightNightAlpha = .30f
        // Four persisted highlight indices map onto 朱/苔/枯茶/黛蓝.
        val highlightAccents = listOf(vermilion, moss, tea, indigo)
        fun color(index: Int, night: Boolean) = all[index.coerceIn(0, all.lastIndex)].color(night)
        fun highlight(index: Int, night: Boolean): Color =
            highlightAccents[index.coerceIn(0, highlightAccents.lastIndex)].color(night)
                .copy(alpha = if (night) highlightNightAlpha else highlightDayAlpha)
        fun coverIndex(title: String): Int {
            var hash = FNV_OFFSET
            for (ch in title) {
                hash = hash xor ch.code
                hash *= FNV_PRIME
            }
            return hash.toUInt().mod(all.size.toUInt()).toInt()
        }
        fun cover(title: String, night: Boolean = false) = color(coverIndex(title), night)
        private const val FNV_OFFSET = -0x7ee3623b
        private const val FNV_PRIME = 0x01000193
    }

    object Comic {
        const val hintMs = 2400L
        const val hintFlashMs = 1200L
        fun background(night: Boolean) = if (night) Color(0xFF0E0F11) else Day.background
        val bar = Color(0xE0111111)
        val ink = Color(0xFFD0CABE)
        val meta = Color(0xFF8E897F)
        val track = Color(0xFF3A3A3E)
        fun barBackground(night: Boolean) = if (night) bar else Day.card
        fun barInk(night: Boolean) = if (night) ink else Day.ink
        fun barMeta(night: Boolean) = if (night) meta else Day.secondary
        fun barTrack(night: Boolean) = if (night) track else Day.stroke
        fun barDivider(night: Boolean) = if (night) Night.stroke else Day.stroke
        fun barAccent(night: Boolean) = if (night) Night.vermilion else Day.vermilion
        /** Overlay surfaces (zoom badge, chapter dialog, page-error card) follow the UI palette. */
        const val zoomBadgeAlpha = .9f
        fun overlayCard(night: Boolean) = colors(night).card
        fun overlayStroke(night: Boolean) = colors(night).stroke
        fun overlayInk(night: Boolean) = colors(night).ink
        fun overlaySecondary(night: Boolean) = colors(night).secondary
        fun overlayAccent(night: Boolean) = colors(night).vermilion
        fun overlayPrimaryFill(night: Boolean) = colors(night).primaryFill
        fun overlayPrimaryText(night: Boolean) = colors(night).primaryText
        fun zoomBadgeBackground(night: Boolean) = overlayCard(night).copy(alpha = zoomBadgeAlpha)
    }

    /** 8% black fading to transparent; explicitly not a palette color. */
    object Curl {
        const val shadowAlpha = .08f
        val shadowColors = intArrayOf(0x14000000, 0x0A000000, 0x00000000)
        const val fallbackOutline = 0x14000000
    }

    object Overlay {
        val black = Color.Black
        val white = Color.White
        const val opaqueArgb = 0xFF000000L
        const val rgbMask = 0xFFFFFFL
    }

    /** PAPER/NIGHT follow the UI palette; other user-selectable themes keep their own colors. */
    object ReaderContent {
        const val paperBackground = 0xFFF4EFE6L
        const val paperInk = 0xFF2B2A27L
        const val paperMeta = 0xFF8A8478L
        const val beigeBackground = 0xFFE8E1CFL
        const val beigeInk = 0xFF3A362FL
        const val mossBackground = 0xFFC9D3C0L
        const val mossInk = 0xFF2F3529L
        const val mossMeta = 0xFF7A8470L
        const val nightBackground = 0xFF16171AL
        const val nightInk = 0xFFD0CABEL
        const val nightMeta = 0xFF7D786EL
        const val greenBackground = 0xFF182820L
        const val greenInk = 0xFFB8C3B1L
        const val greenMeta = 0xFF7A897AL
        const val blackBackground = 0xFF000000L
    }

    object Component {
        val indicatorStroke = 2.dp
        val continueProgressStroke = 2.dp
        val progressStroke = 3.dp
        val statusDot = 5.dp
        val dashLength = 6.dp
        val heatmapCell = 9.dp
        val sliderThumb = 14.dp
        val comicSliderThumb = 16.dp
        val smallIcon = 18.dp
        val mediumIcon = 20.dp
        val toolbarIcon = 22.dp
        val navigationIcon = 23.dp
        val smallCoverWidth = 28.dp
        val switchWidth = 30.dp
        val compactButtonHeight = 48.dp
        val smallControl = 48.dp
        val searchHeight = 48.dp
        val goalRing = 42.dp
        val buttonHeight = 48.dp
        val touchTarget = 48.dp
        val toolbarHeight = 56.dp
        val settingsLabelWidth = 58.dp
        val continueCoverWidth = 48.dp
        val settingValueWidth = 68.dp
        val listCoverHeight = 78.dp
        val continueCoverHeight = 72.dp
        val focusDial = 240.dp
        val drawerMaxWidth = 360.dp
        val dialogMaxHeight = 360.dp
        val outlineMaxHeight = 480.dp
        val settingsMaxHeight = 680.dp
        val readerToolbarElevation = 2.dp
        val readerSheetElevation = 6.dp
        val missingCover = Color(0xFFA9B39A)
        val focusTime = TextStyle(fontFamily = Fonts.song, fontSize = 52.sp, lineHeight = 60.sp)
    }
}
