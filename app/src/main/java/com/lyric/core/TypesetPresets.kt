package com.lyric.core

import com.lyric.ui.theme.LyricTokens

/** Applies every field on a [LyricTokens.Reading.Preset]; values come only from tokens. */
fun ReaderSettings.applyTypesetPreset(preset: LyricTokens.Reading.Preset): ReaderSettings =
    copy(
        fontSize = preset.fontSizeSp,
        lineHeight = preset.lineHeight,
        margin = preset.marginDp,
        paragraphSpace = preset.paragraphEm,
    )

fun ReaderSettings.matchesTypesetPreset(preset: LyricTokens.Reading.Preset): Boolean =
    fontSize == preset.fontSizeSp &&
        lineHeight == preset.lineHeight &&
        margin == preset.marginDp &&
        paragraphSpace == preset.paragraphEm

/** Null / -1 when the current typography is a custom mix, so no chip is falsely selected. */
fun ReaderSettings.matchedTypesetPreset(): LyricTokens.Reading.Preset? =
    LyricTokens.Reading.presets.firstOrNull(::matchesTypesetPreset)

fun ReaderSettings.matchedTypesetPresetIndex(): Int =
    LyricTokens.Reading.presets.indexOfFirst(::matchesTypesetPreset)
