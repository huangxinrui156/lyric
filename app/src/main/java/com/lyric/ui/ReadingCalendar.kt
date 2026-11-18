package com.lyric.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.lyric.data.CalendarDay
import com.lyric.data.DailyStat
import com.lyric.data.ReadingStats
import com.lyric.ui.theme.LyricTokens
import java.time.LocalDate
import java.time.YearMonth

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
private val heatmapAlpha = floatArrayOf(0f, .22f, .42f, .68f, 1f)
private val dayShape = RoundedCornerShape(LyricTokens.Radius.skeleton)

@Composable
internal fun ReadingCalendar(days: List<DailyStat>, today: LocalDate, select: (LocalDate) -> Unit) {
    var annual by rememberSaveable { mutableStateOf(false) }
    var selectedPeriod by rememberSaveable { mutableStateOf<String?>(null) }
    val currentMonth = YearMonth.from(today)
    val month = minOf(selectedPeriod?.let(YearMonth::parse) ?: currentMonth, currentMonth)
    val daysByDate = remember(days) { days.associateBy { it.day } }
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("时光留痕", fontFamily = Serif, style = LyricTokens.Type.subtitle, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { annual = !annual }, shape = LyricTokens.Radius.buttonShape) {
                Text(if (annual) "按月查看" else "按年查看")
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                selectedPeriod = (if (annual) month.minusYears(1) else month.minusMonths(1)).toString()
            }) { Icon(Icons.Outlined.ChevronLeft, if (annual) "上一年" else "上一个月") }
            Text(if (annual) "${month.year} 年" else "${month.year} 年 ${month.monthValue} 月",
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = LyricTokens.Type.subtitle)
            IconButton(enabled = if (annual) month.year < currentMonth.year else month < currentMonth, onClick = {
                val next = minOf(if (annual) month.plusYears(1) else month.plusMonths(1), currentMonth)
                selectedPeriod = next.takeUnless { it == currentMonth }?.toString()
            }) { Icon(Icons.Outlined.ChevronRight, if (annual) "下一年" else "下一个月") }
        }
        if (annual) {
            // Compose only visible months. Daily cells retain click and accessibility semantics.
            key(month.year) {
                LazyRow(Modifier.fillMaxWidth(), state = rememberLazyListState(initialFirstVisibleItemIndex = month.monthValue - 1),
                    horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.lg)) {
                    items(count = 12, key = { it + 1 }) { index ->
                        val shown = YearMonth.of(month.year, index + 1)
                        Column(verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs)) {
                            Text("${shown.monthValue} 月", style = LyricTokens.Type.auxiliary, color = colors.onSurfaceVariant)
                            CalendarMonth(shown, daysByDate, today, compact = true, select = select)
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs)) {
                weekdays.forEach { day ->
                    Text(day, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = LyricTokens.Type.auxiliary, color = colors.onSurfaceVariant)
                }
            }
            CalendarMonth(month, daysByDate, today, compact = false, select = select)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs)) {
            Text("少", style = LyricTokens.Type.auxiliary)
            for (level in 1..heatmapAlpha.lastIndex) {
                Box(Modifier.size(LyricTokens.Component.heatmapCell).background(colors.secondary.copy(alpha = heatmapAlpha[level])))
            }
            Text("多 · 15 / 30 / 60 分", style = LyricTokens.Type.auxiliary)
        }
    }
}

@Composable
private fun CalendarMonth(month: YearMonth, days: Map<String, DailyStat>, today: LocalDate, compact: Boolean, select: (LocalDate) -> Unit) {
    val weeks = remember(month, days) { ReadingStats.calendarMonth(days, month) }
    val grid = if (compact) Modifier else Modifier.fillMaxWidth()
    Column(grid, verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs)) {
        for (week in weeks) {
            Row(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs)) {
                for (day in week) {
                    val cell = if (compact) Modifier.size(LyricTokens.Component.annualHeatmapCell)
                        else Modifier.weight(1f).height(LyricTokens.Component.calendarDayHeight)
                    if (day == null || (compact && day.date > today)) Spacer(cell)
                    else CalendarCell(day, today, compact, cell, select)
                }
            }
        }
        if (compact) repeat(6 - weeks.size) { Spacer(Modifier.height(LyricTokens.Component.annualHeatmapCell)) }
    }
}

@Composable
private fun CalendarCell(day: CalendarDay, today: LocalDate, compact: Boolean, modifier: Modifier, select: (LocalDate) -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (day.date > today) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(day.date.dayOfMonth.toString(), style = LyricTokens.Type.body, color = colors.onSurfaceVariant)
        }
        return
    }
    val fill = if (day.level == 0) Color.Transparent else colors.secondary.copy(alpha = heatmapAlpha[day.level])
    val stroke = if (day.date == today) colors.secondary else colors.outlineVariant
    val description = remember(day) { "${day.date} 阅读 ${day.seconds / 60} 分钟" }
    Box(modifier.background(fill, dayShape).border(LyricTokens.strokeWidth, stroke, dayShape)
        .semantics { contentDescription = description }
        .clickable(role = Role.Button) { select(day.date) }, contentAlignment = Alignment.Center) {
        if (!compact) Text(day.date.dayOfMonth.toString(), style = LyricTokens.Type.body,
            color = if (day.level == heatmapAlpha.lastIndex) colors.onSecondary else colors.onSurface)
    }
}
