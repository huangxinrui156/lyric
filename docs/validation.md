# 验证记录 · 2026-10-30

## 已通过

在本仓库环境（JDK 17、SDK 35、Build Tools 35.0.0、Gradle 8.9）运行：

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug
```

- 19 项 JVM 单元测试，0 失败、0 错误；覆盖编码、TXT/EPUB、缓存与字符/漫画页定位。
- 最新 TXT 基准：13,938,012 bytes，360 章，主机 JVM 177ms。该结果不代表 Android 实机速度。
- Android Lint：0 errors，16 warnings（主要为固定版本依赖、kapt 和资源建议）。
- Debug APK：39,476,428 bytes。
- SHA-256：`4b96e63196804cc0899b1f3a67568171e4ce44c490b7af1336bdfd7f430eaef2`。

## 设备验证限制

尝试了 API 35 x86_64 AVD：KVM 模式在内核启动前停滞，改用 TCG 软件仿真后可启动并安装 APK，但 Android System UI 多次 ANR。`connectedDebugAndroidTest` 返回 `Instrumentation run failed due to Process crashed`，报告显示 **0 项用例执行**，不能记为通过。没有获得足以确认完整阅读流程的界面验证结果。

已提供 3 项可在正常设备重跑的 Android 集成测试（`app/src/androidTest/java/com/lyric/RepositoryTest.kt`）：真实 Room + DataStore、100 个本地文件导入/去重/缓存/失效处理、字体变化下的 StaticLayout 页边界。批次测试使用应用私有临时文件，尚不等于真实 SAF 提供者的 100 本压力测试。

```sh
./gradlew connectedDebugAndroidTest
```

## 实机复验步骤

1. 安装 APK，打开原创示例；翻页，改字号、行距、边距、四种翻页方式，核对同一段文字仍在当前页。
2. 添加书签，退出与重新打开、切后台再返回，检查位置、摘录、时间和书签跳转。
3. 搜索「竹林」，检查首条结果、章节与上下文，点击确认正文高亮；替换关键词检查旧搜索被取消。
4. 导入含 UTF-8、GBK、GB18030 扩展字、UTF-16、Big5 的 TXT 和 EPUB 2/3；目录跳转；更改编码和章节规则后重开。
5. SAF 多选与文件夹各导入一次同一组 100 本书，核对总数、逐本进度、去重；重启应用检查授权仍在。
6. 移动原文件并返回书架，检查失效标记；重导入恢复。长按移出一本，确认原文件仍在。
7. 放入 500 本书，检查网格/列表、标题排序及滚动帧时间；使用 `dumpsys gfxinfo com.lyric.reader framestats` 或 Macrobenchmark 记录，不能以软件仿真结果宣称 60fps。
8. 检查夜间、深绿、纯黑、自定义主题、跟随系统、最低亮度叠加遮光，以及后台/退出后窗口亮度恢复。
