package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import android.os.SystemClock
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.lyric.reader.LoadPresentation
import com.lyric.reader.presentFirstContent
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
internal fun loadPresentation(key: Any, ready: Boolean): LoadPresentation {
    val signal = remember(key) { MutableStateFlow(ready) }
    SideEffect { signal.value = ready }
    var stage by remember(key) { mutableStateOf(LoadPresentation.WAITING) }
    LaunchedEffect(key) { presentFirstContent(signal, SystemClock::uptimeMillis) { stage = it } }
    return stage
}

/** Alpha is read during drawing, so breathing does not recompose the reader/shelf. */
@Composable
internal fun Modifier.loadingBreathe(): Modifier {
    val alpha = rememberInfiniteTransition(label = "loading").animateFloat(LyricTokens.Skeleton.breatheMinAlpha, LyricTokens.Skeleton.breatheMaxAlpha,
        infiniteRepeatable(LyricTokens.Motion.duration(LyricTokens.Skeleton.breatheLegMs), RepeatMode.Reverse), label = "breathing")
    return graphicsLayer { this.alpha = alpha.value }
}

@Composable
internal fun loadContentAlpha(stage: LoadPresentation): State<Float> = animateFloatAsState(
    if (stage.content) 1f else 0f,
    animationSpec = LyricTokens.Motion.duration(if (stage == LoadPresentation.FADING) LyricTokens.Skeleton.fadeMs else LyricTokens.Motion.instantMs), label = "first-content")

@Composable
internal fun ComicPlaceholder(ratio: Float, night: Boolean, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitHeight = ratio < maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        Box(Modifier.aspectRatio(ratio.coerceAtLeast(.01f), matchHeightConstraintsFirst = fitHeight)
            .loadingBreathe().background(LyricTokens.colors(night).skeleton, androidx.compose.foundation.shape.RoundedCornerShape(LyricTokens.Radius.skeleton)))
    }
}
