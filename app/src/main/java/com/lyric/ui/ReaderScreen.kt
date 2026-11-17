@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import android.app.Activity
import android.view.KeyEvent
import com.lyric.ReaderActivity
import com.lyric.reader.VolumeNavigation
import coil.compose.AsyncImage
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.core.*
import com.lyric.data.SettingsScope
import com.lyric.reader.ReaderCanvas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(vm: ReaderViewModel, drawerExtra: @Composable () -> Unit = {}, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val chapterGeneration by vm.contentGeneration.collectAsStateWithLifecycle()
    var ready by remember(vm, chapterGeneration) { mutableStateOf(false) }
    val navigationFailure by vm.navigationFailure.collectAsStateWithLifecycle()
    LaunchedEffect(navigationFailure) { if (navigationFailure > 0) ready = true }
    var decodedRatio by remember(vm) { mutableStateOf<Float?>(null) }
    var generation by remember(vm) { mutableIntStateOf(0) }
    LaunchedEffect(state.loading) { if (state.loading) { ready = false; decodedRatio = null; generation++ } }
    DisposableEffect(vm) { onDispose { com.lyric.reader.PerfLog.finish() } }
    val stage = loadPresentation(Triple(vm, generation, chapterGeneration), ready || state.error != null)
    val alpha = loadContentAlpha(stage)
    val activity = LocalContext.current as? Activity
    LaunchedEffect(stage) {
        if (stage.skeleton) com.lyric.reader.PerfLog.stage("tap_to_skeleton")
        if (stage == com.lyric.reader.LoadPresentation.CONTENT && state.error == null) {
            // Include the hold/fade in device traces; a decoded but hidden page is not readable.
            withFrameNanos { }
            com.lyric.reader.ReaderMetrics.endOpen()
            activity?.reportFullyDrawn()
        }
    }
    val theme = if (prefs.followSystem && isSystemInDarkTheme()) ReaderTheme.NIGHT else prefs.theme
    val paper = Color(if (theme == ReaderTheme.CUSTOM) prefs.customBg else theme.bg)
    val comic = state.book?.contentType == ContentType.COMIC || state.row?.contentType == ContentType.COMIC.name
    val pdf = state.row?.extension == "pdf"
    val isNight = com.lyric.core.ThemePalette.isNight(theme, if (theme == ReaderTheme.CUSTOM) prefs.customBg else theme.bg)
    Box(Modifier.fillMaxSize().background(if (comic && !pdf) LyricTokens.Comic.background(isNight) else paper).drawWithContent {
        drawContent()
        if (com.lyric.reader.PerfLog.enabled && stage == com.lyric.reader.LoadPresentation.CONTENT && state.error == null)
            com.lyric.reader.PerfLog.stage("first_frame_drawn")
    }) {
        if (stage.skeleton) {
            if (comic) {
                val at = vm.locator.value
                val unit = state.book?.chapters?.getOrNull(at.chapterIndex)?.content?.getOrNull(at.page) as? ContentUnit.ImagePage
                val ratio = decodedRatio ?: if (unit?.width != null && unit.height != null && unit.height > 0)
                    unit.width.toFloat() / unit.height else 3f / 4f
                ComicPlaceholder(ratio, !pdf || paper.luminance() < .2f)
            } else PaperSkeleton(paper, Color(theme.ink), prefs.margin)
        }
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.semantics {
            // Only expose a successfully decoded, visible comic page to accessibility/benchmarks.
            if (comic && decodedRatio != null && stage == com.lyric.reader.LoadPresentation.CONTENT)
                contentDescription = "Lyric漫画:" + (state.book?.title ?: "")
        }) {
            ReaderContent(vm, drawerExtra, { ratio ->
                com.lyric.reader.PerfLog.stage(if (comic) "first_bitmap" else "first_window")
                decodedRatio = ratio; ready = true
                vm.firstWindowReady()
            }, back)
        }
    }
}

