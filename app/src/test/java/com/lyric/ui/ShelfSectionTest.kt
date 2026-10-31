package com.lyric.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.*
import com.lyric.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ShelfSectionTest {
    @Test fun comicPageNumberIsGlobalAcrossChaptersAndOneBased() {
        val book = Book("漫画", contentType = ContentType.COMIC, chapters = listOf(
            Chapter(0, "一", content = List(10) { ContentUnit.ImagePage("$it.png", null, null) }),
            Chapter(1, "二", content = List(176) { ContentUnit.ImagePage("$it.png", null, null) }),
        ))
        assertEquals("第 1 页 / 186", comicPageLabel(book, null))
        assertEquals("第 12 页 / 186", comicPageLabel(book, ProgressRow("comic", 1, 0, 1)))
        assertEquals("第 186 页 / 186", comicPageLabel(book, ProgressRow("comic", 1, 0, 999)))
    }

    @Test fun reducedMotionUsesComicMimeFilterWithAnUnfilteredEscapeHatch() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(reducedShelfMotion(context))
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        assertFalse(reducedShelfMotion(context))
        val types = ImportDocuments().createIntent(context, ImportFilter.COMIC).getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList()
        assertTrue(types.containsAll(listOf("image/*", "application/zip", "application/epub+zip", "application/vnd.comicbook+zip")))
        assertFalse(types.contains("text/plain"))
        assertFalse(ImportDocuments().createIntent(context, ImportFilter.ALL).hasExtra(Intent.EXTRA_MIME_TYPES))
    }

    @Test fun transitionHasThreePhasesReversesAndRejectsOverlappingTaps() {
        val state = ShelfTransitionState(ShelfSection.BOOK)
        assertTrue(state.start(ShelfSection.COMIC, false))
        assertFalse(state.start(ShelfSection.BOOK, false))
        assertFalse(state.start(ShelfSection.COMIC, false))
        assertEquals(ShelfSection.COMIC, state.target)
        val start = shelfTransitionFrame(0f, true, false)
        assertEquals(1f, start.outgoing, 0f); assertEquals(0f, start.incoming, 0f)
        val middle = shelfTransitionFrame(375f / 700, true, false)
        assertEquals(0f, middle.outgoing, 0f); assertEquals(0f, middle.incoming, 0f)
        val arrival = shelfTransitionFrame(600f / 700, true, false)
        assertEquals(.5f, arrival.incoming, .001f); assertEquals(24f, arrival.incomingY, .001f)
        val reverse = shelfTransitionFrame(100f / 700, false, false)
        assertEquals(arrival.incoming, reverse.outgoing, .001f)
        assertEquals(arrival.drawing, reverse.drawing, .001f)
        val reduced = shelfTransitionFrame(.5f, true, true)
        assertEquals(.5f, reduced.incoming, 0f); assertEquals(.5f, reduced.outgoing, 0f)
        assertEquals(0f, reduced.incomingY, 0f)
        state.finish()
        assertFalse(state.running)
        assertTrue(state.start(ShelfSection.BOOK, true))
    }
}
