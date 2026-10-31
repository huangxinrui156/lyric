package com.lyric.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyric.core.Book
import com.lyric.core.Locator
import com.lyric.data.BookmarkRow
import com.lyric.data.HighlightRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Per-TOC-entry reading fraction (0..1) from absolute UTF-16 positions. */
fun tocProgress(book: Book, at: Locator): List<Float> {
    val prefix = LongArray(book.chapters.size + 1)
    book.chapters.forEachIndexed { i, c -> prefix[i + 1] = prefix[i] + c.text.length }
    fun abs(ch: Int, off: Int) = prefix[ch.coerceIn(0, book.chapters.size)] + off
    val cur = abs(at.chapterIndex, at.charOffset)
    return book.toc.mapIndexed { i, e ->
        val start = abs(e.chapterIndex, e.charOffset)
        val end = book.toc.getOrNull(i + 1)?.let { abs(it.chapterIndex, it.charOffset) } ?: prefix.last()
        when {
            end <= start -> if (cur >= start) 1f else 0f
            else -> ((cur - start).toFloat() / (end - start)).coerceIn(0f, 1f)
        }
    }
}

/** Bookmark sort: by time (newest first) or by position in the book. */
fun sortBookmarks(marks: List<BookmarkRow>, byChapter: Boolean) =
    if (byChapter) marks.sortedWith(compareBy({ it.chapterIndex }, { it.charOffset })) else marks.sortedByDescending { it.createdAt }

private val dateFmt get() = SimpleDateFormat("MM月dd日", Locale.CHINA)

@Composable
fun TocPanel(book: Book, locator: Locator, current: Int, state: LazyListState, go: (Locator) -> Unit) {
    val progress = remember(book, locator) { tocProgress(book, locator) }
    LazyColumn(state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(book.toc, key = { i, _ -> i }) { i, entry ->
            val on = i == current
            val p = progress.getOrElse(i) { 0f }
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f) else Color.Transparent)
                    .clickable { go(Locator(entry.chapterIndex, entry.charOffset)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(if (on) Vermilion else Color.Transparent))
                Column(Modifier.weight(1f).padding(start = 22.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) Box(Modifier.padding(end = 6.dp).size(5.dp).background(Vermilion, CircleShape))
                        Text(entry.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                    Text(
                        when { p >= .995f -> "已读完"; p > 0f -> "读到 ${(p * 100).toInt()}%"; else -> "未读" },
                        fontSize = 11.sp, color = Stone, modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Box(Modifier.padding(end = 20.dp, start = 12.dp).width(32.dp).height(3.dp).background(Linen)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Moss))
                }
            }
        }
    }
}

@Composable
fun NoteLine(note: String) {
    if (note.isNotBlank()) Text("✎ $note", color = Moss, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun BookmarkPanel(marks: List<BookmarkRow>, go: (BookmarkRow) -> Unit, note: (BookmarkRow) -> Unit, delete: (BookmarkRow) -> Unit) {
    var byChapter by rememberSaveable { mutableStateOf(false) }
    if (marks.isEmpty()) {
        Text("还没有书签\n在喜欢的字句之间，夹一片叶子。", lineHeight = 25.sp, color = Stone, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
        return
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { byChapter = !byChapter }) {
                Text(if (byChapter) "按章节 ▾" else "按时间 ▾", fontSize = 11.sp, color = Stone)
            }
        }
        LazyColumn {
            items(sortBookmarks(marks, byChapter), key = { it.id }) { mark ->
                Row(Modifier.fillMaxWidth().clickable { go(mark) }.padding(start = 24.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${mark.chapterTitle} · ${dateFmt.format(Date(mark.createdAt))}", fontSize = 11.sp, color = Stone)
                        Text(mark.excerpt, fontFamily = Serif, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 3, modifier = Modifier.padding(top = 6.dp))
                        NoteLine(mark.note)
                        Text(if (mark.note.isBlank()) "加备注" else "改备注", fontSize = 11.sp, color = Moss,
                            modifier = Modifier.padding(top = 4.dp).clickable { note(mark) })
                    }
                    IconButton(onClick = { delete(mark) }) { Icon(Icons.Outlined.Close, "删除书签", Modifier.size(18.dp), tint = Stone) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HighlightPanel(rows: List<HighlightRow>, go: (HighlightRow) -> Unit, note: (HighlightRow) -> Unit, delete: (HighlightRow) -> Unit) {
    if (rows.isEmpty()) {
        Text("还没有划线\n长按选中文字，点「划线」或「笔记」。\n长按一条划线可以写想法或删除。", lineHeight = 25.sp, color = Stone, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
        return
    }
    val groups = remember(rows) { rows.groupBy { it.chapterIndex }.toSortedMap() }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        groups.forEach { (_, list) ->
            item(key = "h-${list.first().chapterIndex}") {
                Text("${list.first().chapterTitle} · ${list.size} 条", fontSize = 11.sp, color = Stone,
                    modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 4.dp))
            }
            items(list, key = { it.id }) { h ->
                var actions by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                            .combinedClickable(onClick = { go(h) }, onLongClick = { actions = true })
                            .padding(start = 24.dp, end = 20.dp, top = 7.dp, bottom = 7.dp)
                    ) {
                        Box(Modifier.width(2.dp).fillMaxHeight().background(Vermilion))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(h.text, fontFamily = Serif, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
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
        confirmButton = { TextButton(onClick = { save(text); dismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}

/** Tap on an existing highlight in the text: 改颜色 / 想法 / 删除. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightOptionsSheet(h: HighlightRow, color: (Int) -> Unit, note: () -> Unit, delete: () -> Unit, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Vermilion))
                Text(h.text, fontFamily = Serif, fontSize = 14.sp, lineHeight = 22.sp, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
            }
            NoteLine(h.note)
            Text("颜色", fontSize = 12.sp, color = Stone, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                com.lyric.core.ThemePalette.highlightColors.forEachIndexed { i, rgb ->
                    val c = Color(0xFF000000 or rgb)
                    Box(Modifier.size(32.dp).background(c.copy(alpha = if (i == h.color) 1f else .55f), CircleShape)
                        .clickable { color(i); dismiss() })
                }
            }
            Row(Modifier.padding(top = 16.dp)) {
                TextButton(onClick = { dismiss(); note() }) { Text(if (h.note.isBlank()) "写想法" else "改想法", color = Moss) }
                TextButton(onClick = { dismiss(); delete() }) { Text("删除划线", color = Vermilion) }
            }
        }
    }
}
