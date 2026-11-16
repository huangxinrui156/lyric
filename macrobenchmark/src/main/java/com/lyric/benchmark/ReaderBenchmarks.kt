package com.lyric.benchmark

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.*
import org.junit.*
import org.junit.runner.RunWith

@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()
    private val pkg = "com.lyric.reader.benchmark"
    private fun intent(prepare: Boolean = false) = Intent().apply {
        component = ComponentName(pkg, "com.lyric.BenchmarkActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("prepare", prepare)
    }
    private fun MacrobenchmarkScope.prepare() {
        startActivityAndWait(intent(true))
        check(device.wait(Until.hasObject(By.text("PREPARED")), 60_000)) { "Fixture preparation failed" }
        pressHome()
    }
    private fun MacrobenchmarkScope.awaitReader() {
        check(device.wait(Until.hasObject(By.descStartsWith("Lyric正文:")), 30_000)) { "No content frame" }
    }

    @Test fun pageTurnFrameTiming() = rule.measureRepeated(
        packageName = pkg, metrics = listOf(FrameTimingMetric()), iterations = 5,
        compilationMode = CompilationMode.Full(),
        setupBlock = { prepare(); startActivityAndWait(intent()); awaitReader() },
    ) {
        // Enough animated page turns to produce a useful P95 distribution; normal reader path.
        repeat(30) {
            device.click(device.displayWidth * 9 / 10, device.displayHeight / 2)
            Thread.sleep(350)
        }
    }

    @Test fun tenMegabyteTxtColdOpen() = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(StartupTimingMetric(), TraceSectionMetric("Lyric.ColdOpen")),
        iterations = 5, startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Full(), setupBlock = { prepare() },
    ) {
        startActivityAndWait(intent())
        awaitReader() // First actual text draw, not the skeleton or Activity's initial frame.
    }

    @Test fun searchFirstResult() = rule.measureRepeated(
        packageName = pkg, metrics = listOf(TraceSectionMetric("Lyric.SearchFirstResult")),
        iterations = 5, compilationMode = CompilationMode.Full(),
        setupBlock = {
            prepare(); startActivityAndWait(intent()); awaitReader()
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            device.wait(Until.findObject(By.desc("全文搜索")), 5_000).click()
            check(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))
        },
    ) {
        device.findObject(By.clazz("android.widget.EditText")).text = "LYRIC_NEEDLE"
        check(device.wait(Until.hasObject(By.desc("搜索结果")), 10_000)) { "No search result" }
        device.waitForIdle()
    }
}
