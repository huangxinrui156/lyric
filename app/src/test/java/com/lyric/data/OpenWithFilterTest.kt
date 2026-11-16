package com.lyric.data

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenWithFilterTest {
    private val pm get() = ApplicationProvider.getApplicationContext<Context>().packageManager

    @Test
    fun lyricAppearsForTxtEpubPdfCbzFromFileManagersAndWeChat() {
        val cases = listOf(
            "text/plain" to "book.txt",
            "application/epub+zip" to "book.epub",
            "application/pdf" to "book.pdf",
            "application/vnd.comicbook+zip" to "book.cbz",
            "application/x-cbz" to "book.cbz",
            "application/zip" to "book.cbz",
            "application/octet-stream" to "book.txt",
        )
        for ((mime, name) in cases) {
            val uri = Uri.parse("content://com.tencent.mm.external.fileprovider/share/$name")
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addCategory(Intent.CATEGORY_DEFAULT)
            assertTrue("$mime VIEW should resolve Lyric", resolves(view))
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            assertTrue("$mime SEND should resolve Lyric", resolves(send))
        }
    }

    @Test
    fun lyricAppearsWhenProviderOmitsMimeAndUsesExtension() {
        for (name in listOf("book.txt", "book.epub", "book.pdf", "book.cbz")) {
            val uri = Uri.parse("file:///storage/emulated/0/Download/$name")
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*").addCategory(Intent.CATEGORY_DEFAULT)
            assertTrue("$name extension VIEW should resolve Lyric", resolves(view))
        }
    }

    private fun resolves(intent: Intent): Boolean =
        pm.queryIntentActivities(intent, 0).any { it.activityInfo.name.endsWith("MainActivity") }
}
