package com.lyric.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.ReaderActivity
import com.lyric.core.*
import com.lyric.data.SettingsScope
import com.lyric.reader.*
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import com.lyric.reader.VolumeNavigation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ComicBg = Color(0xFF111111)
private val BarBg = Color(0xE0111111)
private val ComicInk = Color(0xFFD0CABE)
private val ComicMeta = Color(0xFF8E897F)
private val ComicAccent = Color(0xFFE08A6E)
private val Track = Color(0xFF3A3A3E)
private val Skeleton = Color(0xFF1A1A1D)

data class ComicPageRef(val chapter: Int, val page: Int, val unit: ContentUnit.ImagePage)

/** Flattened page list over all chapters; global index <-> Locator(chapter, 0, page). */
class ComicPages(book: Book) {
    val pages: List<ComicPageRef> = book.chapters.flatMap { c ->
        c.units.mapIndexedNotNull { i, u -> (u as? ContentUnit.ImagePage)?.let { ComicPageRef(c.index, i, it) } }
    }
    fun indexOf(l: Locator): Int = pages.indexOfFirst { it.chapter == l.chapterIndex && it.page == l.page }.coerceAtLeast(0)
    fun locator(i: Int): Locator = pages[i.coerceIn(0, pages.lastIndex)].let { Locator(it.chapter, 0, it.page) }
}

/** Which tap zone means "next": right→left books turn forward by tapping the left side. */
object ComicTaps {
    /** -1 previous, +1 next, 0 toggle chrome. */
    fun step(x: Float, width: Float, rtl: Boolean): Int = when {
        x < width / 3 -> if (rtl) 1 else -1
        x > width * 2 / 3 -> if (rtl) -1 else 1
        else -> 0
    }
}

