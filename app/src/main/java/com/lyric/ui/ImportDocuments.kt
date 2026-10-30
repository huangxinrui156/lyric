package com.lyric.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import com.lyric.core.ImportTrace

data class DocumentSelection(val uris: List<Uri>, val flags: Int)

/** No MIME filter: ColorOS may label EPUBs as ZIP, octet-stream or an OEM-specific type. */
class ImportDocuments(private val tree: Boolean = false) : ActivityResultContract<Unit, DocumentSelection>() {
    override fun createIntent(context: Context, input: Unit) = Intent(
        if (tree) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT,
    ).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (tree) addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        else {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        Log.i(ImportTrace.TAG, "启动 SAF action=$action type=$type (无 MIME 过滤)")
    }

    override fun parseResult(resultCode: Int, intent: Intent?): DocumentSelection {
        val uris = if (resultCode == Activity.RESULT_OK && intent != null) buildList {
            intent.data?.let(::add)
            intent.clipData?.let { clip -> repeat(clip.itemCount) { add(clip.getItemAt(it).uri) } }
        }.distinct() else emptyList()
        Log.i(ImportTrace.TAG, "SAF 返回 code=$resultCode count=${uris.size} flags=${intent?.flags}")
        return DocumentSelection(uris, intent?.flags ?: 0)
    }
}
