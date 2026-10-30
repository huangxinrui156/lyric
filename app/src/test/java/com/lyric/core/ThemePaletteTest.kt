package com.lyric.core

import org.junit.Assert.*
import org.junit.Test

class ThemePaletteTest {
    @Test fun designTableValues() {
        val day = ThemePalette.selection(ReaderTheme.PAPER)
        assertEquals(0x38B5563A, day.fill) // #B5563A @22%
        assertEquals(0xFFB5563A.toInt(), day.handle)
        assertEquals(0xFF2A2B2F.toInt(), ThemePalette.selection(ReaderTheme.NIGHT).menuBg)
        val night = ThemePalette.curl(ReaderTheme.NIGHT)
        assertEquals(0xFF1E1F23.toInt(), night.back); assertEquals(.45f, night.castAlpha); assertEquals(.25f, night.dimAlpha)
        assertEquals(0xFFDFD7C3.toInt(), ThemePalette.curl(ReaderTheme.BEIGE).back)
        // Custom dark background picks dark shadow parameters.
        assertEquals(.45f, ThemePalette.curl(ReaderTheme.CUSTOM, 0xFF101010, 0xFFEEEEEE).castAlpha)
        assertEquals(.18f, ThemePalette.curl(ReaderTheme.CUSTOM, 0xFFF0F0F0, 0xFF111111).castAlpha)
    }
}
