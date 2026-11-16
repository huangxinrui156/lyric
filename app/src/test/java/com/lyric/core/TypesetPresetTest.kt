package com.lyric.core

import com.lyric.reader.TextReaderEngine
import com.lyric.ui.theme.LyricTokens
import org.junit.Assert.*
import org.junit.Test

class TypesetPresetTest {
    @Test
    fun defaultSettingsMatchComfortablePreset() {
        val defaults = ReaderSettings()
        assertEquals(LyricTokens.Reading.comfortable, defaults.matchedTypesetPreset())
        assertEquals(LyricTokens.Reading.default, LyricTokens.Reading.comfortable)
        assertEquals(LyricTokens.Reading.presetLabels, listOf("紧凑", "舒适", "宽松"))
        assertEquals(1, defaults.matchedTypesetPresetIndex())
        assertEquals(18f, defaults.fontSize)
        assertEquals(1.7f, defaults.lineHeight)
        assertEquals(24f, defaults.margin)
        assertEquals(.5f, defaults.paragraphSpace)
    }

    @Test
    fun applyingEachPresetWritesOnlyTokenValues() {
        val expected =
            listOf(
                LyricTokens.Reading.compact to listOf(16f, 1.5f, 16f, .4f),
                LyricTokens.Reading.comfortable to listOf(18f, 1.7f, 24f, .5f),
                LyricTokens.Reading.loose to listOf(20f, 1.9f, 32f, .6f),
            )
        assertEquals(LyricTokens.Reading.presets, expected.map { it.first })
        var settings = ReaderSettings(font = LyricTokens.Fonts.heiKey, indent = false, brightness = .3f)
        expected.forEach { (preset, values) ->
            settings = settings.applyTypesetPreset(preset)
            assertEquals(values[0], settings.fontSize)
            assertEquals(values[1], settings.lineHeight)
            assertEquals(values[2], settings.margin)
            assertEquals(values[3], settings.paragraphSpace)
            assertEquals(preset, settings.matchedTypesetPreset())
            assertTrue(settings.matchesTypesetPreset(preset))
            // Unrelated fields stay put so a typography switch does not clobber font or reading chrome.
            assertEquals(LyricTokens.Fonts.heiKey, settings.font)
            assertFalse(settings.indent)
            assertEquals(.3f, settings.brightness)
        }
    }

    @Test
    fun unmatchedCustomTypographySelectsNoPreset() {
        val custom = ReaderSettings(fontSize = 17f, lineHeight = 1.9f, margin = 28f, paragraphSpace = .6f)
        assertNull(custom.matchedTypesetPreset())
        assertEquals(-1, custom.matchedTypesetPresetIndex())
        val almostComfortable = ReaderSettings().copy(margin = 28f)
        assertNull(almostComfortable.matchedTypesetPreset())
    }

    @Test
    fun switchingPresetsKeepsCanonicalReadingLocation() {
        val book =
            Book("测试", chapters = listOf(Chapter(0, "清晨", "字".repeat(1000)), Chapter(1, "黄昏", "雨".repeat(2000))))
        val engine = TextReaderEngine()
        engine.open(book, Locator(1, 437))
        LyricTokens.Reading.presets.forEach { preset ->
            engine.settings(engine.settings.value.applyTypesetPreset(preset))
            assertEquals(Locator(1, 437), engine.currentLocator.value)
            assertEquals(preset, engine.settings.value.matchedTypesetPreset())
        }
    }
}
