# Lyric · 抒情诗

一个安静、离线的 Android TXT / EPUB 阅读器。Kotlin + Jetpack Compose / Material 3，界面为简体中文。没有网络权限、账号、广告或在线书源。首次启动提供三本明确标注的原创示例，可长按移出。

视觉依据 [design/design-spec.md](design/design-spec.md) v1.1 与 `design/src/`：和纸背景、苔绿控件、朱红进度、宋体正文、三列竖排封面、20dp 卡片、24dp 底部面板。书架显示实际藏书数量，不虚构阅读统计；设计稿“发现”改为本地导入。正文与书架标题内置思源宋体 Noto Serif CJK SC，UI 使用系统黑体；可另行导入 TTF/OTF。字体遵循 SIL OFL，授权见 `third_party/NotoSerif-OFL.txt`。提供由原图标重绘的自适应 launcher icon（含单色层）。

## 构建与运行

需要 JDK 17、Android SDK 35 / Build Tools 35.0.0。Gradle 8.9 wrapper 已提交；首次构建需联网下载构建依赖，应用运行不联网。

```bash
export JAVA_HOME=/home/box/jdk17
export ANDROID_HOME=/home/box/Android
# 其他机器：在 local.properties 中设置 sdk.dir，或设置 ANDROID_HOME
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

最低 Android 8.0 / API 26，目标 API 35。APK：`app/build/outputs/apk/debug/app-debug.apk`。测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`；Lint 报告：`app/build/reports/lint-results-debug.html`。

## 操作

- 书架右上角或末格「＋」导入文件；「本地导入」也支持递归扫描文件夹。系统文件选择器可多选。
- 书架支持三列网格 / 列表、最近阅读 / 中文书名排序、TXT / EPUB / 最近筛选。长按封面移出书架，清除该书进度和书签，保留原文件。
- 阅读页轻点左右侧翻页、中央唤出菜单；也支持左右滑动、方向键、音量键。滚动模式上下滑动；在章节边缘继续滑动进入前后章。
- 菜单可拖动全书字符进度、跳章、打开左侧目录 / 书签、全文搜索。右上角夹入带摘录和时间的书签。
- 「排版」实时预览字号、主题、字体、翻页方式。「更多」设置行段距、边距、缩进、对齐、系统深色、遮光、深绿 / 纯黑 / 自定色和点击区域。选择修改范围「全局 / 文字书籍 / 本书」，「恢复继承」移除该层覆盖。
- TXT 的「更多」可切换编码并应用自定义章节正则，留空恢复默认。编码与章节规则始终只对本书生效。

## 架构

```text
SAF URI / 文件 ContentSource
  → ParserRegistry（扩展名 → TxtParser / EpubParser）
  → Book + Chapter → ContentUnit.TextSpan | ContentUnit.ImagePage
  → ReaderSession → EngineRegistry（contentType → ReaderEngine）
  → TextReaderEngine + 后台 StaticLayout / ReaderCanvas
  → Progress / Bookmark（bookId + chapterIndex + Locator）
```

- `core/`：与 Android 无关的模型、编码、解析器。TXT 保留规范化后的全部字符；只将 CRLF/CR 转为 LF、去除 BOM，不插入排版字符。默认检测「第…章 / 卷 / 回 / 节 / 部 / 篇」与 Chapter（含罗马数字）；无标题按 10,000 UTF-16 字符切段，并保护代理对。自定义规则按整行匹配，仅检查短行。
- EPUB：zip + Jsoup，遵循 OPF spine 顺序；优先 EPUB 3 Nav，回退 EPUB 2 NCX，支持锚点字符定位；去除脚本和样式节点，不执行活动内容。限制归档条目数和解压大小，拒绝越界路径。正文转换为纯文本。书籍样式仅读取基础 serif/sans、数值行距及缩进提示。
- `reader/`：`ReaderEngine.open/goTo/currentLocator/settings`；`ReaderSession` 根据内容类型创建引擎。`Typesetter` 在 Default dispatcher 排版，使用 StaticLayout 的真实行边界分页，单次最多排版 12,000 字符，超长章节按窗口继续。覆盖和平移仅在翻页反馈时运行 280ms 动画；无动画直接切换；滚动使用惯性滚动。排版 spans 与缓存窗口均不改变原文偏移。
- `data/`：Room 存书架、进度、书签（外键级联）。SHA-256 流式哈希去重，重导入同一文件会修复来源 URI，不丢失进度。SAF 只申请并持久化读取权限；不申请广泛存储权限。原始文件不复制到书架目录、不写入或删除。解析后原子写入私有 JSON 缓存，内存仅保留最近两本。每次打开和回到书架都检查原文件访问能力，失效时标记并显示错误。
- DataStore：仅存发生变化的设置字段，按默认值 → 全局 → 内容类型 → 本书合并。书架视图与排序也持久化。编码 / 章节规则记在 Room 的书籍记录中。
- `ui/`：单 Activity + Navigation Compose、ViewModel + StateFlow、生命周期感知收集、手动 DI。导入在 Application scope 的 IO dispatcher 执行，离开书架不会中断；每本书分别反馈成功 / 重复 / 失败。全文搜索是可取消的后台 Flow，首条结果立即发出，再分批更新 UI，最多显示 2,000 条。

