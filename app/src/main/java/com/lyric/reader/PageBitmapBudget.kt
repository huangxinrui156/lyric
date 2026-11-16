package com.lyric.reader

import android.graphics.Bitmap
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/** One budget across sources AND pager-held pages. Weak entries stay charged after LRU eviction
 * or source.close() until no caller owns the bitmap and the GC has reclaimed it. Never recycle
 * a displayed bitmap. Decode under the same lock so simultaneous prefetches cannot oversubscribe. */
internal class PageBitmapBudget(val limit: Long) {
    private val live = mutableListOf<WeakReference<Bitmap>>()
    private val cache = LinkedHashMap<String, Bitmap>(16, .75f, true)
    @Synchronized fun usedBytes(): Long {
        live.removeAll { it.get()?.isRecycled != false }
        return live.sumOf { it.get()?.allocationByteCount?.toLong() ?: 0L }
    }
    @Synchronized fun get(key: String): Bitmap? = cache[key]
    @Synchronized fun decode(key: String, desiredBytes: Long, decode: (Long) -> Bitmap): Bitmap {
        cache[key]?.let { return it }
        var available = limit - usedBytes()
        val reserve = minOf(1024L * 1024, limit / 16)
        if (desiredBytes > available - reserve) {
            cache.clear()
            // Weak accounting is conservative until GC. Reclaim unowned pages before lowering
            // quality, otherwise a long import/reading session could exhaust the budget with
            // unreachable bitmaps even though there is no real memory pressure. IO thread only.
            System.gc()
            available = limit - usedBytes()
        }
        check(available >= 4) { "图片内存不足，请稍后重试" }
        val allowed = if (desiredBytes <= available - reserve) desiredBytes else (available / 2).coerceAtLeast(4)
        val bitmap = decode(allowed.coerceAtMost(48L * 1024 * 1024))
        if (bitmap.allocationByteCount > allowed) {
            bitmap.recycle() // Not published yet.
            error("图片解码超出内存预算")
        }
        live += WeakReference(bitmap)
        cache[key] = bitmap
        var cached = cache.values.sumOf { it.allocationByteCount.toLong() }
        while (cached > limit / 2 && cache.isNotEmpty()) {
            val first = cache.entries.iterator()
            cached -= first.next().value.allocationByteCount
            first.remove()
        }
        return bitmap
    }
    @Synchronized fun close(prefix: String) { cache.keys.removeAll { it.startsWith(prefix) } }

    companion object {
        val shared = PageBitmapBudget((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(96L * 1024 * 1024))
        private val sequence = AtomicLong()
        fun sourceKey() = "${sequence.incrementAndGet()}/"
    }
}
