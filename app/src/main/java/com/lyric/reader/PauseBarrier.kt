package com.lyric.reader

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Only waits for already-enqueued application-scope writes. A timeout never cancels the writer.
 * Install the timer on the calling thread before waiting (even if the IO pool is saturated). */
internal fun awaitPauseWrites(timeoutMs: Long = 2_000, drain: suspend () -> Unit): Boolean {
    val completed = runBlocking { withTimeoutOrNull(timeoutMs) { drain(); true } } ?: false
    if (!completed) Log.w("LyricProgress", "暂停落盘等待超时，后台继续保存")
    return completed
}