@Composable
private fun ReaderContent(vm: ReaderViewModel, drawerExtra: @Composable () -> Unit, onFirstReady: (Float?) -> Unit, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Install before the comic/PDF branch so every reader resumes and drains its durable writers.
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.resume()
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) vm.pause()
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.resume()
        onDispose {
            vm.pause()
            lifecycle.lifecycle.removeObserver(observer)
        }
    }
    state.book?.takeIf { it.contentType == ContentType.COMIC }?.let { comic ->
        ComicReaderScreen(vm, comic, back, onFirstReady)
        return
    }
    // Comics never flash the paper colour while loading.
    if (state.book == null && state.row?.contentType == ContentType.COMIC.name) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(LyricTokens.Comic.background(isSystemInDarkTheme())))
        return
    }
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val locatorState = vm.locator.collectAsStateWithLifecycle()
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    val highlightRows by vm.highlights.collectAsStateWithLifecycle()
    var noteEdit by remember { mutableStateOf<Triple<Boolean, Long, String>?>(null) }
    var tappedHighlight by remember { mutableStateOf<com.lyric.data.HighlightRow?>(null) }
    tappedHighlight?.let { h ->
        HighlightOptionsSheet(h, { vm.highlightColor(h.id, it) }, { noteEdit = Triple(true, h.id, h.note) },
            { vm.deleteHighlight(h.id) }, { tappedHighlight = null })
    }
    noteEdit?.let { (isHighlight, rowId, old) ->
        NoteDialog(if (isHighlight) "想法" else "书签备注", old,
            { if (isHighlight) vm.highlightNote(rowId, it) else vm.bookmarkNote(rowId, it) }, { noteEdit = null })
    }
    val highlight by vm.highlight.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    val pageInfo = remember { mutableStateOf("") }
    var canvas by remember { mutableStateOf<ReaderCanvas?>(null) }
    val visibleLocator = remember(state.book) { mutableStateOf(vm.locator.value) }
    var curlFrame by remember { mutableStateOf<CurlFrame?>(null) }
    val pageLayer = rememberGraphicsLayer()
    var pageOrigin by remember { mutableStateOf(Offset.Zero) }
    var bodyOrigin by remember { mutableStateOf(Offset.Zero) }
    var curlCapture by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    LaunchedEffect(state.book, prefs.pageMode, prefs.fontSize, prefs.margin, prefs.orientation) {
        curlCapture?.cancel()
        curlFrame = null
        canvas?.abortCurl()
    }
    var enlargedImage by remember { mutableStateOf<String?>(null) }
    var drawerTab by rememberSaveable { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    DisposableEffect(Unit) {
        onDispose { curlCapture?.cancel(); canvas?.abortCurl(); curlFrame = null }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                curlCapture?.cancel()
                canvas?.abortCurl()
                curlFrame = null
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            snack.showSnackbar(it)
            vm.notice.value = null
        }
    }
    val systemDark = isSystemInDarkTheme()
    val theme = if (prefs.followSystem && systemDark) ReaderTheme.NIGHT else prefs.theme
    val bg = Color(if (theme == ReaderTheme.CUSTOM) prefs.customBg else theme.bg)
    val ink = Color(if (theme == ReaderTheme.CUSTOM) prefs.customInk else theme.ink)
    val meta = Color(theme.meta)
    val darkReader = bg.luminance() < .2f
    val accent = MaterialTheme.colorScheme.secondary
    MaterialTheme(colorScheme = (if (darkReader) DarkColors else LightColors).copy(primary = accent)) {
        val activity = LocalContext.current as Activity
        val controls = menu || settings || search || drawer.targetValue == DrawerValue.Open || drawer.isOpen || enlargedImage != null
        DisposableEffect(activity) {
            onDispose { (activity as? ReaderActivity)?.readerVolumeHandler = null }
        }
        DisposableEffect(activity, prefs.volumeKeys, prefs.swapVolumeKeys, controls, canvas) {
            (activity as? ReaderActivity)?.readerVolumeHandler = { event ->
                val step = VolumeNavigation.step(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN,
                    prefs.volumeKeys, prefs.swapVolumeKeys, controls || canvas?.selectionMenuOpen == true)
                if (step == null || canvas == null) false else {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) canvas?.turn(step)
                    true
                }
            }
            onDispose { (activity as? ReaderActivity)?.readerVolumeHandler = null }
        }
        DisposableEffect(activity, controls, darkReader) {
            val controller =
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !darkReader
            controller.isAppearanceLightNavigationBars = !darkReader
            onDispose { }
        }
        DisposableEffect(activity, prefs.brightness) {
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = prefs.brightness.coerceIn(.01f, 1f)
            }
            onDispose { }
        }
        DisposableEffect(activity, prefs.keepScreenOn, prefs.orientation) {
            val originalOrientation = activity.requestedOrientation
            if (prefs.keepScreenOn) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = when (prefs.orientation) {
                "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            onDispose {
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.requestedOrientation = originalOrientation
            }
        }
        DisposableEffect(activity, systemDark) {
            onDispose {
                WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                    isAppearanceLightStatusBars = !systemDark
                    isAppearanceLightNavigationBars = !systemDark
                }
                activity.window.attributes =
                    activity.window.attributes.apply { screenBrightness = -1f }
            }
        }
        BackHandler {
            when {
                drawer.isOpen -> scope.launch { drawer.close() }
                menu -> menu = false
                else -> back()
            }
        }
        val book = state.book
        if (book == null) {
            Box(
                Modifier.fillMaxSize().background(bg).safeDrawingPadding(),
                contentAlignment = Alignment.Center,
            ) {
                if (!state.loading)
                    Column(
                        Modifier.padding(LyricTokens.Space.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.LinkOff,
                            null,
                            tint = meta,
                            modifier = Modifier.size(LyricTokens.Component.smallControl),
                        )
                        Text(
                            state.error ?: "无法打开书籍",
                            color = ink,
                            modifier = Modifier.padding(vertical = LyricTokens.Space.xl),
                        )
                        Button(onClick = back, shape = LyricTokens.Radius.buttonShape) { Text("返回书架") }
                    }
            }
            return@MaterialTheme
        }
        val lengthsReady = book.deferred?.lengthsReady?.collectAsStateWithLifecycle()?.value ?: true
        ModalNavigationDrawer(
            modifier = Modifier.fillMaxSize().background(bg),
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen,
            drawerContent = {
                val locator = locatorState.value
                val currentToc = remember(book, locator) {
                    book.toc.indexOfLast { it.chapterIndex < locator.chapterIndex ||
                        it.chapterIndex == locator.chapterIndex && (book.chapters[it.chapterIndex].anchors[it.fragment] ?: it.charOffset) <= locator.charOffset }.coerceAtLeast(0)
                }
                val tocScroll = rememberLazyListState()
                LaunchedEffect(drawer.isOpen) {
                    if (drawer.isOpen && drawerTab == 0) tocScroll.scrollToItem(currentToc)
                }
                ModalDrawerSheet(
                    modifier = Modifier.width((LocalConfiguration.current.screenWidthDp * .85f).dp.coerceAtMost(LyricTokens.Component.drawerMaxWidth))
                        .fillMaxHeight(),
                    windowInsets = WindowInsets.safeDrawing,
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                ) {
                    Column(Modifier.padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.lg)) {
                        Text(
                            book.title,
                            fontFamily = Serif,
                            style = LyricTokens.Type.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (lengthsReady) "${(book.percent(locator) * 100).toInt()}% · ${book.chapters.size} 章" else "${book.chapters.size} 章",
                            color = Stone,
                            style = LyricTokens.Type.auxiliary,
                            modifier = Modifier.padding(top = LyricTokens.Space.sm),
                        )
                        drawerExtra()
                    }
                    TabRow(selectedTabIndex = drawerTab, containerColor = Color.Transparent) {
                        listOf("目录", "书签", "划线").forEachIndexed { i, s ->
                            Tab(selected = drawerTab == i, onClick = { drawerTab = i }, text = { Text(s) })
                        }
                    }
                    val goClose: (Locator) -> Unit = {
                        vm.goTo(it)
                        scope.launch { drawer.close() }
                        menu = false
                    }
                    when (drawerTab) {
                        0 -> TocPanel(book, locator, currentToc, tocScroll, goClose)
                        1 -> BookmarkPanel(marks, { goClose(Locator(it.chapterIndex, it.charOffset, it.page)) },
                            { noteEdit = Triple(false, it.id, it.note) }, { vm.deleteBookmark(it.id) })
                        else -> HighlightPanel(highlightRows, { goClose(Locator(it.chapterIndex, it.start)) },
                            { noteEdit = Triple(true, it.id, it.note) }, { vm.deleteHighlight(it.id) })
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { pageOrigin = it.positionInRoot() }
                .pointerInteropFilter { event ->
                    val view = canvas
                    if (prefs.pageMode != PageMode.CURL || controls || view == null) false
                    else {
                        val local = android.view.MotionEvent.obtain(event)
                        local.offsetLocation(-bodyOrigin.x, -bodyOrigin.y)
                        try { view.onTouchEvent(local) } finally { local.recycle() }
                    }
                }) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    // Capture only curl surfaces. Once both snapshots exist the overlay owns
                    // the animation; recording the underlying reader again is wasted work.
                    if (prefs.pageMode == PageMode.CURL && curlFrame?.target == null) {
                        pageLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(pageLayer)
                    } else drawContent()
                }.background(bg).paperGrain(com.lyric.core.ThemePalette.isNight(theme, prefs.customBg))) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                    ).padding(top = LyricTokens.Space.sm, bottom = LyricTokens.Space.sm)) {
                        ReaderChapterHeading(book, prefs, meta, visibleLocator)
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            key(System.identityHashCode(book)) { AndroidView(
                                factory = { context -> ReaderCanvas(context).also { view ->
                                    canvas = view
                                    view.onWindowReady = { onFirstReady(null) }
                                    view.onPrepareChapter = { at, ready -> vm.prepareChapter(at, ready) }
                                    view.onExcerpt = { at, text -> vm.bookmark(at, text) }
                                    view.onHighlightEdit = { hid, action, x, y ->
                                        val row = view.highlights.firstOrNull { it.id == hid }
                                        when (action) {
                                            "color" -> vm.highlightColor(hid, x)
                                            "range" -> row?.let { vm.highlightRange(hid, it.chapterIndex, x, y) }
                                            "note" -> noteEdit = Triple(true, hid, row?.note.orEmpty())
                                            "delete" -> vm.deleteHighlight(hid)
                                        }
                                    }
                                    view.onHighlight = { ch, start, end, text, withNote ->
                                        vm.addHighlight(ch, start, end, text) { if (withNote) noteEdit = Triple(true, it, "") }
                                    }
                                    view.onImage = { enlargedImage = it }
                                    view.onLocator = vm::readingActivity
                                    view.onEndReached = vm::finished
                                    view.onReadingActivity = vm::readingGesture
                                    view.onMenu = { menu = !menu }
                                    view.onPageInfo = { pageInfo.value = it }
                                    view.onVisibleLocator = { visibleLocator.value = it }
                                    view.onCurl = { direction, dragging, x, y ->
                                        val point = Offset(x + bodyOrigin.x, y + bodyOrigin.y)
                                        val frame = CurlFrame(direction, point.y < pageLayer.size.height / 2f,
                                            point, automatic = !dragging)
                                        curlFrame = frame
                                        curlCapture?.cancel()
                                        curlCapture = scope.launch {
                                            try {
                                                frame.current = com.lyric.reader.pageSnapshot { pageLayer.toImageBitmap().asAndroidBitmap() }
                                                if (curlFrame === frame) {
                                                    if (frame.current == null) view.finishCurlWithoutAnimation()
                                                    else view.prepareCurlTarget()
                                                }
                                            } catch (e: Exception) {
                                                if (e is kotlinx.coroutines.CancellationException) throw e
                                                android.util.Log.e("LyricCurl", "页面截图失败", e)
                                                view.abortCurl()
                                                vm.notice.value = "翻页截图失败：${e.javaClass.simpleName}: ${e.message}"
                                            }
                                        }
                                }
                                view.onCurlMove = { x, y -> curlFrame?.touch = Offset(x + bodyOrigin.x, y + bodyOrigin.y) }
                                view.onCurlRelease = { x, velocity ->
                                    curlFrame?.let { frame ->
                                        frame.release = !velocity.isNaN() && curlShouldComplete(frame.direction,
                                            x + bodyOrigin.x, pageLayer.size.width.toFloat(), velocity)
                                    }
                                }
                                view.onCurlReady = {
                                    val frame = curlFrame
                                    if (frame != null && frame.target == null) {
                                        curlCapture?.cancel()
                                        curlCapture = scope.launch {
                                            try {
                                                // Let Compose record the destination chapter title and footer too.
                                                withFrameNanos { }; withFrameNanos { }
                                                if (curlFrame === frame) {
                                                    frame.target = com.lyric.reader.pageSnapshot { pageLayer.toImageBitmap().asAndroidBitmap() }
                                                    if (frame.target == null) view.finishCurlWithoutAnimation()
                                                }
                                            } catch (e: Exception) {
                                                if (e is kotlinx.coroutines.CancellationException) throw e
                                                android.util.Log.e("LyricCurl", "页面截图失败", e)
                                                view.abortCurl()
                                                vm.notice.value = "翻页截图失败：${e.message}"
                                            }
                                        }
                                    }
                                }
                                view.onCurlCancel = {
                                    curlCapture?.cancel()
                                    curlFrame = null
                                }
                                } },
                                update = { view ->
                                    view.controlsOpen = controls
                                    view.highlights = highlightRows
                                    view.bind(book, locatorState.value, prefs, ink.toArgb(), bg.toArgb(), highlight)
                                },
                            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                                bodyOrigin = it.positionInRoot() - pageOrigin
                            },
                        ) }
                    }
                    ReaderFooter(book, prefs, meta, darkReader, pageInfo, visibleLocator)
                }
                }
                curlFrame?.let { frame ->
                    PageCurl(frame, bg, Modifier.fillMaxSize().testTag("page-curl"), ThemePalette.curl(prefs.theme, prefs.customBg, prefs.customInk)) { commit ->
                        canvas?.finishCurl(commit) {
                            if (curlFrame === frame) curlFrame = null
                        }
                    }
                }
                enlargedImage?.let { path ->
                    Dialog(onDismissRequest = { enlargedImage = null },
                        properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        var zoom by remember(path) { mutableFloatStateOf(1f) }
                        var dx by remember(path) { mutableFloatStateOf(0f) }
                        var dy by remember(path) { mutableFloatStateOf(0f) }
                        var data by remember(path) { mutableStateOf<ByteArray?>(null) }
                        LaunchedEffect(path) {
                            data = withContext(Dispatchers.Default) {
                                runCatching { java.util.Base64.getDecoder().decode(book.resources[path]) }.getOrNull()
                            }
                        }
                        Box(Modifier.fillMaxSize().background(LyricTokens.Overlay.black).safeDrawingPadding()) {
                            val request = remember(data, path) {
                                coil.request.ImageRequest.Builder(activity).data(data).apply {
                                    if (path.endsWith(".svg", true)) decoderFactory(coil.decode.SvgDecoder.Factory())
                                }.build()
                            }
                            AsyncImage(request, "书中插图", contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().pointerInput(path) {
                                    detectTransformGestures { _, pan, scale, _ ->
                                        zoom = (zoom * scale).coerceIn(1f, 5f)
                                        dx += pan.x; dy += pan.y
                                    }
                                }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = dx; translationY = dy })
                            IconButton(onClick = { enlargedImage = null }, Modifier.align(Alignment.TopEnd).statusBarsPadding()) {
                                Icon(Icons.Outlined.Close, "关闭大图", tint = LyricTokens.Overlay.white)
                            }
                        }
                    }
                }
                // 0.5.3: any tap outside the top/bottom panels closes the menu; the tap is consumed
                // (no page turn, no selection) because this layer sits above the page and below the panels.
                if (menu) Box(Modifier.fillMaxSize().semantics { contentDescription = "关闭阅读菜单" }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do { val e = awaitPointerEvent(); e.changes.forEach { it.consume() } } while (e.changes.any { it.pressed })
                            menu = false
                        }
                    })
                AnimatedVisibility(visible = menu, enter = fadeIn(LyricTokens.Motion.short()),
                    exit = fadeOut(LyricTokens.Motion.short()), modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
                    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = LyricTokens.Component.readerToolbarElevation) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回书架") }
                            Text(
                                book.title,
                                fontFamily = Serif,
                                style = LyricTokens.Type.subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { vm.bookmark() }) {
                                Icon(Icons.Outlined.BookmarkAdd, "添加书签")
                            }
                            IconButton(onClick = { search = true }) {
                                Icon(Icons.Outlined.Search, "全文搜索")
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = menu,
                    enter = slideInVertically(LyricTokens.Motion.long()) { it } + fadeIn(LyricTokens.Motion.short()),
                    exit = slideOutVertically(LyricTokens.Motion.long()) { it } + fadeOut(LyricTokens.Motion.short()),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape =
                            androidx.compose.foundation.shape.RoundedCornerShape(
                                topStart = LyricTokens.Radius.card,
                                topEnd = LyricTokens.Radius.card,
                            ),
                        shadowElevation = LyricTokens.Component.readerSheetElevation,
                    ) {
                        val locator = locatorState.value
                        Column(
                            Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .7f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.md)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        vm.goTo(
                                            Locator((locator.chapterIndex - 1).coerceAtLeast(0))
                                        )
                                    },
                                    shape = LyricTokens.Radius.buttonShape,
                                ) {
                                    Text("上一章", style = LyricTokens.Type.auxiliary)
                                }
                                Text(
                                    "${locator.chapterIndex + 1} / ${book.chapters.size}",
                                    style = LyricTokens.Type.auxiliary,
                                    color = Stone,
                                    modifier = Modifier.weight(1f),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                TextButton(
                                    onClick = {
                                        vm.goTo(
                                            Locator(
                                                (locator.chapterIndex + 1).coerceAtMost(
                                                    book.chapters.lastIndex
                                                )
                                            )
                                        )
                                    },
                                    shape = LyricTokens.Radius.buttonShape,
                                ) {
                                    Text("下一章", style = LyricTokens.Type.auxiliary)
                                }
                            }
                            var slider by
                                remember(locator, lengthsReady) { mutableFloatStateOf(book.percent(locator)) }
                            Slider(
                                enabled = lengthsReady,
                                value = slider,
                                onValueChange = { slider = it },
                                onValueChangeFinished = {
                                    var chars = (slider * book.totalChars).toLong()
                                    var c = 0
                                    while (
                                        c < book.chapters.lastIndex &&
                                            chars >= book.chapterLength(c)
                                    ) {
                                        chars -= book.chapterLength(c)
                                        c++
                                    }
                                    vm.goTo(Locator(c, chars.toInt()))
                                    menu = false
                                },
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround,
                            ) {
                                ReaderTool(Icons.Outlined.FormatListBulleted, "目录") {
                                    drawerTab = 0
                                    scope.launch { drawer.open() }
                                }
                                ReaderTool(Icons.Outlined.Bookmarks, "书签") {
                                    drawerTab = 1
                                    scope.launch { drawer.open() }
                                }
                                ReaderTool(Icons.Outlined.DarkMode, "夜间") {
                                    vm.updateSettings(
                                        SettingsScope.BOOK,
                                        prefs.copy(
                                            theme =
                                                if (theme == ReaderTheme.NIGHT) ReaderTheme.PAPER
                                                else ReaderTheme.NIGHT,
                                            followSystem = false,
                                        ),
                                    )
                                }
                                ReaderTool(Icons.Outlined.TextFields, "排版") {
                                    menu = false
                                    settings = true
                                }
                            }
                        }
                    }
                }
                SnackbarHost(
                    snack,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                )
                if (prefs.dim > 0f)
                    Box(
                        Modifier.fillMaxSize()
                            .background(LyricTokens.Overlay.black.copy(alpha = prefs.dim.coerceIn(0f, .85f)))
                    )
            }
        }
        if (settings)
            ReaderSettingsSheet(
                vm,
                onDismiss = { settings = false },
                onToc = {
                    settings = false
                    drawerTab = 0
                    scope.launch { drawer.open() }
                },
            )
        if (search)
            SearchDialog(vm) {
                search = false
                menu = false
            }
    }
}

