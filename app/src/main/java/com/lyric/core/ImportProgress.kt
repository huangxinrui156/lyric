package com.lyric.core

/**
 * Per-thread progress/deadline hook for synchronous parsers. The repository installs a listener
 * around parse(); parsers call tick() per unit of work so the UI can show "章节 x / y" and a stuck
 * or cancelled import fails with a clear message instead of spinning forever.
 */
object ImportProgress {
    class Scope(val deadlineNanos: Long, val isCancelled: () -> Boolean, val onProgress: (String, Int, Int) -> Unit)
    private val current = ThreadLocal<Scope?>()

    fun <T> run(timeoutMs: Long, isCancelled: () -> Boolean, onProgress: (String, Int, Int) -> Unit, block: () -> T): T {
        val previous = current.get()
        current.set(Scope(System.nanoTime() + timeoutMs * 1_000_000, isCancelled, onProgress))
        try { return block() } finally { current.set(previous) }
    }

    fun check() {
        val scope = current.get() ?: return
        if (scope.isCancelled()) throw kotlinx.coroutines.CancellationException("导入已取消")
        if (System.nanoTime() > scope.deadlineNanos)
            throw java.util.concurrent.TimeoutException("解析超时，文件过大或结构异常")
    }

    fun tick(stage: String, done: Int, total: Int) {
        val scope = current.get() ?: return
        if (scope.isCancelled()) throw kotlinx.coroutines.CancellationException("导入已取消")
        if (System.nanoTime() > scope.deadlineNanos)
            throw java.util.concurrent.TimeoutException("解析超时（$stage $done / $total），文件过大或结构异常")
        scope.onProgress(stage, done, total)
    }
}
