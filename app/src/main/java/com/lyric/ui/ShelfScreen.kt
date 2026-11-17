@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.lyric.core.Book
import com.lyric.core.BookFormats
import com.lyric.core.ContentType
import com.lyric.core.Locator
import com.lyric.core.clamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.request.ImageRequest
import coil.size.Precision
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.lyric.data.*
import java.io.File
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.Collator
import java.util.Locale

@Composable
fun ShelfScreen(vm: ShelfViewModel, me: @Composable () -> Unit, desk: @Composable () -> Unit, open: (String) -> Unit) {
    val savedSection by vm.section.collectAsStateWithLifecycle()
    val initialSection = savedSection
    if (initialSection == null) {
        Box(Modifier.fillMaxSize().background(Paper))
        return
    }
    val transition = remember { ShelfTransitionState(initialSection) }
    val section = transition.target
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val switch: (ShelfSection) -> Unit = { next ->
        if (transition.start(next, reducedShelfMotion(context))) {
            focus.clearFocus()
            vm.section(next)
        }
    }
    BackHandler(transition.running) { /* Ignore back as well as taps during the transition. */ }
    val books by vm.books.collectAsStateWithLifecycle()
    val status by vm.imports.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var consumedImportBatch by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(status.running, status.batchId, status.message) {
        val text = status.message
        val batch = status.batchId
        if (status.running || text.isBlank() || status.folder ||
            text == BookFormats.UNSUPPORTED_OPEN_WITH || batch == 0L || batch == consumedImportBatch) return@LaunchedEffect
        consumedImportBatch = batch
        try {
            snackbar.showTimedSnackbar(text)
        } finally {
            vm.consumeImportMessage(batch)
        }
    }
    LaunchedEffect(status.running, status.batchId, status.openBookId) {
        if (status.running) return@LaunchedEffect
        vm.consumeOpenBook()?.let(open)
    }
    val clipboard = LocalClipboardManager.current
    var dismissedErrorBatch by remember { mutableLongStateOf(-1L) }
    if (!transition.running && !status.running && status.errors.isNotEmpty() && status.batchId != dismissedErrorBatch) {
        AlertDialog(
            onDismissRequest = { dismissedErrorBatch = status.batchId },
            title = { Text("导入失败") },
            text = { Text(status.summary + "\n\n" + status.errors.joinToString("\n\n"), modifier = Modifier.heightIn(max = LyricTokens.Component.dialogMaxHeight).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                clipboard.setText(AnnotatedString(status.errors.joinToString("\n\n")))
            }, shape = LyricTokens.Radius.buttonShape) { Text("复制错误信息") } },
            dismissButton = { TextButton(onClick = { dismissedErrorBatch = status.batchId }, shape = LyricTokens.Radius.buttonShape) { Text("关闭") } },
        )
    }
    val grid by vm.grid.collectAsStateWithLifecycle()
    var summaryShown by rememberSaveable { mutableLongStateOf(-1L) }
    if (!transition.running && !status.running && status.folder && status.batchId != 0L && status.batchId != summaryShown && status.errors.isEmpty())
        FolderImportSheet(status) { summaryShown = status.batchId }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabState = rememberSaveableStateHolder()
    var importer by remember { mutableStateOf(false) }
    var importFilter by rememberSaveable { mutableStateOf(ImportFilter.ALL) }
    fun prepareImport() { importFilter = if (section == ShelfSection.COMIC) ImportFilter.COMIC else ImportFilter.ALL }
    var actions by remember { mutableStateOf<ShelfBook?>(null) }
    var remove by remember { mutableStateOf<ShelfBook?>(null) }
    val filePicker =
        rememberLauncherForActivityResult(ImportDocuments()) {
            if (it.uris.isNotEmpty()) vm.import(it.uris, grantFlags = it.flags)
        }
    val folderPicker =
        rememberLauncherForActivityResult(ImportDocuments(tree = true)) {
            if (it.uris.isNotEmpty()) vm.import(it.uris, folder = true, grantFlags = it.flags)
        }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (transition.running) Modifier.clearAndSetSemantics { } else Modifier,
            contentWindowInsets = WindowInsets.safeDrawing,
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { TimedSnackbarHost(snackbar) },
            bottomBar = {
                val navStroke = if (deskDark()) LyricTokens.Night.stroke else MaterialTheme.colorScheme.outlineVariant
                NavigationBar(
                    windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    modifier = Modifier.drawWithContent {
                        drawContent()
                        val stroke = LyricTokens.strokeWidth.toPx()
                        drawLine(navStroke, Offset(0f, stroke / 2f), Offset(size.width, stroke / 2f), strokeWidth = stroke)
                    },
                ) {
                    val dark = deskDark()
                    val navColors = if (dark) NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = LyricTokens.Night.moss, selectedTextColor = LyricTokens.Night.moss,
                        unselectedIconColor = LyricTokens.Night.secondary, unselectedTextColor = LyricTokens.Night.secondary,
                    ) else NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent)
                    listOf(
                            section.label to if (section == ShelfSection.COMIC) Icons.Outlined.Collections else Icons.Outlined.AutoStories,
                            "案头" to DeskNavIcon,
                            "我的" to Icons.Outlined.PersonOutline,
                        )
                        .forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { if (!transition.running) tab = i },
                                icon = { Icon(icon, label, modifier = Modifier.size(LyricTokens.Component.navigationIcon)) },
                                label = { Text(label, style = LyricTokens.Type.auxiliary) },
                                colors = navColors,
                            )
                        }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).paperGrain(deskDark())) {
                tabState.SaveableStateProvider(tab) { when (tab) {
                    0 -> ShelfTransition(transition) { shown ->
                        ShelfContent(vm, books, shown, grid, status,
                            switch = switch, open = { id -> if (!transition.running) {
                                if (books.firstOrNull { it.id == id }?.invalid == true)
                                    scope.launch { snackbar.showSnackbar(FILE_ACCESS_EXPIRED_MESSAGE) }
                                else open(id)
                            } },
                            import = { if (!transition.running) { prepareImport(); importer = true } },
                            importFiles = { if (!transition.running) { prepareImport(); filePicker.launch(importFilter) } },
                            actions = { if (!transition.running) actions = it })
                    }
                    1 -> desk()
                    2 -> me()
                } }
            }
        }
        if (transition.running) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    e.changes.forEach { it.consume() }
                    // 0.6.2: a tap skips to the end state (finish() is idempotent: no stacking).
                    if (e.changes.any { it.changedToDown() }) transition.skip()
                }
            }
        }.semantics { contentDescription = "分区切换中" })
    }
    if (importer)
        ModalBottomSheet(
            onDismissRequest = { importer = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = LyricTokens.Radius.cardShape,
        ) {
            Column(Modifier.padding(horizontal = LyricTokens.Space.xl).padding(bottom = LyricTokens.Space.xxl)) {
                Text(if (section == ShelfSection.COMIC) "把一部漫画带进来" else "把一本书带进来", fontFamily = Serif, style = LyricTokens.Type.title)
                Spacer(Modifier.height(LyricTokens.Component.mediumIcon))
                ImportFilterChips(importFilter) { importFilter = it }
                Spacer(Modifier.height(LyricTokens.Space.md))
                ImportOption(Icons.Outlined.InsertDriveFile, "选择文件", if (importFilter == ImportFilter.COMIC) "CBZ / ZIP / EPUB / 图片 · 支持多选" else "所有格式 · 支持多选") {
                    importer = false
                    filePicker.launch(importFilter)
                }
                Spacer(Modifier.height(LyricTokens.Space.md))
                ImportOption(Icons.Outlined.FolderOpen, "选择文件夹", "扫描子文件夹，自动跳过重复书籍") {
                    importer = false
                    folderPicker.launch(importFilter)
                }
            }
        }
    actions?.let { book ->
        ModalBottomSheet(onDismissRequest = { actions = null }, shape = LyricTokens.Radius.cardShape) {
            Column(Modifier.fillMaxWidth().padding(LyricTokens.Space.xl)) {
                Text(book.title, fontFamily = Serif, style = LyricTokens.Type.title)
                TextButton(onClick = { vm.move(book); actions = null }, shape = LyricTokens.Radius.buttonShape) {
                    Text("移到${ShelfSection.valueOf(book.section).other().label}")
                }
                TextButton(onClick = { remove = book; actions = null }, shape = LyricTokens.Radius.buttonShape) { Text("移出收藏", color = Vermilion) }
            }
        }
    }
    status.scannedPdfs.firstOrNull()?.takeIf { !status.running }?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { vm.scannedToComic(id, false) },
            title = { Text("像是扫描版 PDF") },
            text = { Text("《$title》的页面都是图片，放到画架按页阅读会更合适。要移到画架吗？") },
            confirmButton = { TextButton(onClick = { vm.scannedToComic(id, true) }, shape = LyricTokens.Radius.buttonShape) { Text("移到画架") } },
            dismissButton = { TextButton(onClick = { vm.scannedToComic(id, false) }, shape = LyricTokens.Radius.buttonShape) { Text("留在书架") } },
        )
    }
    remove?.let { book ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("移出收藏") },
            text = { Text("将《${book.title}》移出收藏？进度、阅读足迹、书签、划线与原文件保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.remove(book.id)
                        remove = null
                    },
                    shape = LyricTokens.Radius.buttonShape,
                ) {
                    Text("移出", color = Vermilion)
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }, shape = LyricTokens.Radius.buttonShape) { Text("保留") } },
        )
    }
}

