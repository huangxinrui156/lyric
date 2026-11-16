package com.lyric.audio

import android.content.*
import android.media.*
import android.os.Handler
import android.os.Looper
import com.lyric.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/** AudioTrack streams decoded PCM with sample-exact wrapping and a 300ms gain crossfade.
 * Codec, resource reads, writes and release all run on IO. No player/decoder is owned by composition.
 */
class NoisePlayer(private val context: Context, private val scope: CoroutineScope, private val pauseTimer: () -> Unit) {
    private val state = MutableStateFlow(NoiseState(focused = false))
    val settings: StateFlow<NoiseState> = state.asStateFlow()
    private val manager = context.getSystemService(AudioManager::class.java)
    private var heldFocus = false
    private var userSelected = false
    private var userVolume = false
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> state.update { it.copy(focused = true) }
                AudioManager.AUDIOFOCUS_LOSS -> { heldFocus = false; state.update { it.copy(focused = false) } }
                else -> state.update { it.copy(focused = false) }
            }
        }, Handler(Looper.getMainLooper())).build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { state.update { it.copy(focused = false) } }
    }
    private val jobs = mutableListOf<Job>()
    init {
        androidx.core.content.ContextCompat.registerReceiver(context, noisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        jobs += scope.launch {
            val saved = withContext(Dispatchers.IO) {
                context.getSharedPreferences("focus_noise", 0).let { NoiseState.restore(it.getString("sound", null), it.getFloat("volume", NoiseState.DEFAULT_VOLUME)) }
            }
            state.update { it.copy(sound = if (userSelected) it.sound else saved.sound, volume = if (userVolume) it.volume else saved.volume) }
            state.map { it.persisted() }.distinctUntilChanged().collect { (sound, volume) ->
                withContext(Dispatchers.IO) {
                    context.getSharedPreferences("focus_noise", 0).edit().putString("sound", sound).putFloat("volume", volume).commit()
                }
            }
        }
        jobs += scope.launch {
            state.map { it.running && !it.paused && it.sound != NoiseSound.NONE }.distinctUntilChanged().collect { wants ->
                if (wants && !heldFocus) {
                    heldFocus = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    state.update { it.copy(focused = heldFocus) }
                } else if (!wants && heldFocus) {
                    manager.abandonAudioFocusRequest(request); heldFocus = false
                }
            }
        }
        jobs += scope.launch(Dispatchers.IO) { runMixer() }
    }
    fun timer(running: Boolean, paused: Boolean) { state.update { it.timer(running, paused) } }
    fun select(sound: NoiseSound) { userSelected = true; state.update { it.select(sound) } }
    fun volume(value: Float) { userVolume = true; state.update { it.volume(value) } }
    fun close() {
        jobs.forEach { it.cancel() }
        if (heldFocus) manager.abandonAudioFocusRequest(request)
        context.unregisterReceiver(noisy)
    }
    private suspend fun runMixer() {
        val loops = mutableMapOf<NoiseSound, ShortArray>()
        val decoding = mutableMapOf<NoiseSound, Deferred<ShortArray>>()
        var readySound = NoiseSound.NONE
        val positions = IntArray(NoiseSound.entries.size)
        val gains = NoiseGains()
        val buffer = ShortArray(480) // 20ms; fast pause response, bounded hardware buffering.
        var track: AudioTrack? = null
        var playing = false
        try {
            while (currentCoroutineContext().isActive) {
                var s = state.value
                if (!s.running || s.paused || !s.focused) {
                    if (playing) { track?.pause(); track?.flush(); playing = false; gains.reset() }
                    if (!s.running) positions.fill(0)
                    state.first { it.running && !it.paused && it.focused && it.sound != NoiseSound.NONE }
                    continue
                }
                if (s.sound != NoiseSound.NONE && s.sound !in loops) {
                    val sound = s.sound
                    val pending = decoding.getOrPut(sound) {
                        scope.async(Dispatchers.IO) { decode(when (sound) {
                            NoiseSound.RAIN -> R.raw.noise_rain
                            NoiseSound.PAGES -> R.raw.noise_pages
                            else -> R.raw.noise_fire
                        }) }
                    }
                    if (pending.isCompleted) {
                        loops[sound] = pending.await(); decoding.remove(sound)
                        continue // Recheck timer/focus after decode completion.
                    }
                    // Keep the previous loop fed while a new loop decodes on another worker.
                    if (track == null) { delay(10); continue }
                    s = s.select(readySound)
                }
                readySound = s.sound
                if (track == null && s.sound == NoiseSound.NONE) {
                    state.first { it.audible }; continue
                }
                if (track == null) {
                    track = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(maxOf(1920, AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                        .build()
                }
                gains.target(s)
                var silent = true
                for (i in buffer.indices) {
                    val levels = gains.next(); var mixed = 0f
                    loops.forEach { (sound, pcm) ->
                        val index = sound.ordinal
                        mixed += pcm[positions[index]] * levels[index]
                        positions[index] = (positions[index] + 1) % pcm.size
                        if (levels[index] > 0f) silent = false
                    }
                    buffer[i] = mixed.coerceIn(-32767f, 32767f).toInt().toShort()
                }
                if (silent && s.sound == NoiseSound.NONE) {
                    track.pause(); track.flush(); playing = false
                    if (state.value.audible) delay(10) else state.first { it.audible }
                    continue
                }
                val latest = state.value
                if (!latest.running || latest.paused || !latest.focused) continue
                if (!playing) { track.play(); playing = true }
                var written = 0
                while (written < buffer.size) {
                    currentCoroutineContext().ensureActive()
                    val count = track.write(buffer, written, buffer.size - written, AudioTrack.WRITE_BLOCKING)
                    check(count > 0) { "Audio output unavailable" }
                    written += count
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { state.update { it.copy(focused = false) } }
        finally { decoding.values.forEach { it.cancel() }; track?.run { runCatching { pause(); flush() }; release() } }
    }
    private suspend fun decode(resource: Int): ShortArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.resources.openRawResourceFd(resource).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0); codec.start()
            val result = ByteArrayOutputStream(); val info = MediaCodec.BufferInfo()
            var inputEnded = false; var outputEnded = false
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!outputEnded) {
                currentCoroutineContext().ensureActive(); check(System.nanoTime() < deadline)
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val count = extractor.readSampleData(buffer, 0)
                        inputEnded = count < 0
                        codec.queueInputBuffer(index, 0, maxOf(0, count), if (inputEnded) 0 else extractor.sampleTime,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputEnded) extractor.advance()
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = codec.outputFormat
                    check(out.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 24000 && out.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                } else if (index >= 0) {
                    codec.getOutputBuffer(index)?.let { b ->
                        b.position(info.offset); b.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size); b.get(bytes); result.write(bytes)
                        check(result.size() <= 6_000_000)
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            val shorts = java.nio.ByteBuffer.wrap(result.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val all = ShortArray(shorts.remaining()).also { shorts.get(it); check(it.isNotEmpty()) }
            // Generated loops are whole seconds long; drop codec padding so the wrap stays sample-exact.
            val exact = all.size / 24000 * 24000
            return if (exact > 0 && exact < all.size) all.copyOf(exact) else all
        } finally { codec?.run { runCatching { stop() }; release() }; extractor.release() }
    }
}
