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
    private fun intent(prepare: Boolean = false, fixture: String = "txt", scroll: Boolean = false) = Intent().apply {
        component = ComponentName(pkg, "com.lyric.BenchmarkActivity")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("prepare", prepare)
        putExtra("fixture", fixture)
        putExtra("scroll", scroll)
    }
    private fun MacrobenchmarkScope.prepare(fixture: String = "txt", scroll: Boolean = false) {
        startActivityAndWait(intent(true, fixture, scroll))
        val outcome = By.text(java.util.regex.Pattern.compile("PREPARED|FAILED:.*", java.util.regex.Pattern.DOTALL))
        check(device.wait(Until.findObject(outcome), 120_000) != null) { "Fixture preparation timed out ($fixture)" }
        device.findObject(By.textStartsWith("FAILED:"))?.let { error(it.text) }
        pressHome()
    }
    private fun MacrobenchmarkScope.awaitReader(fixture: String = "txt") {
        val prefix = if (fixture == "cbz") "Lyric漫画:" else "Lyric正文:"
        check(device.wait(Until.hasObject(By.descStartsWith(prefix)), 30_000)) { "No content frame ($fixture)" }
        device.waitForIdle() // Let the normal loading hold/fade finish and reportFullyDrawn fire.
    }
    private fun memoryMetric() = MemoryUsageMetric(
        MemoryUsageMetric.Mode.Max,
        listOf(MemoryUsageMetric.SubMetric.HeapSize, MemoryUsageMetric.SubMetric.RssAnon,
            MemoryUsageMetric.SubMetric.RssFile, MemoryUsageMetric.SubMetric.RssShmem,
            MemoryUsageMetric.SubMetric.Gpu),
    )

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

    @Test fun tenMegabyteTxtColdOpen() = coldOpen("txt")
    @Test fun epub1248ColdOpen() = coldOpen("epub")
    @Test fun cbz500ColdOpen() = coldOpen("cbz")

    private fun coldOpen(fixture: String) = rule.measureRepeated(
        packageName = pkg,
        metrics = listOf(StartupTimingMetric(), TraceSectionMetric("Lyric.ColdOpen"), memoryMetric()),
        iterations = 5, startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Full(), setupBlock = { prepare(fixture) },
    ) {
        startActivityAndWait(intent(fixture = fixture))
        awaitReader(fixture) // Actual visible content; reportFullyDrawn also includes the loading fade.
    }

    @Test fun textScrollFrameTiming() = scrollFrames("txt")
    @Test fun comicScrollFrameTiming() = scrollFrames("cbz")

    private fun scrollFrames(fixture: String) = rule.measureRepeated(
        packageName = pkg, metrics = listOf(FrameTimingMetric(), memoryMetric()),
        iterations = 5,
        compilationMode = CompilationMode.Full(),
        setupBlock = {
            prepare(fixture, scroll = true)
            // This restart also keeps the scroll workload free of preparation allocations/caches.
            killProcess()
            startActivityAndWait(intent(fixture = fixture, scroll = true))
            awaitReader(fixture)
        },
    ) {
        repeat(30) {
            val x = device.displayWidth / 2
            val y = device.displayHeight
            if (fixture == "cbz") {
                // RTL pager: swipe right to advance. No center tap/chrome dependency.
                device.swipe(device.displayWidth / 5, y / 2, device.displayWidth * 4 / 5, y / 2, 30)
            } else {
                device.swipe(x, y * 4 / 5, x, y / 5, 30)
            }
            device.waitForIdle()
        }
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
