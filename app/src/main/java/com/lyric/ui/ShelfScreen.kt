@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.lyric.ui

import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.lyric.data.*
import java.io.File
import java.text.Collator
import java.util.Locale

@Composable
fun ShelfScreen(vm: ShelfViewModel, me: @Composable () -> Unit, open: (String) -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    val status by vm.imports.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(status.running, status.message) {
        if (!status.running && status.message.isNotBlank()) snackbar.showSnackbar(status.message)
    }
    val clipboard = LocalClipboardManager.current
    var dismissedErrorBatch by remember { mutableLongStateOf(-1L) }
    if (!status.running && status.errors.isNotEmpty() && status.batchId != dismissedErrorBatch) {
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
    val sortTitle by vm.sortTitle.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var importer by remember { mutableStateOf(false) }
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
    val visible =
        remember(books, sortTitle, filter) {
            val list =
                when (filter) {
                    1 -> books.filter { it.extension == "txt" }
                    2 -> books.filter { it.extension == "epub" }
                    3 -> books.filter { it.lastRead > 0 }
                    else -> books
                }
            if (sortTitle)
                list.sortedWith(
                    compareBy(Collator.getInstance(Locale.SIMPLIFIED_CHINESE)) { it.title }
                )
            else list
        }
    val continuing =
        books.firstOrNull { it.lastRead > 0 && !it.invalid } ?: books.firstOrNull { !it.invalid }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
                modifier =
                    Modifier.border(BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)),
            ) {
                listOf(
                        "书架" to Icons.Outlined.AutoStories,
                        "本地导入" to Icons.Outlined.AddCircleOutline,
                        "我的" to Icons.Outlined.PersonOutline,
                    )
                    .forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(icon, label, modifier = Modifier.size(23.dp)) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors =
                                NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
                        )
                    }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).paperGrain()) {
            when (tab) {
                0 ->
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
                                        fontSize = 30.sp,
                                    )
                                    Text(
                                        " · 书架",
                                        fontFamily = Serif,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 28.sp,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = { importer = true }) {
                                        Icon(Icons.Outlined.Add, "导入书籍")
                                    }
                                }
                                Text(
                                    "本地珍藏 ${books.size} 本 · 一页一行诗",
                                    fontSize = 12.sp,
                                    letterSpacing = 1.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(22.dp))
                                if (continuing != null)
                                    ContinueCard(continuing) { open(continuing.id) }
                                else EmptyShelf { importer = true }
                                Spacer(Modifier.height(16.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    listOf("全部", "TXT", "EPUB", "最近").forEachIndexed { index, label
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
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${visible.size} 本书",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    TextButton(
                                        onClick = { vm.sort(!sortTitle) },
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.height(30.dp),
                                    ) {
                                        Text(
                                            if (sortTitle) "书名排序 ↓" else "最近阅读 ↓",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (status.running) ImportProgress(status)
                            }
                        }
                        items(
                            visible,
                            key = { it.id },
                            contentType = { if (grid) "book_grid" else "book_list" },
                        ) { book ->
                            if (grid) BookTile(book, { open(book.id) }, { remove = book })
                            else BookListRow(book, { open(book.id) }, { remove = book })
                        }
                        item(contentType = "import") {
                            if (grid)
                                Column(Modifier.clickable { importer = true }) {
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
                                        "TXT / EPUB",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            else
                                OutlinedButton(
                                    onClick = { importer = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("＋ 导入本地书籍")
                                }
                        }
                    }
                1 ->
                    Column(
                        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "本地导入",
                            fontSize = 28.sp,
                            fontFamily = Serif,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "让喜欢的文字，安静地留在身边。",
                            modifier = Modifier.padding(top = 12.dp, bottom = 36.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        ImportOption(Icons.Outlined.InsertDriveFile, "选择文件", "一次导入多本 TXT / EPUB") {
                            filePicker.launch(Unit)
                        }
                        Spacer(Modifier.height(16.dp))
                        ImportOption(Icons.Outlined.FolderOpen, "选择文件夹", "扫描所选文件夹及其子文件夹") {
                            folderPicker.launch(Unit)
                        }
                        Spacer(Modifier.height(28.dp))
                        Text(
                            "书籍只在本机解析。相同文件会自动跳过；原文件移动后，重新导入即可恢复访问。",
                            fontSize = 12.sp,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(24.dp))
                        if (status.running || status.message.isNotEmpty()) ImportProgress(status)
                    }
                2 -> me()
            }
        }
    }
    if (importer)
        ModalBottomSheet(
            onDismissRequest = { importer = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 36.dp)) {
                Text("把一本书带进来", fontFamily = Serif, fontSize = 23.sp)
                Spacer(Modifier.height(20.dp))
                ImportOption(Icons.Outlined.InsertDriveFile, "选择文件", "TXT / EPUB · 支持多选") {
                    importer = false
                    filePicker.launch(Unit)
                }
                Spacer(Modifier.height(12.dp))
                ImportOption(Icons.Outlined.FolderOpen, "选择文件夹", "扫描子文件夹，自动跳过重复书籍") {
                    importer = false
                    tab = 1
                    folderPicker.launch(Unit)
                }
            }
        }
    remove?.let { book ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("移出书架") },
            text = { Text("将《${book.title}》移出书架？此书的进度会被清除，阅读足迹、书签摘录与原文件保留。") },
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
        if (book.coverPath != null)
            AsyncImage(
                File(book.coverPath),
                book.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        else {
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
private fun ContinueCard(book: ShelfBook, open: () -> Unit) {
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
                "${book.author} · ${book.chapterTitle}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            Text(
                book.excerpt,
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
private fun EmptyShelf(import: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(44.dp), tint = Moss)
        Text(
            "留一处空白，等一本好书。",
            fontFamily = Serif,
            fontSize = 17.sp,
            modifier = Modifier.padding(vertical = 18.dp),
        )
        OutlinedButton(onClick = import) { Text("导入第一本书") }
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

@Composable
private fun ImportProgress(status: ImportStatus) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            if (status.running) "正在导入 ${status.done} / ${status.total}" else "导入完成",
            fontSize = 13.sp,
        )
        if (status.running && status.total > 0)
            LinearProgressIndicator(
                progress = { status.done.toFloat() / status.total },
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                color = Moss,
            )
        else if (status.running)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
        Text(
            status.message,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
