package com.lyric.reader

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.core.Locator
import com.lyric.data.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KillCheckTest {
    @Test fun comparesDurableProgressWithSynchronousRecordAfterRestart() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(c, LibraryDatabase::class.java).build()
        try {
            val repo = LibraryRepository(c, db, SettingsStore(c, PreferenceDataStoreFactory.create(scope = scope) { File(c.filesDir, "k.preferences_pb") }))
            repo.dao.insert(ShelfBook("b", "file:///x", "x.txt", "书", "人", "txt", totalChars = 100, chapterCount = 1, encoding = "UTF-8"))
            repo.dao.saveProgress(ProgressRow("b", 0, 40, 2))
            KillCheck.setEnabled(c, true)
            KillCheck.record(c, "b", "书", Locator(0, 40, 2))
            // Same process: never judged (not a restart).
            KillCheck.verifyAfterRestart(c, repo)
            assertTrue(KillCheck.results(c).isEmpty())
            // Simulate a new process by rewriting the recorded pid.
            val p = c.getSharedPreferences("kill_check", 0)
            p.edit().putString("pending", JSONObject(p.getString("pending", null)!!).put("pid", -1).toString()).commit()
            KillCheck.verifyAfterRestart(c, repo)
            assertTrue(KillCheck.results(c).single().passed)
            KillCheck.record(c, "b", "书", Locator(0, 60, 3))
            p.edit().putString("pending", JSONObject(p.getString("pending", null)!!).put("pid", -1).toString()).commit()
            KillCheck.verifyAfterRestart(c, repo)
            val bad = KillCheck.results(c).last()
            assertFalse(bad.passed); assertEquals(20, bad.charDelta)
        } finally { db.close(); scope.cancel() }
    }
}
