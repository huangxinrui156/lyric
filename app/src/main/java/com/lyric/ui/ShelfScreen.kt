@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.lyric.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.lyric.core.Book
import com.lyric.core.ContentType
import com.lyric.core.Locator
import com.lyric.core.clamp
import kotlinx.coroutines.CancellationException
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
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
    LaunchedEffect(status.running, status.message) {
        if (!status.running && status.message.isNotBlank() && !status.folder) snackbar.showSnackbar(status.message)
    }
    val clipboard = LocalClipboardManager.current
    var dismissedErrorBatch by remember { mutableLongStateOf(-1L) }
    if (!transition.running && !status.running && status.errors.isNotEmpty() && status.batchId != dismissedErrorBatch) {
        AlertDialog(
            onDismissRequest = { dismissedErrorBatch = status.batchId },
            title = { Text("导入失败") },
            text = { Text(status.errors.joinToString("\n\n"), modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                clipboard.setText(AnnotatedString(status.errors.joinToString("\n\n")))
            }) { Text("复制错误信息") } },
            dismissButton = { TextButton(onClick = { dismissedErrorBatch = status.batchId }) { Text("关闭") } },
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
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    modifier =
                        Modifier.border(BorderStroke(.5.dp, if (deskDark()) Color(0xFF2A2B2F) else MaterialTheme.colorScheme.outlineVariant)),
                ) {
                    val dark = deskDark()
                    val navColors = if (dark) NavigationBarItemDefaults.colors(
                        indicatorColor = Color.Transparent,
                        selectedIconColor = Color(0xFFA3B38A), selectedTextColor = Color(0xFFA3B38A),
                        unselectedIconColor = Color(0xFF7D786E), unselectedTextColor = Color(0xFF7D786E),
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
                                icon = { Icon(icon, label, modifier = Modifier.size(23.dp)) },
                                label = { Text(label, fontSize = 11.sp) },
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
                            switch = switch, open = { if (!transition.running) open(it) },
                            import = { if (!transition.running) { prepareImport(); importer = true } },
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
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
                Text(if (section == ShelfSection.COMIC) "把一部漫画带进来" else "把一本书带进来", fontFamily = Serif, fontSize = 23.sp)
                Spacer(Modifier.height(20.dp))
                ImportFilterChips(importFilter) { importFilter = it }
                Spacer(Modifier.height(12.dp))
                ImportOption(Icons.Outlined.InsertDriveFile, "选择文件", if (importFilter == ImportFilter.COMIC) "CBZ / ZIP / EPUB / 图片 · 支持多选" else "所有格式 · 支持多选") {
                    importer = false
                    filePicker.launch(importFilter)
                }
                Spacer(Modifier.height(12.dp))
                ImportOption(Icons.Outlined.FolderOpen, "选择文件夹", "扫描子文件夹，自动跳过重复书籍") {
                    importer = false
                    folderPicker.launch(importFilter)
                }
            }
        }
    actions?.let { book ->
        ModalBottomSheet(onDismissRequest = { actions = null }) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(book.title, fontFamily = Serif, fontSize = 20.sp)
                TextButton(onClick = { vm.move(book); actions = null }) {
                    Text("移到${ShelfSection.valueOf(book.section).other().label}")
                }
                TextButton(onClick = { remove = book; actions = null }) { Text("移出收藏", color = Vermilion) }
            }
        }
    }
    status.scannedPdfs.firstOrNull()?.takeIf { !status.running }?.let { (id, title) ->
        AlertDialog(
            onDismissRequest = { vm.scannedToComic(id, false) },
            title = { Text("像是扫描版 PDF") },
            text = { Text("《$title》的页面都是图片，放到画架按页阅读会更合适。要移到画架吗？") },
            confirmButton = { TextButton(onClick = { vm.scannedToComic(id, true) }) { Text("移到画架") } },
            dismissButton = { TextButton(onClick = { vm.scannedToComic(id, false) }) { Text("留在书架") } },
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
                    }
                ) {
                    Text("移出", color = Vermilion)
                }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("保留") } },
        )
    }
}

