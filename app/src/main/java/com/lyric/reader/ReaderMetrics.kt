package com.lyric.reader

import android.os.Build
import android.os.Trace

/** Async slices include coroutine suspension and the first content frame. */
object ReaderMetrics {
    private var opening = false
    private var searching = false
    fun beginOpen() {
        endOpen()
        opening = true
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection("Lyric.ColdOpen", 1)
    }
    fun endOpen() {
        if (opening && Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("Lyric.ColdOpen", 1)
        opening = false
    }
    fun beginSearch() {
        endSearch()
        searching = true
        if (Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection("Lyric.SearchFirstResult", 2)
    }
    fun endSearch() {
        if (searching && Build.VERSION.SDK_INT >= 29) Trace.endAsyncSection("Lyric.SearchFirstResult", 2)
        searching = false
    }
}