@Composable
private fun ShelfContent(
    vm: ShelfViewModel, allBooks: List<ShelfBook>, section: ShelfSection, grid: Boolean,
    status: ImportStatus, switch: (ShelfSection) -> Unit,
    open: (String) -> Unit, import: () -> Unit, importFiles: () -> Unit, actions: (ShelfBook) -> Unit,
) {
    val sortMode by (if (section == ShelfSection.COMIC) vm.comicSortMode else vm.sortMode).collectAsStateWithLifecycle()
    val books = remember(allBooks, section) { allBooks.filter { it.section == section.name } }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val visible =
        remember(books, sortMode, filter, query) {
            val list =
                when (val label = filterLabels(section).getOrNull(filter)) {
                    null, "全部" -> books
                    "最近" -> books.filter { it.lastRead > 0 }
                    "MOBI" -> books.filter { it.extension == "mobi" || it.extension == "azw3" }
                    else -> books.filter { it.extension == label.lowercase() }
                }
            sortShelf(filterShelf(list, query), sortMode)
        }
    val empty = books.isEmpty()
    val continuing =
        continueReadingBook(books)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val emptyAnchor = maxHeight * LyricTokens.emptyShelfHeightFraction
        LazyVerticalGrid(
            columns = shelfGridCells(grid),
            contentPadding = PaddingValues(start = LyricTokens.Page.margin, end = LyricTokens.Page.margin, bottom = LyricTokens.Space.xl),
            horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.lg),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Column {
                    Spacer(Modifier.height(LyricTokens.Space.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "lyric",
                            style = LyricTokens.Type.wordmark,
                        )
                        Text(
                            " · ${section.label}",
                            fontFamily = Serif,
                            style = LyricTokens.Type.title,
                        )
                        Spacer(Modifier.weight(1f))
                        ShelfSectionSwitch(section, switch)
                        IconButton(onClick = import, modifier = Modifier.size(LyricTokens.Component.searchHeight)) {
                            Icon(Icons.Outlined.Add, LyricTokens.Shelf.addLabel)
                        }
                    }
                    if (empty) {
                        val copy = if (section == ShelfSection.COMIC) LyricTokens.Shelf.emptyComicCopy else LyricTokens.Shelf.emptyCopy
                        val label = if (section == ShelfSection.COMIC) LyricTokens.Shelf.emptyComicImportLabel else LyricTokens.Shelf.emptyImportLabel
                        EmptyShelf(emptyAnchor, copy, label, importFiles)
                    } else {
                    Text(
                        if (section == ShelfSection.COMIC) "本地珍藏 ${books.size} 部 · 一格一格画" else "本地珍藏 ${books.size} 本 · 一页一行诗",
                        style = LyricTokens.Type.auxiliary,

                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(LyricTokens.Component.toolbarIcon))
                    if (continuing != null) {
                        ContinueCard(continuing, vm.repository) { open(continuing.id) }
                        Spacer(Modifier.height(LyricTokens.Space.xl))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        filterLabels(section).forEachIndexed { index, label
                            ->
                            Column(
                                Modifier.padding(end = LyricTokens.Space.lg).clickable {
                                    filter = index
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    label,
                                    style = LyricTokens.Type.body,
                                    color =
                                        if (filter == index)
                                            MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(LyricTokens.Component.statusDot))
                                Box(
                                    Modifier.width(LyricTokens.Component.toolbarIcon)
                                        .height(LyricTokens.Component.indicatorStroke)
                                        .background(
                                            if (filter == index) Vermilion
                                            else Color.Transparent
                                        )
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = { vm.grid(!grid) },
                            modifier = Modifier.size(LyricTokens.Component.smallControl),
                        ) {
                            Icon(
                                if (grid) Icons.Outlined.ViewList
                                else Icons.Outlined.GridView,
                                if (grid) "切换列表" else "切换网格",
                                Modifier.size(LyricTokens.Component.mediumIcon),
                            )
                        }
                    }
                    ShelfSearchBox(query, section) { query = it }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.sm),
                        verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs),
                    ) {
                        ShelfSortChips(sortMode) { vm.sort(it, section) }
                        Text(
                            if (section == ShelfSection.COMIC) "${visible.size} 部漫画" else "${visible.size} 本书",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = LyricTokens.Type.auxiliary,
                        )
                    }
                    if (status.running) ImportProgress(status) { vm.cancelImport() }
                    }
                }
            }
            items(
                visible,
                key = { it.id },
                contentType = { if (grid) "book_grid" else "book_list" },
            ) { book ->
                if (grid) BookTile(book, { open(book.id) }, { actions(book) })
                else BookListRow(book, { open(book.id) }, { actions(book) })
            }
            if (!empty) item(contentType = "import") {
                if (grid)
                    Column(Modifier.clickable { import() }) {
                        Box(
                            Modifier.fillMaxWidth()
                                .aspectRatio(.74f)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(LyricTokens.Radius.cover),
                                )
                                .border(
                                    LyricTokens.strokeWidth,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(LyricTokens.Radius.cover),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                "导入",
                                tint = Stone,
                                modifier = Modifier.size(LyricTokens.Component.switchWidth),
                            )
                        }
                        Text(
                            "导入",
                            style = LyricTokens.Type.auxiliary,
                            modifier = Modifier.padding(top = LyricTokens.Space.xs),
                        )
                        Text(
                            if (section == ShelfSection.COMIC) "CBZ / EPUB / 图片" else "TXT / EPUB / PDF / DOCX / MOBI / AZW3",
                            style = LyricTokens.Type.auxiliary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else
                    OutlinedButton(
                        onClick = { import() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = LyricTokens.Radius.buttonShape,
                    ) {
                        Text("＋ 导入本地书籍")
                    }
            }
        }
    }
}

