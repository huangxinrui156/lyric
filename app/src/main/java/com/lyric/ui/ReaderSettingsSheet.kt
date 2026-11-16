@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.core.*
import com.lyric.data.SettingsScope
import java.io.File
import kotlinx.coroutines.*

private val brightnessLevels = listOf(.01f) + (1..10).map { it / 10f }
private val fontSizeLevels = (12..36).map { it.toFloat() }
private val pageModeLabels = PageMode.entries.map { it.label }

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
            androidx.compose.foundation.shape.RoundedCornerShape(topStart = LyricTokens.Radius.card, topEnd = LyricTokens.Radius.card),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = LyricTokens.Component.settingsMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = LyricTokens.Page.margin)
                .padding(bottom = LyricTokens.Space.xl)
                // 0.8.3: no paper grain here — it was redrawn over the whole scroll content
                // on every frame of the sheet's enter animation.
        ) {
            if (advanced) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("阅读排版", fontFamily = Serif, style = LyricTokens.Type.title)
                    TextButton(onClick = { vm.resetSettings(scope) }, shape = LyricTokens.Radius.buttonShape) {
                        Text("恢复继承", style = LyricTokens.Type.auxiliary)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.sm)) {
                    SettingsScope.entries.forEach { item ->
                        FilterChip(
                            selected = scope == item,
                            onClick = { scope = item },
                            label = { Text(item.label, style = LyricTokens.Type.auxiliary) },
                            shape = LyricTokens.Radius.pill,
                        )
                    }
                }
                Text(
                    "全局 → 文字书籍 → 本书，逐层覆盖",
                    style = LyricTokens.Type.auxiliary,
                    color = Stone,
                    modifier = Modifier.padding(bottom = LyricTokens.Space.sm),
                )
            }
            SettingStepper("亮度", prefs.brightness, brightnessLevels, "${kotlin.math.round(prefs.brightness * 100).toInt()}%") {
                update(prefs.copy(brightness = it))
            }
            TypesetPresetRow(prefs, ::update)
            Row(
                Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("主题", color = Stone, style = LyricTokens.Type.auxiliary)
                ReaderTheme.entries.take(4).forEach { theme ->
                    Box(
                        Modifier.size(LyricTokens.Component.buttonHeight)
                            .border(
                                if (prefs.theme == theme) LyricTokens.Component.indicatorStroke else LyricTokens.strokeWidth,
                                if (prefs.theme == theme) Vermilion
                                else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                            .padding(LyricTokens.Space.xs)
                            .background(Color(theme.bg), CircleShape)
                            .clickable { update(prefs.copy(theme = theme, followSystem = false)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(theme.label, color = Color(theme.ink), style = LyricTokens.Type.auxiliary)
                    }
                }
            }
            ChoiceRow(
                "字体",
                listOf(LyricTokens.Fonts.songLabel, LyricTokens.Fonts.heiLabel, if (prefs.font.startsWith('/')) "已导入" else "导入"),
                when {
                    LyricTokens.Fonts.isCustom(prefs.font) -> 2
                    LyricTokens.Fonts.resolveKey(prefs.font) == LyricTokens.Fonts.songKey -> 0
                    else -> 1
                },
            ) { index ->
                when (index) {
                    0 -> update(prefs.copy(font = LyricTokens.Fonts.songKey))
                    1 -> update(prefs.copy(font = LyricTokens.Fonts.heiKey))
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
                pageModeLabels,
                PageMode.entries.indexOf(prefs.pageMode),
            ) {
                update(prefs.copy(pageMode = PageMode.entries[it]))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = LyricTokens.Space.md),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                TextButton(onClick = onToc, shape = LyricTokens.Radius.buttonShape) {
                    Icon(Icons.Outlined.FormatListBulleted, null, Modifier.size(LyricTokens.Space.lg))
                    Text(" 目录", style = LyricTokens.Type.auxiliary)
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
                    },
                    shape = LyricTokens.Radius.buttonShape,
                ) {
                    Icon(Icons.Outlined.DarkMode, null, Modifier.size(LyricTokens.Space.lg))
                    Text(" 夜间", style = LyricTokens.Type.auxiliary)
                }
                TextButton(onClick = { advanced = !advanced }, shape = LyricTokens.Radius.buttonShape) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(LyricTokens.Space.lg))
                    Text(if (advanced) " 收起" else " 更多", style = LyricTokens.Type.auxiliary)
                }
            }
            if (advanced) {
                HorizontalDivider(Modifier.padding(vertical = LyricTokens.Space.lg))
                SettingStepper("字号", prefs.fontSize, fontSizeLevels, "${prefs.fontSize.toInt()} sp") {
                    update(prefs.copy(fontSize = it))
                }
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
                Text("遮光可进一步降低屏幕亮度。", style = LyricTokens.Type.auxiliary, color = Stone)
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
                HorizontalDivider(Modifier.padding(vertical = LyricTokens.Space.lg))
                Text("点击区域", fontFamily = Serif, style = LyricTokens.Type.subtitle)
                Text(
                    "左侧上一页 · 中央菜单 · 右侧下一页",
                    style = LyricTokens.Type.auxiliary,
                    color = Stone,
                    modifier = Modifier.padding(vertical = LyricTokens.Space.sm),
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
                Text("阅读信息栏", fontFamily = Serif, style = LyricTokens.Type.subtitle)
                ReaderInfoToggles(prefs, ::update)
                if (state.row?.extension == "epub") {
                    HorizontalDivider(Modifier.padding(vertical = LyricTokens.Space.lg))
                    SettingToggle("采用书籍样式", prefs.useBookStyle) {
                        update(prefs.copy(useBookStyle = it))
                    }
                    Text(
                        "采用书中字体、对齐、缩进及字号。图片与注音始终显示。",
                        style = LyricTokens.Type.auxiliary,
                        color = Stone,
                        )
                }
                if (state.row?.extension == "txt") {
                    HorizontalDivider(Modifier.padding(vertical = LyricTokens.Space.lg))
                    Text("文本解析 · 仅本书", fontFamily = Serif, style = LyricTokens.Type.subtitle)
                    Text(
                        "当前编码：${state.row?.encoding}",
                        style = LyricTokens.Type.auxiliary,
                        color = Stone,
                        modifier = Modifier.padding(vertical = LyricTokens.Space.sm),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs)) {
                        Encoding.choices.forEach { encoding ->
                            FilterChip(
                                selected = (state.row?.encodingOverride ?: "自动") == encoding,
                                onClick = { vm.reparse(encoding, state.row?.chapterRegex) },
                                label = { Text(encoding, style = LyricTokens.Type.auxiliary) },
                                shape = LyricTokens.Radius.pill,
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
                        label = { Text("自定义章节正则（留空恢复默认）", style = LyricTokens.Type.auxiliary) },
                        isError = !valid,
                        supportingText = {
                            Text(
                                if (valid) "按整行匹配；仅检测不超过 160 字符的行。" else "正则表达式无效",
                                style = LyricTokens.Type.auxiliary,
                            )
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = LyricTokens.Space.md),
                        minLines = 2,
                    )
                    Button(
                        onClick = { vm.reparse(state.row?.encodingOverride, rule) },
                        enabled = valid && !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = LyricTokens.Radius.buttonShape,
                    ) {
                        Text(if (state.loading) "正在重新解析…" else "应用章节规则")
                    }
                }
                Spacer(Modifier.height(LyricTokens.Space.md))
            }
        }
    }
}

