# 「我的」页需求（摘自需求文档 canvas 94bf7077）

      <Section heading="「我的」页设计">
        <Callout lead="现状（Lyric 0.1 debug APK 逆向）">底部为 书架 / 本地导入 / 我的。「我的」目前只有一张静态卡：珍藏与读过本数、版本 0.1.0、“没有账号、广告或在线书源”的说明和几条操作提示，没有可进入的功能。数据层仅 `books`、`progress`、`bookmarks` 三张 Room 表 + `SettingsStore`（DataStore，分全局/文字书籍/本书三层）。</Callout>
        <Section heading="布局分区（自上而下）">
          <Table columns={["分区", "内容", "优先级"]} rows={[
            ["1 阅读足迹", "今日/本周时长、累计读完本数、连续天数，一行三数字", "P0"],
            ["2 时长热力图", "近 26 周日历方格，点日期看当天读了哪些书", "P1"],
            ["3 阅读目标", "每日分钟目标的细环进度，可关闭", "P1"],
            ["4 书签与摘录", "全部书签按书分组，搜索、跳转、导出 Markdown", "P0"],
            ["5 外观", "应用主题（跟随系统/浅/深）、强调色、默认阅读主题", "P0"],
            ["6 阅读偏好", "全局排版默认值、翻页方式、点击区、音量键、常亮、横竖屏", "P0"],
            ["7 数据", "备份/恢复（本地 zip）、WebDAV、导出统计", "P0 本地 / P2 WebDAV"],
            ["8 存储", "解析缓存与字体占用、一键清理、失效文件处理", "P1"],
            ["9 关于", "版本、开源许可、隐私声明（数据仅存本机）、反馈邮箱", "P0"],
          ]} />
        </Section>
        <Section heading="用户故事与验收标准">
          <Table columns={["功能", "用户故事", "验收标准"]} rows={[
            ["阅读足迹 P0", "我想知道自己最近读了多少。", "阅读页前台且有翻页/滚动才计时，30 秒无操作暂停；单次 < 10 秒不计；数据与书架删除书籍解耦（书删了统计保留）。"],
            ["书签与摘录 P0", "我想在一个地方回看所有夹过的叶子。", "按书分组、按时间倒序；关键词过滤 < 300ms；点击直达原位置；失效书显示灰态；一键导出 Markdown 到用户选择的位置。"],
            ["外观 P0", "我希望整个 App 和我的阅读主题一致。", "切换即时生效无重启；强调色限 6 种预设，保持克制。"],
            ["阅读偏好 P0", "我想一次设好，以后每本书都这样。", "直接编辑 SettingsStore 全局层；显示“N 本书有单独设置”并可一键恢复继承。"],
            ["备份恢复 P0", "换手机时我不想丢进度和书签。", "导出 `lyric-backup-日期.zip`（数据库 JSON + 设置 + 字体，可选）；恢复时按文件哈希匹配书籍，未找到的书提示重新导入；恢复前预览条目数并二次确认。"],
            ["关于 P0", "我想确认它不收集我的数据。", "明示无网络权限用途、无账号；列出开源许可。"],
            ["热力图 P1", "我想看到自己阅读的节奏。", "一年数据渲染 < 100ms；颜色 4 档单色；无数据的日子留白。"],
            ["阅读目标 P1", "我想温和地督促自己。", "默认关闭；达成仅细微动画，不推送、不打卡弹窗。"],
            ["存储管理 P1", "手机空间紧张时我想清理。", "分项显示缓存/字体/封面大小；清缓存后再次打开书自动重建；不触碰原文件。"],
            ["WebDAV P2", "我想在平板和手机间同步进度。", "手动/打开时同步；冲突按 `updatedAt` 新者胜。"],
          ]} />
        </Section>
        <Section heading="对应代码改动">
          <Table columns={["位置", "新增", "说明"]} rows={[
            ["Room 表", "`reading_sessions`(id, bookId, startAt, endAt, durationSec, chars)", "统计与热力图来源；bookId 不设级联删除"],
            ["Room 视图/查询", "`daily_stats` 按 date 聚合", "LibraryDao 新增 `observeDailyStats(from,to)`、`observeTotals()`"],
            ["LibraryDao", "`observeAllBookmarks()` 联表 books", "书签汇总页"],
            ["SettingsStore", "appTheme、accentColor、dailyGoalMin、goalEnabled", "沿用全局层"],
            ["ReaderViewModel", "`SessionTracker`：onResume/onPause/翻页事件", "写入 reading_sessions"],
            ["data 包", "`BackupManager`(export/import zip)、`StorageInspector`", "kotlinx.serialization 已在用"],
            ["ui 包", "`MeScreen` + `MeViewModel`、`BookmarksScreen`、`StatsScreen`、`SettingsScreen`", "从 ShelfScreen 中拆出「我的」标签"],
            ["迁移", "Room version +1，`Migration(1,2)` 建表", "不清空用户数据"],
          ]} />
        </Section>
      </Section>

实施状态与逐项验收证据：[me-page-implementation.md](me-page-implementation.md)。
