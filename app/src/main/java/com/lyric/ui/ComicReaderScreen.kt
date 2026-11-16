package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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

private val BarBg = LyricTokens.Comic.bar
private val ComicInk = LyricTokens.Comic.ink
private val ComicMeta = LyricTokens.Comic.meta
private val ComicAccent = LyricTokens.Night.vermilion
private val Track = LyricTokens.Comic.track
private val Skeleton = LyricTokens.Night.skeleton

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
fun ComicReaderScreen(vm: ReaderViewModel, book: Book, back: () -> Unit, onFirstReady: (Float?) -> Unit = {}) {
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val comicNight = com.lyric.core.ThemePalette.isNight(
        if (prefs.followSystem && isSystemInDarkTheme()) com.lyric.core.ReaderTheme.NIGHT else prefs.theme,
        prefs.customBg,
    )
    val comicBg = LyricTokens.Comic.background(comicNight)
    val locator by vm.locator.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val isPdf = book.chapters.firstOrNull()?.units?.firstOrNull().let { it is com.lyric.core.ContentUnit.ImagePage && it.resource.startsWith(com.lyric.reader.PDF_PAGE_PREFIX) }
    // PDFs read left to right (or as a vertical strip); the manga default does not apply.
    val dir = (prefs.comicDirection.takeIf { it in ComicDirections.all } ?: "rtl").let { if (isPdf && it == "rtl") "ltr" else it }
    val rtl = dir == "rtl"
    val vertical = dir == "vertical"
    val pages = remember(book) { ComicPages(book) }
    var firstPageReady by remember(book) { mutableStateOf(false) }
    val latestFirstReady by rememberUpdatedState(onFirstReady)
    val reportReady = { ratio: Float? ->
        if (!firstPageReady) {
            firstPageReady = true
            latestFirstReady(ratio)
        }
    }
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
    LaunchedEffect(archive) {
        if (archive?.isFailure == true || (archive != null && pages.pages.isEmpty())) reportReady(null)
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val largeScreen = LocalConfiguration.current.smallestScreenWidthDp >= LyricTokens.Page.tabletSmallestWidthDp
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
    var chapters by remember { mutableStateOf(false) }
    val outline by produceState(emptyList<PdfOutline.Entry>(), archive, chapters) {
        val source = archive?.getOrNull() as? PdfPageSource
        if (source != null && chapters) value = withContext(Dispatchers.IO) { source.outline() }
    }
    var layoutMenu by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(false) }
    // Landscape keeps an immersive background; artwork and chrome reserve safe insets.
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
                    if (prefs.comicDirection == "vertical") listState.animateScrollBy(step * listState.layoutInfo.viewportSize.height * 0.85f, LyricTokens.Motion.medium())
                    else pager.animateScrollToPage((pager.currentPage + step).coerceIn(0, spreads.lastIndex), animationSpec = LyricTokens.Motion.medium())
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
    LaunchedEffect(hint) { if (hint) { delay(LyricTokens.Comic.hintMs); hint = false } }
    LaunchedEffect(listState, vertical) {
        if (vertical) snapshotFlow { listState.firstVisibleItemIndex }.collect { i ->
            vm.readingActivity(pages.locator(i)); if (i == pages.pages.lastIndex) vm.finished()
        }
    }
    val currentSpread = spreads.getOrNull(pager.currentPage).orEmpty()
    val currentIndex = if (vertical) listState.firstVisibleItemIndex else
        pages.indexOf(locator).takeIf { it in currentSpread } ?: currentSpread.firstOrNull() ?: 0

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(archive, vertical, currentIndex, viewport, firstPageReady) {
        val source = archive?.getOrNull() ?: return@LaunchedEffect
        if (!firstPageReady || !vertical || viewport.width <= 0) return@LaunchedEffect
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
        .background(if (isPdf) Color(prefs.theme.bg) else comicBg).testTag("comic-reader")) {
        if (archive == null) {
            Box(Modifier.fillMaxSize().background(Skeleton))
        } else if (pages.pages.isEmpty() || archive.isFailure) {
            ComicError(if (isPdf) "这份 PDF 没能打开" else "这本漫画没能打开", archive.exceptionOrNull()?.localizedMessage ?: "没有图片", null, null)
        } else if (vertical) {
            // 条漫: images fill the screen width, stacked without gaps.
            LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(readerSafeInsets()).testTag("comic-strip"), state = listState) {
                items(pages.pages.size, key = { it }) { index ->
                    ComicStripPage(archive.getOrThrow(), pages.pages[index],
                        decodeEnabled = firstPageReady || index == currentIndex,
                        initial = !firstPageReady && index == currentIndex,
                        onReady = { if (index == currentIndex) reportReady(it) }) { hint = false; chrome = !chrome }
                }
            }
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                HorizontalPager(pager, Modifier.fillMaxSize().windowInsetsPadding(readerSafeInsets()), beyondViewportPageCount = if (firstPageReady) 2 else 0,
                    userScrollEnabled = !zoomed, key = { it }) { index ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        val pair = spreads[index].let { if (rtl) it.reversed() else it }
                        ComicPage(archive.getOrThrow(), pages.pages[pair.first()],
                            second = pair.getOrNull(1)?.let { pages.pages[it] }, trim = prefs.comicTrim, fillWidth = landscape,
                            decodeEnabled = firstPageReady || index == pager.currentPage,
                            initial = !firstPageReady && index == pager.currentPage,
                            onReady = { if (index == pager.currentPage) reportReady(it) },
                            onZoom = { if (index == pager.currentPage) zoomed = it },
                            onTap = { x, w ->
                                hint = false
                                when (val step = ComicTaps.step(x, w, rtl)) {
                                    0 -> chrome = !chrome
                                    else -> scope.launch {
                                        pager.animateScrollToPage((pager.currentPage + step).coerceIn(0, spreads.lastIndex), animationSpec = LyricTokens.Motion.medium())
                                    }
                                }
                            },
                            onSkip = { scope.launch { pager.animateScrollToPage((index + 1).coerceAtMost(spreads.lastIndex), animationSpec = LyricTokens.Motion.medium()) } })
                    }
                }
            }
        }
        // "Next page" zone flash with the first-time hint.
        AnimatedVisibility(hint && !vertical, enter = fadeIn(LyricTokens.Motion.short()), exit = fadeOut(LyricTokens.Motion.short()),
            modifier = Modifier.fillMaxHeight().fillMaxWidth(1f / 3).align(if (rtl) Alignment.CenterStart else Alignment.CenterEnd)) {
            var flash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { delay(LyricTokens.Comic.hintFlashMs); flash = false }
            Box(Modifier.fillMaxSize().background(LyricTokens.Overlay.white.copy(alpha = if (flash) .08f else 0f)))
        }
        AnimatedVisibility(hint, enter = fadeIn(LyricTokens.Motion.short()), exit = fadeOut(LyricTokens.Motion.short()),
            modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(bottom = LyricTokens.Space.xxl)) {
            ComicDirectionHint(dir) { choose(it); hint = false }
        }

        val current = pages.pages.getOrNull(currentIndex)
        AnimatedVisibility(chrome, enter = fadeIn(LyricTokens.Motion.short()),
            exit = fadeOut(LyricTokens.Motion.short()), modifier = Modifier.align(Alignment.TopCenter)) {
            ComicTopBar(book.title, current?.let { book.chapters[it.chapter].title }, dir,
                back = back, changeDirection = { choose(ComicDirections.next(dir)) })
        }
        AnimatedVisibility(chrome, enter = fadeIn(LyricTokens.Motion.short()),
            exit = fadeOut(LyricTokens.Motion.short()), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(BarBg).windowInsetsPadding(readerSafeInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.sm)) {
                Text("${currentIndex + 1} / ${pages.pages.size}", color = ComicInk, style = LyricTokens.Type.auxiliary,
                    fontFamily = LyricTokens.Fonts.hei, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (rtl) "${pages.pages.size}" else "1", color = ComicMeta, style = LyricTokens.Type.auxiliary)
                    // Mirrored for right-to-left: the start of the book is on the right.
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        var drag by remember { mutableStateOf<Float?>(null) }
                        Slider(drag ?: currentIndex.toFloat(), { drag = it },
                            valueRange = 0f..(pages.pages.lastIndex.coerceAtLeast(1)).toFloat(),
                            onValueChangeFinished = { drag?.let { v -> scope.launch { jumpToPage(v.toInt()) } }; drag = null },
                            modifier = Modifier.weight(1f).padding(horizontal = LyricTokens.Space.sm),
                            colors = SliderDefaults.colors(thumbColor = ComicAccent, activeTrackColor = ComicAccent, inactiveTrackColor = Track))
                    }
                    Text(if (rtl) "1" else "${pages.pages.size}", color = ComicMeta, style = LyricTokens.Type.auxiliary)
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
            Surface(color = LyricTokens.Comic.errorPanel, shape = RoundedCornerShape(LyricTokens.Radius.button)) {
                LazyColumn(Modifier.heightIn(max = LyricTokens.Component.outlineMaxHeight).padding(vertical = LyricTokens.Space.sm)) {
                    if (isPdf) {
                        if (outline.isNotEmpty()) itemsIndexed(outline) { _, entry ->
                            Text("${entry.title} · ${entry.page + 1}", color = if (entry.page == currentIndex) ComicAccent else ComicInk,
                                style = LyricTokens.Type.body, modifier = Modifier.fillMaxWidth().clickable {
                                    chapters = false
                                    scope.launch { jumpToPage(entry.page) }
                                }.padding(start = LyricTokens.Page.margin + LyricTokens.Space.lg * entry.level.coerceAtMost(10), end = LyricTokens.Component.mediumIcon, top = LyricTokens.Space.md, bottom = LyricTokens.Space.md))
                        } else items(pages.pages.size) { page ->
                            Text("第 ${page + 1} 页", color = ComicInk, modifier = Modifier.fillMaxWidth().clickable {
                                chapters = false
                                scope.launch { jumpToPage(page) }
                            }.padding(LyricTokens.Space.lg))
                        }
                    } else itemsIndexed(book.chapters) { i, c ->
                        Text("${c.title} · ${c.units.size} 页", color = if (current?.chapter == i) ComicAccent else ComicInk,
                            style = LyricTokens.Type.body, modifier = Modifier.fillMaxWidth().clickable {
                                chapters = false
                                scope.launch { pages.indexOf(Locator(i, 0, 0)).let { jumpToPage(it) } }
                            }.padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.md))
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
fun DirectionIcon(dir: String, tint: Color, modifier: Modifier = Modifier.size(LyricTokens.Space.xl)) {
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
private fun ComicStripPage(archive: com.lyric.reader.PageSource, ref: ComicPageRef, decodeEnabled: Boolean, initial: Boolean, onReady: (Float?) -> Unit, onTap: () -> Unit) {
    val latestTap by rememberUpdatedState(onTap)
    var width by remember { mutableIntStateOf(0) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(ref, attempt) { mutableStateOf<Result<Bitmap>?>(null) }
    val readerManaged = remember(ref, attempt) { initial && attempt == 0 }
    val readyCallback by rememberUpdatedState(onReady)
    val stage = if (readerManaged) LoadPresentation.CONTENT else loadPresentation(ref to attempt, result != null)
    val alpha = loadContentAlpha(stage)
    LaunchedEffect(result) { result?.let { r -> readyCallback(r.getOrNull()?.let { it.width.toFloat() / it.height }) } }
    LaunchedEffect(archive, ref, attempt, width, decodeEnabled) {
        if (width > 0 && decodeEnabled) result = withContext(Dispatchers.IO) { runCatching { archive.page(ref.unit.resource, width) } }
    }
    val ratio = ref.unit.width?.let { w -> ref.unit.height?.takeIf { it > 0 && w > 0 }?.let { w.toFloat() / it } } ?: (1 / 1.4f)
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).onSizeChanged { width = it.width }
        .pointerInput(ref) { detectTapGestures(onTap = { latestTap() }) }) {
        val r = result
        when {
            r == null || (!readerManaged && !stage.content) -> if (!readerManaged && stage.skeleton) ComicPlaceholder(ratio, true)
            r.isFailure -> ComicError("这一页没能打开",
                "${ref.unit.resource.substringAfterLast('/')} · ${r.exceptionOrNull()?.localizedMessage?.substringAfter(" · ") ?: "未知错误"}",
                { attempt++ }, null)
            else -> Image(r.getOrThrow().asImageBitmap(), null, contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize().graphicsLayer { this.alpha = if (readerManaged) 1f else alpha.value })
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
                      decodeEnabled: Boolean, initial: Boolean, onReady: (Float?) -> Unit, onTap: (Float, Float) -> Unit, onSkip: () -> Unit) {
    val latestTap by rememberUpdatedState(onTap)
    val latestZoom by rememberUpdatedState(onZoom)
    var size by remember { mutableStateOf(IntSize.Zero) }
    var attempt by remember { mutableIntStateOf(0) }
    var result by remember(ref, second, attempt) { mutableStateOf<Result<List<PageArtwork>>?>(null) }
    val readerManaged = remember(ref, second, attempt) { initial && attempt == 0 }
    val readyCallback by rememberUpdatedState(onReady)
    val stage = if (readerManaged) LoadPresentation.CONTENT else loadPresentation(listOf(ref, second, attempt), result != null)
    val alpha = loadContentAlpha(stage)
    LaunchedEffect(result) { result?.let { r -> readyCallback(r.getOrNull()?.let { artworkRatio(it, false) }) } }
    LaunchedEffect(archive, ref, second, attempt, size, fillWidth, decodeEnabled) {
        if (size.width == 0 || !decodeEnabled) return@LaunchedEffect
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
                    androidx.compose.animation.core.animate(0f, 1f, animationSpec = LyricTokens.Motion.medium()) { t, _ ->
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
        val ratio = listOfNotNull(ref, second).sumOf { page ->
            (page.unit.width?.let { w -> page.unit.height?.takeIf { it > 0 && w > 0 }?.let { w.toFloat() / it } } ?: (3f / 4)).toDouble()
        }.toFloat()
        when {
            r == null || (!readerManaged && !stage.content) -> if (!readerManaged && stage.skeleton) ComicPlaceholder(ratio, true)
            r.isFailure -> ComicError("这一页没能打开",
                "${ref.unit.resource.substringAfterLast('/')} · ${r.exceptionOrNull()?.localizedMessage?.substringAfter(" · ") ?: "未知错误"}",
                { attempt++ }, onSkip)
            else -> trimmedArtwork(r.getOrThrow(), trim).let { art ->
                val trimmed = trim && art.any { it.crop != PageCrop() }
                Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = if (readerManaged) 1f else alpha.value }) {
                    ComicArtworkViewport(art, trimmed, fillWidth, scale, offset)
                }
            }
        }
        if (scale > 1.01f) Text("${"%.1f".format(scale)}×", color = ComicInk, style = LyricTokens.Type.auxiliary,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(LyricTokens.Space.md)
                .background(BarBg, RoundedCornerShape(LyricTokens.Radius.button)).padding(horizontal = LyricTokens.Space.sm, vertical = LyricTokens.Space.xs))
    }
}

@Composable
private fun ComicError(title: String, reason: String, retry: (() -> Unit)?, skip: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(LyricTokens.Space.lg).fillMaxWidth().aspectRatio(1 / 1.4f, matchHeightConstraintsFirst = false)
            .background(Skeleton, RoundedCornerShape(LyricTokens.Radius.skeleton))
            .drawBehindDashed(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.BrokenImage, null, tint = ComicMeta, modifier = Modifier.size(LyricTokens.Component.smallControl))
            Spacer(Modifier.height(LyricTokens.Space.md))
            Text(title, color = ComicInk, style = LyricTokens.Type.body, fontFamily = Serif)
            Text(reason, color = ComicMeta, style = LyricTokens.Type.auxiliary, modifier = Modifier.padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.md)) {
                retry?.let { OutlinedButton(it, Modifier.heightIn(min = LyricTokens.Space.xxl), border = BorderStroke(LyricTokens.strokeWidth, ComicAccent),
                    contentPadding = PaddingValues(horizontal = LyricTokens.Space.lg), shape = LyricTokens.Radius.buttonShape) { Text("重试", color = ComicAccent, style = LyricTokens.Type.auxiliary) } }
                skip?.let { OutlinedButton(it, Modifier.heightIn(min = LyricTokens.Space.xxl), border = BorderStroke(LyricTokens.strokeWidth, LyricTokens.Comic.outline),
                    contentPadding = PaddingValues(horizontal = LyricTokens.Space.lg), shape = LyricTokens.Radius.buttonShape) { Text("跳过此页", color = ComicInk, style = LyricTokens.Type.auxiliary) } }
            }
        }
    }
}