@Composable
internal fun TypesetPresetRow(prefs: ReaderSettings, update: (ReaderSettings) -> Unit) {
    ChoiceRow("排版", LyricTokens.Reading.presetLabels, prefs.matchedTypesetPresetIndex()) { index ->
        update(prefs.applyTypesetPreset(LyricTokens.Reading.presets[index]))
    }
}

/** Explicit levels; existing fractional preferences are preserved until the user taps. */
@Composable
internal fun SettingStepper(label: String, value: Float, levels: List<Float>, suffix: String, change: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = LyricTokens.Component.touchTarget), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LyricTokens.Type.auxiliary, color = Stone, modifier = Modifier.weight(1f))
        IconButton(onClick = { levels.lastOrNull { it < value - .001f }?.let(change) }, enabled = value > levels.first()) {
            Icon(Icons.Outlined.Remove, "减少$label", tint = MaterialTheme.colorScheme.secondary)
        }
        Text(suffix, fontFamily = Serif, style = LyricTokens.Type.body, modifier = Modifier.width(LyricTokens.Component.settingValueWidth),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = { levels.firstOrNull { it > value + .001f }?.let(change) }, enabled = value < levels.last()) {
            Icon(Icons.Outlined.Add, "增加$label", tint = MaterialTheme.colorScheme.secondary)
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
        Modifier.fillMaxWidth().heightIn(min = LyricTokens.Component.touchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LyricTokens.Type.auxiliary, color = Stone, modifier = Modifier.width(LyricTokens.Component.settingsLabelWidth))
        Slider(
            value = value.coerceIn(range),
            onValueChange = change,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.secondary,
                activeTrackColor = MaterialTheme.colorScheme.secondary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.weight(1f).padding(horizontal = LyricTokens.Space.xs),
        )
        Text(
            suffix,
            fontFamily = Serif,
            style = LyricTokens.Type.body,
            modifier = Modifier.widthIn(min = LyricTokens.Component.switchWidth),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
internal fun ChoiceRow(label: String, options: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LyricTokens.Type.auxiliary, color = Stone, modifier = Modifier.width(LyricTokens.Component.touchTarget))
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(LyricTokens.Space.xs),
        ) {
            options.forEachIndexed { i, title ->
                FilterChip(
                    selected = selected == i,
                    onClick = { choose(i) },
                    label = { Text(title, style = LyricTokens.Type.auxiliary) },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    shape = LyricTokens.Radius.pill,
                )
            }
        }
    }
}

@Composable
internal fun SettingToggle(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = LyricTokens.Component.touchTarget),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LyricTokens.Type.body, modifier = Modifier.weight(1f).padding(end = LyricTokens.Space.sm))
        Switch(checked = checked, onCheckedChange = change)
    }
}

@Composable
private fun ColorField(label: String, value: Long, change: (Long) -> Unit) {
    var hex by remember(value) { mutableStateOf("%06X".format(value and LyricTokens.Overlay.rgbMask)) }
    OutlinedTextField(
        value = hex,
        onValueChange = { text ->
            hex = text.take(6)
            if (text.length == 6) text.toLongOrNull(16)?.let { change(LyricTokens.Overlay.opaqueArgb or it) }
        },
        label = { Text("$label · 六位 HEX") },
        leadingIcon = { Text("#") },
        isError = hex.length != 6 || hex.toLongOrNull(16) == null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.xs),
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
