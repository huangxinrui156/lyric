@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lyric.ui

import com.lyric.ui.theme.LyricTokens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyric.BuildConfig
import com.lyric.core.*

@Composable
fun SettingsScreen(vm: MeViewModel, section: String, back: () -> Unit) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val ownBooks by vm.ownBooks.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    var includeFonts by rememberSaveable { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var clearCache by remember { mutableStateOf(false) }
    var clearInvalid by remember { mutableStateOf(false) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportBackup(it, includeFonts) }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::previewBackup) }
    val stats = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::exportStats) }
    val title = when (section) { "appearance" -> "外观"; "reading" -> "阅读偏好"; "data" -> "数据"; "storage" -> "存储"; "goal" -> "阅读目标"; else -> "关于 Lyric" }
    LaunchedEffect(section) { if (section == "storage") vm.inspect() }
    MePage(title, back) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding), verticalArrangement = Arrangement.spacedBy(LyricTokens.Space.md)) {
            when (section) {
                "appearance" -> {
                    ChoiceRow("应用", listOf("跟随系统", "浅色", "深色"), listOf("system", "light", "dark").indexOf(prefs.appTheme)) {
                        vm.update(prefs.copy(appTheme = listOf("system", "light", "dark")[it]))
                    }
                    Text("强调色", fontFamily = Serif, modifier = Modifier.padding(top = LyricTokens.Space.lg))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AccentPresets.forEachIndexed { index, accent ->
                            Box(Modifier.size(LyricTokens.Component.buttonHeight).border(if (prefs.accentColor == index) LyricTokens.Component.indicatorStroke else 0.dp,
                                if (prefs.accentColor == index) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .padding(LyricTokens.Space.xs).background(accent, CircleShape)
                                .clickable { vm.update(prefs.copy(accentColor = index)) }
                                .then(Modifier.semanticsLabel("${AccentNames[index]}${if (prefs.accentColor == index) "，已选中" else ""}")))
                        }
                    }
                    Text("默认阅读主题", fontFamily = Serif, modifier = Modifier.padding(top = LyricTokens.Space.lg))
                    ChoiceRow("纸色", ReaderTheme.entries.take(4).map { it.label }, ReaderTheme.entries.indexOf(prefs.theme)) {
                        vm.update(prefs.copy(theme = ReaderTheme.entries[it], followSystem = false))
                    }
                    SettingToggle("阅读主题跟随系统深色", prefs.followSystem) { vm.update(prefs.copy(followSystem = it)) }
                    Hint("主题与强调色即时生效。默认阅读主题应用于继承全局设置的书籍。")
                }
                "reading" -> {
                    Text("$ownBooks 本书有单独设置", fontFamily = Serif, style = LyricTokens.Type.title)
                    TextButton(enabled = ownBooks > 0, onClick = { reset = true }, shape = LyricTokens.Radius.buttonShape) { Text("一键恢复所有书籍继承") }
                    Hint("这里直接编辑全局层。文字书籍层和本书层仍可覆盖全局；恢复继承只移除本书层。")
                    TypesetSettingsBlock(prefs) { vm.update(it) }
                    ChoiceRow("字体", listOf(LyricTokens.Fonts.songLabel, LyricTokens.Fonts.heiLabel),
                        listOf(LyricTokens.Fonts.songKey, LyricTokens.Fonts.heiKey).indexOf(LyricTokens.Fonts.resolveKey(prefs.font)).coerceAtLeast(0)) {
                        vm.update(prefs.copy(font = listOf(LyricTokens.Fonts.songKey, LyricTokens.Fonts.heiKey)[it]))
                    }
                    SettingToggle("首行缩进", prefs.indent) { vm.update(prefs.copy(indent = it)) }
                    SettingToggle("两端对齐", prefs.justify) { vm.update(prefs.copy(justify = it)) }
                    ChoiceRow("翻页", PageMode.entries.map { it.label }, PageMode.entries.indexOf(prefs.pageMode)) { vm.update(prefs.copy(pageMode = PageMode.entries[it])) }
                    SettingSlider("左区宽", prefs.tapLeft, .1f.. .4f, "${(prefs.tapLeft * 100).toInt()}%") { vm.update(prefs.copy(tapLeft = it)) }
                    SettingSlider("右区宽", 1 - prefs.tapRight, .1f.. .4f, "${((1 - prefs.tapRight) * 100).toInt()}%") { vm.update(prefs.copy(tapRight = 1 - it)) }
                    SettingToggle("左右翻页互换", prefs.reverseTaps) { vm.update(prefs.copy(reverseTaps = it)) }
                    SettingToggle("音量键翻页", prefs.volumeKeys) { vm.update(prefs.copy(volumeKeys = it)) }
                    SettingToggle("音量键方向互换", prefs.swapVolumeKeys) { vm.update(prefs.copy(swapVolumeKeys = it)) }
                    ReaderInfoToggles(prefs) { vm.update(it) }
                    SettingToggle("阅读时保持屏幕常亮", prefs.keepScreenOn) { vm.update(prefs.copy(keepScreenOn = it)) }
                    ChoiceRow("方向", listOf("跟随系统", "竖屏", "横屏"), listOf("system", "portrait", "landscape").indexOf(prefs.orientation)) {
                        vm.update(prefs.copy(orientation = listOf("system", "portrait", "landscape")[it]))
                    }
                }
                "goal" -> {
                    SettingToggle("开启每日阅读目标", prefs.goalEnabled) { vm.update(prefs.copy(goalEnabled = it)) }
                    SettingSlider("分钟", prefs.dailyGoalMin.toFloat(), 5f..120f, "${prefs.dailyGoalMin}") { vm.update(prefs.copy(dailyGoalMin = it.toInt())) }
                    Hint("默认关闭。开启后在「我的」显示细环进度，达成时轻轻填满；没有推送和打卡弹窗。")
                }
                "data" -> {
                    Text("把阅读的痕迹带走", fontFamily = Serif, style = LyricTokens.Type.title)
                    Hint("备份包含书籍索引、进度、书签、阅读记录和设置，不包含书籍原文件。请另行保管原文件。")
                    SettingToggle("备份时包含导入的字体", includeFonts) { includeFonts = it }
                    Button(onClick = { backup.launch(vm.backupFilename) }, modifier = Modifier.fillMaxWidth(), shape = LyricTokens.Radius.buttonShape) { Text("导出本地备份 zip") }
                    OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth(), shape = LyricTokens.Radius.buttonShape) { Text("选择备份并预览") }
                    OutlinedButton(onClick = { stats.launch("lyric-stats-${java.time.LocalDate.now()}.csv") }, modifier = Modifier.fillMaxWidth(), shape = LyricTokens.Radius.buttonShape) { Text("导出统计 CSV") }
                    Hint("恢复按 SHA-256 文件哈希匹配；找不到原文件的书会保留进度和摘录，并提示重新导入。相同书籍保留更新的进度，重复恢复不会重复添加摘录或时长。")
                    HorizontalDivider()
                    Text("WebDAV · 尚未实现（P2）", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                "storage" -> {
                    Text("本机占用", fontFamily = Serif, style = LyricTokens.Type.title)
                    StorageRow("解析与临时缓存", usage.cache)
                    StorageRow("导入字体", usage.fonts)
                    StorageRow("封面", usage.covers)
                    Button(onClick = { clearCache = true }, modifier = Modifier.fillMaxWidth(), shape = LyricTokens.Radius.buttonShape) { Text("清理缓存") }
                    Hint("清理解析、图片与临时缓存；再次打开书籍时自动重建。字体、书架数据、封面原件和书籍原文件均保留。")
                    OutlinedButton(onClick = { clearInvalid = true }, shape = LyricTokens.Radius.buttonShape) { Text("移除失效书籍") }
                    Hint("文件移动或授权失效时，可在「本地导入」重新选择同一文件修复；也可移除失效项，保留阅读足迹和摘录。")
                    TextButton(onClick = vm::inspect, shape = LyricTokens.Radius.buttonShape) { Text("重新计算") }
                }
                else -> AboutContent()
            }
            Spacer(Modifier.height(LyricTokens.Space.xl))
        }
    }
    if (reset) Confirm("恢复继承", "移除 $ownBooks 本书的单独设置，之后使用全局与文字书籍层的默认值。", { reset = false }) { reset = false; vm.resetInheritance() }
    if (clearCache) Confirm("清理缓存", "下次打开书籍将重新解析；不会删除原文件或阅读数据。", { clearCache = false }) { clearCache = false; vm.clearCache() }
    if (clearInvalid) Confirm("移除失效书籍", "仅移除无法访问的书架项和相应进度。阅读足迹与书签摘录仍会保留。", { clearInvalid = false }) { clearInvalid = false; vm.removeInvalid() }
    preview?.let { p ->
        Confirm("恢复前预览", "书籍 ${p.data.books.size} 本（已匹配 ${p.matched}，需重新导入 ${p.missing}）\n进度 ${p.data.progress.size} 条\n书签与摘录 ${p.data.bookmarks.size} 条\n阅读记录 ${p.data.sessions.size} 条\n读完 ${p.data.finished.size} 本\n设置 ${p.data.settings.strings.size} 层\n字体 ${p.fonts.size} 个\n\n确认合并阅读数据并替换设置？", { vm.preview.value = null }, vm::restore)
    }
    MeNotices(vm)
}

