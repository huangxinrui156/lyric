package com.lyric.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lyric.core.*
import com.lyric.data.LibraryRepository
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device self test (no adb needed). Measures in-process timings on the phone itself.
 * These are honest proxies, not Macrobenchmark: frame cost = CPU time to draw one page into a
 * bitmap; cold open = parse + first typeset of a 10 MB TXT in this process.
 */
object SelfTest {
    data class Metric(val name: String, val value: Double, val unit: String, val limit: Double?, val note: String = "") {
        val passed get() = limit == null || value <= limit
    }

    fun deviceLine(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "RAM ${mem.totalMem / (1 shl 20)} MB · heap ${Runtime.getRuntime().maxMemory() / (1 shl 20)} MB"
    }

    private fun tenMegabyteTxt(dir: File): File = File(dir, "selftest-10mb.txt").apply {
        if (length() >= 10L * 1024 * 1024) return@apply
        val han = (0x4e00 until 0x4e00 + 2500).map { it.toChar() }
        val rnd = java.util.Random(7)
        bufferedWriter().use { w ->
            var bytes = 0L; var chapter = 1
            while (bytes < 10L * 1024 * 1024) {
                val line = if (rnd.nextInt(60) == 0) "第${chapter++}章 自测\n"
                else buildString { repeat(40 + rnd.nextInt(160)) { append(han[rnd.nextInt(han.size)]) }; append("。\n") }
                w.write(line); bytes += line.toByteArray().size
            }
        }
    }

    private fun p95(values: List<Double>) = values.sorted()[((values.size - 1) * .95).toInt()]

    /** Must be called from a coroutine; UI-thread work is hopped to Main. */
    suspend fun run(context: Context, repository: LibraryRepository, lowEnd: Boolean, log: (String) -> Unit): List<Metric> {
        val out = mutableListOf<Metric>()
        val frameLimit = if (lowEnd) 25.0 else 16.7
        val openLimit = if (lowEnd) 3000.0 else 1500.0
        val dir = File(context.cacheDir, "selftest").apply { mkdirs() }
        log("生成 10MB TXT 样本…")
        val txt = withContext(Dispatchers.IO) { tenMegabyteTxt(dir) }
        System.gc()
        log("解析 10MB TXT…")
        val t0 = System.nanoTime()
        val book = withContext(Dispatchers.Default) { TxtParser().parse(object : FileContentSource { override val file = txt; override val displayName = txt.name; override fun open() = txt.inputStream() }) }
        val parseMs = (System.nanoTime() - t0) / 1e6
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels; val h = metrics.heightPixels
        log("首屏排版…")
        val (firstMs, view) = withContext(Dispatchers.Main) {
            val view = ReaderCanvas(context)
            val ready = CompletableDeferred<Unit>()
            view.onVisibleLocator = { ready.complete(Unit) }
            val t1 = System.nanoTime()
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            view.bind(book, Locator(), ReaderSettings(pageMode = PageMode.NONE), 0xFF2B2A27.toInt(), 0xFFF4EFE6.toInt(), "")
            withTimeoutOrNull(10_000) { while (!ready.isCompleted) { yield(); delay(1) } }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            ((System.nanoTime() - t1) / 1e6) to (view to bmp)
        }
        out += Metric("10MB TXT 冷打开（解析+首屏）", parseMs + firstMs, "ms", openLimit, "解析 ${"%.0f".format(parseMs)} ms")
        out += Metric("首屏正文（排版+绘制）", firstMs, "ms", 500.0)
        log("翻页绘制 120 页…")
        val frames = withContext(Dispatchers.Main) {
            val (v, bmp) = view
            val canvas = Canvas(bmp)
            (0 until 120).map {
                v.turn(1)
                val t = System.nanoTime(); v.draw(canvas); (System.nanoTime() - t) / 1e6
            }.drop(5)
        }
        out += Metric("翻页单帧绘制 P95", p95(frames), "ms", frameLimit, "CPU 绘制耗时，非 GPU 合成帧")
        log("全文搜索…")
        val needle = book.chapters[book.chapters.size / 2].text.substring(10, 14)
        val t2 = System.nanoTime()
        withContext(Dispatchers.Default) { repository.search(book, needle).firstOrNull() }
        out += Metric("搜索首条结果", (System.nanoTime() - t2) / 1e6, "ms", 1000.0)
        log("漫画大图解码…")
        val comic = withContext(Dispatchers.IO) {
            val page = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888).apply {
                val c = Canvas(this); val p = android.graphics.Paint()
                for (y in 0 until 2400 step 40) { p.color = (0xFF000000 or (y * 2654435761L and 0xFFFFFF)).toInt(); c.drawRect(0f, y.toFloat(), 1600f, y + 40f, p) }
            }
            val jpg = java.io.ByteArrayOutputStream().also { page.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            page.recycle()
            val cbz = File(dir, "selftest.cbz")
            java.util.zip.ZipOutputStream(cbz.outputStream()).use { z ->
                repeat(6) { z.putNextEntry(java.util.zip.ZipEntry("p$it.jpg")); z.write(jpg); z.closeEntry() }
            }
            ComicArchive(cbz, "p0.jpg").use { a ->
                (0 until 6).map { i -> val t = System.nanoTime(); a.page("p$i.jpg", w); (System.nanoTime() - t) / 1e6 }
            }
        }
        out += Metric("漫画页解码（1600×2400 JPEG）P95", p95(comic), "ms", null, "预载前后各 2 页，解码在后台线程")
        val rt = Runtime.getRuntime()
        out += Metric("当前 Java 堆占用", (rt.totalMemory() - rt.freeMemory()) / 1048576.0, "MB", null)
        withContext(Dispatchers.IO) { txt.delete() }
        return out
    }

