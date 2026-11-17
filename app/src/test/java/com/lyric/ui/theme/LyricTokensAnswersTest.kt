package com.lyric.ui.theme

import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.lyric.core.ReaderTheme
import org.junit.Assert.*
import org.junit.Test

class LyricTokensAnswersTest {
    @Test fun accentDayNightPairsMatchTokensMd() {
        val expected = listOf(
            "苔绿" to (0xFF5E6B4E.toInt() to 0xFFA3B38A.toInt()),
            "朱红" to (0xFFB5563A.toInt() to 0xFFE08A6E.toInt()),
            "枯茶" to (0xFF8A6A4A.toInt() to 0xFFC2A07E.toInt()),
            "黛蓝" to (0xFF4A5A6B.toInt() to 0xFF8FA3B8.toInt()),
            "灰紫" to (0xFF6E6278.toInt() to 0xFFA99BB5.toInt()),
            "石色" to (0xFF7A7770.toInt() to 0xFFADA89D.toInt()),
        )
        assertEquals(expected.map { it.first }, LyricTokens.Accents.names)
        expected.forEachIndexed { i, (_, pair) ->
            assertEquals(pair.first, LyricTokens.Accents.color(i, false).toArgb())
            assertEquals(pair.second, LyricTokens.Accents.color(i, true).toArgb())
        }
        val title = "剑来"
        assertEquals(LyricTokens.Accents.color(LyricTokens.Accents.coverIndex(title), false), LyricTokens.Accents.cover(title))
    }

    @Test fun savedMonospaceRendersAsHeiAndCustomPathsAreKept() {
        assertEquals(LyricTokens.Fonts.heiKey, LyricTokens.Fonts.resolveKey("monospace"))
        assertEquals(LyricTokens.Fonts.heiKey, LyricTokens.Fonts.resolveKey("sans-serif"))
        assertEquals(LyricTokens.Fonts.songKey, LyricTokens.Fonts.resolveKey("serif"))
        assertEquals("/fonts/custom.ttf", LyricTokens.Fonts.resolveKey("/fonts/custom.ttf"))
        assertTrue(LyricTokens.Fonts.isCustom("/fonts/custom.ttf"))
        assertFalse(LyricTokens.Fonts.isCustom("monospace"))
    }

    @Test fun shelfSwitchIs300MsAndTouchTargetsAreAtLeast48() {
        assertEquals(300L, LyricTokens.Motion.shelfSwitchMs)
        assertEquals(LyricTokens.Motion.longMs.toLong(), LyricTokens.Motion.shelfSwitchMs)
        listOf(
            LyricTokens.Component.touchTarget,
            LyricTokens.Component.buttonHeight,
            LyricTokens.Component.searchHeight,
            LyricTokens.Component.compactButtonHeight,
            LyricTokens.Component.smallControl,
        ).forEach { assertTrue(it.toString(), it >= 48.dp) }
    }

    @Test fun defaultReaderThemesFollowUiPalette() {
        assertEquals(LyricTokens.Day.background.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.PAPER.bg)
        assertEquals(LyricTokens.Day.ink.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.PAPER.ink)
        assertEquals(LyricTokens.Night.background.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.NIGHT.bg)
        assertEquals(LyricTokens.Night.ink.toArgb().toLong() and 0xFFFFFFFFL, ReaderTheme.NIGHT.ink)
        assertEquals(.25f, LyricTokens.Accents.highlightDayAlpha)
        assertEquals(.30f, LyricTokens.Accents.highlightNightAlpha)
        assertEquals(0xFFF4EFE6.toInt(), LyricTokens.Comic.background(false).toArgb())
        assertEquals(0xFF0E0F11.toInt(), LyricTokens.Comic.background(true).toArgb())
    }

    @Test fun coverHashSpreadsAcrossAllSixAccents() {
        val titles = listOf(
            "红楼梦", "浮生六记", "西游记", "水浒传", "三国演义", "聊斋志异", "古文观止", "陶庵梦忆",
            "牡丹亭", "西厢记", "窦娥冤", "诗经", "楚辞", "史记", "论语", "孟子", "庄子", "老子",
            "韩非子", "孙子兵法", "世说新语", "文心雕龙", "徐霞客游记", "桃花扇", "长生殿", "镜花缘",
            "儒林外史", "官场现形记", "老残游记", "孽海花", "山海经", "点石斋画报", "搜神记", "洛神赋",
            "归去来兮辞", "出师表", "岳阳楼记", "醉翁亭记", "滕王阁序", "阿房宫赋", "桃花源记", "项脊轩志",
            "将进酒", "蜀道难", "春江花月夜", "琵琶行", "长恨歌", "赤壁赋", "兰亭集序", "陋室铭",
        )
        val indexes = titles.map { LyricTokens.Accents.coverIndex(it) }.toSet()
        assertEquals(setOf(0, 1, 2, 3, 4, 5), indexes)
        // Same title always maps to the same color.
        assertEquals(LyricTokens.Accents.coverIndex("红楼梦"), LyricTokens.Accents.coverIndex("红楼梦"))
    }

    @Test fun comicOverlaysFollowThemeTokens() {
        assertEquals(.9f, LyricTokens.Comic.zoomBadgeAlpha, .0f)
        assertEquals(LyricTokens.Day.card.copy(alpha = LyricTokens.Comic.zoomBadgeAlpha), LyricTokens.Comic.zoomBadgeBackground(false))
        assertEquals(LyricTokens.Night.card.copy(alpha = LyricTokens.Comic.zoomBadgeAlpha), LyricTokens.Comic.zoomBadgeBackground(true))
        assertEquals(LyricTokens.Day.card, LyricTokens.Comic.overlayCard(false))
        assertEquals(LyricTokens.Night.card, LyricTokens.Comic.overlayCard(true))
        assertEquals(LyricTokens.Day.stroke, LyricTokens.Comic.overlayStroke(false))
        assertEquals(LyricTokens.Night.stroke, LyricTokens.Comic.overlayStroke(true))
        assertEquals(LyricTokens.Day.ink, LyricTokens.Comic.overlayInk(false))
        assertEquals(LyricTokens.Night.ink, LyricTokens.Comic.overlayInk(true))
        assertEquals(LyricTokens.Day.secondary, LyricTokens.Comic.overlaySecondary(false))
        assertEquals(LyricTokens.Night.secondary, LyricTokens.Comic.overlaySecondary(true))
        assertEquals(LyricTokens.Day.primaryFill, LyricTokens.Comic.overlayPrimaryFill(false))
        assertEquals(LyricTokens.Night.primaryFill, LyricTokens.Comic.overlayPrimaryFill(true))
        // Day overlays must not reuse the old night-only panel/outline.
        assertNotEquals(0xFF1A1A1D.toInt(), LyricTokens.Comic.overlayCard(false).toArgb())
        assertNotEquals(0xFF45444A.toInt(), LyricTokens.Comic.overlayStroke(false).toArgb())
    }
}
