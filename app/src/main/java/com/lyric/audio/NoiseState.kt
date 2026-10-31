package com.lyric.audio

/** Pure state shared by UI, persistence and the worker mixer; elapsed timer ticks are excluded. */
enum class NoiseSound(val label: String) { NONE("无"), RAIN("雨声"), PAGES("翻书"), FIRE("炉火") }

data class NoiseState(
    val sound: NoiseSound = NoiseSound.NONE,
    val volume: Float = .55f,
    val running: Boolean = false,
    val paused: Boolean = false,
    val focused: Boolean = true,
) {
    val audible get() = running && !paused && focused && sound != NoiseSound.NONE
    fun timer(running: Boolean, paused: Boolean) = copy(running = running, paused = paused)
    fun select(sound: NoiseSound) = copy(sound = sound)
    fun volume(value: Float) = copy(volume = if (value.isFinite()) value.coerceIn(0f, 1f) else .55f)
    fun persisted() = sound.name to volume
    companion object {
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
    private val duration = sampleRate * 300 / 1000
    fun target(state: NoiseState) {
        val next = FloatArray(current.size)
        if (state.audible) next[state.sound.ordinal] = state.volume
        if (next.contentEquals(target)) return
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
