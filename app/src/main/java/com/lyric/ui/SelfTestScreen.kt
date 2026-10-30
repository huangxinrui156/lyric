package com.lyric.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lyric.LyricApp
import com.lyric.reader.KillCheck
import com.lyric.reader.SelfTest
import kotlinx.coroutines.launch

/** Hidden 「性能自测」: tap the version line 7 times in 关于. Runs without adb or a computer. */
@Composable
fun SelfTestDialog(dismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as LyricApp
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var lowEnd by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var metrics by remember { mutableStateOf<List<SelfTest.Metric>>(emptyList()) }
    var killOn by remember { mutableStateOf(KillCheck.enabled(context)) }
    var kills by remember { mutableStateOf(KillCheck.results(context)) }
    val report = SelfTest.report(context, metrics, kills)
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().testTag("selftest")) {
            Column(Modifier.verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp)) {
                Text("性能自测", fontFamily = Serif, fontSize = 26.sp)
                Text(SelfTest.deviceLine(context), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Text("一、性能基准", fontFamily = Serif, fontSize = 18.sp)
                Text("在本机内运行：10MB TXT 冷打开、首屏、翻页绘制 P95、搜索首条、漫画大图解码。约 10–40 秒，期间请勿离开。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(lowEnd, { lowEnd = it }); Text("按入门机标准判定（翻页 ≤25ms、冷打开 <3s）", fontSize = 13.sp)
                }
                Button(enabled = !running, onClick = {
                    running = true
                    scope.launch {
                        metrics = runCatching { SelfTest.run(context, app.repository, lowEnd) { progress = it } }
                            .getOrElse { listOf(SelfTest.Metric("运行失败：${it.javaClass.simpleName} ${it.message}", 0.0, "", 0.0 - 1)) }
                        progress = "完成"; running = false
                    }
                }) { Text(if (running) "运行中…" else "开始基准测试") }
                if (running || progress.isNotBlank()) Text(progress, fontSize = 12.sp)
                Spacer(Modifier.height(20.dp))
                Text("二、杀进程自检", fontFamily = Serif, fontSize = 18.sp)
                Text("打开开关后去读一本书：翻几页、停 2 秒，然后从最近任务划掉 Lyric，或在 设置 › 应用 › Lyric 里「强制停止」。" +
                    "重新打开 Lyric 后回到这里查看结果。每种方式各做 20 次。判定：进度偏差 0、时长损失 ≤15 秒。",
                    fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(killOn, { killOn = it; KillCheck.setEnabled(context, it) })
                    Spacer(Modifier.width(8.dp)); Text(if (killOn) "已开启（${kills.size} 次结果）" else "已关闭")
                    Spacer(Modifier.weight(1f))
                    TextButton({ kills = KillCheck.results(context) }) { Text("刷新") }
                    TextButton({ KillCheck.clear(context); kills = emptyList() }) { Text("清空") }
                }
                Spacer(Modifier.height(20.dp))
                Text("结果", fontFamily = Serif, fontSize = 18.sp)
                Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp)
                Row {
                    Button({
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("Lyric 性能自测", report))
                        progress = "已复制，可直接粘贴发给我"
                    }) { Text("复制结果") }
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(dismiss) { Text("关闭") }
                }
            }
        }
    }
}
