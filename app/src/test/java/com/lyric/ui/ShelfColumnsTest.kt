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
}
