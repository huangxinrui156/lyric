@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lyric.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.core.Locator
import com.lyric.data.HighlightRow
import kotlin.math.min

private val FocusBg = Color(0xFF16171A)
private val FocusRingTrack = Color(0xFF2A2B2F)
private val FocusRing = Color(0xFFE08A6E)
private val FocusMuted = Color(0xFF7D786E)
private val FocusBtnFill = Color(0xFFD0CABE)
private val FocusBtnBorder = Color(0xFF3A3B40)

@Composable
fun DeskScreen(
    vm: DeskViewModel,
    openBook: (String, Locator) -> Unit,
) {
    val focus by vm.focus.collectAsStateWithLifecycle()
    var showNotes by rememberSaveable { mutableStateOf(false) }
    var pickingBook by rememberSaveable { mutableStateOf(false) }

    if (focus.running) {
        FocusTimerScreen(
            vm = vm,
            focus = focus,
            openBook = { openBook(focus.bookId, Locator()) },
        )
        return
    }
    if (showNotes) {
        DeskNotesScreen(vm, openBook) { showNotes = false }
        return
    }

    val summary by vm.summary.collectAsStateWithLifecycle()
    val quote by vm.quote.collectAsStateWithLifecycle()
    val book by vm.selectedBook.collectAsStateWithLifecycle()
    val planned by vm.plannedMin.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val preview = remember(highlights) { highlights.take(2) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .paperGrain()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        Text(
            text = "",
            modifier = Modifier.padding(start = 20.dp, top = 28.dp, end = 20.dp),
        )
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                "Lyric",
                fontFamily = Serif,
                fontStyle = FontStyle.Italic,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
            )
            Text(
                " · 案头",
                fontFamily = Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
            )
        }
        Text(
            "今天读了 ${summary.minutes} 分钟 · ${summary.pages} 页 · ${summary.notes} 条笔记",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        // 今日一句
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardPaper),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("今日一句", fontSize = 12.sp, color = Stone)
                    Text(
                        "换一句 ↻",
                        fontSize = 12.sp,
                        color = Moss,
                        modifier = Modifier.clickable { vm.reshuffleQuote() },
                    )
                }
                if (quote == null) {
                    Text(
                        "还没有划线。读到喜欢的句子时，长按划下来。",
                        fontFamily = Serif,
                        fontSize = 16.sp,
                        lineHeight = 28.sp,
                        color = Stone,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(top = 12.dp)
                            .clickable { openBook(quote!!.bookId, vm.openLocator(quote!!)) },
                    ) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .fillMaxHeight()
                                .background(Vermilion),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                quote!!.text,
                                fontFamily = Serif,
                                fontSize = 18.sp,
                                lineHeight = (18 * 1.75).sp,
                                color = Ink,
                            )
                            Text(
                                "— 《${quote!!.bookTitle.ifBlank { "未名" }}》 ${quote!!.chapterTitle}",
                                fontSize = 12.sp,
                                color = Moss,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        // 专注阅读
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardPaper),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("专注阅读", fontSize = 12.sp, color = Stone)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 45).forEach { min ->
                        val on = planned == min
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(if (on) Moss else Paper, RoundedCornerShape(18.dp))
                                .clickable { vm.planned(min) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$min 分钟",
                                fontSize = 14.sp,
                                color = if (on) Paper else Ink,
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clickable { pickingBook = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(28.dp)
                            .height(36.dp)
                            .background(Color(0xFFA9B39A), RoundedCornerShape(3.dp)),
                    )
                    Text(
                        book?.let { "${it.title} · ${it.chapterTitle}" } ?: "还没有可读的书",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text("更换 ›", fontSize = 13.sp, color = Stone)
                }
                // 屏蔽通知：默认关；打开时才申请勿扰权限
                var mute by rememberSaveable { mutableStateOf(false) }
                val context = LocalContext.current
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("屏蔽通知", fontSize = 13.sp)
                        Text("需要勿扰权限", fontSize = 11.sp, color = Stone)
                    }
                    Switch(
                        checked = mute,
                        onCheckedChange = { want ->
                            if (want) {
                                if (!hasDndAccess(context)) {
                                    context.startActivity(
                                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                    // Keep switch off until permission is granted on next toggle.
                                    mute = false
                                    vm.setMuteNotifications(false)
                                } else {
                                    mute = true
                                    vm.setMuteNotifications(true)
                                }
                            } else {
                                mute = false
                                vm.setMuteNotifications(false)
                            }
                        },
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(44.dp)
                        .background(Ink, RoundedCornerShape(22.dp))
                        .clickable(enabled = book != null) { vm.setMuteNotifications(mute); vm.startFocus() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("开始", fontSize = 15.sp, color = Paper)
                }
            }
        }

        // 笔记预览
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardPaper),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("笔记 · ${highlights.size} 条", fontSize = 12.sp, color = Stone)
                    Text(
                        "全部 ›",
                        fontSize = 12.sp,
                        color = Moss,
                        modifier = Modifier.clickable { showNotes = true },
                    )
                }
                if (preview.isEmpty()) {
                    Text(
                        "划线与想法会汇集在这里。",
                        fontFamily = Serif,
                        fontSize = 14.sp,
                        color = Stone,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    preview.forEachIndexed { index, h ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openBook(h.bookId, vm.openLocator(h)) }
                                .padding(vertical = 10.dp)
                                .then(
                                    if (index < preview.lastIndex)
                                        Modifier.border(
                                            width = 0.dp,
                                            color = Color.Transparent,
                                        )
                                    else Modifier,
                                ),
                        ) {
                            Text(h.text, fontFamily = Serif, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${h.bookTitle.ifBlank { "未名" }} · ${DeskViewModel.relativeDay(h.createdAt)}",
                                fontSize = 11.sp,
                                color = Stone,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                            if (index < preview.lastIndex) {
                                HorizontalDivider(
                                    Modifier.padding(top = 10.dp),
                                    color = Color(0xFFDDD5C6),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pickingBook) {
        BookPickSheet(vm) { pickingBook = false }
    }
}

@Composable
private fun BookPickSheet(vm: DeskViewModel, dismiss: () -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    val readable = remember(books) { books.filter { !it.invalid } }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.background) {
        Text("选择一本书", fontFamily = Serif, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 24.dp))
        LazyColumn(Modifier.padding(bottom = 28.dp)) {
            if (readable.isEmpty()) {
                item { Text("书架还是空的。", Modifier.padding(24.dp), color = Stone) }
            }
            items(readable, key = { it.id }) { book ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.pickBook(book.id); dismiss() }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(book, Modifier.width(36.dp).height(48.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(book.title, fontFamily = Serif, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(book.chapterTitle, fontSize = 11.sp, color = Stone)
                    }
                }
            }
        }
    }
}

@Composable
fun FocusTimerScreen(vm: DeskViewModel, focus: FocusUi, openBook: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? ComponentActivity

    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hadDnd = focus.muteNotifications && hasDndAccess(context)
        if (hadDnd) {
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            if (hadDnd) {
                runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
            }
        }
    }

    // Re-apply DND when toggle state changes while running (mute is set before start).
    LaunchedEffect(focus.muteNotifications) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (focus.muteNotifications && hasDndAccess(context)) {
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE) }
        }
    }

    LaunchedEffect(Unit) {
        vm.focusPulse.collect { lightVibrate(context) }
    }

    BackHandler { /* stay on timer; use 暂停 / abandon via long path */ }

    Box(Modifier.fillMaxSize().background(FocusBg)) {
        Text(
            if (focus.muteNotifications) "勿扰中 · ${focus.bookTitle}" else focus.bookTitle,
            color = FocusMuted,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 20.dp, end = 20.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        val progress = if (focus.plannedMin <= 0) 0f
        else (1f - focus.remainSec.toFloat() / (focus.plannedMin * 60f)).coerceIn(0f, 1f)

        Box(Modifier.align(Alignment.TopCenter).padding(top = 200.dp).size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val diameter = min(size.width, size.height) - stroke
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(
                    color = FocusRingTrack,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = FocusRing,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        Text(
            DeskViewModel.formatRemain(focus.remainSec),
            fontFamily = Serif,
            fontWeight = FontWeight.Light,
            fontSize = 52.sp,
            color = FocusBtnFill,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 280.dp),
        )
        Text(
            "共 ${focus.plannedMin} 分钟",
            fontSize = 12.sp,
            color = FocusMuted,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 355.dp),
        )

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier
                    .height(44.dp)
                    .border(1.dp, FocusBtnBorder, RoundedCornerShape(22.dp))
                    .clickable {
                        if (focus.paused) vm.resumeFocus() else vm.pauseFocus()
                    }
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (focus.paused) "继续" else "暂停", color = FocusBtnFill, fontSize = 14.sp)
            }
            Box(
                Modifier
                    .height(44.dp)
                    .background(FocusBtnFill, RoundedCornerShape(22.dp))
                    .clickable(onClick = openBook)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("继续阅读", color = FocusBg, fontSize = 14.sp)
            }
        }

        Text(
            if (focus.paused) "已暂停 · 点「继续」恢复计时" else "计时结束轻震一下，自动记录时长",
            fontSize = 12.sp,
            color = Color(0xFF5A574F),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
        )
    }
}

