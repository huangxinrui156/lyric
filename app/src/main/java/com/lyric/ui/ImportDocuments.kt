package com.lyric.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import com.lyric.core.ImportTrace

data class DocumentSelection(val uris: List<Uri>, val flags: Int)

enum class ImportFilter { ALL, COMIC }

/** Keep an explicit unfiltered escape hatch for providers with OEM-specific MIME types. */
class ImportDocuments(private val tree: Boolean = false) : ActivityResultContract<ImportFilter, DocumentSelection>() {
    override fun createIntent(context: Context, input: ImportFilter) = Intent(
        if (tree) Intent.ACTION_OPEN_DOCUMENT_TREE else Intent.ACTION_OPEN_DOCUMENT,
    ).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (tree) addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        else {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (input == ImportFilter.COMIC) putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.comicbook+zip", "application/x-cbz", "application/zip",
                "application/x-zip", "application/x-zip-compressed", "application/epub+zip",
                "application/x-epub+zip", "image/*", "application/octet-stream",
            ))
        }
        Log.i(ImportTrace.TAG, "启动 SAF action=$action type=$type filter=$input")
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