@Composable
private fun ShelfContent(
    vm: ShelfViewModel, allBooks: List<ShelfBook>, section: ShelfSection, grid: Boolean,
    status: ImportStatus, switch: (ShelfSection) -> Unit,
    open: (String) -> Unit, import: () -> Unit, actions: (ShelfBook) -> Unit,
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
    val continuing =
        books.firstOrNull { it.lastRead > 0 && !it.invalid } ?: books.firstOrNull { !it.invalid }
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (grid) 3 else 1),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Column {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Lyric",
                        fontFamily = Serif,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 26.sp,
                    )
                    Text(
                        " · ${section.label}",
                        fontFamily = Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 24.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    ShelfSectionSwitch(section, switch)
                    IconButton(onClick = import, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Add, "导入书籍")
                    }
                }
                Text(
                    if (section == ShelfSection.COMIC) "本地珍藏 ${books.size} 部 · 一格一格画" else "本地珍藏 ${books.size} 本 · 一页一行诗",
                    fontSize = 12.sp,
                    letterSpacing = 1.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(22.dp))
                if (continuing != null)
                    ContinueCard(continuing, section, vm.repository) { open(continuing.id) }
                else EmptyShelf(section, import)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    filterLabels(section).forEachIndexed { index, label
                        ->
                        Column(
                            Modifier.padding(end = 16.dp).clickable {
                                filter = index
                            },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                color =
                                    if (filter == index)
                                        MaterialTheme.colorScheme.onBackground
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight =
                                    if (filter == index) FontWeight.SemiBold
                                    else FontWeight.Normal,
                            )
                            Spacer(Modifier.height(5.dp))
                            Box(
                                Modifier.width(22.dp)
                                    .height(2.dp)
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
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            if (grid) Icons.Outlined.ViewList
                            else Icons.Outlined.GridView,
                            if (grid) "切换列表" else "切换网格",
                            Modifier.size(20.dp),
                        )
                    }
                }
                ShelfSearchBox(query, section) { query = it }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShelfSortChips(sortMode) { vm.sort(it, section) }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (section == ShelfSection.COMIC) "${visible.size} 部漫画" else "${visible.size} 本书",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                if (status.running) ImportProgress(status) { vm.cancelImport() }
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
        item(contentType = "import") {
            if (grid)
                Column(Modifier.clickable { import() }) {
                    Box(
                        Modifier.fillMaxWidth()
                            .aspectRatio(.74f)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp),
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            "导入",
                            tint = Stone,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    Text(
                        "导入",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        if (section == ShelfSection.COMIC) "CBZ / EPUB / 图片" else "TXT / EPUB / PDF / DOCX / MOBI / AZW3",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            else
                OutlinedButton(
                    onClick = { import() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("＋ 导入本地书籍")
                }
        }
    }
}

private val coverColors =
    listOf(0xFF5E6B4E, 0xFF8A6A4F, 0xFF3E4A52, 0xFFB5563A, 0xFF9A9178, 0xFF6F5B6E)

@Composable
fun BookCover(book: ShelfBook, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                Color(coverColors[(book.title.hashCode() and Int.MAX_VALUE) % coverColors.size]),
                RoundedCornerShape(6.dp),
            )
            .paperGrain(),
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
                    .width(4.dp)
                    .background(Color.Black.copy(alpha = .08f))
            )
            Text(
                book.title.take(12).map { it.toString() }.joinToString("\n"),
                fontFamily = Serif,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Paper,
                maxLines = 9,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        if (book.invalid)
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("文件失效", color = Paper, fontSize = 11.sp)
            }
    }
}

