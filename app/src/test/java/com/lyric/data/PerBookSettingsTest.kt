package com.lyric.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.ContentType
import com.lyric.core.PageMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PerBookSettingsTest {
    @Test
    fun directionPageModeSpreadAndTrimSurviveProcessKillAndNewBooksUseGlobalDefaults() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, "per-book-${System.nanoTime()}.preferences_pb")
        val firstJob = SupervisorJob()
        try {
            val first = SettingsStore(
                context,
                PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO)) { file },
            )
            val before = first.settings("read", ContentType.COMIC).first()
            assertEquals(PageMode.SLIDE, before.pageMode)
            assertEquals("rtl", before.comicDirection)
            assertNull(before.comicSpread)
            assertFalse(before.comicTrim)

            first.update(
                SettingsScope.BOOK,
                "read",
                before,
                before.copy(
                    pageMode = PageMode.SCROLL,
                    comicDirection = "ltr",
                    comicSpread = true,
                    comicTrim = true,
                ),
                ContentType.COMIC,
            )
            val global = first.global.first()
            first.update(
                SettingsScope.GLOBAL,
                "",
                global,
                global.copy(
                    pageMode = PageMode.CURL,
                    comicDirection = "vertical",
                    comicSpread = false,
                    comicTrim = false,
                ),
            )

            val saved = first.settings("read", ContentType.COMIC).first()
            assertEquals(PageMode.SCROLL, saved.pageMode)
            assertEquals("ltr", saved.comicDirection)
            assertEquals(true, saved.comicSpread)
            assertTrue(saved.comicTrim)

            val fresh = first.settings("new", ContentType.COMIC).first()
            assertEquals(PageMode.CURL, fresh.pageMode)
            assertEquals("vertical", fresh.comicDirection)
            assertEquals(false, fresh.comicSpread)
            assertFalse(fresh.comicTrim)
        } finally {
            firstJob.cancelAndJoin()
        }

        val secondJob = SupervisorJob()
        try {
            val reopened = SettingsStore(
                context,
                PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO)) { file },
            )
            val restored = reopened.settings("read", ContentType.COMIC).first()
            assertEquals(PageMode.SCROLL, restored.pageMode)
            assertEquals("ltr", restored.comicDirection)
            assertEquals(true, restored.comicSpread)
            assertTrue(restored.comicTrim)

            val anotherNew = reopened.settings("another", ContentType.TEXT).first()
            assertEquals(PageMode.CURL, anotherNew.pageMode)
            assertEquals("vertical", anotherNew.comicDirection)
            assertEquals(false, anotherNew.comicSpread)
            assertFalse(anotherNew.comicTrim)
        } finally {
            secondJob.cancelAndJoin()
            file.delete()
        }
    }
}