@Composable
fun DeskNotesScreen(vm: DeskViewModel, openBook: (String, Locator) -> Unit, back: () -> Unit) {
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var byBook by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(highlights, query, byBook) { vm.filterNotes(highlights, query, byBook) }
    BackHandler(onBack = back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("笔记", fontFamily = Serif) },
                navigationIcon = {
                    TextButton(onClick = back) { Text("返回", color = Moss) }
                },
                actions = {
                    TextButton(onClick = { byBook = !byBook }) {
                        Text(if (byBook) "按书 ▾" else "按时间 ▾", fontSize = 12.sp, color = Stone)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        Column(Modifier.fillMaxSize().padding(scaffoldPadding).paperGrain()) {
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("搜索划线、想法或书名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                "${filtered.size} 条",
                fontSize = 12.sp,
                color = Stone,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "还没有划线。",
                            fontFamily = Serif,
                            color = Stone,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                if (byBook) {
                    val groups = filtered.groupBy { it.bookId }
                    groups.forEach { (_, list) ->
                        item(key = "book-${list.first().bookId}") {
                            Text(
                                list.first().bookTitle.ifBlank { "未名" },
                                fontFamily = Serif,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                            )
                        }
                        items(list, key = { it.id }) { h ->
                            DeskNoteRow(h) { openBook(h.bookId, vm.openLocator(h)) }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }) { h ->
                        DeskNoteRow(h) { openBook(h.bookId, vm.openLocator(h)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeskNoteRow(h: HighlightRow, open: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 10.dp)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Vermilion))
        Column(Modifier.padding(start = 10.dp)) {
            Text(h.text, fontFamily = Serif, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            NoteLine(h.note)
            Text(
                "${h.bookTitle.ifBlank { "未名" }} · ${h.chapterTitle} · ${DeskViewModel.relativeDay(h.createdAt)}",
                fontSize = 11.sp,
                color = Stone,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 32.dp))
}

internal fun hasDndAccess(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.isNotificationPolicyAccessGranted
}

private fun lightVibrate(context: Context) {
    runCatching {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            mgr.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }
}

/** Open-book line icon for the 案头 tab; stroke weight matches other Outlined nav icons. */
val DeskNavIcon = Icons.AutoMirrored.Outlined.MenuBook