定位不依赖页码：文字为 `chapterIndex + charOffset`（章节规范文本中的 UTF-16 offset），漫画为 `chapterIndex + page`。调整字体、页边距、翻页方式不修改定位。进度按字符数计算；漫画按图片页数计算，已有序列化与定位测试。V2 新增漫画 ContentSource / Parser 和 ImageReaderEngine / 视图即可，书架、Room 进度和书签表无需变更。书签摘录通过内容类型统一生成。当前未提供漫画导入或渲染引擎。

进度在翻页 / 滚动停顿后 450ms 防抖保存；退出、Activity 暂停 / 停止时立即提交到应用协程。字体变化保留原文锚点，显示包含该锚点的新页。重设编码 / 章节规则时按全书绝对字符位置重映射进度与书签（编码更正若改变字符数，只能尽力接近原位置）。

## 验收状态

| # | 结果 | 已实现与限制 |
|---|---|---|
| 1 书架 | 功能完成，性能待实机量测 | 继续阅读、Lazy 网格 / 列表、最近 / 书名排序、长按移出。500 本仅按可见项组合；尚未给出 500 本真机帧时间报告。 |
| 2 导入 | 功能完成 | SAF 多文件与递归文件夹、持久读取授权、SHA-256 去重、逐本进度、失效来源处理。导入不使用前台服务；进程被杀后需重新选择未完成的批次，已导入项保留。 |
| 3 编码 | 完成 | UTF-8/BOM、UTF-16 LE/BE、GBK/GB18030、Big5；juniversalchardet 加 BOM、UTF-8 验证和 UTF-16 零字节特征；提供每书覆盖。无 BOM 的短文本存在固有歧义，可手动更正。 |
| 4 章节 / 目录 | 功能完成，设备时延待量测 | 默认 / 自定义 TXT 正则、无标题 10k 回退、EPUB Nav/NCX/锚点、缓存和当前目录高亮。主机 JVM 约 13.9MB TXT 解析约 230ms；这不是 Android 设备上的性能保证。 |
| 5 翻页 | 功能完成，60fps 待实机量测 | 覆盖 / 平移 / 垂直滚动 / 无动画、点击区与左右互换、模式切换保留锚点。覆盖是平面遮盖，不是拟真卷纸。分页数量显示当前章节窗口；不预排整书。 |
| 6 排版 | 部分 | 字号、行段距、边距、缩进、两端对齐、TTF/OTF、实时预览；EPUB 的「书籍样式」仅基础提示，不支持完整 CSS、复杂嵌套排版、ruby、内嵌字体和图片渲染。 |
| 7 主题 | 完成 | 日间 / 纸张 / 青苔 / 夜间 / 深绿 / 纯黑 / 自定背景与字色、系统深色、窗口亮度与额外遮光、沉浸系统栏。 |
| 8 进度 / 书签 | 完成 | 稳定字符定位、字符百分比、退出 / 后台保存、摘录 / 时间 / 跳转 / 删除。系统强制结束可能丢失最后一段未提交的防抖位置。 |
| 9 搜索 | 功能完成，设备时延待量测 | 后台流式搜索、即时首条、章节 / 上下文、点击跳转与原文高亮、可取消；冷启动需先加载解析缓存，不宣称任意设备均 <1s。 |

## 验证

已通过 `./gradlew testDebugUnitTest assembleDebug lintDebug`。19 项单元测试包括：

- UTF-8/BOM、GBK/GB18030 扩展字、UTF-16 双字节序、Big5、人工覆盖、换行规范化；
- 中英章节、定制 / 错误正则、10k 回退与代理对、13.9MB 解析时限；
- EPUB spine 顺序、元数据、Nav / NCX 回退、锚点、脚本剔除、损坏 / 越界归档；
- 字体与四种模式改变后的定位稳定、进度计算、边界修正、缓存往返、漫画页定位。

Lint 无错误；保留的提示主要为固定版本依赖可升级、kapt 可迁移 KSP 与图标资源建议。未把依赖升级提示当作功能失败。

设备验证尝试、限制和实机复验步骤见 [docs/validation.md](docs/validation.md)。额外 3 项 Android 集成测试已编译，但本环境的模拟器出现 System UI ANR，测试运行在执行用例前退出，未记为通过。60fps、500 本滚动、100 本实际 SAF 批次、低端设备冷搜索延迟仍需对应设备上的专项测量。
