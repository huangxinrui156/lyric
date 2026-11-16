package com.lyric.audio

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NoisePersistenceTest {
    @Test fun choiceAndVolumeSurvivePlayerRecreationWithoutStartingAudio() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var player: NoisePlayer? = null
        try {
            context.getSharedPreferences("focus_noise", 0).edit().clear().commit()
            player = NoisePlayer(context, scope) {}
            player.select(NoiseSound.PAGES); player.volume(.73f)
            withTimeout(5000) {
                while (context.getSharedPreferences("focus_noise", 0).getString("sound", "") != "PAGES" ||
                    context.getSharedPreferences("focus_noise", 0).getFloat("volume", 0f) != .73f) delay(10)
            }
            assertFalse(player.settings.value.audible)
            player.close()
            player = NoisePlayer(context, scope) {}
            val restored = withTimeout(5000) { player.settings.first { it.sound == NoiseSound.PAGES } }
            assertEquals(.73f, restored.volume); assertFalse(restored.running); assertFalse(restored.audible)
        } finally { player?.close(); scope.cancel() }
    }
}
