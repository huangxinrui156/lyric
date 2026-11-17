# Changelog

本文件记录面向使用者的显著变更。更细的分版本说明见 `docs/v0.3.4-changes.md` 至 `docs/v0.9-changes.md`。

This file lists user-facing changes. More detailed notes live in `docs/v0.3.4-changes.md` through `docs/v0.9-changes.md`.

## Unreleased

1.0 公开版筹备中：视觉 token 统一、性能底线、全书百分比、屏幕适配、排版三档、空书架、系统打开方式、继续阅读、按书设置，以及本仓库的 README / CHANGELOG / Issue 模板、许可证检查，和 JVM 截图脚本（tools/render-screens.sh）。正式签名发布包尚未就绪。

- 明确声明不采集、不上传任何数据。性能记录仅出现在调试包（关于页连点版本号 7 次）；正式包无法开启，也不会根据旧偏好写入记录。
- 视觉审查：小写 lyric 标题、继续阅读自定义进度条、正文段距与章标题、漫画日间顶底栏、底栏仅顶边、生成封面改用均匀哈希。

Work toward the 1.0 public release is in progress (visual tokens, performance baseline, open-with, continue reading, per-book settings, and this repository’s public docs). A signed release build is not available yet. The app does not collect or upload data; performance recording is debug-only.

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
