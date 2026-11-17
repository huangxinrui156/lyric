# Changelog

本文件记录面向使用者的显著变更。更细的分版本说明见 `docs/v0.3.4-changes.md` 至 `docs/v0.9-changes.md`。

This file lists user-facing changes. More detailed notes live in `docs/v0.3.4-changes.md` through `docs/v0.9-changes.md`.

## Unreleased

## 1.0.0

版本 1.0.0（versionCode 36）。自 0.9.7.1 起的公开版变更如下。正式签名包尚未就绪，当前提供 debug 与 benchmark APK（benchmark 为正式风格但使用 debug 签名）。

- 统一 v1.0 视觉 token；按验收修正标题、进度条、段距、漫画栏与封面色；夜间页标题使用正文墨色。
- 标准书架与画架固定两列；增加紧凑封面（手机约四列），书架与画架各自记忆。
- 排版三档：紧凑 / 舒适 / 宽松。案头专注时长可选 25 分钟、45 分钟或自定义（5–180 分钟）。
- 书架与画架可按阅读状态、导入时间筛选；筛完为空时可一键清除。
- 空书架一句引导、一键导入。导入一开始就显示「正在导入 n 本…」；完成后不跳转当前页面，摘要只在书架出现一次，4 秒或点击后消失。
- 系统打开方式：在文件管理或微信里点书可直接进入阅读。继续阅读卡片出现在书架顶部。阅读方向、分页、双页和裁白边按书保存。全书百分比未就绪时先显示「第 n 章」。
- 屏幕与安全区适配。增加 1.0 性能底线测量路径（验收机型 Find X9 Pro，真机指标仍待测）。
- 不采集、不上传任何数据。性能记录仅调试包可开（关于页连点版本号 7 次）。补充 README、变更日志、Issue 模板与许可证检查。

## 0.9.7.1

- 大书按需解析：打开时先解析目录与当前章前后，其余在后台补齐并缓存。
- 性能记录可在调试包「我的 → 关于 → 连点版本号 7 次」开启并导出；正式用途默认关闭。
- 版本 0.9.7.1（versionCode 35）。主机上 1248 章 EPUB 首窗约 0.39 / 0.26 秒；Find X9 Pro 真机指标见 README。

## 0.9.x

- 新图标与应用名 lyric；漫画双页与裁白边；跨品牌兼容审查与加载优化。
- 详见 [docs/v0.9-changes.md](docs/v0.9-changes.md)。

## 0.8

- 底栏改为书架/画架 · 案头 · 我的；今日小结、今日一句、专注计时。
- 详见 [docs/v0.8-changes.md](docs/v0.8-changes.md)。

## 0.7

- 支持导入 DOCX、PDF、MOBI / AZW3；扫描 PDF 可移到画架。
- 详见 [docs/v0.7-changes.md](docs/v0.7-changes.md)。

## 0.6

- 书架与画架分区，继续阅读在当前分区生效。
- 详见 [docs/v0.6-changes.md](docs/v0.6-changes.md)。

## 0.5

- 划线与笔记、书内搜索、书架搜索与文件夹导入。
- 详见 [docs/v0.5-changes.md](docs/v0.5-changes.md)。

## 0.4

- 本地漫画：CBZ / 图片文件夹、分页与条漫、按书阅读方向。
- 详见 [docs/v0.4-changes.md](docs/v0.4-changes.md)。

## 0.3

- 仿真翻页、横屏、音量键翻页、预分页与骨架屏、EPUB 排版子集。
- 详见 [docs/v0.3-changes.md](docs/v0.3.4-changes.md)。
