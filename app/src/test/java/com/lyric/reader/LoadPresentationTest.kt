package com.lyric.reader

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadPresentationTest {
    @Test fun readyBeforeThresholdSkipsSkeletonAndFade() = runTest {
        for (time in listOf(0L, 1L, 299L)) {
            val ready = MutableStateFlow(false)
            val seen = mutableListOf<LoadPresentation>()
            val job = launch { presentFirstContent(ready, { testScheduler.currentTime }, seen::add) }
            runCurrent(); advanceTimeBy(time); ready.value = true; runCurrent()
            assertEquals(listOf(LoadPresentation.WAITING, LoadPresentation.CONTENT), seen)
            job.join()
        }
    }

    @Test fun slowReadyKeepsSkeletonFor300msThenFadesFor150ms() = runTest {
        val ready = MutableStateFlow(false)
        val seen = mutableListOf<Pair<Long, LoadPresentation>>()
        launch { presentFirstContent(ready, { testScheduler.currentTime }) { seen += testScheduler.currentTime to it } }
        advanceTimeBy(300); runCurrent()
        assertEquals(300L to LoadPresentation.SKELETON, seen.last())
        advanceTimeBy(1); ready.value = true; runCurrent()
        advanceTimeBy(298); runCurrent()
        assertEquals(LoadPresentation.SKELETON, seen.last().second)
        advanceTimeBy(1); runCurrent()
        assertEquals(600L to LoadPresentation.FADING, seen.last())
        assertEquals(150, seen.last().second.fadeMs)
        advanceTimeBy(149); runCurrent()
        assertEquals(LoadPresentation.FADING, seen.last().second)
        advanceTimeBy(1); runCurrent()
        assertEquals(750L to LoadPresentation.CONTENT, seen.last())
    }

    @Test fun readyAtThresholdBeforeTimerRunsSkipsSkeleton() = runTest {
        val ready = MutableStateFlow(false)
        val seen = mutableListOf<LoadPresentation>()
        launch { presentFirstContent(ready, { testScheduler.currentTime }, seen::add) }
        runCurrent(); advanceTimeBy(300)
        ready.value = true; runCurrent()
        assertEquals(listOf(LoadPresentation.WAITING, LoadPresentation.CONTENT), seen)
    }

    @Test fun lateReadyDoesNotAddAnother300ms() = runTest {
        val ready = MutableStateFlow(false)
        val seen = mutableListOf<Pair<Long, LoadPresentation>>()
        launch { presentFirstContent(ready, { testScheduler.currentTime }) { seen += testScheduler.currentTime to it } }
        advanceTimeBy(1200); ready.value = true; runCurrent()
        assertEquals(1200L to LoadPresentation.FADING, seen.last())
        advanceTimeBy(150); runCurrent()
        assertEquals(1350L to LoadPresentation.CONTENT, seen.last())
    }

    @Test fun cancellationCannotShowAnOldSkeleton() = runTest {
        val seen = mutableListOf<LoadPresentation>()
        val job = launch { presentFirstContent(MutableStateFlow(false), { testScheduler.currentTime }, seen::add) }
        runCurrent(); advanceTimeBy(100); job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(listOf(LoadPresentation.WAITING), seen)
    }
}
