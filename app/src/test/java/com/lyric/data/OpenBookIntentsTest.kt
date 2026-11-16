package com.lyric.data

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenBookIntentsTest {
    private val txt = Uri.parse("content://com.tencent.mm.external.fileprovider/share/book.txt")
    private val epub = Uri.parse("content://downloads/my-book.epub")
    private val pdf = Uri.parse("file:///storage/emulated/0/book.pdf")

    @Test
    fun viewIntentUsesData() {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(txt, "text/plain")
        assertTrue(intent.isBookOpenIntent())
        assertEquals(listOf(txt), intent.bookUris())
    }

    @Test
    fun sendIntentUsesStream() {
        val intent = Intent(Intent.ACTION_SEND).setType("application/epub+zip").putExtra(Intent.EXTRA_STREAM, epub)
        assertTrue(intent.isBookOpenIntent())
        assertEquals(listOf(epub), intent.bookUris())
    }

    @Test
    fun sendMultipleAndClipDataAreDeduped() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf")
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(pdf, txt, pdf))
        intent.clipData = android.content.ClipData.newRawUri("files", pdf).apply { addItem(android.content.ClipData.Item(txt)) }
        assertTrue(intent.isBookOpenIntent())
        assertEquals(listOf(pdf, txt), intent.bookUris())
    }

    @Test
    fun launcherIntentIsIgnored() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertFalse(intent.isBookOpenIntent())
        assertTrue(intent.bookUris().isEmpty())
    }
}