@Composable
private fun ReaderChapterHeading(book: Book, prefs: ReaderSettings, meta: Color, visible: State<Locator>) {
    if (prefs.showChapter) Text(
        book.chapters[visible.value.chapterIndex.coerceIn(book.chapters.indices)].title,
        color = meta, fontFamily = Serif, style = LyricTokens.Type.auxiliary,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = prefs.margin.dp).padding(bottom = LyricTokens.Space.sm),
    )
}

@Composable
private fun ReaderFooter(book: Book, prefs: ReaderSettings, meta: Color, night: Boolean,
                         pageInfo: State<String>, visible: State<Locator>) {
    val lengthsReady = book.deferred?.lengthsReady?.collectAsStateWithLifecycle()?.value ?: true
    Row(Modifier.fillMaxWidth().padding(horizontal = prefs.margin.dp).padding(top = LyricTokens.Space.md),
        horizontalArrangement = Arrangement.SpaceBetween) {
        val infoStyle = LyricTokens.Type.auxiliary
        if (prefs.showPage) Text(pageInfo.value, color = meta, style = infoStyle)
        if (prefs.showPercent) {
            val at = visible.value
            ReaderProgressLabel(
                lengthsReady = lengthsReady,
                chapterIndex = at.chapterIndex,
                fraction = if (lengthsReady) book.percent(at) else 0f,
                night = night,
                percentColor = meta,
                style = infoStyle,
                chapterCount = book.chapters.size,
            )
        }
        if (prefs.showTime || prefs.showBattery) {
            val (clock, battery) = readerClockAndBattery()
            if (prefs.showTime) Text(clock, color = meta, style = infoStyle)
            if (prefs.showBattery) Text(battery, color = meta, style = infoStyle)
        }
    }
}

