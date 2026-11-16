@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.core.Locator
import com.lyric.data.HighlightRow
import kotlin.math.min
import kotlinx.coroutines.launch

private val FocusBg = LyricTokens.Night.background
private val FocusRingTrack = LyricTokens.Night.stroke
private val FocusRing = LyricTokens.Night.vermilion
private val FocusMuted = LyricTokens.Night.secondary
private val FocusBtnFill = LyricTokens.Night.ink
private val FocusBtnBorder = LyricTokens.Night.stroke

@Composable
fun DeskScreen(
    vm: DeskViewModel,
    openBook: (String, Locator) -> Unit,
    openFocus: () -> Unit,
    openNotes: () -> Unit,
    continueReading: (String) -> Unit = {},
) {
    // 0.8.1: timer and full notes are their own nav routes, so system Back always lands here.
    // The 250ms timer tick is NOT collected at this level (only in FocusRunningRow).
    var pickingBook by rememberSaveable { mutableStateOf(false) }

    val summary by vm.summary.collectAsStateWithLifecycle()
    val quote by vm.quote.collectAsStateWithLifecycle()
    val book by vm.selectedBook.collectAsStateWithLifecycle()
    val planned by vm.plannedMin.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val preview = remember(highlights) { highlights.take(2) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LyricTokens.Space.xl),
    ) {
      item(key = "header", contentType = "header") {
        Text(
            text = "",
            modifier = Modifier.padding(start = LyricTokens.Page.margin, top = LyricTokens.Space.xl, end = LyricTokens.Page.margin),
        )
        Row(Modifier.padding(horizontal = LyricTokens.Page.margin), verticalAlignment = Alignment.Bottom) {
            Text(
                "Lyric",
                fontFamily = Serif,
                fontStyle = FontStyle.Italic,
                style = LyricTokens.Type.title,
            )
            Text(
                " · 案头",
                fontFamily = Serif,
                style = LyricTokens.Type.title,
            )
        }
        Text(
            "今天读了 ${summary.minutes} 分钟 · ${summary.pages} 页 · ${summary.notes} 条笔记",
            style = LyricTokens.Type.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.xs),
        )

      }
      item(key = "quote", contentType = "quote") {
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = LyricTokens.Page.margin, end = LyricTokens.Page.margin, top = LyricTokens.Space.lg),
            shape = RoundedCornerShape(LyricTokens.Radius.card),
            colors = CardDefaults.cardColors(containerColor = dCard),
            border = dCardStroke,
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(Modifier.padding(LyricTokens.Space.lg)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("今日一句", style = LyricTokens.Type.auxiliary, color = dSub)
                    Text(
                        "换一句 ↻",
                        style = LyricTokens.Type.auxiliary,
                        color = dLink,
                        modifier = Modifier.clickable { vm.reshuffleQuote() },
                    )
                }
                if (quote == null) {
                    Text(
                        "还没有划线。读到喜欢的句子时，长按划下来。",
                        fontFamily = Serif,
                        style = LyricTokens.Type.subtitle,
                        color = dSub,
                        modifier = Modifier.padding(top = LyricTokens.Space.md, bottom = LyricTokens.Space.xs),
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(top = LyricTokens.Space.md)
                            .clickable { openBook(quote!!.bookId, vm.openLocator(quote!!)) },
                    ) {
                        Box(
                            Modifier
                                .width(LyricTokens.Component.indicatorStroke)
                                .fillMaxHeight()
                                .background(dBar),
                        )
                        Column(Modifier.padding(start = LyricTokens.Space.md)) {
                            // 0.9: long notes no longer push the desk down — 4 lines, tap 展开/收起.
                            var expanded by rememberSaveable(quote!!.id) { mutableStateOf(false) }
                            val text = remember(quote!!.id, quote!!.text) { compactPreview(quote!!.text) }
                            Text(
                                text,
                                fontFamily = Serif,
                                style = LyricTokens.Type.subtitle,
                                color = dText,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (text.length > 60) Text(
                                if (expanded) "收起" else "展开",
                                style = LyricTokens.Type.auxiliary, color = dSub,
                                modifier = Modifier.padding(top = LyricTokens.Space.xs).clickable { expanded = !expanded },
                            )
                            Text(
                                "— 《${quote!!.bookTitle.ifBlank { "未名" }}》 ${quote!!.chapterTitle}",
                                style = LyricTokens.Type.auxiliary,
                                color = dLink,
                                modifier = Modifier.padding(top = LyricTokens.Space.sm),
                            )
                        }
                    }
                }
            }
        }

      }
      item(key = "focus", contentType = "focus") {
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = LyricTokens.Page.margin, end = LyricTokens.Page.margin, top = LyricTokens.Space.lg),
            shape = RoundedCornerShape(LyricTokens.Radius.card),
            colors = CardDefaults.cardColors(containerColor = dCard),
            border = dCardStroke,
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(Modifier.padding(LyricTokens.Space.lg)) {
                Text("专注阅读", style = LyricTokens.Type.auxiliary, color = dSub)
                val focusState by vm.focus.collectAsStateWithLifecycle()
                // Only flips when a session starts/ends, not on every tick.
                val active by remember { derivedStateOf { focusState.running } }
                if (!active) Row(Modifier.padding(top = LyricTokens.Space.md), horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.sm)) {
                    listOf(25, 45).forEach { min ->
                        val on = planned == min
                        Box(
                            Modifier
                                .weight(1f)
                                .height(LyricTokens.Component.smallControl)
                                .background(if (on) dSegOn else dSegOff, LyricTokens.Radius.pill)
                                .clickable { vm.planned(min) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$min 分钟",
                                style = LyricTokens.Type.body,
                                color = if (on) dSegOnText else dSegOffText,
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = LyricTokens.Space.sm)
                        .clickable { pickingBook = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val b = book
                    if (b != null) BookCover(b, Modifier.width(LyricTokens.Component.smallCoverWidth).height(LyricTokens.Component.smallControl))
                    else Box(Modifier.width(LyricTokens.Component.smallCoverWidth).height(LyricTokens.Component.smallControl).background(LyricTokens.Component.missingCover, RoundedCornerShape(LyricTokens.Radius.cover)))
                    Text(
                        book?.let { deskBookLabel(it.title, it.chapterTitle) } ?: "还没有可读的书",
                        style = LyricTokens.Type.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = LyricTokens.Space.sm),
                    )
                    Text("更换 ›", style = LyricTokens.Type.body, color = dSub)
                }
                FocusRunningRow(vm, openFocus, continueReading)
                if (!active) Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = LyricTokens.Space.md)
                        .height(LyricTokens.Component.buttonHeight)
                        .background(dPrimary, RoundedCornerShape(LyricTokens.Radius.button))
                        .clickable(enabled = book != null) { if (vm.startFocus()) openFocus() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("开始", style = LyricTokens.Type.body, color = dPrimaryText)
                }
            }
        }

      }
      item(key = "notes", contentType = "notes") {
        Card(
            modifier = Modifier.fillMaxWidth().padding(start = LyricTokens.Page.margin, end = LyricTokens.Page.margin, top = LyricTokens.Space.lg),
            shape = RoundedCornerShape(LyricTokens.Radius.card),
            colors = CardDefaults.cardColors(containerColor = dCard),
            border = dCardStroke,
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(Modifier.padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.md)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = LyricTokens.Space.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("笔记 · ${highlights.size} 条", style = LyricTokens.Type.auxiliary, color = dSub)
                    Text(
                        "全部 ›",
                        style = LyricTokens.Type.auxiliary,
                        color = dLink,
                        modifier = Modifier.clickable(onClick = openNotes),
                    )
                }
                if (preview.isEmpty()) {
                    Text(
                        "划线与想法会汇集在这里。",
                        fontFamily = Serif,
                        style = LyricTokens.Type.body,
                        color = dSub,
                        modifier = Modifier.padding(vertical = LyricTokens.Space.lg),
                    )
                } else {
                    preview.forEachIndexed { index, h ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openBook(h.bookId, vm.openLocator(h)) }
                                .padding(vertical = LyricTokens.Space.sm)
                                .then(
                                    if (index < preview.lastIndex)
                                        Modifier.border(
                                            width = 0.dp,
                                            color = Color.Transparent,
                                        )
                                    else Modifier,
                                ),
                        ) {
                            Text(remember(h.id, h.text) { compactPreview(h.text) }, fontFamily = Serif, style = LyricTokens.Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${h.bookTitle.ifBlank { "未名" }} · ${DeskViewModel.relativeDay(h.createdAt)}",
                                style = LyricTokens.Type.auxiliary,
                                color = dSub,
                                modifier = Modifier.padding(top = LyricTokens.Space.xs),
                            )
                            if (index < preview.lastIndex) {
                                HorizontalDivider(
                                    Modifier.padding(top = LyricTokens.Space.sm),
                                    color = dDivider,
                                )
                            }
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
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.background, shape = LyricTokens.Radius.cardShape) {
        Text("选择一本书", fontFamily = Serif, style = LyricTokens.Type.title, modifier = Modifier.padding(horizontal = LyricTokens.Page.margin))
        LazyColumn(Modifier.padding(bottom = LyricTokens.Space.xl)) {
            if (readable.isEmpty()) {
                item { Text("书架还是空的。", Modifier.padding(LyricTokens.Space.xl), color = dSub) }
            }
            items(readable, key = { it.id }) { book ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.pickBook(book.id); dismiss() }
                        .padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(book, Modifier.width(LyricTokens.Component.smallControl).height(LyricTokens.Component.touchTarget))
                    Column(Modifier.padding(start = LyricTokens.Space.md)) {
                        Text(book.title, fontFamily = Serif, style = LyricTokens.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(book.chapterTitle, style = LyricTokens.Type.auxiliary, color = dSub)
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
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    LaunchedEffect(Unit) {
        vm.focusPulse.collect { lightVibrate(context) }
    }

    // Back (incl. predictive gesture) is handled by the nav route: pops to 案头, session keeps running.

    val night = deskDark()
    val background = if (night) FocusBg else Paper
    val ink = if (night) FocusBtnFill else Ink
    val meta = if (night) FocusMuted else Stone
    val accent = if (night) FocusRing else Vermilion
    Column(Modifier.fillMaxSize().background(background).navigationBarsPadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = LyricTokens.Space.xxl, vertical = LyricTokens.Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(focus.bookTitle, color = meta, style = LyricTokens.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(LyricTokens.Component.toolbarHeight))
        FocusRingDisplay(focus, ink, meta, accent, if (night) FocusRingTrack else Linen)
        Spacer(Modifier.height(LyricTokens.Component.touchTarget))
        NoiseControls(vm, night)
        Spacer(Modifier.height(LyricTokens.Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.lg)) {
            OutlinedButton(onClick = { if (focus.paused) vm.resumeFocus() else vm.pauseFocus() },
                modifier = Modifier.weight(1f).height(LyricTokens.Component.touchTarget), border = BorderStroke(LyricTokens.strokeWidth, if (night) FocusBtnBorder else Linen), shape = LyricTokens.Radius.buttonShape) {
                Text(if (focus.paused) "继续" else "暂停", color = ink)
            }
            Button(onClick = openBook, modifier = Modifier.weight(1f).height(LyricTokens.Component.touchTarget),
                colors = ButtonDefaults.buttonColors(containerColor = ink, contentColor = background), shape = LyricTokens.Radius.buttonShape) { Text("继续阅读") }
        }
        TextButton(onClick = { vm.resetFocus() }, shape = LyricTokens.Radius.buttonShape) { Text("重置", color = meta) }
        Text(if (focus.paused) "已暂停 · 点「继续」恢复计时" else "计时结束轻震一下，自动记录时长", color = meta, style = LyricTokens.Type.auxiliary)
    }
}

@Composable
private fun FocusRingDisplay(focus: FocusUi, ink: Color, meta: Color, accent: Color, track: Color) {
    Box(Modifier.size(LyricTokens.Component.focusDial), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = LyricTokens.Component.progressStroke.toPx()
            val diameter = min(size.width, size.height) - stroke
            val top = Offset(stroke / 2, stroke / 2)
            val extent = Size(diameter, diameter)
            drawArc(track, -90f, 360f, false, top, extent, style = Stroke(stroke))
            val progress = if (focus.plannedMin <= 0) 0f else (1f - focus.remainSec / (focus.plannedMin * 60f)).coerceIn(0f, 1f)
            drawArc(accent, -90f, 360f * progress, false, top, extent, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(DeskViewModel.formatRemain(focus.remainSec), fontFamily = Serif, style = LyricTokens.Component.focusTime, color = ink)
            Text("共 ${focus.plannedMin} 分钟", color = meta, style = LyricTokens.Type.auxiliary)
        }
    }
}

@Composable
private fun NoiseControls(vm: DeskViewModel, night: Boolean) {
    val noise by vm.noise.collectAsStateWithLifecycle()
    val selected = if (night) LyricTokens.Night.moss else Moss
    val fill = if (night) FocusRingTrack else CardPaper
    val meta = if (night) FocusMuted else Stone
    Column(Modifier.widthIn(max = LyricTokens.Component.dialogMaxHeight).fillMaxWidth()) {
        Text("环境声", color = meta, style = LyricTokens.Type.body)
        Spacer(Modifier.height(LyricTokens.Space.md))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.sm)) {
            com.lyric.audio.NoiseSound.entries.forEach { sound ->
                val active = noise.sound == sound
                Box(Modifier.width(LyricTokens.Component.settingValueWidth).height(LyricTokens.Component.smallControl).background(if (active) selected else fill, LyricTokens.Radius.pill)
                    .clickable { vm.noiseSound(sound) }, contentAlignment = Alignment.Center) {
                    Text(sound.label, style = LyricTokens.Type.body, color = if (active) { if (night) FocusBg else Paper } else { if (night) LyricTokens.Night.secondary else Stone })
                }
            }
        }
        if (noise.sound != com.lyric.audio.NoiseSound.NONE) {
            NoiseVolumeSlider(noise.volume, vm::noiseVolume, active = selected,
                inactive = if (night) FocusRingTrack else Linen, thumb = if (night) FocusBtnFill else LyricTokens.Day.background,
                thumbBorder = if (night) Color.Transparent else Linen)
        }
    }
}

@Composable
fun DeskNotesScreen(vm: DeskViewModel, openBook: (String, Locator) -> Unit, back: () -> Unit) {
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val night = deskDark()
    var exporting by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<HighlightRow?>(null) }
    fun share(note: HighlightRow? = null, bookId: String? = null) {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                val file = if (note == null) vm.exportNotes(bookId) else vm.exportCard(note, night)
                com.lyric.export.NoteExport.share(context, file, if (note == null) "text/markdown" else "image/png")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { android.widget.Toast.makeText(context, "导出失败：${e.localizedMessage}", android.widget.Toast.LENGTH_LONG).show() }
            finally { exporting = false }
        }
    }
    exportNote?.let { note ->
        AlertDialog(onDismissRequest = { exportNote = null }, title = { Text("导出笔记") },
            text = { Column {
                TextButton(onClick = { exportNote = null; share(note = note) }, shape = LyricTokens.Radius.buttonShape) { Text("分享图片卡片") }
                TextButton(onClick = { exportNote = null; share(bookId = note.bookId) }, shape = LyricTokens.Radius.buttonShape) { Text("本书笔记 · Markdown") }
            } }, confirmButton = { TextButton(onClick = { exportNote = null }, shape = LyricTokens.Radius.buttonShape) { Text("取消") } })
    }
    var query by rememberSaveable { mutableStateOf("") }
    var byBook by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(highlights, query, byBook) { vm.filterNotes(highlights, query, byBook) }
    val groups = remember(filtered, byBook) { if (byBook) filtered.groupBy { it.bookId } else emptyMap() }
    BackHandler(onBack = back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("笔记", fontFamily = Serif) },
                navigationIcon = {
                    TextButton(onClick = back, shape = LyricTokens.Radius.buttonShape) { Text("返回", color = dLink) }
                },
                actions = {
                    TextButton(enabled = !exporting && highlights.isNotEmpty(), onClick = { share() }, shape = LyricTokens.Radius.buttonShape) { Text(if (exporting) "导出中…" else "导出全部", color = dLink, style = LyricTokens.Type.auxiliary) }
                    TextButton(onClick = { byBook = !byBook }, shape = LyricTokens.Radius.buttonShape) {
                        Text(if (byBook) "按书 ▾" else "按时间 ▾", style = LyricTokens.Type.auxiliary, color = dSub)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
        Column(Modifier.fillMaxSize().padding(scaffoldPadding).paperGrain(deskDark())) {
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("搜索划线、想法或书名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.sm),
            )
            Text(
                "${filtered.size} 条",
                style = LyricTokens.Type.auxiliary,
                color = dSub,
                modifier = Modifier.padding(start = LyricTokens.Page.margin, end = LyricTokens.Page.margin, bottom = LyricTokens.Space.xs),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = LyricTokens.Space.xl)) {
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "还没有划线。",
                            fontFamily = Serif,
                            color = dSub,
                            modifier = Modifier.padding(LyricTokens.Space.xl),
                        )
                    }
                }
                if (byBook) {
                    groups.forEach { (_, list) ->
                        item(key = "book-${list.first().bookId}", contentType = "book-heading") {
                            Text(
                                list.first().bookTitle.ifBlank { "未名" },
                                fontFamily = Serif,
                                style = LyricTokens.Type.subtitle,
                                modifier = Modifier.padding(start = LyricTokens.Space.lg, top = LyricTokens.Space.lg, bottom = LyricTokens.Space.xs),
                            )
                        }
                        items(list, key = { it.id }, contentType = { "note" }) { h ->
                            DeskNoteRow(h, export = { exportNote = h }) { openBook(h.bookId, vm.openLocator(h)) }
                        }
                    }
                } else {
                    items(filtered, key = { it.id }, contentType = { "note" }) { h ->
                        DeskNoteRow(h, export = { exportNote = h }) { openBook(h.bookId, vm.openLocator(h)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeskNoteRow(h: HighlightRow, export: () -> Unit, open: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = open)
            .padding(start = LyricTokens.Page.margin, end = LyricTokens.Page.margin, top = LyricTokens.Space.sm, bottom = LyricTokens.Space.sm)
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(LyricTokens.Component.indicatorStroke).fillMaxHeight().background(dBar))
        Column(Modifier.weight(1f).padding(start = LyricTokens.Space.sm)) {
            Text(h.text, fontFamily = Serif, style = LyricTokens.Type.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
            NoteLine(h.note)
            Text(
                "${h.bookTitle.ifBlank { "未名" }} · ${h.chapterTitle} · ${DeskViewModel.relativeDay(h.createdAt)}",
                style = LyricTokens.Type.auxiliary,
                color = dSub,
                modifier = Modifier.padding(top = LyricTokens.Space.xs),
            )
        }
        TextButton(onClick = export, shape = LyricTokens.Radius.buttonShape) { Text("导出", color = dLink, style = LyricTokens.Type.auxiliary) }
    }
    HorizontalDivider(color = if (deskDark()) dDivider else MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = LyricTokens.Space.xxl))
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

/** Only this small row observes the ticking timer, so the desk list doesn't recompose 4×/s. */
@Composable
private fun FocusRunningRow(vm: DeskViewModel, openFocus: () -> Unit, continueReading: (String) -> Unit) {
    val focus by vm.focus.collectAsStateWithLifecycle()
    if (!focus.running) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = LyricTokens.Space.md)
            .then(if (deskDark()) Modifier.border(LyricTokens.strokeWidth, FocusBtnBorder, LyricTokens.Radius.cardShape) else Modifier.background(Ink, LyricTokens.Radius.cardShape))
            .padding(horizontal = LyricTokens.Page.margin, vertical = LyricTokens.Space.md),
    ) {
        Text(
            "${if (focus.paused) "已暂停" else "专注中"} ${DeskViewModel.formatRemain(focus.remainSec)} · ${focus.bookTitle}",
            color = if (deskDark()) FocusMuted else Paper, style = LyricTokens.Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth().padding(top = LyricTokens.Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.height(LyricTokens.Component.compactButtonHeight).background(FocusBtnFill, RoundedCornerShape(LyricTokens.Radius.button))
                    .clickable { continueReading(focus.bookId) }.padding(horizontal = LyricTokens.Page.margin),
                contentAlignment = Alignment.Center,
            ) { Text("继续阅读", color = FocusBg, style = LyricTokens.Type.body) }
            Spacer(Modifier.weight(1f))
            Text("回到计时", color = FocusBtnFill, style = LyricTokens.Type.auxiliary,
                modifier = Modifier.clickable(onClick = openFocus).padding(LyricTokens.Space.sm))
            Text("重置", color = FocusRing, style = LyricTokens.Type.auxiliary,
                modifier = Modifier.clickable { vm.resetFocus() }.padding(LyricTokens.Space.sm))
        }
    }
}

/** Reader drawer header line: 「专注中 · 剩余 42:48」. Only this composable ticks. */
@Composable
fun FocusRemainingLabel(vm: DeskViewModel, bookId: String) {
    val focus by vm.focus.collectAsStateWithLifecycle()
    if (!focus.running || focus.bookId != bookId) return
    Text(
        "${if (focus.paused) "已暂停" else "专注中"} · 剩余 ${DeskViewModel.formatRemain(focus.remainSec)}",
        color = if (focus.paused) dSub else if (deskDark()) FocusRing else dBar,
        style = LyricTokens.Type.auxiliary,
        modifier = Modifier.padding(top = LyricTokens.Space.xs),
    )
}

/** Full-screen route wrapper: collects focus here, closes itself when the session ends. */
@Composable
fun FocusRoute(vm: DeskViewModel, back: () -> Unit, openBook: (String) -> Unit) {
    val focus by vm.focus.collectAsStateWithLifecycle()
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(focus.running) {
        if (focus.running) wasRunning = true
        else if (wasRunning) back()
    }
    BackHandler(onBack = back)
    FocusTimerScreen(vm, focus) { openBook(focus.bookId) }
}

/** 「雪中悍刀行 · Cover」 → 「雪中悍刀行」: skip cover/empty/untitled chapter labels. */
internal fun deskBookLabel(title: String, chapter: String): String {
    val c = chapter.trim()
    val junk = c.isEmpty() || c == "未读" || Regex("(?i)^(cover|封面|title ?page|扉页|目录|toc|contents)$").matches(c)
    return if (junk) title else "$title · $c"
}

// 0.8.4 美术设计 dark spec for 案头; light values are the 0.8.0 ones, unchanged.
@Composable internal fun deskDark() = MaterialTheme.colorScheme.background.luminance() < .5f
private val dCard @Composable get() = if (deskDark()) LyricTokens.Night.card else CardPaper
private val dCardStroke @Composable get() = BorderStroke(LyricTokens.strokeWidth, LyricTokens.colors(deskDark()).stroke)
private val dText @Composable get() = if (deskDark()) LyricTokens.Night.ink else Ink
private val dSub @Composable get() = if (deskDark()) LyricTokens.Night.secondary else Stone
private val dLink @Composable get() = if (deskDark()) LyricTokens.Night.moss else Moss
private val dBar @Composable get() = if (deskDark()) LyricTokens.Night.vermilion else Vermilion
private val dSegOn @Composable get() = if (deskDark()) LyricTokens.Night.moss else Moss
private val dSegOnText @Composable get() = if (deskDark()) LyricTokens.Night.background else Paper
private val dSegOff @Composable get() = if (deskDark()) LyricTokens.Night.stroke else Paper
private val dSegOffText @Composable get() = if (deskDark()) LyricTokens.Night.secondary else Ink
private val dPrimary @Composable get() = if (deskDark()) LyricTokens.Night.ink else Ink
private val dPrimaryText @Composable get() = if (deskDark()) LyricTokens.Night.background else Paper
private val dDivider @Composable get() = if (deskDark()) LyricTokens.Night.stroke else LyricTokens.Day.stroke

/** Collapse blank paragraphs / line breaks into one spaced line for previews. */
internal fun compactPreview(s: String): String =
    s.trim().replace(Regex("\\s*\\n[\\s\\n]*"), " ").replace(Regex("[ \\t\u3000]{2,}"), " ")


/** 0.9.2: thin 2dp track, 14dp round paper thumb, no M3 thumb gap or stop-indicator dot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoiseVolumeSlider(value: Float, onChange: (Float) -> Unit, active: Color, inactive: Color, thumb: Color, thumbBorder: Color) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Slider(value, onChange, modifier = Modifier.fillMaxWidth().padding(top = LyricTokens.Space.sm).height(LyricTokens.Space.xxl),
        interactionSource = interaction,
        thumb = {
            Box(Modifier.size(LyricTokens.Component.sliderThumb).background(thumb, androidx.compose.foundation.shape.CircleShape)
                .border(LyricTokens.strokeWidth, thumbBorder, androidx.compose.foundation.shape.CircleShape))
        },
        track = { state ->
            val fraction = state.value.coerceIn(0f, 1f)
            Canvas(Modifier.fillMaxWidth().height(LyricTokens.Component.indicatorStroke)) {
                val y = size.height / 2
                val x = size.width * fraction
                drawLine(inactive, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                if (x > 0f) drawLine(active, Offset(0f, y), Offset(x, y), size.height, StrokeCap.Round)
            }
        })
}
