package com.lyric.reader

import android.content.Context
import com.lyric.BuildConfig
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit opt-in diagnostics only: no book text, filenames, identifiers, or network. */
object PerfLog {
    @Volatile var enabled = false
        private set
    /** Release builds never record. Tests may override to exercise that path. */
    @Volatile internal var recordingAllowedOverride: Boolean? = null
    internal fun recordingAllowed(): Boolean = recordingAllowedOverride ?: BuildConfig.DEBUG
    private var context: Context? = null
    private val thread by lazy { HandlerThread("LyricPerf", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() } }
    private val handler by lazy { Handler(thread.looper) }
    private var serial = 0L
    private class Open(val id: Long, val start: Long) {
        val stages = HashSet<String>()
        val histogram = LongArray(10001)
        var frames = 0L
        var slow = 0L
        var frozen = 0L
        var slowNs = 0L
        var frozenNs = 0L
        var javaPeak = 0L
        var nativePeak = 0L
        var drops = 0L
    }
    private var current: Open? = null
    private var attached: Window? = null
    private var observing = false
    private fun removeListener() {
        if (observing) attached?.removeOnFrameMetricsAvailableListener(listener)
        observing = false
    }
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
        synchronized(this) {
            val open = current ?: return@OnFrameMetricsAvailableListener
            if (!enabled) return@OnFrameMetricsAvailableListener
            val ns = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            if (ns <= 0) return@OnFrameMetricsAvailableListener
            open.frames++
            open.histogram[(ns / 1_000_000).toInt().coerceIn(0, 10000)]++
            if (ns > 16_700_000) { open.slow++; open.slowNs += ns }
            if (ns > 100_000_000) { open.frozen++; open.frozenNs += ns }
            open.drops += drops
        }
    }
    private val sample = object : Runnable {
        override fun run() {
            synchronized(this@PerfLog) {
                if (!enabled || current == null) return
                memory(current!!)
            }
            handler.postDelayed(this, 250)
        }
    }
    suspend fun initialize(app: Context) = withContext(Dispatchers.IO) {
        enabled = recordingAllowed() && app.getSharedPreferences("perf_log", 0).getBoolean("enabled", false)
    }
    suspend fun setEnabled(app: Context, value: Boolean) = withContext(Dispatchers.IO) {
        val next = recordingAllowed() && value
        synchronized(this@PerfLog) {
            if (enabled && !next) finishLocked("switch_off")
            enabled = next
            context = app.applicationContext
        }
        if (recordingAllowed()) {
            app.getSharedPreferences("perf_log", 0).edit().putBoolean("enabled", next).apply()
        }
        if (!next) android.os.Handler(android.os.Looper.getMainLooper()).post {
            synchronized(this@PerfLog) { if (!enabled) removeListener() }
        }
    }
    @Synchronized fun attach(window: Window) {
        if (attached === window) return
        removeListener()
        attached = window
        // Listener installation is also opt-in; toggling on takes effect at the next reader open.
        if (enabled) {
            window.addOnFrameMetricsAvailableListener(listener, handler)
            observing = true
        }
    }
    @Synchronized fun detach(window: Window) {
        if (attached !== window) return
        finishLocked("activity_stop")
        removeListener()
        attached = null
    }
    @Synchronized fun beginOpen(fallback: Boolean = false) {
        if (!enabled) return
        if (fallback && current != null) return
        finishLocked("next_open")
        current = Open(++serial, SystemClock.elapsedRealtimeNanos())
        attached?.let { window ->
            removeListener()
            window.addOnFrameMetricsAvailableListener(listener, handler)
            observing = true
        }
        handler.removeCallbacks(sample)
        handler.post(sample)
        write("open=$serial tap")
    }
    @Synchronized fun source(format: String, bytes: Long) {
        if (enabled) current?.let { write("open=${it.id} format=$format file_bytes=$bytes") }
    }
    @Synchronized fun stage(name: String) {
        if (!enabled) return
        val open = current ?: return
        if (!open.stages.add(name)) return
        memory(open)
        write("open=${open.id} stage=$name elapsed_ms=${(SystemClock.elapsedRealtimeNanos() - open.start) / 1_000_000.0}")
        if (name == "first_frame_drawn") {
            if ("tap_to_skeleton" !in open.stages) write("open=${open.id} skeleton_skipped=true")
            summary(open, "first_frame")
        }
    }
    @Synchronized fun finish(reason: String = "reader_exit") { if (enabled) finishLocked(reason) }
    private fun finishLocked(reason: String) {
        current?.let { memory(it); summary(it, reason) }
        current = null
        if (enabled) handler.removeCallbacks(sample)
    }
    private fun memory(open: Open) {
        val runtime = Runtime.getRuntime()
        open.javaPeak = maxOf(open.javaPeak, runtime.totalMemory() - runtime.freeMemory())
        open.nativePeak = maxOf(open.nativePeak, Debug.getNativeHeapAllocatedSize())
    }
    private fun summary(o: Open, reason: String) {
        val target = kotlin.math.ceil(o.frames * .95).toLong()
        var cumulative = 0L
        val p95 = if (o.frames == 0L) -1 else o.histogram.indexOfFirst { cumulative += it; cumulative >= target } + 1
        write("open=${o.id} summary=$reason frames=${o.frames} gt16_7_count=${o.slow} gt16_7_ms=${o.slowNs / 1_000_000.0} gt100_count=${o.frozen} gt100_ms=${o.frozenNs / 1_000_000.0} p95_upper_ms=$p95 dropped_callbacks=${o.drops} java_peak_bytes=${o.javaPeak} native_peak_bytes=${o.nativePeak}")
    }
    private fun file(app: Context) = File(app.filesDir, "performance/lyric-perf.log")
    private fun write(line: String) {
        val app = context ?: return
        val message = "${System.currentTimeMillis()} $line"
        handler.post {
            runCatching {
                val file = file(app)
                file.parentFile?.mkdirs()
                if (file.length() > 2 * 1024 * 1024) file.renameTo(File(file.parentFile, "lyric-perf.previous.log"))
                file.appendText(message + "\n")
                Log.i("LyricPerf", message)
            }
        }
    }
    suspend fun export(app: Context): File {
        // Serialize with outstanding writes, producing a snapshot safe for other apps to read.
        val result = kotlinx.coroutines.CompletableDeferred<File>()
        synchronized(this) { current?.let { summary(it, "export") } }
        handler.post {
            runCatching {
                val folder = File(app.cacheDir, "note_exports").apply { mkdirs() }
                File.createTempFile("lyric-perf-", ".txt", folder).apply {
                    writeText("Lyric ${com.lyric.BuildConfig.VERSION_NAME} / ${android.os.Build.MODEL}\nFrameMetrics TOTAL_DURATION; P95 upper bound in 1ms buckets; memory sampled 250ms, excludes graphics.\n")
                    listOf(File(file(app).parentFile, "lyric-perf.previous.log"), file(app)).filter { it.isFile }.forEach { appendText(it.readText()) }
                }
            }.onSuccess { result.complete(it) }.onFailure { result.completeExceptionally(it) }
        }
        return result.await()
    }
}