@Composable
fun BookCover(book: ShelfBook, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(LyricTokens.Radius.cover))
            .background(
                LyricTokens.Accents.cover(book.title),
                RoundedCornerShape(LyricTokens.Radius.cover),
            )
            .paperGrain(deskDark()),
        contentAlignment = Alignment.Center,
    ) {
        if (book.coverPath != null) {
            val context = LocalContext.current
            var dimensions by remember { mutableStateOf(IntSize.Zero) }
            val size = dimensions
            val request = remember(context, book.coverPath, book.contentRevision, book.addedAt, size) {
                if (size.width == 0 || size.height == 0) null else
                ImageRequest.Builder(context)
                    .data(File(book.coverPath))
                    .size(size.width, size.height)
                    .precision(Precision.INEXACT)
                    .crossfade(false)
                    .memoryCacheKey("cover:${book.coverPath}:${book.contentRevision}:${book.addedAt}")
                    .apply {
                        if (book.coverPath.endsWith(".svg", true)) decoderFactory(coil.decode.SvgDecoder.Factory())
                    }
                    .build()
            }
            AsyncImage(
                request,
                book.title,
                modifier = Modifier.fillMaxSize().onSizeChanged { dimensions = it },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(LyricTokens.Space.xs)
                    .background(LyricTokens.Overlay.black.copy(alpha = .08f))
            )
            Text(
                book.title.take(12).map { it.toString() }.joinToString("\n"),
                fontFamily = Serif,
                style = LyricTokens.Type.body,
                color = LyricTokens.Day.background,
                maxLines = 9,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = LyricTokens.Space.md),
            )
        }
        if (book.invalid)
            Box(
                Modifier.fillMaxSize()
                    .background(LyricTokens.Overlay.black.copy(alpha = .55f), RoundedCornerShape(LyricTokens.Radius.cover)),
                contentAlignment = Alignment.Center,
            ) {
                Text("文件失效", color = LyricTokens.Day.background, style = LyricTokens.Type.auxiliary)
            }
    }
}

