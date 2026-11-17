package com.lyric.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lyric.ui.theme.LyricTokens
import kotlinx.coroutines.delay

@Composable
fun TimedSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    val data = hostState.currentSnackbarData
    // Timeout lives in composition so Compose UI tests can drive it with mainClock.
    LaunchedEffect(data) {
        if (data == null) return@LaunchedEffect
        delay(LyricTokens.Motion.ephemeralSnackbarMs)
        data.dismiss()
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        if (data != null) {
            Snackbar(
                snackbarData = data,
                modifier = Modifier.clickable(onClickLabel = "关闭") { data.dismiss() },
            )
        }
    }
}

/** Show [text] until the host's 4000ms timer elapses, or until the snackbar is tapped. */
suspend fun SnackbarHostState.showTimedSnackbar(text: String) {
    showSnackbar(message = text, duration = SnackbarDuration.Indefinite)
}