@Composable
private fun AboutContent() {
    val context = LocalContext.current
    var licenses by rememberSaveable { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    Text("lyric", style = LyricTokens.Type.wordmark)
    var taps by remember { mutableIntStateOf(0) }
    var selfTest by remember { mutableStateOf(false) }
    Text("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { if (BuildConfig.DEBUG && ++taps >= 7) { taps = 0; selfTest = true } })
    if (selfTest) SelfTestDialog { selfTest = false }
    Text("隐私声明", fontFamily = Serif, style = LyricTokens.Type.title, modifier = Modifier.padding(top = LyricTokens.Space.lg))
    Hint("不采集、不上传任何数据。所有书籍、进度、摘录、阅读统计与设置仅存本机。没有账号、广告、分析 SDK 或遥测。应用未声明 INTERNET 网络权限，不访问网络；本地文件仅使用系统文件选择器授予的权限读取。仅在你主动导出时，将备份或摘录写到你选择的位置。卸载应用将删除私有数据。")
    TextButton(onClick = { licenses = !licenses }, shape = LyricTokens.Radius.buttonShape) { Text(if (licenses) "收起开源许可" else "查看开源许可") }
    if (licenses) {
        LaunchedEffect(Unit) { notice = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.assets.open("open-source-notices.txt").bufferedReader().use { it.readText() } } }
        Text(notice, style = LyricTokens.Type.auxiliary, )
    }
    Text("反馈", fontFamily = Serif, style = LyricTokens.Type.title)
    Hint("本仓库尚未配置维护者反馈邮箱。可通过项目 issue 或向 APK 提供者反馈；应用不会自动发送日志或个人数据。")
}

@Composable private fun StorageRow(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = LyricTokens.Space.sm), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Text(if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "%.1f KB".format(bytes / 1024f) else "%.1f MB".format(bytes / (1024f * 1024)), color = MaterialTheme.colorScheme.secondary)
    }
}
@Composable private fun Hint(text: String) { Text(text, style = LyricTokens.Type.auxiliary, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun Confirm(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, fontFamily = Serif) }, text = { Text(body) },
        dismissButton = { TextButton(onClick = dismiss, shape = LyricTokens.Radius.buttonShape) { Text("取消") } }, confirmButton = { TextButton(onClick = confirm, shape = LyricTokens.Radius.buttonShape) { Text("确认") } })
}
private fun Modifier.semanticsLabel(label: String) = this.then(Modifier.semantics { contentDescription = label })