@Composable
private fun ContinueCard(book: ShelfBook, repository: LibraryRepository, open: () -> Unit) {
    val pages by produceState<String?>(null, book.id, book.lastRead, book.contentType) {
        value = null
        if (book.contentType == ContentType.COMIC.name) {
            try {
                val (parsed, progress) = repository.comicSummary(book.id)
                value = comicPageLabel(parsed, progress)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                value = "页码暂不可用"
            }
        }
    }
    val chapterIndex by produceState<Int?>(null, book.id, book.lastRead, book.chapterTitle, book.contentType) {
        if (book.contentType != ContentType.COMIC.name) {
            val title = book.chapterTitle.trim()
            if (title.isEmpty() || title == "未读") {
                value = try {
                    withContext(Dispatchers.IO) { repository.dao.progress(book.id)?.chapterIndex }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }
        }
    }
    val chapterLabel = continueReadingChapterLabel(book.chapterTitle, chapterIndex)
    val percentText = "${(book.percent * 100).toInt()}%"
    val percentLabel = if (chapterLabel.isEmpty()) percentText else " · $percentText"
    val comicLabel = if (book.contentType == ContentType.COMIC.name) pages ?: "正在读取页码…" else null
    Row(
        Modifier.fillMaxWidth()
            .background(CardPaper, RoundedCornerShape(LyricTokens.Radius.card))
            .border(LyricTokens.strokeWidth, Linen, RoundedCornerShape(LyricTokens.Radius.card))
            .clickable(onClick = open)
            .padding(LyricTokens.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book, Modifier.width(LyricTokens.Component.continueCoverWidth).height(LyricTokens.Component.continueCoverHeight))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = LyricTokens.Type.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (comicLabel != null) {
                Text(
                    comicLabel,
                    style = LyricTokens.Type.auxiliary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = LyricTokens.Space.xs),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = LyricTokens.Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chapterLabel,
                        style = LyricTokens.Type.auxiliary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        percentLabel,
                        style = LyricTokens.Type.auxiliary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            ContinueProgressBar(
                progress = book.percent,
                modifier = Modifier.fillMaxWidth().padding(top = LyricTokens.Space.sm),
            )
        }
    }
}

@Composable
private fun BookTile(book: ShelfBook, open: () -> Unit, remove: () -> Unit) {
    Column(Modifier.combinedClickable(onClick = open, onLongClick = remove)) {
        BookCover(book, Modifier.fillMaxWidth().aspectRatio(.74f))
        Text(
            book.title,
            style = LyricTokens.Type.auxiliary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = LyricTokens.Space.xs),
        )
        Text(
            if (book.invalid) "请重新导入"
            else if (book.lastRead == 0L) "未读" else "${(book.percent * 100).toInt()}%",
            style = LyricTokens.Type.auxiliary,
            color = if (book.invalid) Vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookListRow(book: ShelfBook, open: () -> Unit, remove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = remove),
        horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.lg),
    ) {
        BookCover(book, Modifier.width(LyricTokens.Component.toolbarHeight).height(LyricTokens.Component.listCoverHeight))
        Column(Modifier.weight(1f).padding(top = LyricTokens.Space.xs)) {
            Text(
                book.title,
                fontFamily = Serif,
                style = LyricTokens.Type.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = LyricTokens.Type.auxiliary,
                modifier = Modifier.padding(vertical = LyricTokens.Space.xs),
            )
            Text(
                if (book.invalid) "文件失效 · 重新导入可恢复"
                else
                    "${book.extension.uppercase()} · ${book.chapterCount} 章 · ${(book.percent * 100).toInt()}%",
                style = LyricTokens.Type.auxiliary,
                color = if (book.invalid) Vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun EmptyShelf(anchor: Dp, copy: String = LyricTokens.Shelf.emptyCopy, label: String = LyricTokens.Shelf.emptyImportLabel, import: () -> Unit) {
    val density = LocalDensity.current
    var top by remember { mutableFloatStateOf(0f) }
    val inset = (anchor - with(density) { top.toDp() }).coerceAtLeast(0.dp)
    Column(
        Modifier.fillMaxWidth().onGloballyPositioned { top = it.positionInParent().y }.padding(top = inset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(copy, style = LyricTokens.Type.body, color = Stone)
        Spacer(Modifier.height(LyricTokens.Space.lg))
        Button(onClick = import, shape = RoundedCornerShape(LyricTokens.Radius.button)) { Text(label) }
    }
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, subtitle: String, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(LyricTokens.Radius.card))
            .border(LyricTokens.strokeWidth, Linen, RoundedCornerShape(LyricTokens.Radius.card))
            .clickable(onClick = click)
            .padding(LyricTokens.Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.lg),
    ) {
        Icon(icon, title, Modifier.size(LyricTokens.Component.smallCoverWidth), tint = MaterialTheme.colorScheme.secondary)
        Column {
            Text(title, style = LyricTokens.Type.subtitle)
            Text(
                subtitle,
                style = LyricTokens.Type.auxiliary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = LyricTokens.Space.xs),
            )
        }
    }
}

private fun filterLabels(section: ShelfSection) =
    if (section == ShelfSection.COMIC) listOf("全部", "CBZ", "EPUB", "最近")
    else listOf("全部", "TXT", "EPUB", "PDF", "DOCX", "MOBI", "最近")

@Composable
private fun ImportProgress(status: ImportStatus, cancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (status.running) "正在导入 ${status.done} / ${status.total}" else "导入完成",
                style = LyricTokens.Type.body, modifier = Modifier.weight(1f),
            )
            if (status.running) TextButton(onClick = cancel, shape = LyricTokens.Radius.buttonShape) { Text("取消", style = LyricTokens.Type.body) }
        }
        if (status.running && status.total > 0)
            LinearProgressIndicator(
                progress = { ((status.done + status.detailFraction) / status.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.sm),
                color = Moss,
            )
        else if (status.running)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.sm))
        if (status.running && status.detail.isNotBlank())
            Text(status.detail, style = LyricTokens.Type.auxiliary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            status.message,
            style = LyricTokens.Type.auxiliary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Title/author contains, case-insensitive. */
fun filterShelf(books: List<ShelfBook>, query: String): List<ShelfBook> {
    val q = query.trim()
    if (q.isEmpty()) return books
    return books.filter { it.title.contains(q, true) || it.author.contains(q, true) || it.name.contains(q, true) }
}

/** 0 最近阅读 · 1 导入时间 · 2 书名. */
fun sortShelf(books: List<ShelfBook>, mode: Int): List<ShelfBook> = when (mode) {
    1 -> books.sortedByDescending { it.addedAt }
    2 -> books.sortedWith(compareBy(Collator.getInstance(Locale.SIMPLIFIED_CHINESE)) { it.title })
    else -> books.sortedWith(compareByDescending<ShelfBook> { it.lastRead }.thenByDescending { it.addedAt })
}

@Composable
private fun ShelfSearchBox(query: String, section: ShelfSection, change: (String) -> Unit) {
    Row(
        Modifier.padding(vertical = LyricTokens.Space.sm).fillMaxWidth().heightIn(min = LyricTokens.Component.searchHeight)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(LyricTokens.Radius.card))
            .border(LyricTokens.strokeWidth, Linen, RoundedCornerShape(LyricTokens.Radius.card))
            .padding(horizontal = LyricTokens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(LyricTokens.Component.smallIcon), tint = Stone)
        Box(Modifier.weight(1f).padding(start = LyricTokens.Space.sm)) {
            if (query.isEmpty()) Text("搜索书名或作者", style = LyricTokens.Type.body, color = Stone)
            androidx.compose.foundation.text.BasicTextField(
                query, change, singleLine = true,
                textStyle = LyricTokens.Type.body.copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Moss),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索${section.label}" },
            )
        }
        if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "清空", Modifier.size(LyricTokens.Component.smallIcon).clickable { change("") }, tint = Stone)
    }
}

