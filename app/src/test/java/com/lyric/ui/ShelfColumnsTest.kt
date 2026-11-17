package com.lyric.ui

import androidx.compose.ui.unit.Density
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
import org.junit.Test

class ShelfColumnsTest {
    @Test fun standardGridIsAlwaysTwoColumns() {
        for ((width, smallestWidth) in listOf(360 to 360, 393 to 393, 600 to 600, 840 to 600)) {
            for (scale in listOf(1f, 2f)) {
                val density = Density(scale)
                val available = with(density) { (width - LyricTokens.Page.margin(smallestWidth).value * 2).toInt() * scale }.toInt()
                val sizes = with(shelfGridCells(true, compact = false)) {
                    with(density) { calculateCrossAxisCellSizes(available, LyricTokens.Space.md.roundToPx()) }
                }
                assertEquals("standard columns at $width dp / density $scale", LyricTokens.Shelf.standardColumns, sizes.size)
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
}