    fun report(context: Context, metrics: List<Metric>, kill: List<KillCheck.Result>): String = buildString {
        appendLine("Lyric ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()} 性能自测")
        appendLine(deviceLine(context))
        appendLine("时间 ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())}")
        metrics.forEach { m ->
            append("${if (m.passed) "✓" else "✗"} ${m.name}: ${"%.1f".format(m.value)} ${m.unit}")
            m.limit?.let { append("（目标 ≤ ${it.toInt().takeIf { v -> v.toDouble() == it } ?: it} ${m.unit}）") }
            if (m.note.isNotBlank()) append(" · ${m.note}")
            appendLine()
        }
        if (kill.isNotEmpty()) {
            val pass = kill.count { it.passed }
            appendLine("杀进程自检 $pass / ${kill.size} 通过（目标：进度偏差 0、时长损失 ≤ 15 秒）")
            kill.takeLast(40).forEach { appendLine("  ${it.summary()}") }
        }
    }
}

/**
 * Kill/restore check without adb. While enabled, every reading position change is committed
 * synchronously to SharedPreferences (independent of Room). On the next process start the app
 * compares Room's durable progress/session with the last expected state.
 */
object KillCheck {
    private const val PREFS = "kill_check"
    @Serializable data class Result(val at: Long, val book: String, val expected: String, val durable: String,
                                    val pageDelta: Int, val charDelta: Int, val lossSec: Long) {
        val passed get() = pageDelta == 0 && charDelta == 0 && lossSec <= 15
        fun summary() = "${if (passed) "✓" else "✗"} ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(at)} " +
            "$book 期望 $expected 实际 $durable 偏差 ${if (charDelta == Int.MAX_VALUE) "跨章" else "$charDelta 字"}/${pageDelta} 页 时长损失 ${lossSec}s"
    }
    private val json = Json { ignoreUnknownKeys = true }
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, 0)
    @Volatile private var active = false
    fun enabled(c: Context) = active
    fun setEnabled(c: Context, on: Boolean) { active = on; prefs(c).edit().putBoolean("enabled", on).commit() }

    fun record(c: Context, bookId: String, title: String, l: Locator) {
        if (!enabled(c)) return
        prefs(c).edit().putString("pending", JSONObject().put("book", bookId).put("title", title)
            .put("c", l.chapterIndex).put("o", l.charOffset).put("p", l.page)
            .put("t", System.currentTimeMillis()).put("pid", android.os.Process.myPid()).toString()).commit()
    }
    fun activity(c: Context) { if (enabled(c)) prefs(c).edit().putLong("activityAt", System.currentTimeMillis()).commit() }

    /** Called once per process start, off the main thread. */
    suspend fun verifyAfterRestart(c: Context, repo: LibraryRepository) {
        val p = prefs(c)
        active = p.getBoolean("enabled", false)
        val raw = p.getString("pending", null) ?: return
        val pending = JSONObject(raw)
        if (pending.optInt("pid") == android.os.Process.myPid()) return
        val id = pending.getString("book")
        val durable = repo.dao.progress(id) ?: return
        val session = repo.dao.sessions().filter { it.bookId == id }.maxOfOrNull { it.endAt } ?: 0L
        val activityAt = p.getLong("activityAt", 0L)
        val result = Result(System.currentTimeMillis(), pending.optString("title"),
            "${pending.getInt("c")}:${pending.getInt("o")}/${pending.getInt("p")}",
            "${durable.chapterIndex}:${durable.charOffset}/${durable.page}",
            kotlin.math.abs(durable.page - pending.getInt("p")) + 100 * kotlin.math.abs(durable.chapterIndex - pending.getInt("c")),
            if (durable.chapterIndex == pending.getInt("c")) kotlin.math.abs(durable.charOffset - pending.getInt("o")) else Int.MAX_VALUE,
            if (activityAt > 0 && session > 0) ((activityAt - session) / 1000).coerceAtLeast(0) else 0)
        val list = results(c) + result
        p.edit().putString("results", json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Result.serializer()), list.takeLast(100)))
            .remove("pending").commit()
    }
    fun results(c: Context): List<Result> = prefs(c).getString("results", null)?.let {
        runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Result.serializer()), it) }.getOrNull()
    } ?: emptyList()
    fun clear(c: Context) { prefs(c).edit().remove("results").remove("pending").commit() }
}
