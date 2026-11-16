package com.lyric.data

import android.content.Intent
import android.net.Uri
import android.os.Build

/** URIs carried by a file-manager or WeChat "open with" / share intent. */
fun Intent.bookUris(): List<Uri> {
    val uris = mutableListOf<Uri>()
    data?.let(uris::add)
    clipData?.let { clip ->
        for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(uris::add)
    }
    when (action) {
        Intent.ACTION_SEND -> streamUri()?.let(uris::add)
        Intent.ACTION_SEND_MULTIPLE -> streamUriList().forEach(uris::add)
    }
    return uris.distinct()
}

fun Intent.isBookOpenIntent(): Boolean =
    when (action) {
        Intent.ACTION_VIEW, Intent.ACTION_EDIT, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE ->
            bookUris().isNotEmpty()
        else -> false
    }

private fun Intent.streamUri(): Uri? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)

private fun Intent.streamUriList(): List<Uri> =
    (
        if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") getParcelableArrayListExtra(Intent.EXTRA_STREAM)
    ).orEmpty()
