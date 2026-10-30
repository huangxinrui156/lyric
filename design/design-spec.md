# Lyric · 安卓阅读器设计规范 v1.1

## 1. 设计理念
「一页一行诗，留白即韵律」。以侘寂美学为底：和纸米白、墨色、苔绿、朱红；叠加极淡胶片颗粒（opacity 4–6%）带来复古质感；布局学习苹果发布会式克制——大标题、大留白、单一强调色。ACG 情感通过竖排书名、印章式朱红点缀、未来漫画模块承接。

## 2. 命名理念：Lyric
- **Lyric（抒情诗）**：源自古希腊伴里拉琴吟唱的短诗，后成为西方诗歌最主要的体裁——篇幅短、情感私密、重在韵律与意象。
- **与产品契合**：阅读是一个人安静的内心独白，正如抒情诗是“独白式”的诗；排版追求行距与留白的节奏感，即视觉上的“韵律”。
- **与美学契合**：抒情诗“以小见大”，呼应侘寂的不完美与微距视角；词形短、易读、国际化，未来扩展漫画也不受限。
- **品牌写法**：拉丁衬线斜体 *Lyric*（Noto Serif Italic），中文语境可附“抒情诗”；字标旁可配一道朱红短竖线，象征书签与诗行。

## 3. 配色
| 角色 | 名称 | HEX |
|---|---|---|
| UI 主色 Primary | 苔绿 | #5E6B4E |
| 强调 Accent/Tertiary | 朱红 | #B5563A |
| 辅助 Secondary | 枯茶 | #8A6A4F |
| 次要文字 | 灰石 | #8C8478 |
| 分割线 | 麻 | #E2DACB |
| 卡片表面 | 生成 | #EAE3D6 |

阅读主题：
| 主题 | 背景 | 正文 | 页眉/页码 |
|---|---|---|---|
| 日间 | #F4EFE6 | #2B2A27 | #9C9384 |
| 护眼纸张 | #E8E1CF | #3A362F | #9C9384 |
| 青苔（可选） | #C9D3C0 | #2F3529 | #7A8470 |
| 夜间 | #16171A | #B8B2A6 | #5C5A55 |
| 深绿（代码预设，设置面板未露出） | #182820 | #B8C3B1 | #7A897A |
| 纯黑（代码预设，设置面板未露出） | #000000 | #B8B2A6 | #5C5A55 |
| 自定 | 用户自选，默认同日间 | | |
> 0.3.4 核对：`ReaderTheme` 有 6 套预设 + 自定；设置面板显示前四套（日间/纸张/青苔/夜间），默认排版 17sp / 段距 0.3em / 边距 20dp。
夜间不用纯黑纯白，降低对比避免刺眼。

## 4. 字体
- 中文正文：思源宋体 Noto Serif CJK SC Regular，17sp，行高 1.9，首行缩进 2em，段距 0.3em；可选霞鹜文楷（楷体）、思源黑体。
- 标题：思源宋体 SemiBold（书架大标题 28sp）。
- UI 文字：思源黑体 Noto Sans CJK SC，12–14sp；数字可用 Inter。

## 5. 形状/间距/阴影
- 圆角：封面 6dp；卡片 20dp；底部面板 24dp(顶)；chip 14dp；图标 112/512。
- 间距：4dp 网格；页面边距 20dp，阅读页左右 20dp；网格间距 14/18dp。
- 阴影：卡片 0 2 10 rgba(60,50,30,.08)；封面 0 3 8 rgba(0,0,0,.18)；面板 0 -6 24 rgba(0,0,0,.15)。整体扁平，阴影只为层级。

## 6. 图标风格
线性 1.6dp 描边、圆角端点、24dp 栅格，墨色单色；选中态切换为苔绿。App 图标：米白和纸底 + 墨线开本 + 朱红书签 + 一片苔绿叶 + 一点朱印。

## 7. 核心页面
- **书架**：大标题+阅读统计 → “继续阅读”卡（封面、书摘、朱红进度条）→ 分类 tabs（全部/小说/漫画/最近）→ 三列竖排书名封面网格，末格“导入” → 底部导航（书架/发现/我的）。长按进入多选编辑。
- **阅读页**：全屏沉浸，仅顶部章节名与底部页码/百分比；点击中区唤出菜单，左右区翻页。
- **设置面板**：底部抽屉，亮度、字号滑杆、主题色圆片（选中朱红描边）、字体与翻页方式 chip、底部快捷（目录/夜间/更多）。
- **目录**：左侧抽屉 85% 宽，顶部书名+进度，章节列表当前章苔绿高亮，Tab 切换“目录/书签/笔记”。
- **发现/导入**：本地文件扫描（TXT/EPUB/MOBI）、WebDAV/局域网传书、书源（后续）；空状态用一行诗意文案+线描插画。
- **漫画阅读预留**：黑底 #111，条漫纵向滚动/日漫右→左翻页切换，双击缩放，底部页码缩略条；书架封面改为 3:4 彩色封面，角标“漫”。

## 8. 动效
- 翻页：仿真卷页 / 平移 280ms，FastOutSlowIn。
- 打开书：封面共享元素放大到全屏 350ms，背景纸色淡入。
- 面板：底部弹出 250ms spring(dampingRatio 0.85)；遮罩 25% 淡入。
- 主题切换：颜色交叉淡化 400ms，不闪屏。
- 进度条与按压：轻微缩放 0.97 + 触感反馈。

## 9. Material 3 / Compose 色值
```kotlin
val LightColors = lightColorScheme(
  primary = Color(0xFF5E6B4E), onPrimary = Color(0xFFF4EFE6),
  primaryContainer = Color(0xFFDDE3D0), onPrimaryContainer = Color(0xFF1C2414),
  secondary = Color(0xFF8A6A4F), onSecondary = Color(0xFFFFFFFF),
  tertiary = Color(0xFFB5563A), onTertiary = Color(0xFFFFFFFF),
  background = Color(0xFFF4EFE6), onBackground = Color(0xFF2B2A27),
  surface = Color(0xFFF7F3EB), onSurface = Color(0xFF2B2A27),
  surfaceVariant = Color(0xFFEAE3D6), onSurfaceVariant = Color(0xFF8C8478),
  outline = Color(0xFFD2C9B8), outlineVariant = Color(0xFFE2DACB))
val DarkColors = darkColorScheme(
  primary = Color(0xFFA9B796), onPrimary = Color(0xFF223015),
  secondary = Color(0xFFC9A98C), tertiary = Color(0xFFE08A6E),
  background = Color(0xFF16171A), onBackground = Color(0xFFB8B2A6),
  surface = Color(0xFF1E1F23), onSurface = Color(0xFFD0CABE),
  surfaceVariant = Color(0xFF2A2B2F), onSurfaceVariant = Color(0xFF8E897F),
  outline = Color(0xFF45444A))
// 阅读主题单独定义：data class ReaderTheme(bg, text, meta)
```
Shape：small 6dp / medium 14dp / large 20dp / extraLarge 24dp。
