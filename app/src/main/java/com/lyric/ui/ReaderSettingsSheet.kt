@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.lyric.ui

import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.core.*
import com.lyric.data.SettingsScope
import java.io.File
import kotlinx.coroutines.*

@Composable
fun ReaderSettingsSheet(vm: ReaderViewModel, onDismiss: () -> Unit, onToc: () -> Unit) {
    val prefs by vm.settings.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var scope by rememberSaveable { mutableStateOf(SettingsScope.BOOK) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fontPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                coroutineScope.launch {
                    try {
                        val file =
                            withContext(Dispatchers.IO) {
                                val dir = File(context.filesDir, "fonts").apply { mkdirs() }
                                val temp = File.createTempFile("font-", ".ttf", dir)
                                try {
                                    val bytes =
                                        context.contentResolver.openInputStream(uri)?.use { input ->
                                            input.readBounded(30 * 1024 * 1024 + 1)
                                        } ?: error("无法打开字体")
                                    require(bytes.size <= 30 * 1024 * 1024) { "字体超过 30MB" }
                                    temp.writeBytes(bytes)
                                    Typeface.createFromFile(temp) // Validate before remembering it.
                                    temp
                                } catch (e: Exception) {
                                    temp.delete()
                                    throw e
                                }
                            }
                        vm.updateSettings(scope, vm.settings.value.copy(font = file.absolutePath))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        vm.notice.value = "字体导入失败：${e.localizedMessage}"
                    }
                }
            }
        }
    fun update(next: ReaderSettings) = vm.updateSettings(scope, next)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        shape =
            androidx.compose.foundation.shape.RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 24.dp)
                // 0.8.3: no paper grain here — it was redrawn over the whole scroll content
                // on every frame of the sheet's enter animation.
        ) {
            if (advanced) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("阅读排版", fontFamily = Serif, fontSize = 20.sp)
                    TextButton(onClick = { vm.resetSettings(scope) }) {
                        Text("恢复继承", fontSize = 11.sp)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsScope.entries.forEach { item ->
                        FilterChip(
                            selected = scope == item,
                            onClick = { scope = item },
                            label = { Text(item.label, fontSize = 11.sp) },
                        )
                    }
                }
                Text(
                    "全局 → 文字书籍 → 本书，逐层覆盖",
                    fontSize = 10.sp,
                    color = Stone,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            SettingStepper("亮度", prefs.brightness, listOf(.01f) + (1..10).map { it / 10f }, "${kotlin.math.round(prefs.brightness * 100).toInt()}%") {
                update(prefs.copy(brightness = it))
            }
            SettingStepper("字号", prefs.fontSize, (12..36).map { it.toFloat() }, "${prefs.fontSize.toInt()} sp") {
                update(prefs.copy(fontSize = it))
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("主题", color = Stone, fontSize = 12.sp)
                ReaderTheme.entries.take(4).forEach { theme ->
                    Box(
                        Modifier.size(44.dp)
                            .border(
                                if (prefs.theme == theme) 2.dp else .5.dp,
                                if (prefs.theme == theme) Vermilion
                                else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                            .padding(3.dp)
                            .background(Color(theme.bg), CircleShape)
                            .clickable { update(prefs.copy(theme = theme, followSystem = false)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(theme.label, color = Color(theme.ink), fontSize = 10.sp)
                    }
                }
            }
            ChoiceRow(
                "字体",
                listOf("宋体", "黑体", "等宽", if (prefs.font.startsWith('/')) "已导入" else "导入"),
                when (prefs.font) {
                    "serif" -> 0
                    "sans-serif" -> 1
                    "monospace" -> 2
                    else -> 3
                },
            ) { index ->
                when (index) {
                    0 -> update(prefs.copy(font = "serif"))
                    1 -> update(prefs.copy(font = "sans-serif"))
                    2 -> update(prefs.copy(font = "monospace"))
                    else ->
                        fontPicker.launch(
                            arrayOf(
                                "font/ttf",
                                "font/otf",
                                "application/x-font-ttf",
                                "application/x-font-opentype",
                                "application/octet-stream",
                            )
                        )
                }
            }
            ChoiceRow(
                "翻页",
                PageMode.entries.map { it.label },
                PageMode.entries.indexOf(prefs.pageMode),
            ) {
                update(prefs.copy(pageMode = PageMode.entries[it]))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                TextButton(onClick = onToc) {
                    Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(16.dp))
                    Text(" 目录", fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        update(
                            prefs.copy(
                                theme =
                                    if (prefs.theme == ReaderTheme.NIGHT) ReaderTheme.PAPER
                                    else ReaderTheme.NIGHT,
                                followSystem = false,
                            )
                        )
                    }
                ) {
                    Icon(Icons.Outlined.DarkMode, null, Modifier.size(16.dp))
                    Text(" 夜间", fontSize = 12.sp)
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(16.dp))
                    Text(if (advanced) " 收起" else " 更多", fontSize = 12.sp)
                }
            }
            if (advanced) {
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                SettingSlider("行距", prefs.lineHeight, 1.1f..2.5f, "%.1f".format(prefs.lineHeight)) {
                    update(prefs.copy(lineHeight = it))
                }
                SettingSlider(
                    "段距",
                    prefs.paragraphSpace,
                    0f..1.5f,
                    "%.1f".format(prefs.paragraphSpace),
                ) {
                    update(prefs.copy(paragraphSpace = it))
                }
                SettingSlider("页边距", prefs.margin, 12f..52f, "${prefs.margin.toInt()}") {
                    update(prefs.copy(margin = it))
                }
                SettingToggle("首行缩进", prefs.indent) { update(prefs.copy(indent = it)) }
                SettingToggle("两端对齐", prefs.justify) { update(prefs.copy(justify = it)) }
                SettingToggle("跟随系统深色", prefs.followSystem) {
                    update(prefs.copy(followSystem = it))
                }
                SettingSlider("夜间遮光", prefs.dim, 0f..0.85f, "${(prefs.dim * 100).toInt()}%") {
                    update(prefs.copy(dim = it))
                }
                Text("遮光可进一步降低屏幕亮度。", fontSize = 10.sp, color = Stone)
                ChoiceRow(
                    "深色",
                    listOf("深绿", "纯黑", "自定"),
                    when (prefs.theme) {
                        ReaderTheme.GREEN -> 0
                        ReaderTheme.BLACK -> 1
                        ReaderTheme.CUSTOM -> 2
                        else -> -1
                    },
                ) {
                    update(
                        prefs.copy(
                            theme =
                                listOf(ReaderTheme.GREEN, ReaderTheme.BLACK, ReaderTheme.CUSTOM)[
                                    it],
                            followSystem = false,
                        )
                    )
                }
                if (prefs.theme == ReaderTheme.CUSTOM) {
                    ColorField("背景色", prefs.customBg) { update(prefs.copy(customBg = it)) }
                    ColorField("文字色", prefs.customInk) { update(prefs.copy(customInk = it)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("点击区域", fontFamily = Serif, fontSize = 17.sp)
                Text(
                    "左侧上一页 · 中央菜单 · 右侧下一页",
                    fontSize = 11.sp,
                    color = Stone,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                SettingSlider(
                    "左区宽度",
                    prefs.tapLeft,
                    0.1f..0.4f,
                    "${(prefs.tapLeft * 100).toInt()}%",
                ) {
                    update(prefs.copy(tapLeft = it))
                }
                SettingSlider(
                    "右区宽度",
                    1f - prefs.tapRight,
                    0.1f..0.4f,
                    "${((1 - prefs.tapRight) * 100).toInt()}%",
                ) {
                    update(prefs.copy(tapRight = 1f - it))
                }
                SettingToggle("左右翻页互换", prefs.reverseTaps) { update(prefs.copy(reverseTaps = it)) }
                SettingToggle("音量键翻页", prefs.volumeKeys) { update(prefs.copy(volumeKeys = it)) }
                SettingToggle("音量键方向互换", prefs.swapVolumeKeys) { update(prefs.copy(swapVolumeKeys = it)) }
                Text("阅读信息栏", fontFamily = Serif, fontSize = 17.sp)
                ReaderInfoToggles(prefs, ::update)
                if (state.row?.extension == "epub") {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    SettingToggle("采用书籍样式", prefs.useBookStyle) {
                        update(prefs.copy(useBookStyle = it))
                    }
                    Text(
                        "采用书中字体、对齐、缩进及字号。图片与注音始终显示。",
                        fontSize = 11.sp,
                        color = Stone,
                        lineHeight = 20.sp,
                    )
                }
                if (state.row?.extension == "txt") {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("文本解析 · 仅本书", fontFamily = Serif, fontSize = 17.sp)
                    Text(
                        "当前编码：${state.row?.encoding}",
                        fontSize = 11.sp,
                        color = Stone,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Encoding.choices.forEach { encoding ->
                            FilterChip(
                                selected = (state.row?.encodingOverride ?: "自动") == encoding,
                                onClick = { vm.reparse(encoding, state.row?.chapterRegex) },
                                label = { Text(encoding, fontSize = 10.sp) },
                            )
                        }
                    }
                    var rule by
                        remember(state.row?.chapterRegex) {
                            mutableStateOf(state.row?.chapterRegex.orEmpty())
                        }
                    val valid = remember(rule) { runCatching { TxtParser.rule(rule) }.isSuccess }
                    OutlinedTextField(
                        value = rule,
                        onValueChange = { rule = it },
                        label = { Text("自定义章节正则（留空恢复默认）", fontSize = 12.sp) },
                        isError = !valid,
                        supportingText = {
                            Text(
                                if (valid) "按整行匹配；仅检测不超过 160 字符的行。" else "正则表达式无效",
                                fontSize = 10.sp,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        minLines = 2,
                    )
                    Button(
                        onClick = { vm.reparse(state.row?.encodingOverride, rule) },
                        enabled = valid && !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.loading) "正在重新解析…" else "应用章节规则")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

/** Explicit levels; existing fractional preferences are preserved until the user taps. */
@Composable
internal fun SettingStepper(label: String, value: Float, levels: List<Float>, suffix: String, change: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = Stone, modifier = Modifier.weight(1f))
        IconButton(onClick = { levels.lastOrNull { it < value - .001f }?.let(change) }, enabled = value > levels.first()) {
            Icon(Icons.Outlined.Remove, "减少$label", tint = MaterialTheme.colorScheme.primary)
        }
        Text(suffix, fontFamily = Serif, fontSize = 14.sp, modifier = Modifier.width(68.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { levels.firstOrNull { it > value + .001f }?.let(change) }, enabled = value < levels.last()) {
            Icon(Icons.Outlined.Add, "增加$label", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    change: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = Stone, modifier = Modifier.width(58.dp))
        Slider(
            value = value.coerceIn(range),
            onValueChange = change,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        Text(
            suffix,
            fontFamily = Serif,
            fontSize = 13.sp,
            modifier = Modifier.widthIn(min = 30.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
internal fun ChoiceRow(label: String, options: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = Stone, modifier = Modifier.width(48.dp))
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { i, title ->
                FilterChip(
                    selected = selected == i,
                    onClick = { choose(i) },
                    label = { Text(title, fontSize = 11.sp) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                )
            }
        }
    }
}

@Composable
internal fun SettingToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = change)
    }
}

@Composable
private fun ColorField(label: String, value: Long, change: (Long) -> Unit) {
    var hex by remember(value) { mutableStateOf("%06X".format(value and 0xFFFFFF)) }
    OutlinedTextField(
        value = hex,
        onValueChange = { text ->
            hex = text.take(6)
            if (text.length == 6) text.toLongOrNull(16)?.let { change(0xFF000000 or it) }
        },
        label = { Text("$label · 六位 HEX") },
        leadingIcon = { Text("#") },
        isError = hex.length != 6 || hex.toLongOrNull(16) == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
fun ReaderInfoToggles(prefs: com.lyric.core.ReaderSettings, update: (com.lyric.core.ReaderSettings) -> Unit) {
    SettingToggle("章节名", prefs.showChapter) { update(prefs.copy(showChapter = it)) }
    SettingToggle("页码", prefs.showPage) { update(prefs.copy(showPage = it)) }
    SettingToggle("百分比", prefs.showPercent) { update(prefs.copy(showPercent = it)) }
    SettingToggle("时间", prefs.showTime) { update(prefs.copy(showTime = it)) }
    SettingToggle("电量", prefs.showBattery) { update(prefs.copy(showBattery = it)) }
}