@Composable
private fun ReaderTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    click: () -> Unit,
) {
    Column(
        Modifier.clickable(onClick = click).padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(LyricTokens.Component.toolbarIcon))
        Text(label, style = LyricTokens.Type.auxiliary, modifier = Modifier.padding(top = LyricTokens.Space.xs))
    }
}

@Composable
private fun SearchDialog(vm: ReaderViewModel, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf(vm.highlight.value) }
    val hits by vm.hits.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()
    Dialog(
        onDismissRequest = dismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = LyricTokens.Space.sm, vertical = LyricTokens.Space.md),
                ) {
                    IconButton(onClick = dismiss) { Icon(Icons.Outlined.ArrowBack, "返回阅读") }
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            vm.search(it)
                        },
                        label = { Text("搜索书中文字") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (query.isNotEmpty())
                                IconButton(
                                    onClick = {
                                        query = ""
                                        vm.search("")
                                    }
                                ) {
                                    Icon(Icons.Outlined.Close, "清空搜索")
                                }
                        },
                    )
                }
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (query.isBlank()) "输入关键词，在字句之间寻找。"
                    else "找到 ${hits.size} 处${if (searching) " · 搜索中" else ""}",
                    style = LyricTokens.Type.auxiliary,
                    color = Stone,
                    modifier = Modifier.padding(LyricTokens.Space.lg),
                )
                LazyColumn {
                    items(hits, key = { "${it.chapterIndex}:${it.charOffset}" }) { hit ->
                        Column(
                            Modifier.fillMaxWidth()
                                .semantics { contentDescription = "搜索结果" }
                                .drawWithContent { drawContent(); com.lyric.reader.ReaderMetrics.endSearch() }
                                .clickable {
                                    vm.goTo(Locator(hit.chapterIndex, hit.charOffset))
                                    dismiss()
                                }
                                .padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.lg)
                        ) {
                            Text(hit.chapterTitle, style = LyricTokens.Type.auxiliary, color = Stone)
                            val context =
                                androidx.compose.ui.text.buildAnnotatedString {
                                    append(hit.context)
                                    var at = hit.context.indexOf(query, ignoreCase = true)
                                    while (at >= 0 && query.isNotEmpty()) {
                                        addStyle(
                                            androidx.compose.ui.text.SpanStyle(
                                                background = Vermilion.copy(alpha = .26f),
                                            ),
                                            at,
                                            at + query.length,
                                        )
                                        at = hit.context.indexOf(query, at + query.length, true)
                                    }
                                }
                            Text(
                                context,
                                fontFamily = Serif,
                                style = LyricTokens.Type.body,
                                modifier = Modifier.padding(top = LyricTokens.Space.xs),
                            )
                        }
                        HorizontalDivider(
                            Modifier.padding(horizontal = LyricTokens.Page.margin),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}
