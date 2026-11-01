package com.lyric.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
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
@Config(application = Application::class, sdk = [28], shadows = [PageMemorySafetyTest.PdfBackend::class, PageMemorySafetyTest.PdfPage::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageMemorySafetyTest {
    // Robolectric has no PDF native renderer. Stub only that backend; page allocation,
    // sizing, shared accounting, cache lifecycle and CBZ decode all use production code.
    @Implements(PdfRenderer::class)
    class PdfBackend {
        @Implementation fun __constructor__(fd: ParcelFileDescriptor) = Unit
        @Implementation fun getPageCount() = 3
        @Implementation fun openPage(index: Int): PdfRenderer.Page = ReflectionHelpers.newInstance(PdfRenderer.Page::class.java)
        @Implementation fun close() = Unit
    }
    @Implements(PdfRenderer.Page::class)
    class PdfPage {
        @Implementation fun getWidth() = 400
        @Implementation fun getHeight() = 600
        @Implementation fun render(bitmap: Bitmap, clip: Rect?, matrix: Matrix?, mode: Int) = Unit
        @Implementation fun close() = Unit
    }

    @Test fun sharedBudgetCountsHeldComicAndPdfPagesAfterEvictionAndClose() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val cbz = File.createTempFile("memory-", ".cbz", app.cacheDir)
        val pdf = File.createTempFile("memory-", ".pdf", app.cacheDir)
        val original = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        original.recycle()
        ZipOutputStream(cbz.outputStream()).use { zip -> repeat(10) {
            zip.putNextEntry(ZipEntry("$it.png")); zip.write(bytes); zip.closeEntry()
        } }
        val budget = PageBitmapBudget(4L * 1024 * 1024)
        val held = mutableListOf<Bitmap>() // Models visible pages, prefetch and an outgoing pager.
        try {
            ComicArchive(cbz, null, budget).use { source ->
                held += source.page("0.png", 400, 600)
                assertEquals(400, held.first().width) // Normal-size rendering unchanged.
                assertSame(held.first(), source.page("0.png", 400, 600))
                repeat(5) { held += source.page("${it+1}.png", 400, 600) }
            }
            val afterClose = held.sumOf { it.allocationByteCount.toLong() }
            assertEquals(afterClose, budget.usedBytes())
            PdfPageSource(pdf, budget).use { source ->
                repeat(3) { held += source.page("pdf:$it", 400, 600) }
            }
            assertTrue(held.last().width < 400)
            assertEquals(held.sumOf { it.allocationByteCount.toLong() }, budget.usedBytes())
            assertTrue(budget.usedBytes() <= budget.limit)
            assertTrue(held.none { it.isRecycled })
        } finally { held.forEach { it.recycle() }; cbz.delete(); pdf.delete() }
    }

    @Test fun concurrentPrefetchCannotOversubscribeBudget() {
        val budget = PageBitmapBudget(1024 * 1024)
        val held = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())
        val threads = (0..7).map { index -> Thread {
            held += budget.decode("$index", 512 * 512 * 4L) { allowed ->
                val (w, h) = pdfPageSize(512, 512, 512, 512, allowed)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }
        }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(8, held.size)
        assertTrue(held.sumOf { it.allocationByteCount.toLong() } <= budget.limit)
        held.forEach { it.recycle() }
    }

    @Test fun pressureCollectsUnownedPagesBeforeReducingResolution() {
        val budget = PageBitmapBudget(1_200_000)
        fun decode(key: String) = budget.decode(key, 400 * 600 * 4L) { allowed ->
            val (w, h) = pdfPageSize(400, 600, 400, 600, allowed)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        fun discardPage() { decode("old") }
        discardPage() // Over the strong-cache allowance, with no pager owner.
        val next = decode("next")
        assertEquals(400, next.width)
        assertEquals(600, next.height)
        next.recycle()
    }

    @Test fun snapshotOomFallsBackAndSuccessfulCaptureIsUnchanged() {
        assertNull(pageSnapshot<Bitmap> { throw OutOfMemoryError("injected capture failure") })
        assertNull(pageSnapshot(320, 240) { throw OutOfMemoryError("injected drawing failure") })
        val good = pageSnapshot(320, 240) { it.drawColor(android.graphics.Color.WHITE) }
        assertNotNull(good)
        assertEquals(320, good!!.width)
        good.recycle()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            pageSnapshot<Bitmap> { throw kotlinx.coroutines.CancellationException("cancel") }
        }
    }
}