private fun Modifier.drawBehindDashed() = this.then(Modifier.drawWithCacheDashed())
private fun Modifier.drawWithCacheDashed() = this.drawBehind {
    drawRoundRect(LyricTokens.Comic.outline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(LyricTokens.Component.dashLength.toPx()),
        style = Stroke(LyricTokens.strokeWidth.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(LyricTokens.Component.dashLength.toPx(), LyricTokens.Space.xs.toPx()))))
}

@Composable
internal fun ComicTopBar(
    title: String, chapter: String?, direction: String, back: () -> Unit, changeDirection: () -> Unit,
    insets: WindowInsets = readerSafeInsets(),
) {
    Row(
        Modifier.fillMaxWidth().background(BarBg)
            .windowInsetsPadding(insets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = LyricTokens.Component.toolbarHeight).padding(horizontal = LyricTokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = ComicInk) }
        Column(Modifier.weight(1f)) {
            Text(title, color = ComicInk, style = LyricTokens.Type.body, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            chapter?.let { Text(it, color = ComicMeta, style = LyricTokens.Type.auxiliary, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        IconButton(changeDirection,
            Modifier.semantics { contentDescription = "当前${ComicDirections.label(direction)}，切换为${ComicDirections.label(ComicDirections.next(direction))}" }) {
            DirectionIcon(direction, ComicAccent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComicDirectionHint(direction: String, choose: (String) -> Unit) {
    FlowRow(
        Modifier.heightIn(min = LyricTokens.Space.xxl).background(BarBg, LyricTokens.Radius.pill)
            .padding(start = LyricTokens.Space.md, end = LyricTokens.Space.xs).testTag("direction-hint"),
        horizontalArrangement = Arrangement.spacedBy(LyricTokens.Component.dashLength),
    ) {
        Text(ComicDirections.hint(direction), color = ComicInk, style = LyricTokens.Type.auxiliary, modifier = Modifier.align(Alignment.CenterVertically))
        Row {
            ComicDirections.all.forEach { d ->
                Box(Modifier.size(LyricTokens.Space.xxl).clickable { choose(d) }
                    .semantics { contentDescription = ComicDirections.label(d) + if (d == direction) "（当前）" else "" },
                    contentAlignment = Alignment.Center) {
                    DirectionIcon(d, if (d == direction) ComicAccent else ComicInk, Modifier.size(LyricTokens.Component.mediumIcon))
                }
            }
        }
    }
}
