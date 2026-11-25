# 阅读脚本快照的字节契约

`default.js` 与 `night-scroll.js` 是已提交的 UTF-8 字节参考。`ReaderBootstrapGoldenTest` 同时核对两份输出，另保留 16 个公开 API 检查；比较不 trim、不移除 BOM、不归一化换行或尾随空白。

## F21：Windows 失败的实际原因

2026-11-25 三平台定向采证提交 `d58c31cd7c5e70dd6ee3693d599aa2c44e27ab3e` 的原始字节确认：Windows Git 系统配置 `core.autocrlf=true` 把参考资源在 checkout 时变为 CRLF；资源复制后仍为这些字节，而实际 bootstrap 输出与 Git HEAD 均为 LF。两份 Windows 快照都失败，不能只依据原先首份失败判断第二份。

| 快照 | Windows expected / worktree | Windows actual / Git HEAD | Linux / macOS |
| --- | --- | --- | --- |
| default | 79,593 bytes；1,549 CRLF | 78,044 bytes；1,549 LF | expected / actual / HEAD 均与 LF 版本逐字节相同 |
| night-scroll | 79,639 bytes；1,549 CRLF | 78,090 bytes；1,549 LF | expected / actual / HEAD 均与 LF 版本逐字节相同 |

两份首差均在 byte offset 21：expected 为 `0D`，actual 为 `0A`。所有文件都是有效 UTF-8、无 BOM；三平台实际输出的 SHA-256 都等于同份 Git HEAD。Windows worktree/expected 的 SHA 又与独立 `core.autocrlf=true` Git checkout 对照完全相同，证明差异来自 checkout 转换，而非 JS 生成内容或 locale。

规范 LF 字节的 SHA-256：

- default：`25ed6604c4e4c9923184ec831cab4286ef2a9f627c71474fe79f963ab2389b57`
- night-scroll：`66fb01675e2867a5fc6001ce17d2b673257c2c3d090e8157527b7a10be6a7393`

修复只在仓库 `.gitattributes` 对这两个精确路径指定 `text eol=lf`。不修改它们的内容、不扩大到其它 JS/Kotlin 文件，也不修改生产脚本或比较规则。Git checkout 对照中，仅加入这两条属性就恢复与原 blob/实际输出完全相同的字节。

原始证据保存在本机忽略目录：根集成副本 `artifacts/failure-repair-20261125/ci-initial/{Windows,Linux,macOS}/reader-bootstrap-golden/`，以及 distribution 副本 `artifacts/failure-windows-20261125/checkout-control/`。修复后 Windows 原生绿及三平台无回归须由下一轮独立 CI 另行确认，本机通过不替代该结果。

初版采证的 `ls-files` / `check-attr` stdout 为空：测试工作目录为 `src/shared`，辅助命令却传了仓库相对路径。原 JSON 和失败产物保持不变；上述判因使用真实 raw HEAD/worktree/expected/actual 与 checkout 对照，不依赖该空输出。辅助命令另以 `git -C <实际仓库根>` 修正，供下一轮记录有效属性。

## 诊断与有意更新

每次执行测试都在 `src/shared/build/reader-bootstrap-golden/` 保存 actual、expected、Git HEAD、worktree 原字节与 metadata/environment JSON。正常模式缺少参考资源必须失败，不自动生成并通过。

确有经过审阅的生产脚本变更时，可显式设置 `LR_WRITE_GOLDEN=1` 并定向执行测试（用 `--rerun-tasks` 确保任务实际运行）。这只在 build 目录生成 `default.js`、`night-scroll.js` 待审阅候选，测试仍明确失败；不会写回本目录，也不计为普通验收通过。人工核对差异及实际 WebView 行为后再决定是否更新参考资源，随后清除生成开关，重新执行严格比较。
