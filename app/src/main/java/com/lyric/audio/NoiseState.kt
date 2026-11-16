package com.lyric.audio

/** Pure state shared by UI, persistence and the worker mixer; elapsed timer ticks are excluded. */
enum class NoiseSound(val label: String) { NONE("无"), RAIN("雨声"), PAGES("翻书"), FIRE("炉火") }

data class NoiseState(
    val sound: NoiseSound = NoiseSound.NONE,
    val volume: Float = DEFAULT_VOLUME,
    val running: Boolean = false,
    val paused: Boolean = false,
    val focused: Boolean = true,
) {
    val audible get() = running && !paused && focused && sound != NoiseSound.NONE
    fun timer(running: Boolean, paused: Boolean) = copy(running = running, paused = paused)
    fun select(sound: NoiseSound) = copy(sound = sound)
    fun volume(value: Float) = copy(volume = if (value.isFinite()) value.coerceIn(0f, 1f) else DEFAULT_VOLUME)
    fun persisted() = sound.name to volume
    companion object {
        const val DEFAULT_VOLUME = .35f
        /** Perceptual (≈ audio taper) curve: slider position -> linear gain. 35% ≈ -18 dB. */
        fun gain(volume: Float): Float = if (volume <= 0f) 0f else (volume * volume * volume).coerceIn(0f, 1f)
        fun restore(sound: String?, volume: Float) = NoiseState(
            sound = NoiseSound.entries.firstOrNull { it.name == sound } ?: NoiseSound.NONE).volume(volume)
    }
}

/** Per-sample interpolation permits interruption by another choice with no gain discontinuity. */
class NoiseGains(private val sampleRate: Int = 24000) {
    private val current = FloatArray(NoiseSound.entries.size)
    private var from = current.copyOf()
    private var target = current.copyOf()
    private var elapsed = 0
    private val crossfade = sampleRate * 300 / 1000
    private val fadeIn = sampleRate * 800 / 1000
    private var duration = crossfade
    fun target(state: NoiseState) {
        val next = FloatArray(current.size)
        if (state.audible) next[state.sound.ordinal] = NoiseState.gain(state.volume)
        if (next.contentEquals(target)) return
        val silent = current.all { it == 0f }
        // Starting from silence fades in over 800 ms; switching sounds crossfades over 300 ms.
        // Volume-only changes on the same sound use a short ramp to avoid zipper noise.
        val sameSound = target.indices.all { (target[it] > 0f) == (next[it] > 0f) } && !silent
        duration = when { silent -> fadeIn; sameSound -> sampleRate * 50 / 1000; else -> crossfade }
        from = current.copyOf(); target = next; elapsed = 0
    }
    fun next(): FloatArray {
        elapsed = (elapsed + 1).coerceAtMost(duration)
        val t = elapsed.toFloat() / duration
        current.indices.forEach { current[it] = from[it] + (target[it] - from[it]) * t }
        return current
    }
    fun reset() { current.fill(0f); from.fill(0f); target.fill(0f); elapsed = 0 }
}