@Composable
private fun ShelfSortChips(mode: Int, pick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs), verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs)) {
        listOf("最近阅读", "导入时间", "书名").forEachIndexed { i, label ->
            val on = i == mode
            Box(
                Modifier.heightIn(min = LyricTokens.Component.smallCoverWidth).background(if (on) Moss else MaterialTheme.colorScheme.surfaceVariant, LyricTokens.Radius.pill)
                    .clickable { pick(i) }.padding(horizontal = LyricTokens.Space.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = LyricTokens.Type.auxiliary, color = if (on) Paper else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderImportSheet(status: ImportStatus, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.background, shape = LyricTokens.Radius.cardShape) {
        Column(Modifier.padding(horizontal = LyricTokens.Space.xl).padding(bottom = LyricTokens.Space.xl)) {
            Text("导入完成", fontFamily = Serif, style = LyricTokens.Type.subtitle)
            Text(status.summary, style = LyricTokens.Type.body, modifier = Modifier.padding(top = LyricTokens.Space.md))
            Text("新增 ${status.added} 本", style = LyricTokens.Type.body, modifier = Modifier.padding(top = LyricTokens.Space.xs))
            Text(androidx.compose.ui.text.buildAnnotatedString {
                append("跳过重复 ")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Vermilion)); append("${status.duplicates}"); pop()
                append(" 本")
            }, style = LyricTokens.Type.body, modifier = Modifier.padding(top = LyricTokens.Space.xs))
            if (status.failures > 0) Text("失败 ${status.failures} 本", style = LyricTokens.Type.body, color = Stone, modifier = Modifier.padding(top = LyricTokens.Space.xs))
            if (status.source.isNotEmpty()) Text("来自 ${status.source}", style = LyricTokens.Type.auxiliary, color = Stone, modifier = Modifier.padding(top = LyricTokens.Space.xs))
            Button(onClick = dismiss, colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().padding(top = LyricTokens.Space.lg).heightIn(min = LyricTokens.Component.buttonHeight), shape = RoundedCornerShape(LyricTokens.Radius.button)) { Text("好的") }
        }
    }
}

@Composable
private fun ShelfSectionSwitch(section: ShelfSection, switch: (ShelfSection) -> Unit) {
    Row(
        Modifier.heightIn(min = LyricTokens.Space.xxl).background(MaterialTheme.colorScheme.surfaceVariant, LyricTokens.Radius.pill),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShelfSection.entries.forEachIndexed { index, item ->
            if (index == 1) Text("⇄", color = Stone, style = LyricTokens.Type.auxiliary)
            Box(
                Modifier.width(LyricTokens.Component.switchWidth).heightIn(min = LyricTokens.Space.xxl)
                    .background(if (section == item) Moss else Color.Transparent, LyricTokens.Radius.pill)
                    .clickable { switch(item) }
                    .semantics { contentDescription = "切换到${item.label}" },
                contentAlignment = Alignment.Center,
            ) { Text(if (item == ShelfSection.BOOK) "书" else "画", style = LyricTokens.Type.body, color = if (section == item) Paper else Stone) }
        }
    }
}

@Composable
private fun ImportFilterChips(filter: ImportFilter, change: (ImportFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.md)) {
        FilterChip(selected = filter == ImportFilter.COMIC, onClick = { change(ImportFilter.COMIC) }, label = { Text("漫画格式") }, shape = LyricTokens.Radius.pill)
        FilterChip(selected = filter == ImportFilter.ALL, onClick = { change(ImportFilter.ALL) }, label = { Text("所有格式") }, shape = LyricTokens.Radius.pill)
    }
}

