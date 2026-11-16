package com.lyric.reader

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lyric.data.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelfTestSmokeTest {
    @Test fun runsAllMetricsAndProducesCopyableReport() {
        val c = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val db = Room.inMemoryDatabaseBuilder(c, LibraryDatabase::class.java).build()
        try {
            val repo = LibraryRepository(c, db, SettingsStore(c, PreferenceDataStoreFactory.create(scope = scope) { File(c.filesDir, "s.preferences_pb") }))
            val logs = mutableListOf<String>()
            // Main is Robolectric's paused looper; drive the coroutine on a background thread and idle main.
            val job = scope.async { SelfTest.run(c, repo, lowEnd = false) { logs += it } }
            val deadline = System.currentTimeMillis() + 240_000
            while (!job.isCompleted && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); Thread.sleep(2)
            }
            val metrics = runBlocking { job.await() }
            val report = SelfTest.report(c, metrics, emptyList())
            println(report)
            assertTrue(metrics.size >= 6)
            assertTrue(report.contains("10MB TXT 冷打开"))
            assertTrue(report.contains("翻页单帧绘制 P95"))
        } finally { db.close(); scope.cancel() }
    }
}