@Composable
fun ComicReaderScreen(vm: ReaderViewModel, book: Book, back: () -> Unit) {
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val locator by vm.locator.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isPdf = book.chapters.firstOrNull()?.units?.firstOrNull().let { it is com.lyric.core.ContentUnit.ImagePage && it.resource.startsWith(com.lyric.reader.PDF_PAGE_PREFIX) }
    // PDFs read left to right (or as a vertical strip); the manga default does not apply.
    val dir = (prefs.comicDirection.takeIf { it in ComicDirections.all } ?: "rtl").let { if (isPdf && it == "rtl") "ltr" else it }
    val rtl = dir == "rtl"
    val vertical = dir == "vertical"
    val pages = remember(book) { ComicPages(book) }
    val sourceState by produceState<Result<com.lyric.reader.PageSource>?>(null, vm.id, isPdf) {
        var source: com.lyric.reader.PageSource? = null
        try {
            value = withContext(Dispatchers.IO) {
                runCatching<com.lyric.reader.PageSource> {
                    if (isPdf) com.lyric.reader.PdfPageSource(vm.repository.comicFile(vm.id))
                    else ComicArchive(vm.repository.comicFile(vm.id), pages.pages.firstOrNull()?.unit?.resource)
                }.also { source = it.getOrNull() }
            }
            kotlinx.coroutines.awaitCancellation()
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { source?.close() }
        }
    }
    val archive = sourceState
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val largeScreen = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val spreadOn = Spreads.enabled(prefs.comicSpread, largeScreen)
    val spreads = remember(pages, landscape, spreadOn, prefs.comicOffset, vertical) {
        Spreads.pair(pages.pages.size, landscape, spreadOn && !vertical, prefs.comicOffset)
    }
    val pager = key(spreads, vertical) {
        rememberPagerState(initialPage = Spreads.indexOf(spreads, pages.indexOf(locator))) { spreads.size }
    }
    val listState = key(vertical) {
        androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = pages.indexOf(locator))
    }
    val latestLocator by rememberUpdatedState(locator)
    val outline by produceState(emptyList<PdfOutline.Entry>(), archive) {
        val source = archive?.getOrNull() as? PdfPageSource
        value = if (source == null) emptyList() else withContext(Dispatchers.IO) { source.outline() }
    }
    var layoutMenu by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(false) }
    // 0.9.2: landscape comic reading is fully immersive (system bars hidden, content under the cutout).
    val comicActivity = LocalContext.current as? android.app.Activity
    if (comicActivity != null && landscape) DisposableEffect(comicActivity) {
        val window = comicActivity.window
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val bars = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        controller.hide(bars)
        val previousCutout = if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        onDispose {
            controller.show(bars)
            if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = previousCutout }
        }
    }
    var zoomed by remember { mutableStateOf(false) }
    var chapters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun jumpToPage(page: Int) {
        val target = page.coerceIn(0, pages.pages.lastIndex)
        if (vertical) listState.scrollToItem(target) else pager.scrollToPage(Spreads.indexOf(spreads, target))
        vm.readingActivity(pages.locator(target))
    }
    BackHandler { if (layoutMenu) layoutMenu = false else if (chapters) chapters = false else back() }

    LaunchedEffect(pager, vertical) {
        if (!vertical) snapshotFlowPage(pager) { i ->
            val spread = spreads.getOrNull(i) ?: return@snapshotFlowPage
            // Keep the exact physical anchor through rotation/settings changes until a real turn.
            if (pages.indexOf(latestLocator) !in spread) vm.readingActivity(pages.locator(spread.first()))
            if (spread.last() == pages.pages.lastIndex) vm.finished()
        }
    }
    // Volume keys and D-pad: same direction semantics as text (down = next page).
    val activity = context as? Activity
    DisposableEffect(prefs.volumeKeys, prefs.swapVolumeKeys, chapters, layoutMenu, pager, vertical) {
        (activity as? ReaderActivity)?.readerVolumeHandler = { event ->
            val step = VolumeNavigation.step(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN,
                prefs.volumeKeys, prefs.swapVolumeKeys, chapters || layoutMenu)
            if (step != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                scope.launch {
                    if (prefs.comicDirection == "vertical") listState.animateScrollBy(step * listState.layoutInfo.viewportSize.height * 0.85f)
                    else pager.animateScrollToPage((pager.currentPage + step).coerceIn(0, spreads.lastIndex))
                }
            step != null
        }
        onDispose { (activity as? ReaderActivity)?.readerVolumeHandler = null }
    }

    // First open of a book: apply the direction the file declares (if any), otherwise RTL stays,
    // and show the hint bar with the three directions; untouched, it fades after 2.4s.
    val hints by produceState<android.content.SharedPreferences?>(null, context) {
        value = withContext(Dispatchers.IO) {
            context.getSharedPreferences("comic_hints", 0).also { it.contains("loaded") }
        }
    }
    // Per-book flag: has the reader picked a direction by hand? Until then the file's declared
    // direction wins (also for books opened in older builds) and the first-open hint shows once.
    val chosenKey = "chosen_${vm.id}"
    val hintKey = "hint_v3_${vm.id}"
    var hint by remember { mutableStateOf(false) }
    fun apply(next: String) { if (next != prefs.comicDirection) vm.updateSettings(SettingsScope.BOOK, prefs.copy(comicDirection = next)) }
    fun choose(next: String) { hints?.edit()?.putBoolean(chosenKey, true)?.apply(); apply(next) }
    LaunchedEffect(hints) {
        val hints = hints ?: return@LaunchedEffect
        hint = !hints.getBoolean(chosenKey, false) && !hints.getBoolean(hintKey, false)
        if (!hints.getBoolean(chosenKey, false)) book.readingDirection?.takeIf { it in ComicDirections.all }?.let { apply(it) }
        if (hint) hints.edit().putBoolean(hintKey, true).apply()
    }
    LaunchedEffect(hint) { if (hint) { delay(2400); hint = false } }
    LaunchedEffect(listState, vertical) {
        if (vertical) snapshotFlow { listState.firstVisibleItemIndex }.collect { i ->
            vm.readingActivity(pages.locator(i)); if (i == pages.pages.lastIndex) vm.finished()
        }
    }
    val currentSpread = spreads.getOrNull(pager.currentPage).orEmpty()
    val currentIndex = if (vertical) listState.firstVisibleItemIndex else
        pages.indexOf(locator).takeIf { it in currentSpread } ?: currentSpread.firstOrNull() ?: 0

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(archive, vertical, currentIndex, viewport) {
        val source = archive?.getOrNull() ?: return@LaunchedEffect
        if (!vertical || viewport.width <= 0) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            for (distance in 1..2) for (index in listOf(currentIndex + distance, currentIndex - distance)) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                pages.pages.getOrNull(index)?.let { ref ->
                    runCatching { source.page(ref.unit.resource, viewport.width) }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }
        .background(if (isPdf) Color(prefs.theme.bg) else ComicBg).testTag("comic-reader")) {
        if (archive == null) {
            Box(Modifier.fillMaxSize().background(Skeleton))
        } else if (pages.pages.isEmpty() || archive.isFailure) {
            ComicError(if (isPdf) "这份 PDF 没能打开" else "这本漫画没能打开", archive.exceptionOrNull()?.localizedMessage ?: "没有图片", null, null)
        } else if (vertical) {
            // 条漫: images fill the screen width, stacked without gaps.
            LazyColumn(Modifier.fillMaxSize().testTag("comic-strip"), state = listState) {
                items(pages.pages.size, key = { it }) { index ->
                    ComicStripPage(archive.getOrThrow(), pages.pages[index]) { hint = false; chrome = !chrome }
                }
            }
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 2,
                    userScrollEnabled = !zoomed, key = { it }) { index ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        val pair = spreads[index].let { if (rtl) it.reversed() else it }
                        ComicPage(archive.getOrThrow(), pages.pages[pair.first()],
                            second = pair.getOrNull(1)?.let { pages.pages[it] }, trim = prefs.comicTrim, fillWidth = landscape,
                            onZoom = { if (index == pager.currentPage) zoomed = it },
                            onTap = { x, w ->
                                hint = false
                                when (val step = ComicTaps.step(x, w, rtl)) {
                                    0 -> chrome = !chrome
                                    else -> scope.launch {
                                        pager.animateScrollToPage((pager.currentPage + step).coerceIn(0, spreads.lastIndex))
                                    }
                                }
                            },
                            onSkip = { scope.launch { pager.animateScrollToPage((index + 1).coerceAtMost(spreads.lastIndex)) } })
                    }
                }
            }
        }
        // "Next page" zone flash with the first-time hint.
        AnimatedVisibility(hint && !vertical, enter = fadeIn(), exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxHeight().fillMaxWidth(1f / 3).align(if (rtl) Alignment.CenterStart else Alignment.CenterEnd)) {
            var flash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { delay(1200); flash = false }
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = if (flash) .08f else 0f)))
        }
        AnimatedVisibility(hint, enter = fadeIn(), exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 56.dp)) {
            Row(Modifier.height(32.dp).background(BarBg, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 4.dp)
                .testTag("direction-hint"), verticalAlignment = Alignment.CenterVertically) {
                Text(ComicDirections.hint(dir), color = ComicInk, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                ComicDirections.all.forEach { d ->
                    Box(Modifier.size(32.dp).clickable { choose(d); hint = false }
                        .semantics { contentDescription = ComicDirections.label(d) + if (d == dir) "（当前）" else "" },
                        contentAlignment = Alignment.Center) {
                        DirectionIcon(d, if (d == dir) ComicAccent else ComicInk, Modifier.size(20.dp))
                    }
                }
            }
        }
        val current = pages.pages.getOrNull(currentIndex)
        AnimatedVisibility(chrome, enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(160, easing = FastOutSlowInEasing)), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().background(BarBg).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = ComicInk) }
                Column(Modifier.weight(1f)) {
                    Text(book.title, color = ComicInk, fontSize = 15.sp, maxLines = 1)
                    current?.let { Text(book.chapters[it.chapter].title, color = ComicMeta, fontSize = 11.sp, maxLines = 1) }
                }
                IconButton({ choose(ComicDirections.next(dir)) },
                    Modifier.semantics { contentDescription = "当前${ComicDirections.label(dir)}，切换为${ComicDirections.label(ComicDirections.next(dir))}" }) {
                    DirectionIcon(dir, ComicAccent)
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(tween(200, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(160, easing = FastOutSlowInEasing)), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(BarBg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("${currentIndex + 1} / ${pages.pages.size}", color = ComicInk, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rtl) "${pages.pages.size}" else "1", color = ComicMeta, fontSize = 11.sp)
                    // Mirrored for right-to-left: the start of the book is on the right.
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        var drag by remember { mutableStateOf<Float?>(null) }
                        Slider(drag ?: currentIndex.toFloat(), { drag = it },
                            valueRange = 0f..(pages.pages.lastIndex.coerceAtLeast(1)).toFloat(),
                            onValueChangeFinished = { drag?.let { v -> scope.launch { jumpToPage(v.toInt()) } }; drag = null },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(thumbColor = ComicAccent, activeTrackColor = ComicAccent, inactiveTrackColor = Track))
                    }
                    Text(if (rtl) "1" else "${pages.pages.size}", color = ComicMeta, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                    IconButton({ chapters = true }) { Icon(Icons.AutoMirrored.Outlined.List, "章节", tint = ComicInk) }
                    IconButton({ choose(ComicDirections.next(dir)) }) { DirectionIcon(dir, ComicInk) }
                    IconButton({ layoutMenu = true }) { Icon(Icons.Outlined.Tune, "版面", tint = ComicInk) }
                    IconButton({ vm.bookmark(pages.locator(currentIndex)) }) { Icon(Icons.Outlined.BookmarkBorder, "书签", tint = ComicInk) }
                }
            }
        }
        if (layoutMenu) ComicLayoutSheet(prefs, vertical, largeScreen, { layoutMenu = false }) { next ->
            vm.updateSettings(SettingsScope.BOOK, next)
        }
        if (chapters) Dialog({ chapters = false }) {
            Surface(color = Color(0xFF1A1A1D), shape = RoundedCornerShape(10.dp)) {
                LazyColumn(Modifier.heightIn(max = 480.dp).padding(vertical = 8.dp)) {
                    if (isPdf) {
                        if (outline.isNotEmpty()) itemsIndexed(outline) { _, entry ->
                            Text("${entry.title} · ${entry.page + 1}", color = if (entry.page == currentIndex) ComicAccent else ComicInk,
                                fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable {
                                    chapters = false
                                    scope.launch { jumpToPage(entry.page) }
                                }.padding(start = (20 + entry.level.coerceAtMost(10) * 16).dp, end = 20.dp, top = 12.dp, bottom = 12.dp))
                        } else items(pages.pages.size) { page ->
                            Text("第 ${page + 1} 页", color = ComicInk, modifier = Modifier.fillMaxWidth().clickable {
                                chapters = false
                                scope.launch { jumpToPage(page) }
                            }.padding(20.dp))
                        }
                    } else itemsIndexed(book.chapters) { i, c ->
                        Text("${c.title} · ${c.units.size} 页", color = if (current?.chapter == i) ComicAccent else ComicInk,
                            fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable {
                                chapters = false
                                scope.launch { pages.indexOf(Locator(i, 0, 0)).let { jumpToPage(it) } }
                            }.padding(horizontal = 20.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}

private suspend fun snapshotFlowPage(pager: androidx.compose.foundation.pager.PagerState, onPage: (Int) -> Unit) {
    snapshotFlow { pager.settledPage }.collect(onPage)
}

object ComicDirections {
    val all = listOf("rtl", "ltr", "vertical")
    fun next(d: String) = all[(all.indexOf(d).coerceAtLeast(0) + 1) % all.size]
    fun label(d: String) = when (d) { "ltr" -> "从左往右"; "vertical" -> "条漫"; else -> "从右往左" }
    fun hint(d: String) = when (d) {
        "ltr" -> "左→右：点右侧翻下一页"; "vertical" -> "条漫 · 向上滑动连续阅读"; else -> "从右往左读 · 点左侧翻下一页"
    }
}

/** design §2: 24dp grid, 1.6dp round strokes. Paged = two page frames + arrow; 条漫 = two stacked frames + down arrow. */
@Composable
fun DirectionIcon(dir: String, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val s = size.width / 24f
        val stroke = Stroke(1.6f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val round = androidx.compose.ui.graphics.StrokeCap.Round
        if (dir == "vertical") {
            drawRoundRect(tint, Offset(4 * s, 3 * s), androidx.compose.ui.geometry.Size(11 * s, 8 * s), style = stroke)
            drawRoundRect(tint, Offset(4 * s, 13 * s), androidx.compose.ui.geometry.Size(11 * s, 8 * s), style = stroke)
            drawLine(tint, Offset(19.5f * s, 5 * s), Offset(19.5f * s, 19 * s), 1.6f * s, round)
            drawLine(tint, Offset(19.5f * s, 19 * s), Offset(17 * s, 16 * s), 1.6f * s, round)
            drawLine(tint, Offset(19.5f * s, 19 * s), Offset(22 * s, 16 * s), 1.6f * s, round)
            return@Canvas
        }
        val rtl = dir != "ltr"
        drawRoundRect(tint, Offset(3 * s, 4 * s), androidx.compose.ui.geometry.Size(8 * s, 11 * s), style = stroke)
        drawRoundRect(tint, Offset(13 * s, 4 * s), androidx.compose.ui.geometry.Size(8 * s, 11 * s), style = stroke)
        val y = 19.5f * s
        drawLine(tint, Offset(6 * s, y), Offset(18 * s, y), 1.6f * s, round)
        val tip = if (rtl) 6 * s else 18 * s; val d = if (rtl) 3 * s else -3 * s
        drawLine(tint, Offset(tip, y), Offset(tip + d, y - 2.5f * s), 1.6f * s, round)
        drawLine(tint, Offset(tip, y), Offset(tip + d, y + 2.5f * s), 1.6f * s, round)
    }
}

/** 条漫 page: full width, height from the header aspect ratio (no layout jump), decoded at screen width. */
@Composable
private fun ComicStripPage(archive: com.lyric.reader.PageSource, ref: ComicPageRef, onTap: () -> Unit) {
    val latestTap by rememberUpdatedState(onTap)
    var width by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(ref, attempt) { mutableStateOf<Result<Bitmap>?>(null) }
    LaunchedEffect(archive, ref, attempt, width) {
        if (width > 0) result = withContext(Dispatchers.IO) { runCatching { archive.page(ref.unit.resource, width) } }
    }
    val ratio = ref.unit.width?.let { w -> ref.unit.height?.takeIf { it > 0 && w > 0 }?.let { w.toFloat() / it } } ?: (1 / 1.4f)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).onSizeChanged { width = it.width }
        .pointerInput(ref) { detectTapGestures(onTap = { latestTap() }) }) {
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize().background(Skeleton))
            r.isFailure -> ComicError("这一页没能打开",
                "${ref.unit.resource.substringAfterLast('/')} · ${r.exceptionOrNull()?.localizedMessage?.substringAfter(" · ") ?: "未知错误"}",
                { attempt++ }, null)
            else -> Image(r.getOrThrow().asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 裁白边 detection runs only once trim is on (worker thread); crops are kept for this page result so toggling animates both ways. */
@Composable
private fun trimmedArtwork(loaded: List<PageArtwork>, trim: Boolean): List<PageArtwork> {
    var crops by remember(loaded) { mutableStateOf<List<PageCrop>?>(null) }
    LaunchedEffect(loaded, trim) {
        if (trim && crops == null) crops = withContext(Dispatchers.Default) { loaded.map { PageTrim.detect(it.bitmap) } }
    }
    val c = crops
    return remember(loaded, c) { if (c == null) loaded else loaded.mapIndexed { i, p -> p.copy(crop = c[i]) } }
}

/** Width / height of the (trimmed) page or side-by-side spread at a common height. */
internal fun artworkRatio(art: List<PageArtwork>, trimmed: Boolean): Float = art.sumOf { p ->
    val c = if (trimmed) p.crop else PageCrop()
    val w = p.bitmap.width * (c.right - c.left); val h = p.bitmap.height * (c.bottom - c.top)
    (w / h.coerceAtLeast(1f)).toDouble()
}.toFloat()

@Composable
private fun ComicPage(archive: com.lyric.reader.PageSource, ref: ComicPageRef, second: ComicPageRef? = null, trim: Boolean = false, fillWidth: Boolean = false, onZoom: (Boolean) -> Unit,
                      onTap: (Float, Float) -> Unit, onSkip: () -> Unit) {
    val latestTap by rememberUpdatedState(onTap)
    val latestZoom by rememberUpdatedState(onZoom)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(ref, second, attempt) { mutableStateOf<Result<List<PageArtwork>>?>(null) }
    var slow by remember(ref, second, attempt) { mutableStateOf(false) }
    var verySlow by remember(ref, second, attempt) { mutableStateOf(false) }
    LaunchedEffect(archive, ref, second, attempt, size, fillWidth) {
        if (size.width == 0) return@LaunchedEffect
        launch { delay(300); slow = true; delay(700); verySlow = true }
        result = withContext(Dispatchers.IO) { runCatching {
            listOfNotNull(ref, second).map { page ->
                // Fill-width (landscape) pages are taller than the viewport: bound by width, not height.
                val width = if (second == null) size.width * (if (fillWidth) 1 else 2) else size.width
                val height = if (fillWidth) size.width * 4 else size.height * 2
                val bitmap = archive.page(page.unit.resource, width, height)
                PageArtwork(bitmap, PageCrop())
            }
        } }
    }
    var scale by remember(ref, second) { mutableFloatStateOf(1f) }
    var offset by remember(ref, second) { mutableStateOf(Offset.Zero) }
    val anim = rememberCoroutineScope()
    LaunchedEffect(scale > 1.01f) { latestZoom(scale > 1.01f) }
    fun clampOffset(o: Offset, s: Float) = Offset(
        o.x.coerceIn(-size.width * (s - 1) / 2, size.width * (s - 1) / 2),
        o.y.coerceIn(-size.height * (s - 1) / 2, size.height * (s - 1) / 2))
    Box(Modifier.fillMaxSize().onSizeChanged { size = it }
        .pointerInput(ref) {
            detectTapGestures(onDoubleTap = { p ->
                val target = if (scale > 1.01f) 1f else 2f
                val from = scale; val fromOffset = offset
                val toOffset = if (target == 1f) Offset.Zero else clampOffset((Offset(size.width / 2f, size.height / 2f) - p) * (target - 1), target)
                anim.launch {
                    androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(250)) { t, _ ->
                        scale = from + (target - from) * t; offset = fromOffset + (toOffset - fromOffset) * t
                    }
                }
            }, onTap = { p -> if (scale <= 1.01f) latestTap(p.x, size.width.toFloat()) })
        }
        .pointerInput(ref) {
            // Pinch 1–4x; one-finger pan only while zoomed so the pager keeps its swipe otherwise.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2 || scale > 1.01f) {
                        val zoom = event.calculateZoom(); val pan = event.calculatePan()
                        val next = (scale * zoom).coerceIn(1f, 4f)
                        offset = clampOffset(offset + pan, next); scale = next
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                if (scale < 1.05f) { scale = 1f; offset = Offset.Zero }
            }
        }, contentAlignment = Alignment.Center) {
        val r = result
        val ratio = ref.unit.width?.let { w -> ref.unit.height?.takeIf { it > 0 && w > 0 }?.let { w.toFloat() / it } } ?: (3f / 4)
        when {
            r == null -> Box(Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = ratio < size.width.toFloat() / size.height.coerceAtLeast(1))) {
                if (slow) {
                    val breathe by rememberInfiniteTransition(label = "skeleton").animateFloat(.06f, .09f,
                        infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "breathe")
                    Box(Modifier.fillMaxSize().background(Skeleton).background(Color.White.copy(alpha = breathe)))
                    if (verySlow) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(.4f).height(2.dp).background(ComicAccent.copy(alpha = .7f)))
                }
            }
            r.isFailure -> ComicError("这一页没能打开",
                "${ref.unit.resource.substringAfterLast('/')} · ${r.exceptionOrNull()?.localizedMessage?.substringAfter(" · ") ?: "未知错误"}",
                { attempt++ }, onSkip)
            else -> trimmedArtwork(r.getOrThrow(), trim).let { art ->
                val trimmed = trim && art.any { it.crop != PageCrop() }
                val zoomLayer = Modifier.graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                }
                if (fillWidth) {
                    // Landscape: fill the width, pan vertically inside the page/spread; taps still turn pages.
                    val ratio = remember(art, trimmed) { artworkRatio(art, trimmed) }
                    val scroll = rememberScrollState()
                    Box(Modifier.fillMaxSize().then(zoomLayer).verticalScroll(scroll, enabled = scale <= 1.01f),
                        contentAlignment = Alignment.Center) {
                        ComicArtwork(art, trimmed, Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                            .heightIn(min = with(LocalDensity.current) { size.height.toDp() })
                            .aspectRatio(ratio.coerceAtLeast(.05f), matchHeightConstraintsFirst = false))
                    }
                } else ComicArtwork(art, trimmed, Modifier.fillMaxSize().padding(4.dp).then(zoomLayer))
            }
        }
        if (scale > 1.01f) Text("${"%.1f".format(scale)}×", color = ComicInk, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
                .background(BarBg, RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun ComicError(title: String, reason: String, retry: (() -> Unit)?, skip: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(20.dp).fillMaxWidth().aspectRatio(1 / 1.4f, matchHeightConstraintsFirst = false)
            .background(Skeleton, RoundedCornerShape(6.dp))
            .drawBehindDashed(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.BrokenImage, null, tint = ComicMeta, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(12.dp))
            Text(title, color = ComicInk, fontSize = 15.sp, fontFamily = Serif)
            Text(reason, color = ComicMeta, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                retry?.let { OutlinedButton(it, Modifier.height(32.dp), border = BorderStroke(1.dp, ComicAccent),
                    contentPadding = PaddingValues(horizontal = 16.dp)) { Text("重试", color = ComicAccent, fontSize = 12.sp) } }
                skip?.let { OutlinedButton(it, Modifier.height(32.dp), border = BorderStroke(1.dp, Color(0xFF45444A)),
                    contentPadding = PaddingValues(horizontal = 16.dp)) { Text("跳过此页", color = ComicInk, fontSize = 12.sp) } }
            }
        }
    }
}

private fun Modifier.drawBehindDashed() = this.then(Modifier.drawWithCacheDashed())
private fun Modifier.drawWithCacheDashed() = this.drawBehind {
    drawRoundRect(Color(0xFF45444A), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
}
