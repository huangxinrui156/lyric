@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*
import com.lyric.core.ReaderSettings
import com.lyric.core.ReaderTheme
import com.lyric.reader.PageCrop
import kotlin.math.roundToInt

internal data class PageArtwork(val bitmap: Bitmap, val crop: PageCrop)

/** Keep the safe inset outside scrolling and zooming so every viewport edge stays clear. */
@Composable
internal fun ComicArtworkViewport(art: List<PageArtwork>, trimmed: Boolean, fillWidth: Boolean,
                                  scale: Float, offset: Offset) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(LyricTokens.Space.xs).clipToBounds()) {
        val zoom = Modifier.graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        }
        if (fillWidth) {
            val ratio = remember(art, trimmed) { artworkRatio(art, trimmed) }
            val viewportHeight = maxHeight
            Box(Modifier.fillMaxSize().then(zoom).verticalScroll(rememberScrollState(), enabled = scale <= 1.01f),
                contentAlignment = Alignment.Center) {
                ComicArtwork(art, trimmed, Modifier.fillMaxWidth().heightIn(min = viewportHeight)
                    .aspectRatio(ratio.coerceAtLeast(.05f), matchHeightConstraintsFirst = false))
            }
        } else ComicArtwork(art, trimmed, Modifier.fillMaxSize().then(zoom))
    }
}

/** Animation reads stay in drawing. Two pages share one fitted height and meet at a single seam. */
@Composable
internal fun ComicArtwork(pages: List<PageArtwork>, trim: Boolean, modifier: Modifier) {
    val fraction = animateFloatAsState(if (trim) 1f else 0f, LyricTokens.Motion.short(), label = "trim")
    val images = remember(pages.map { it.bitmap }) { pages.map { it.bitmap.asImageBitmap() } }
    Canvas(modifier) {
        val f = fraction.value
        val crops = pages.map { p ->
            val c = p.crop
            val x = (c.left * f * p.bitmap.width).roundToInt()
            val y = (c.top * f * p.bitmap.height).roundToInt()
            val w = ((1f + (c.right - c.left - 1f) * f) * p.bitmap.width).roundToInt().coerceAtLeast(1)
            val h = ((1f + (c.bottom - c.top - 1f) * f) * p.bitmap.height).roundToInt().coerceAtLeast(1)
            IntOffset(x, y) to IntSize(w.coerceAtMost(p.bitmap.width - x), h.coerceAtMost(p.bitmap.height - y))
        }
        val ratios = crops.map { it.second.width.toFloat() / it.second.height }
        val height = minOf(size.height, size.width / ratios.sum())
        val width = height * ratios.sum()
        var x = (size.width - width) / 2
        val y = (size.height - height) / 2
        var seam = 0f
        crops.forEachIndexed { i, (src, extent) ->
            val next = x + height * ratios[i]
            drawImage(images[i], srcOffset = src, srcSize = extent,
                dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
                dstSize = IntSize((next.roundToInt() - x.roundToInt()).coerceAtLeast(1), height.roundToInt().coerceAtLeast(1)))
            x = next; if (i == 0) seam = next
        }
        if (pages.size == 2) {
            val half = LyricTokens.Space.md.toPx()
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, LyricTokens.Pending.overlayBlack.copy(alpha = .08f)), seam - half, seam),
                Offset(seam - half, y), Size(half, height))
            drawRect(Brush.horizontalGradient(listOf(LyricTokens.Pending.overlayBlack.copy(alpha = .08f), Color.Transparent), seam, seam + half),
                Offset(seam, y), Size(half, height))
        }
    }
}

@Composable
internal fun ComicLayoutSheet(prefs: ReaderSettings, vertical: Boolean, largeScreen: Boolean, dismiss: () -> Unit, update: (ReaderSettings) -> Unit) {
    val night = prefs.theme in listOf(ReaderTheme.NIGHT, ReaderTheme.BLACK, ReaderTheme.GREEN)
    val bg = LyricTokens.colors(night).card
    val ink = if (night) LyricTokens.Night.ink else Ink
    val meta = if (night) LyricTokens.Night.secondary else Stone
    val divider = if (night) LyricTokens.Night.stroke else Linen
    val selected = if (night) LyricTokens.Night.moss else Moss
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = bg, shape = LyricTokens.Radius.cardShape) {
        Column(Modifier.fillMaxWidth().padding(horizontal = LyricTokens.Space.xxl).padding(bottom = LyricTokens.Space.xl)) {
            Text("版面 · 仅对本书生效", color = meta, style = LyricTokens.Type.body)
            @Composable fun toggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
                Row(Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.md), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, color = ink, style = LyricTokens.Type.subtitle)
                        Text(subtitle, color = meta, style = LyricTokens.Type.auxiliary)
                    }
                    Switch(checked, change, enabled = enabled, colors = SwitchDefaults.colors(
                        checkedTrackColor = selected, uncheckedTrackColor = divider,
                        checkedThumbColor = Paper, uncheckedThumbColor = Paper,
                        checkedBorderColor = Color.Transparent, uncheckedBorderColor = Color.Transparent))
                }
            }
            toggle("双页", if (largeScreen) "横屏时自动开启" else "横屏时可并排显示两页",
                com.lyric.reader.Spreads.enabled(prefs.comicSpread, largeScreen), !vertical) { update(prefs.copy(comicSpread = it)) }
            HorizontalDivider(color = divider)
            toggle("页码偏移一页", "跨页大图对不齐时打开", prefs.comicOffset, !vertical) { update(prefs.copy(comicOffset = it)) }
            HorizontalDivider(color = divider)
            toggle("裁白边", "去掉扫描页四周的白边或黑边", prefs.comicTrim, !vertical) { update(prefs.copy(comicTrim = it)) }
        }
    }
}