internal fun continueReadingBook(books: List<ShelfBook>): ShelfBook? =
    books.filter { it.lastRead > 0 && !it.invalid }.maxByOrNull { it.lastRead }


@Composable
internal fun ContinueProgressBar(progress: Float, modifier: Modifier = Modifier) {
    val read = MaterialTheme.colorScheme.secondary
    val unread = MaterialTheme.colorScheme.outlineVariant
    val stroke = LyricTokens.Component.continueProgressStroke
    Canvas(modifier.fillMaxWidth().height(stroke)) {
        val width = size.width
        val height = size.height
        val amount = progress.coerceIn(0f, 1f)
        drawRect(unread, size = androidx.compose.ui.geometry.Size(width, height))
        if (amount > 0f) {
            drawRect(read, size = androidx.compose.ui.geometry.Size(width * amount, height))
        }
    }
}

/** Chapter label for the continue-reading card; blank or the “未读” placeholder become “第 n 章”. */
internal fun continueReadingChapterLabel(chapterTitle: String, chapterIndex: Int?): String {
    val title = chapterTitle.trim()
    if (title.isNotEmpty() && title != "未读") return title
    // Wait for the saved index so the card does not flash 「第 1 章」 on a later chapter.
    return if (chapterIndex == null) "" else "第 ${chapterIndex + 1} 章"
}

internal fun comicPageLabel(book: Book, progress: ProgressRow?): String {
    val total = book.chapters.sumOf { it.units.size }
    if (total == 0) return "暂无页面"
    val at = book.clamp(progress?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: Locator())
    val page = book.chapters.take(at.chapterIndex).sumOf { it.units.size } + at.page + 1
    return "第 ${page.coerceIn(1, total)} 页 / $total"
}

/** List mode keeps a single track; grid tracks never shrink below the shelf token. */
internal fun shelfGridCells(grid: Boolean): GridCells =
    if (grid) GridCells.Adaptive(LyricTokens.Shelf.minColumnWidth) else GridCells.Fixed(1)
