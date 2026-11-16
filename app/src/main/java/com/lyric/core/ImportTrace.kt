package com.lyric.core

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Keep the original exception and stage intact across parser/repository coroutine boundaries. */
class ImportFailure(val stage: String, cause: Throwable) : Exception(
    "[$stage] ${cause.javaClass.simpleName}: ${cause.message ?: "无详细信息"}", cause,
)

/** Messages already meant for the reader (DRM, legacy .doc): shown verbatim, no stage prefix. */
open class FriendlyImportException(message: String) : IllegalStateException(message)

/** Keep diagnostic stages in the log; show actionable reasons in the import summary. */
internal fun Throwable.importReason(): String = when (this) {
    is ImportFailure -> cause?.importReason() ?: "导入失败，请重新选择文件"
    is java.util.concurrent.TimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> "文件太大或结构异常，超时已停止"
    is CancellationException -> "导入已取消"
    is SecurityException, is java.io.FileNotFoundException -> "无法读取文件，请重新选择"
    is java.util.zip.ZipException -> "压缩包已损坏或格式不受支持"
    is android.database.sqlite.SQLiteException -> "保存书籍失败，请重试"
    else -> localizedMessage?.takeIf { it.isNotBlank() } ?: "导入失败，请检查文件后重试"
}

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
