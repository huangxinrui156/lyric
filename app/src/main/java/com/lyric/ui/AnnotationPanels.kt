package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.lyric.core.Book
import com.lyric.core.Locator
import com.lyric.data.BookmarkRow
import com.lyric.data.HighlightRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-TOC-entry reading fraction (0..1) from absolute UTF-16 positions. */
fun tocProgress(book: Book, at: Locator): List<Float> {
    val index = TocProgressIndex(book)
    val position = index.position(at)
    return book.toc.indices.map { index.fraction(it, position) }
}

/** Book geometry is built once; only visible TOC rows compute a fraction while reading. */
internal class TocProgressIndex(book: Book) {
    private val prefix = LongArray(book.chapters.size + 1) { book.textOffset(it) }
    private val starts = LongArray(book.toc.size) { i ->
        val e = book.toc[i]
        position(Locator(e.chapterIndex, book.chapters[e.chapterIndex].anchors[e.fragment] ?: e.charOffset))
    }
    fun position(at: Locator): Long = prefix[at.chapterIndex.coerceIn(0, prefix.lastIndex)] + at.charOffset
    fun fraction(index: Int, position: Long): Float {
        val start = starts[index]
        val end = starts.getOrNull(index + 1) ?: prefix.last()
        return if (end <= start) { if (position >= start) 1f else 0f }
        else ((position - start).toFloat() / (end - start)).coerceIn(0f, 1f)
    }
}

/** Bookmark sort: by time (newest first) or by position in the book. */
fun sortBookmarks(marks: List<BookmarkRow>, byChapter: Boolean) =
    if (byChapter) marks.sortedWith(compareBy({ it.chapterIndex }, { it.charOffset })) else marks.sortedByDescending { it.createdAt }

private val dateFmt get() = SimpleDateFormat("MM月dd日", Locale.CHINA)

@Composable
fun TocPanel(book: Book, locator: Locator, current: Int, state: LazyListState, go: (Locator) -> Unit) {
    val ready = book.deferred?.lengthsReady?.collectAsState()?.value ?: true
    val progress = remember(book, ready) { TocProgressIndex(book) }
    val position = progress.position(locator)
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = LyricTokens.Space.sm)) {
        itemsIndexed(book.toc, key = { i, _ -> i }) { i, entry ->
            val on = i == current
            val p = if (ready) progress.fraction(i, position) else if (entry.chapterIndex < locator.chapterIndex) 1f else 0f
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f) else Color.Transparent)
                    .clickable { go(Locator(entry.chapterIndex, entry.charOffset, fragment = entry.fragment)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(LyricTokens.Component.indicatorStroke).fillMaxHeight().background(if (on) Vermilion else Color.Transparent))
                Column(Modifier.weight(1f).padding(start = LyricTokens.Space.xl, top = LyricTokens.Space.md, bottom = LyricTokens.Space.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) Box(Modifier.padding(end = LyricTokens.Space.xs).size(LyricTokens.Component.statusDot).background(Vermilion, CircleShape))
                        Text(entry.title, style = LyricTokens.Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        when { p >= .995f -> "已读完"; p > 0f -> "读到 ${(p * 100).toInt()}%"; else -> "未读" },
                        style = LyricTokens.Type.auxiliary, color = Stone, modifier = Modifier.padding(top = LyricTokens.Space.xs),
                    )
                }
                Box(Modifier.padding(end = LyricTokens.Space.lg, start = LyricTokens.Space.md).width(LyricTokens.Space.xxl).height(LyricTokens.Component.progressStroke).background(Linen)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Moss))
                }
            }
        }
    }
}

@Composable
fun NoteLine(note: String) {
    if (note.isNotBlank()) Text("✎ $note", color = Moss, style = LyricTokens.Type.auxiliary, modifier = Modifier.padding(top = LyricTokens.Space.xs))
}

