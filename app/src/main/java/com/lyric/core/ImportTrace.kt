package com.lyric.core

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Keep the original exception and stage intact across parser/repository coroutine boundaries. */
class ImportFailure(val stage: String, cause: Throwable) : Exception(
    "[$stage] ${cause.javaClass.simpleName}: ${cause.message ?: "无详细信息"}", cause,
)

/** Messages already meant for the reader (DRM, legacy .doc): shown verbatim, no stage prefix. */
open class FriendlyImportException(message: String) : IllegalStateException(message)

object ImportTrace {
    const val TAG = "LyricImport"
    inline fun <T> stage(name: String, block: () -> T): T {
        Log.i(TAG, "开始 $name")
        try {
            return block().also { Log.i(TAG, "完成 $name") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "失败 $name: ${e.javaClass.name}: ${e.message}", e)
            throw if (e is ImportFailure || e is FriendlyImportException) e else ImportFailure(name, e)
        }
    }
}
