# LinguaReader / 语境阅读

面向中文母语学习者的 Android 英文阅读器。**离线优先**：默认不联网，所有联网能力（AI 语境翻译、云 TTS）都是可选增强且均有离线降级路径。

核心闭环：导入图书（EPUB / TXT / FB2 / 文字版 PDF）→ 点击语境查词（词形还原 / 短语 / 义项排序）→ 收藏例句 → 间隔复习（SM-2 风格阶梯 + 提醒）→ CSV 导出；另有多引擎多角色「听书」（系统 / Azure / 火山 / 自建 OpenAI 兼容 / 本地 sherpa-onnx，规则 + LLM 说话人标注分角色配音）。

## 审查报告（后续迭代的基础）

- **[`审查报告-第四轮.md`](审查报告-第四轮.md)** — 2026-09-11 全量审查（HEAD `00d5c4b` / v1.7.0）：7 个审查点的逐项结论、57 条问题总表（按严重度排序）、修复优先级、未验证清单。**后续迭代以此为基线**；每条结论都附可复现证据，配套实验台见 [`审查附件-第四轮/`](审查附件-第四轮/)。
- 更早一轮已归档：[`归档-历史文档/审查报告-第三轮.md`](归档-历史文档/审查报告-第三轮.md)（被第四轮取代）。

## 构建

```powershell
# Gradle 根在 src/，不是仓库根（Windows / PowerShell）
cd src
.\gradlew.bat assembleDebug
```

新克隆先跑 `scripts\download_tts_models.ps1` 取回被 gitignore 的 sherpa-onnx `.onnx` 模型，否则内置离线音色不可用。单测：`.\gradlew.bat testDebugUnitTest`。

## 文档

- `FEATURE_SPEC.md` — 功能规约（权威）：3.x 逐功能规约、数据存储、质量门槛、§6 变更记录判断「某功能有没有做」
- `VALIDATION.md` — 滚动验证日志：每轮验证结论与真机记录
- `AGENTS.md` — 仓库工作约定（构建事实、Git 工作流、验证纪律）

当前版本 1.8.0（versionCode 17），minSdk 23 / targetSdk 35，Kotlin 2.1 / Jetpack Compose。词典数据来自 [ECDICT](https://github.com/skywind3000/ECDICT)。