@Composable
private fun ContinueCard(book: ShelfBook, section: ShelfSection, repository: LibraryRepository, open: () -> Unit) {
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
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = open)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BookCover(book, Modifier.width(64.dp).height(96.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                fontFamily = Serif,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (book.contentType == ContentType.COMIC.name) pages ?: "正在读取页码…"
                else if (section == ShelfSection.COMIC) book.author else "${book.author} · ${book.chapterTitle}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                if (book.contentType == ContentType.COMIC.name) book.author else book.excerpt,
                fontSize = 12.sp,
                lineHeight = 19.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(
                progress = { book.percent },
                color = Vermilion,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 0.dp),
            )
            Text(
                "${(book.percent * 100).toInt()}% · 继续阅读 →",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
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
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            if (book.invalid) "请重新导入"
            else if (book.lastRead == 0L) "未读" else "${(book.percent * 100).toInt()}%",
            fontSize = 10.sp,
            color = if (book.invalid) Vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BookListRow(book: ShelfBook, open: () -> Unit, remove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClick = remove),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BookCover(book, Modifier.width(56.dp).height(78.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            Text(
                book.title,
                fontFamily = Serif,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
                if (book.invalid) "文件失效 · 重新导入可恢复"
                else
                    "${book.extension.uppercase()} · ${book.chapterCount} 章 · ${(book.percent * 100).toInt()}%",
                fontSize = 11.sp,
                color = if (book.invalid) Vermilion else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyShelf(section: ShelfSection, import: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(44.dp), tint = Moss)
        Text(
            if (section == ShelfSection.COMIC) "留一处空白，等一部好漫画。" else "留一处空白，等一本好书。",
            fontFamily = Serif,
            fontSize = 17.sp,
            modifier = Modifier.padding(vertical = 18.dp),
        )
        OutlinedButton(onClick = import) { Text(if (section == ShelfSection.COMIC) "导入第一部漫画" else "导入第一本书") }
    }
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, subtitle: String, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .clickable(onClick = click)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, title, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, fontSize = 16.sp)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

private fun filterLabels(section: ShelfSection) =
    if (section == ShelfSection.COMIC) listOf("全部", "CBZ", "EPUB", "最近")
    else listOf("全部", "TXT", "EPUB", "PDF", "DOCX", "MOBI", "最近")

@Composable
private fun ImportProgress(status: ImportStatus, cancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (status.running) "正在导入 ${status.done} / ${status.total}" else "导入完成",
                fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            if (status.running) TextButton(onClick = cancel) { Text("取消", fontSize = 13.sp) }
        }
        if (status.running && status.total > 0)
            LinearProgressIndicator(
                progress = { ((status.done + status.detailFraction) / status.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                color = Moss,
            )
        else if (status.running)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
        if (status.running && status.detail.isNotBlank())
            Text(status.detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            status.message,
            fontSize = 11.sp,
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
        Modifier.padding(vertical = 10.dp).fillMaxWidth().height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = Stone)
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            if (query.isEmpty()) Text("搜索书名或作者", fontSize = 13.sp, color = Stone)
            androidx.compose.foundation.text.BasicTextField(
                query, change, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Moss),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "搜索${section.label}" },
            )
        }
        if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "清空", Modifier.size(18.dp).clickable { change("") }, tint = Stone)
    }
}

@Composable
private fun ShelfSortChips(mode: Int, pick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("最近阅读", "导入时间", "书名").forEachIndexed { i, label ->
            val on = i == mode
            Box(
                Modifier.height(28.dp).background(if (on) Moss else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .clickable { pick(i) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 12.sp, color = if (on) Paper else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderImportSheet(status: ImportStatus, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Text("导入完成", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text("新增 ${status.added} 本", fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp))
            Text(androidx.compose.ui.text.buildAnnotatedString {
                append("跳过重复 ")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Vermilion)); append("${status.duplicates}"); pop()
                append(" 本")
            }, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
            if (status.failures > 0) Text("失败 ${status.failures} 本", fontSize = 13.sp, color = Stone, modifier = Modifier.padding(top = 6.dp))
            if (status.source.isNotEmpty()) Text("来自 ${status.source}", fontSize = 11.sp, color = Stone, modifier = Modifier.padding(top = 6.dp))
            Button(onClick = dismiss, colors = ButtonDefaults.buttonColors(containerColor = Moss),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(44.dp), shape = RoundedCornerShape(22.dp)) { Text("好的") }
        }
    }
}

@Composable
private fun ShelfSectionSwitch(section: ShelfSection, switch: (ShelfSection) -> Unit) {
    Row(
        Modifier.height(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShelfSection.entries.forEachIndexed { index, item ->
            if (index == 1) Text("⇄", color = Stone, fontSize = 12.sp)
            Box(
                Modifier.width(30.dp).fillMaxHeight()
                    .background(if (section == item) Moss else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { switch(item) }
                    .semantics { contentDescription = "切换到${item.label}" },
                contentAlignment = Alignment.Center,
            ) { Text(if (item == ShelfSection.BOOK) "书" else "画", fontSize = 13.sp, color = if (section == item) Paper else Stone) }
        }
    }
}

@Composable
private fun ImportFilterChips(filter: ImportFilter, change: (ImportFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(selected = filter == ImportFilter.COMIC, onClick = { change(ImportFilter.COMIC) }, label = { Text("漫画格式") })
        FilterChip(selected = filter == ImportFilter.ALL, onClick = { change(ImportFilter.ALL) }, label = { Text("所有格式") })
    }
}

internal fun comicPageLabel(book: Book, progress: ProgressRow?): String {
    val total = book.chapters.sumOf { it.units.size }
    if (total == 0) return "暂无页面"
    val at = book.clamp(progress?.let { Locator(it.chapterIndex, it.charOffset, it.page) } ?: Locator())
    val page = book.chapters.take(at.chapterIndex).sumOf { it.units.size } + at.page + 1
    return "第 ${page.coerceIn(1, total)} 页 / $total"
}
