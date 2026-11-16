package com.lyric.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log

/** OOM is recoverable for an optional animation. Cancellation and other errors still propagate. */
internal inline fun <T> pageSnapshot(capture: () -> T): T? = try { capture() }
catch (e: OutOfMemoryError) { Log.w("LyricPage", "翻页截图内存不足，直接翻页", e); null }

internal fun pageSnapshot(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap? = pageSnapshot {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try { draw(Canvas(bitmap)); bitmap }
    catch (e: Throwable) { bitmap.recycle(); throw e }
}
