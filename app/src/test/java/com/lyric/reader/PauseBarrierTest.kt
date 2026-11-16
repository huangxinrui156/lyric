package com.lyric.reader

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PauseBarrierTest {
    @Test fun slowWritesTimeOutButRemainOrderedAndEventuallyDurable() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val unblock = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val persisted = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val writer = ProgressWriter<Int>(scope) {
            started.complete(Unit)
            unblock.await()
            persisted += it
        }
        val sessions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val sessionDone = CompletableDeferred<Unit>()
        scope.launch { for (write in sessions) write() }
        try {
            writer.submit(1)
            started.await()
            val final = writer.submit(2)
            sessions.send { unblock.await(); persisted += 3 }
            sessions.send { sessionDone.complete(Unit); Unit }
            val before = System.nanoTime()
            assertFalse(awaitPauseWrites(50) { writer.await(final); sessionDone.await() })
            assertTrue((System.nanoTime() - before) / 1_000_000 < 1500)
            assertTrue(persisted.isEmpty())
            unblock.complete(Unit)
            withTimeout(3000) { writer.await(final); sessionDone.await() }
            assertTrue(persisted.indexOf(1) < persisted.indexOf(2))
            assertTrue(3 in persisted)
            assertTrue(awaitPauseWrites { writer.await(final); sessionDone.await() })
        } finally { writer.close(); sessions.close(); scope.cancel() }
    }

    @Test fun sessionDrainAlsoSharesTheDeadlineAfterProgressCompletes() {
        val session = CompletableDeferred<Unit>()
        assertFalse(awaitPauseWrites(30) { session.await() })
        assertFalse(session.isCancelled)
        session.complete(Unit)
        assertTrue(awaitPauseWrites { session.await() })
    }
}
