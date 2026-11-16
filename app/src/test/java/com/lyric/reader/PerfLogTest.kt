package com.lyric.reader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PerfLogTest {
    @Test fun diagnosticsAreOffByDefaultAndOnlyExplicitSwitchAllowsWriting() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("perf_log", 0).edit().clear().commit()
        val previous = PerfLog.recordingAllowedOverride
        try {
            // Exercise the debug opt-in path even when this flavor is a release/benchmark build.
            PerfLog.recordingAllowedOverride = true
            PerfLog.initialize(app)
            assertFalse(PerfLog.enabled)
            PerfLog.beginOpen(); PerfLog.source("epub", 123); PerfLog.stage("first_window")
            assertFalse(File(app.filesDir, "performance/lyric-perf.log").exists())
            PerfLog.setEnabled(app, true)
            PerfLog.beginOpen(); PerfLog.source("epub", 123)
            PerfLog.stage("first_window"); PerfLog.stage("first_window")
            val report = PerfLog.export(app).readText()
            assertTrue(report.contains("format=epub file_bytes=123"))
            assertEquals(1, Regex("stage=first_window").findAll(report).count())
            assertTrue(report.contains("java_peak_bytes="))
            PerfLog.setEnabled(app, false)
            val snapshot = PerfLog.export(app).readText()
            PerfLog.beginOpen(); PerfLog.stage("disabled_stage")
            assertEquals(snapshot, PerfLog.export(app).readText())
        } finally {
            PerfLog.recordingAllowedOverride = previous
            PerfLog.setEnabled(app, false)
            app.getSharedPreferences("perf_log", 0).edit().clear().commit()
        }
    }

    @Test fun releaseBuildsCannotEnableRecordingEvenWithAStalePreference() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("perf_log", 0).edit().putBoolean("enabled", true).commit()
        val previous = PerfLog.recordingAllowedOverride
        try {
            PerfLog.recordingAllowedOverride = false
            PerfLog.initialize(app)
            assertFalse(PerfLog.enabled)
            PerfLog.setEnabled(app, true)
            assertFalse(PerfLog.enabled)
            PerfLog.beginOpen(); PerfLog.source("epub", 123); PerfLog.stage("first_window")
            assertFalse(File(app.filesDir, "performance/lyric-perf.log").exists())
        } finally {
            PerfLog.recordingAllowedOverride = previous
            PerfLog.setEnabled(app, false)
            app.getSharedPreferences("perf_log", 0).edit().clear().commit()
        }
    }
}
