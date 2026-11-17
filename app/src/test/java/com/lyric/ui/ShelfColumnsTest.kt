package com.lyric.ui

import androidx.compose.ui.unit.Density
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
import org.junit.Test

class ShelfColumnsTest {
    @Test fun adaptiveColumnsRespectMinimumOnPhoneTabletAndWideFold() {
        // Use Compose's actual Adaptive track calculation, including production margins/gaps.
        for ((width, smallestWidth, expected) in listOf(Triple(360, 360, 2), Triple(600, 600, 4), Triple(840, 600, 6))) {
            for (scale in listOf(1f, 2f)) {
                val density = Density(scale)
                val available = with(density) { (width - LyricTokens.Page.margin(smallestWidth).value * 2).toInt() * scale }.toInt()
                val sizes = with(shelfGridCells(true)) {
                    with(density) { calculateCrossAxisCellSizes(available, LyricTokens.Space.md.roundToPx()) }
                }
                assertEquals("columns at $width dp / density $scale", expected, sizes.size)
                assertTrue(sizes.all { it >= with(density) { LyricTokens.Shelf.minColumnWidth.roundToPx() } })
                val listSizes = with(shelfGridCells(false)) {
                    with(density) { calculateCrossAxisCellSizes(available, LyricTokens.Space.md.roundToPx()) }
                }
                assertEquals(listOf(available), listSizes)
            }
        }
    }

    @Test fun compactFourColumnsOnPhoneAndAdaptiveOnTablet() {
        for ((width, smallestWidth, expected) in listOf(Triple(360, 360, 4), Triple(393, 393, 4), Triple(600, 600, 7))) {
            for (scale in listOf(1f, 2f)) {
                val density = Density(scale)
                val available = with(density) { (width - LyricTokens.Page.margin(smallestWidth).value * 2).toInt() * scale }.toInt()
                val sizes = with(shelfGridCells(true, compact = true)) {
                    with(density) { calculateCrossAxisCellSizes(available, LyricTokens.Shelf.compactColumnGap.roundToPx()) }
                }
                assertEquals("compact columns at $width dp / density $scale", expected, sizes.size)
                assertTrue(sizes.all { it >= with(density) { LyricTokens.Shelf.compactMinColumnWidth.roundToPx() } })
            }
        }
    }

    @Test fun standardGridStaysTwoColumnsOnPhone() {
        val density = Density(1f)
        val available = with(density) { (360 - LyricTokens.Page.margin(360).value * 2).toInt() }
        val sizes = with(shelfGridCells(true, compact = false)) {
            with(density) { calculateCrossAxisCellSizes(available, LyricTokens.Space.md.roundToPx()) }
        }
        assertEquals(2, sizes.size)
    }
}
