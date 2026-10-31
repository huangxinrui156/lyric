package com.lyric.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageSourceCacheTest {
    @Test fun heightChangesInvalidateCacheAndClosingDoesNotRecycleDisplayedPages() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File.createTempFile("pages-", ".cbz", app.cacheDir)
        val bitmap = Bitmap.createBitmap(800, 2400, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("page.png")); it.write(bytes); it.closeEntry()
        }
        try {
            val source = ComicArchive(file)
            val tall = source.page("page.png", 800, 2400)
            assertSame(tall, source.page("page.png", 800, 2400))
            val short = source.page("page.png", 800, 600)
            assertNotSame(tall, short)
            assertTrue(short.height < tall.height)
            source.close()
            source.close()
            assertFalse(tall.isRecycled)
            assertFalse(short.isRecycled)
        } finally { file.delete() }
    }
}
