@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.lyric.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun ReaderScreen(vm: ReaderViewModel, back: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    state.book?.takeIf { it.contentType == ContentType.COMIC }?.let { comic ->
        ComicReaderScreen(vm, comic, back)
        return
    }
    // Comics never flash the paper colour while loading.
    if (state.book == null && state.row?.contentType == ContentType.COMIC.name) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF111111)))
        return
    }
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val locator by vm.locator.collectAsStateWithLifecycle()
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    val highlightRows by vm.highlights.collectAsStateWithLifecycle()
    var noteEdit by remember { mutableStateOf<Triple<Boolean, Long, String>?>(null) }
    noteEdit?.let { (isHighlight, rowId, old) ->
        NoteDialog(if (isHighlight) "想法" else "书签备注", old,
            { if (isHighlight) vm.highlightNote(rowId, it) else vm.bookmarkNote(rowId, it) }, { noteEdit = null })
    }
    val highlight by vm.highlight.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var pageInfo by remember { mutableStateOf("") }
    var canvas by remember { mutableStateOf<ReaderCanvas?>(null) }
    var visibleLocator by remember(state.book) { mutableStateOf(locator) }
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
    val (clock, battery) = readerClockAndBattery()
    var drawerTab by rememberSaveable { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    DisposableEffect(Unit) {
        onDispose { curlCapture?.cancel(); canvas?.abortCurl(); curlFrame = null }
    }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.resume()
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                curlCapture?.cancel()
                canvas?.abortCurl()
                curlFrame = null
                vm.pause()
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) vm.resume()
        onDispose {
            vm.pause()
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
    val accent = MaterialTheme.colorScheme.primary
    MaterialTheme(colorScheme = (if (darkReader) DarkColors else LightColors).copy(primary = accent)) {
        val activity = LocalContext.current as Activity
        val controls = menu || settings || search || drawer.targetValue == DrawerValue.Open || drawer.isOpen || enlargedImage != null
        DisposableEffect(activity) {
            onDispose { (activity as? ReaderActivity)?.readerVolumeHandler = null }
        }
        SideEffect {
            (activity as? ReaderActivity)?.readerVolumeHandler = { event ->
                val step = VolumeNavigation.step(event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN,
                    prefs.volumeKeys, prefs.swapVolumeKeys, controls || canvas?.selectionMenuOpen == true)
                if (step == null || canvas == null) false else {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) canvas?.turn(step)
                    true
                }
            }
            val controller =
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (controls) controller.show(WindowInsetsCompat.Type.systemBars())
            else controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = !darkReader
            controller.isAppearanceLightNavigationBars = !darkReader
            activity.window.attributes =
                activity.window.attributes.apply {
                    screenBrightness = prefs.brightness.coerceIn(.01f, 1f)
                }
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
                if (state.loading) PaperSkeleton(bg, ink, prefs.margin)
                else
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.LinkOff,
                            null,
                            tint = meta,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            state.error ?: "无法打开书籍",
                            color = ink,
                            lineHeight = 24.sp,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                        Button(onClick = back) { Text("返回书架") }
                    }
            }
            return@MaterialTheme
        }
        val currentToc =
            remember(book, locator) {
                book.toc
                    .indexOfLast {
                        it.chapterIndex < locator.chapterIndex ||
                            it.chapterIndex == locator.chapterIndex &&
                                it.charOffset <= locator.charOffset
                    }
                    .coerceAtLeast(0)
            }
        val tocScroll = rememberLazyListState()
        LaunchedEffect(drawer.isOpen) {
            if (drawer.isOpen && drawerTab == 0) tocScroll.scrollToItem(currentToc)
        }
        ModalNavigationDrawer(
            modifier = Modifier.fillMaxSize().background(bg),
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width((LocalConfiguration.current.screenWidthDp * .85f).coerceAtMost(360f).dp)
                        .fillMaxHeight(),
                    windowInsets = WindowInsets.safeDrawing,
                    drawerContainerColor = MaterialTheme.colorScheme.background,
                ) {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                        Text(
                            book.title,
                            fontFamily = Serif,
                            fontSize = 23.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${(book.percent(locator) * 100).toInt()}% · ${book.chapters.size} 章",
                            color = Stone,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
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
                    pageLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(pageLayer)
                }.background(bg).paperGrain()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(
                        WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                    ).padding(top = 8.dp, bottom = 8.dp)) {
                        if (prefs.showChapter) Text(
                            book.chapters[visibleLocator.chapterIndex.coerceIn(book.chapters.indices)].title,
                            color = meta,
                            fontFamily = Serif,
                            fontSize = (prefs.fontSize * .7f).sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.padding(horizontal = prefs.margin.dp).padding(bottom = 8.dp),
                        )
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            AndroidView(
                                factory = { context -> ReaderCanvas(context).also { canvas = it } },
                                update = { view ->
                                    view.onContentReady = { activity.reportFullyDrawn() }
                                    view.controlsOpen = controls
                                    view.onExcerpt = { at, text -> vm.bookmark(at, text) }
                                    view.highlights = highlightRows
                                    view.onHighlight = { ch, start, end, text, withNote ->
                                        vm.addHighlight(ch, start, end, text) { if (withNote) noteEdit = Triple(true, it, "") }
                                    }
                                    view.onImage = { enlargedImage = it }
                                    view.onLocator = vm::readingActivity
                                    view.onEndReached = vm::finished
                                    view.onReadingActivity = vm::readingGesture
                                    view.onMenu = { menu = !menu }
                                    view.onPageInfo = { pageInfo = it }
                                    view.onVisibleLocator = { visibleLocator = it }
                                    view.onCurl = { direction, dragging, x, y ->
                                        val point = Offset(x + bodyOrigin.x, y + bodyOrigin.y)
                                        val frame = CurlFrame(direction, point.y < pageLayer.size.height / 2f,
                                            point, automatic = !dragging)
                                        curlFrame = frame
                                        curlCapture?.cancel()
                                        curlCapture = scope.launch {
                                            try {
                                                frame.current = pageLayer.toImageBitmap().asAndroidBitmap()
                                                if (curlFrame === frame) view.prepareCurlTarget()
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
                                                if (curlFrame === frame) frame.target = pageLayer.toImageBitmap().asAndroidBitmap()
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
                                view.bind(book, locator, prefs, ink.toArgb(), bg.toArgb(), highlight)
                            },
                            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                                bodyOrigin = it.positionInRoot() - pageOrigin
                            },
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = prefs.margin.dp)
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        val infoSize = (prefs.fontSize * .7f).sp
                        if (prefs.showPage) Text(pageInfo, color = meta, fontSize = infoSize)
                        if (prefs.showPercent) Text(readingPercentLabel(book.percent(visibleLocator)), color = meta, fontSize = infoSize)
                        if (prefs.showTime) Text(clock, color = meta, fontSize = infoSize)
                        if (prefs.showBattery) Text(battery, color = meta, fontSize = infoSize)
                    }
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
                        Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
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
                                Icon(Icons.Outlined.Close, "关闭大图", tint = Color.White)
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = menu, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
                    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回书架") }
                            Text(
                                book.title,
                                fontFamily = Serif,
                                fontSize = 17.sp,
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
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape =
                            androidx.compose.foundation.shape.RoundedCornerShape(
                                topStart = 24.dp,
                                topEnd = 24.dp,
                            ),
                        shadowElevation = 6.dp,
                    ) {
                        Column(
                            Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * .7f).dp)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        vm.goTo(
                                            Locator((locator.chapterIndex - 1).coerceAtLeast(0))
                                        )
                                    }
                                ) {
                                    Text("上一章", fontSize = 12.sp)
                                }
                                Text(
                                    "${locator.chapterIndex + 1} / ${book.chapters.size}",
                                    fontSize = 12.sp,
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
                                    }
                                ) {
                                    Text("下一章", fontSize = 12.sp)
                                }
                            }
                            var slider by
                                remember(locator) { mutableFloatStateOf(book.percent(locator)) }
                            Slider(
                                value = slider,
                                onValueChange = { slider = it },
                                onValueChangeFinished = {
                                    var chars = (slider * book.totalChars).toLong()
                                    var c = 0
                                    while (
                                        c < book.chapters.lastIndex &&
                                            chars >= book.chapters[c].text.length
                                    ) {
                                        chars -= book.chapters[c].text.length
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
                            .background(Color.Black.copy(alpha = prefs.dim.coerceIn(0f, .85f)))
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
private fun ReaderTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    click: () -> Unit,
) {
    Column(
        Modifier.clickable(onClick = click).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, Modifier.size(22.dp))
        Text(label, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
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
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
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
                    fontSize = 12.sp,
                    color = Stone,
                    modifier = Modifier.padding(20.dp),
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
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            Text(hit.chapterTitle, fontSize = 11.sp, color = Stone)
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
                                fontSize = 15.sp,
                                lineHeight = 25.sp,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        HorizontalDivider(
                            Modifier.padding(horizontal = 24.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }
    }
}
