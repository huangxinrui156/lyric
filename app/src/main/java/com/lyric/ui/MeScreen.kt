@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.core.Locator
import com.lyric.data.*
import java.time.LocalDate

@Composable
fun MeScreen(vm: MeViewModel, navigate: (String) -> Unit) {
    val days by vm.days.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    val todaySeconds = remember(days, today) {
        val date = today.toString()
        days.firstOrNull { it.day == date }?.durationSec ?: 0L
    }
    val weekSeconds = remember(days, today) {
        val week = ReadingStats.weekStart(today).toString()..today.toString()
        days.sumOf { if (it.day in week) it.durationSec else 0L }
    }
    val streak = remember(days, today) { ReadingStats.streak(days, today) }
    val selectDay: (LocalDate) -> Unit = remember(vm) { vm::selectDay }
    LazyColumn(contentPadding = PaddingValues(LyricTokens.Page.margin), verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.lg)) {
        item { Text("我的", fontFamily = Serif, style = LyricTokens.Type.title, color = Ink) }
        item {
            Card(onClick = { navigate("stats") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(LyricTokens.Radius.card),
                border = BorderStroke(LyricTokens.strokeWidth, Linen)) {
                Column(Modifier.padding(LyricTokens.Space.lg)) {
                    Text("阅读足迹", fontFamily = Serif, style = LyricTokens.Type.subtitle)
                    Row(Modifier.fillMaxWidth().padding(top = LyricTokens.Space.xl), horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.sm)) {
                        Footprint("${todaySeconds / 60} 分", "今日 · 本周 ${weekSeconds / 60} 分", Modifier.weight(1f))
                        Footprint("${totals.finished} 本", "累计读完", Modifier.weight(1f))
                        Footprint("$streak 天", "连续阅读", Modifier.weight(1f))
                    }
                    Text("共读 ${totals.durationSec / 60} 分钟  ›", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = LyricTokens.Type.auxiliary, modifier = Modifier.padding(top = LyricTokens.Space.lg))
                }
            }
        }
        item(key = "reading-calendar") {
            Column {
                ReadingCalendar(days, today, selectDay)
                TextButton(onClick = { navigate("stats") }, shape = LyricTokens.Radius.buttonShape) { Text("查看阅读日历", style = LyricTokens.Type.auxiliary) }
            }
        }
        item {
            if (prefs.goalEnabled) {
                val target = prefs.dailyGoalMin.coerceAtLeast(1)
                val progress by animateFloatAsState((todaySeconds / (target * 60f)).coerceIn(0f, 1f), animationSpec = LyricTokens.Motion.short(), label = "goal")
                Row(Modifier.fillMaxWidth().clickable { navigate("settings/goal") }.padding(vertical = LyricTokens.Space.sm),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(LyricTokens.Component.goalRing), strokeWidth = LyricTokens.Component.indicatorStroke,
                        trackColor = MaterialTheme.colorScheme.outlineVariant)
                    Column(Modifier.padding(start = LyricTokens.Space.lg)) {
                        Text("每天 ${target} 分钟", fontFamily = Serif)
                        Text(if (progress >= 1) "今日的阅读，已悄悄盛满。" else "已读 ${todaySeconds / 60} 分钟", style = LyricTokens.Type.auxiliary,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else MeLink("阅读目标", "未开启 · 给自己留一点阅读时间") { navigate("settings/goal") }
        }
        item { MeLink("书签与摘录", "${marks.size} 片叶子 · 按书分组") { navigate("bookmarks") } }
        item { MeLink("外观", "应用主题 · 强调色 · 默认阅读主题") { navigate("settings/appearance") } }
        item { MeLink("阅读偏好", "全局排版与阅读操作") { navigate("settings/reading") } }
        item { MeLink("数据", "本地备份与恢复 · 导出统计") { navigate("settings/data") } }
        item { MeLink("存储", "解析缓存 · 字体 · 封面") { navigate("settings/storage") } }
        item { MeLink("关于 Lyric", "版本 · 开源许可 · 隐私") { navigate("settings/about") } }
        item { Text("一页一行诗，留白即韵律。", fontFamily = Serif, color = MaterialTheme.colorScheme.onSurfaceVariant, style = LyricTokens.Type.auxiliary) }
    }
    DayDialog(vm)
}

@Composable
private fun Footprint(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontFamily = Serif, style = LyricTokens.Type.title)
        Text(label, style = LyricTokens.Type.auxiliary, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = LyricTokens.Space.sm))
    }
}

@Composable
internal fun MeLink(title: String, subtitle: String, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = LyricTokens.Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontFamily = Serif, style = LyricTokens.Type.subtitle, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ChevronRight, "打开$title", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = LyricTokens.Type.auxiliary, modifier = Modifier.padding(top = LyricTokens.Space.xs))
        HorizontalDivider(Modifier.padding(top = LyricTokens.Space.md), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun MePage(title: String, back: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing, topBar = { TopAppBar(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top), title = { Text(title, fontFamily = Serif) }, navigationIcon = {
        IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回") }
    }) }) { padding -> Box(Modifier.fillMaxSize().padding(padding).paperGrain(deskDark())) { content(PaddingValues(LyricTokens.Page.margin)) } }
}