@Composable
fun BookmarkPanel(marks: List<BookmarkRow>, go: (BookmarkRow) -> Unit, note: (BookmarkRow) -> Unit, delete: (BookmarkRow) -> Unit) {
    var byChapter by rememberSaveable { mutableStateOf(false) }
    if (marks.isEmpty()) {
        Text("还没有书签\n在喜欢的字句之间，夹一片叶子。", color = Stone, style = LyricTokens.Type.body, modifier = Modifier.padding(LyricTokens.Space.xl))
        return
    }
    val sorted = remember(marks, byChapter) { sortBookmarks(marks, byChapter) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = LyricTokens.Page.margin), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { byChapter = !byChapter }, shape = LyricTokens.Radius.buttonShape) {
                Text(if (byChapter) "按章节 ▾" else "按时间 ▾", style = LyricTokens.Type.auxiliary, color = Stone)
            }
        }
        LazyColumn {
            items(sorted, key = { it.id }) { mark ->
                Row(Modifier.fillMaxWidth().clickable { go(mark) }.padding(start = LyricTokens.Space.xl, top = LyricTokens.Space.md, bottom = LyricTokens.Space.md, end = LyricTokens.Space.xs)) {
                    Column(Modifier.weight(1f)) {
                        Text("${mark.chapterTitle} · ${dateFmt.format(Date(mark.createdAt))}", style = LyricTokens.Type.auxiliary, color = Stone)
                        Text(mark.excerpt, fontFamily = Serif, style = LyricTokens.Type.body, maxLines = 3, modifier = Modifier.padding(top = LyricTokens.Space.xs))
                        NoteLine(mark.note)
                        Text(if (mark.note.isBlank()) "加备注" else "改备注", style = LyricTokens.Type.auxiliary, color = Moss,
                            modifier = Modifier.padding(top = LyricTokens.Space.xs).clickable { note(mark) })
                    }
                    IconButton(onClick = { delete(mark) }) { Icon(Icons.Outlined.Close, "删除书签", Modifier.size(LyricTokens.Component.smallIcon), tint = Stone) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HighlightPanel(rows: List<HighlightRow>, go: (HighlightRow) -> Unit, note: (HighlightRow) -> Unit, delete: (HighlightRow) -> Unit) {
    if (rows.isEmpty()) {
        Text("还没有划线\n长按选中文字，点「划线」或「笔记」。\n长按一条划线可以写想法或删除。", color = Stone, style = LyricTokens.Type.body, modifier = Modifier.padding(LyricTokens.Space.xl))
        return
    }
    val groups = remember(rows) { rows.groupBy { it.chapterIndex }.toSortedMap() }
    LazyColumn(contentPadding = PaddingValues(vertical = LyricTokens.Space.sm)) {
        groups.forEach { (_, list) ->
            item(key = "h-${list.first().chapterIndex}") {
                Text("${list.first().chapterTitle} · ${list.size} 条", style = LyricTokens.Type.auxiliary, color = Stone,
                    modifier = Modifier.padding(start = LyricTokens.Space.xl, top = LyricTokens.Space.md, bottom = LyricTokens.Space.xs))
            }
            items(list, key = { it.id }) { h ->
                var actions by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .combinedClickable(onClick = { go(h) }, onLongClick = { actions = true })
                            .padding(start = LyricTokens.Space.xl, end = LyricTokens.Space.lg, top = LyricTokens.Space.sm, bottom = LyricTokens.Space.sm)
                    ) {
                        Box(Modifier.width(LyricTokens.Component.indicatorStroke).fillMaxHeight().background(Vermilion))
                        Column(Modifier.padding(start = LyricTokens.Space.sm)) {
                            Text(h.text, fontFamily = Serif, style = LyricTokens.Type.body, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            NoteLine(h.note)
                        }
                    }
                    DropdownMenu(expanded = actions, onDismissRequest = { actions = false }) {
                        DropdownMenuItem(text = { Text(if (h.note.isBlank()) "写想法" else "改想法") }, onClick = { actions = false; note(h) })
                        DropdownMenuItem(text = { Text("删除划线") }, onClick = { actions = false; delete(h) })
                    }
                }
            }
        }
    }
}

@Composable
fun NoteDialog(title: String, initial: String, save: (String) -> Unit, dismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text("写点什么…") }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { save(text); dismiss() }, shape = LyricTokens.Radius.buttonShape) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss, shape = LyricTokens.Radius.buttonShape) { Text("取消") } },
    )
}

/** Tap on an existing highlight in the text: 改颜色 / 想法 / 删除. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightOptionsSheet(h: HighlightRow, color: (Int) -> Unit, note: () -> Unit, delete: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.background, shape = LyricTokens.Radius.cardShape) {
        Column(Modifier.padding(horizontal = LyricTokens.Page.margin).padding(bottom = LyricTokens.Space.xl)) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(LyricTokens.Component.indicatorStroke).fillMaxHeight().background(Vermilion))
                Text(h.text, fontFamily = Serif, style = LyricTokens.Type.body, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = LyricTokens.Space.sm))
            }
            NoteLine(h.note)
            Text("颜色", style = LyricTokens.Type.auxiliary, color = Stone, modifier = Modifier.padding(top = LyricTokens.Space.lg, bottom = LyricTokens.Space.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.md)) {
                com.lyric.core.ThemePalette.highlightColors.forEachIndexed { i, rgb ->
                    val c = Color(LyricTokens.Overlay.opaqueArgb or rgb)
                    Box(Modifier.size(LyricTokens.Space.xxl).background(c.copy(alpha = if (i == h.color) 1f else .55f), CircleShape)
                        .clickable { color(i); dismiss() })
                }
            }
            Row(Modifier.padding(top = LyricTokens.Space.lg)) {
                TextButton(onClick = { dismiss(); note() }, shape = LyricTokens.Radius.buttonShape) { Text(if (h.note.isBlank()) "写想法" else "改想法", color = Moss) }
                TextButton(onClick = { dismiss(); delete() }, shape = LyricTokens.Radius.buttonShape) { Text("删除划线", color = Vermilion) }
            }
        }
    }
}
