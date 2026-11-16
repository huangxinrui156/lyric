package com.lyric.reader

import com.lyric.ui.theme.LyricTokens
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** First-content policy, independent of Compose and driven by a monotonic clock. */
enum class LoadPresentation(val content: Boolean, val skeleton: Boolean, val fadeMs: Int) {
    WAITING(false, false, 0), SKELETON(false, true, 0),
    FADING(true, true, LyricTokens.Skeleton.fadeMs), CONTENT(true, false, 0),
}

internal suspend fun presentFirstContent(
    ready: StateFlow<Boolean>,
    nowMs: () -> Long,
    present: (LoadPresentation) -> Unit,
) = coroutineScope {
    var shownAt: Long? = null
    present(LoadPresentation.WAITING)
    val threshold = launch {
        delay(LyricTokens.Skeleton.skipMs)
        if (!ready.value) {
            shownAt = nowMs()
            present(LoadPresentation.SKELETON)
        }
    }
    ready.first { it }
    threshold.cancelAndJoin()
    shownAt?.let { start ->
        delay((LyricTokens.Skeleton.minimumMs - (nowMs() - start)).coerceAtLeast(0))
        present(LoadPresentation.FADING)
        delay(LyricTokens.Skeleton.fadeMs.toLong())
    }
    present(LoadPresentation.CONTENT)
}