@Composable
fun BookmarksScreen(vm: MeViewModel, back: () -> Unit, open: (String, Locator, Long) -> Unit) {
    val marks by vm.bookmarks.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filtered by remember { mutableStateOf<List<BookmarkSummary>>(emptyList()) }
    LaunchedEffect(marks, query) {
        filtered = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            BookmarkFilter.filter(marks, query)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { it.mark.bookId }.values.toList() }
    var exportSnapshot by remember { mutableStateOf<List<BookmarkSummary>>(emptyList()) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { vm.exportBookmarks(it, exportSnapshot) }
    }
    MePage("书签与摘录", back) { padding ->
        Column(Modifier.padding(horizontal = LyricTokens.Page.margin)) {
            OutlinedTextField(query, { query = it }, label = { Text("搜索书名、章节或摘录") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${filtered.size} 条摘录", modifier = Modifier.weight(1f), style = LyricTokens.Type.auxiliary)
                TextButton(enabled = filtered.isNotEmpty(), onClick = {
                    exportSnapshot = filtered.toList(); export.launch("lyric-bookmarks-${LocalDate.now()}.md")
                }, shape = LyricTokens.Radius.buttonShape) { Text("导出 Markdown") }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = LyricTokens.Space.lg)) {
                if (groups.isEmpty()) item { Text("还没有夹起的叶子。", fontFamily = Serif, modifier = Modifier.padding(vertical = LyricTokens.Space.xxl)) }
                groups.forEach { group ->
                    item(key = "book:${group.first().mark.bookId}") {
                        Text(group.first().title, fontFamily = Serif, style = LyricTokens.Type.title,
                            color = if (group.first().unavailable) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = LyricTokens.Space.lg, bottom = LyricTokens.Space.md))
                    }
                    items(group, key = { it.mark.id }) { item ->
                        val mark = item.mark
                        val color = if (item.unavailable) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f) else MaterialTheme.colorScheme.onSurface
                        Column(Modifier.fillMaxWidth().clickable(enabled = !item.unavailable) {
                            open(mark.bookId, Locator(mark.chapterIndex, mark.charOffset, mark.page), mark.id)
                        }.padding(vertical = LyricTokens.Space.lg)) {
                            Text(mark.chapterTitle, style = LyricTokens.Type.auxiliary, color = color)
                            Text(mark.excerpt, fontFamily = Serif, style = LyricTokens.Type.subtitle, color = color, modifier = Modifier.padding(vertical = LyricTokens.Space.sm))
                            Text("${java.time.Instant.ofEpochMilli(mark.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}${if (item.unavailable) " · 原文件不可用，请重新导入" else " · 点击回到原文"}",
                                color = color, style = LyricTokens.Type.auxiliary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
    MeNotices(vm)
}

@Composable
fun StatsScreen(vm: MeViewModel, back: () -> Unit) {
    val days by vm.days.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val totals by vm.totals.collectAsStateWithLifecycle()
    val selectDay: (LocalDate) -> Unit = remember(vm) { vm::selectDay }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(vm::exportStats) }
    MePage("阅读足迹", back) { padding ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.lg)) {
            item { Text("累计 ${totals.durationSec / 60} 分钟 · 读完 ${totals.finished} 本", fontFamily = Serif, style = LyricTokens.Type.title) }
            item(key = "reading-calendar") { ReadingCalendar(days, today, selectDay) }
            item { Text("只有前台翻页或滚动才开始计时；30 秒未操作暂停，少于 10 秒不计。", style = LyricTokens.Type.auxiliary, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { OutlinedButton(onClick = { export.launch("lyric-stats-$today.csv") }, shape = LyricTokens.Radius.buttonShape) { Text("导出统计 CSV") } }
            items(days.asReversed(), key = { it.day }) { day ->
                Row(Modifier.fillMaxWidth().clickable { vm.selectDay(LocalDate.parse(day.day)) }.padding(vertical = LyricTokens.Space.md)) {
                    Text(day.day, modifier = Modifier.weight(1f))
                    Text("${day.durationSec / 60} 分 ${day.durationSec % 60} 秒", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
    DayDialog(vm)
    MeNotices(vm)
}

@Composable
private fun DayDialog(vm: MeViewModel) {
    val day by vm.selectedDay.collectAsStateWithLifecycle()
    val books by vm.dayBooks.collectAsStateWithLifecycle()
    if (day != null) AlertDialog(onDismissRequest = { vm.selectedDay.value = null }, title = { Text(day.toString()) },
        text = { Column(Modifier.heightIn(max = LyricTokens.Component.dialogMaxHeight).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.md)) {
            if (books.isEmpty()) Text("这一天留白。")
            books.forEach { Text("${it.title} · ${it.durationSec / 60} 分 ${it.durationSec % 60} 秒") }
        } }, confirmButton = { TextButton(onClick = { vm.selectedDay.value = null }, shape = LyricTokens.Radius.buttonShape) { Text("收起") } })
}

@Composable
internal fun MeNotices(vm: MeViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    if (busy) androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Surface(shape = RoundedCornerShape(LyricTokens.Radius.card)) { Row(Modifier.padding(LyricTokens.Space.xl), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(LyricTokens.Space.xl), strokeWidth = LyricTokens.Component.indicatorStroke); Text("  正在处理…")
        } }
    }
    notice?.let { message -> AlertDialog(onDismissRequest = { vm.notice.value = null },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.notice.value = null }, shape = LyricTokens.Radius.buttonShape) { Text("知道了") } }) }
}
