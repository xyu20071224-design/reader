## 2026-09-03 AI 整本翻译管线加固五件套真机验收（PKB110 / Android 16 / ColorOS，提交 ba46859 + 349644e + 0dcb719）

**范围**：断路器中止、确认框新文案（服务商名/术语注入/批数估算）、出版译本守卫路由、中止后状态复位。**不出网零费用**：伪服务商 TestGW 指向 `http://127.0.0.1:9`（端口 9 = 连接拒绝，模拟系统性失败），`run-as` 直写 `shared_prefs/ai_settings.xml`（明文 Key 走解密回退路径生效）。构建方式：`git worktree` 检出 `0dcb719` 干净构建 `-PverifyBuild` debug 包（避开工作树未提交的对齐器改动），覆盖安装旧 release verify 包（数据保留、恢复 run-as 取证）。

**结果**（全部通过）：

| 验证点 | 方法 | 结果 |
| --- | --- | --- |
| 确认框服务商名参数化 | dump 解析对话框文本 | 「用 **TestGW** 逐章翻译」✅（非 DeepSeek 硬编码） |
| 术语注入透明化 | 同上 | 「当前术语表 3 条，实际注入翻译 3 条（手动译法优先；超出的不生效）」✅ |
| 批数估算 | 同上 | 12 章 24 批 ✅ |
| 来源选择中性文案 | 同上 | 「需要先在 AI 中心配置服务商与 Key」✅ |
| 断路器中止 | 开始生成→0.5s 间隔轮询 dump | t1「取消生成」(生成中) → t2 notice「**连续 3 批翻译失败，生成已中止。请检查服务商配置与网络；已完成批次已保留，重新生成会自动续跑**」✅（Snackbar 4 秒窗口内抓到文本+截图） |
| 中止不落盘 | run-as 查盘 | `ai/ai-translations/<bookId>/` 仅 `style.json`，**零检查点、零译文正文、零新 memory**——不是「24 批全占位跑完」（那会写正文 + saveTranslation）✅ |
| 中止后状态复位 | 重启 app 后 dump | 书架回「加译本」，无残留进度 ✅ |
| 出版译本守卫 | 魔戒（导入译本）点「译本 ✓」 | 只弹删除确认框，**无「重新生成」选项** ✅ |
| 进度百分比 | 生成中 dump | 「AI 翻译中 8%」正常刷新 ✅ |

**取消路径未完整验证**：伪服务商下断路器 ~2 秒即中止，「生成中」窗口太短，点「取消生成」时按钮已变回「加译本」，两次误触重开了对话框（测试操作问题，非产品缺陷；状态复位正常）。该路径（CancellationException catch → notice → finally 清理）有 JVM 单测覆盖，留待真实 Key 的分钟级生成窗口下顺带复验。

**设备状态清理**：伪 `ai_settings.xml`、`style.json` 已删；`ai_translation.xml`（记住翻译模式的正常产物）保留；verify 包与测试书保留未卸（如需清理：`adb uninstall com.linguareader.app.verify`）。截图 5 张入 `验证截图/AI译本-*.png`。验证用的 worktree `wt-verify/` 验证后应删（`git worktree remove wt-verify`，`.git/info/exclude` 里的条目一并移除）。

## 2026-09-02 译本对照「分句/引号归属」修复（第一步落地，提交 28478a4）

**背景**：100 样本人工评估（上一条目）的「错配」经逐条诊断，发现是两类问题的混合——分句/引号残渣（第一凶手）+ 真·语义错配。档案 12,692 条句级句对中 734 条 zs=纯残渣（「」）、1,482 条以裸引号开头（合计 17.5%）；ECDICT 词典释义词面共现经实验**证明无效**（文学意译零词面重合，24 条「对」样本 22 条 0 分），故不引入词典桥，直接修分句。

**落地规则**：R1 中文分句纯标点/引号残渣并入前句；R2 中文片段以闭合引号（」』"'）】）开头并入前句；R3 英文句末标点后跟小写字母=引导语延续不切句；R4 其余（缩写/首字母缩写/…/中文直接切）不变。

**效果**（全档案 3,464 个中文段落模拟）：残渣段 1,461 → **0**、裸引号开头段 1,742 → **0**（段数 14,870 → 11,667）；真实案例 'I am sorry, Frodo!' he cried, full of concern. 不再拆残句。

**配套**：档案 v2 新增 alignerVersion 字段（对齐器 VERSION=1，旧档案默认 0，重对齐才用新规则）；+5 条回归单测；全量 515 tests / 0 failures / 1 skipped。

**注意**：TTS 分句同步变化（...!' he said. 型 2 句→1 句），**真机听书验证待做**；旧档案不迁移（需重新导入译本触发重对齐）。

## 2026-09-02 译本对齐 100 样本人工评估（第二步、有用户判定基线）

**方法**：从真机档案分层抽 100 样本（S1 句级≥0.85 18 / S2 0.5–0.85 14 / S3 <0.5 14 / S4 长度比异常 15 / P1 段级合并成分 16 / P2 邻近兜底 4 / P3 整段降级 4 / U 无对照 15），生成单文件 HTML 判定器（artifacts/alignment-eval.html，契约见页首），用户（中文母语）逐条判「对/勉强对/错」。原始判定 artifacts/eval-verdicts.txt，样本清单 alignment-eval.csv。

**结果**：

- 各层错配率：S1 **11%**（2/18）、S2 **36%**（5/14）、S3 **100%**（14/14）、S4 **100%**（15/15）、P1 **38%**（宽口 62%）、P2/P3 各 4/4 全错（均出自前言/注释区，规模微小）；
- 长度比带 × 错配率**单调**：<0.5 = 100%、0.5–0.67 = 60%、**0.67–1.5 = 10%**、1.5–2.0 = 40%、>2.0 = 85% → 「长度比带外 ≈ 必错」成立；
- 高置信错配的两个铁证（长度比都正常、现有信号防不住）：he cried, full of concern. ↔ 「對不起，佛羅多！」（0.974）；Not all those who wander are lost; ↔ 「真金不一定閃閃發光，」（0.919，邻行串位）；
- U 层：正文 9 句漏配确认（占全量 0.06%），非正文 6 句不计入；
- 门槛推算（12,692 句级句对）：G1 现状错配 **29.3%** → G2（conf≥0.5 且 ratio∈[0.5,2.0]）**14.7%**（显示 78%）→ G3（ratio∈[0.67,1.5]）**8.8%**（显示 61.7%）；G2 对 ≥0.85 带无损（保留 99.8%）。

**结论**：置信度能排序但 A 带仍 ~11% 错配（需独立信号），长度比可作强力拒斥信号；第三步方向 = 展示门 G2/G3 + ECDICT 词级锚点 + splitChinese 残渣修复（详见「译本对齐-质量画像-魔戒.md」§10）。

**注意**：样本量小（S1 n=18，CI 约 2%–33%），方向可靠、点估计勿当精确值。

## 2026-09-02 译本对齐质量画像（离线全量统计，真机档案）

**目标**：为「译本对照为什么感觉不靠谱」（BUG-030/031/039 背后的算法问题）先建立离线基线，再决定算法改动方向。

**方法**：从真机 debug 包（com.linguareader.app，1.5.1）拉取 2026-08-30 构建的 5.14 MB 对齐档案 + 双书抽取物到 artifacts/lotr-*；一次性 JVM 审计（重放当前 main 对齐器逐条比对 + 复刻 TranslationMemoryIndex.lookup 对全部 14,336 个英文句模拟点词）。审计工具已按约定删除，数据保留在 artifacts/（gitignore）。

**主要结果**（详见「译本对齐-质量画像-魔戒.md」）：

- 档案构成：13,646 句对，句级 93.0% / 段级 7.0%；
- 全量点词模拟：句级命中 88.7%（精确 88.5% + 模糊 0.2%）、仅段级兜底 10.1%、完全无对照 1.2%（169 句，其中正文 23 章仅 8 句，其余全在目录/版权/附录等非正文）；
- 置信度双峰：P50=0.85、P90=0.98、P99≈1.00；<0.30 占 4.9%；句级句对 71.4% 挤在 [0.80, 1.00]——**置信度主体偏高且与「长度比 + 专名锚点」同源，不是语义正确率**；
- 长度比：中位 1.04，但 22.0%（2,791 条）在 [0.5, 2.0] 之外（<0.5 桶平均 conf 0.40、>2.0 桶 0.52）；最失衡 30 条全部是 splitChinese 把「」引号残渣单独切句的产物（conf 0.15 地板）；
- 前 4 章空：英文封面/扉页（0–4 文本块）对中文标题页（0 块），**内容空缺而非章节对齐失败**；首对产出 c=4→z=3，映射链连续；
- 重放一致性 99.0%（138 条仅中文章落 U+3000/半角空白级差异，句对本身全同；已排除 jsoup 版本与文件字节差异，列为已知残余，不影响统计）。

**对后续的启示**：正文「没对上」不是主要矛盾（仅 8 句）；主要噪声在 10.1% 段级兜底与 88.7% 句级命中里的低质量句对。下一步建议按 级别×置信度×章节类型 分层抽 100 样本人工评估，先拿错配率基线再动算法（候选：ECDICT 词级锚点、中译分句残渣修复、段级单独抬高门槛）。

## 2026-09-02 1.6.0 release 包（R8 + 资源裁剪）真机 smoke（PKB110 / Android 16 / ColorOS）

包：`assembleRelease -PverifyBuild` → `com.linguareader.app.verify` **12 / 1.6.0-verify**，用 debug keystore 经 `apksigner` 签名后安装（工程无 `signingConfig`，产物是 `app-release-unsigned.apk`）。

**体积**：debug 55.7 MB → release **33.3 MB**（−40%）；其中 **dex 合计 20 MB → 1.8 MB（−91%）**。剩下的大头压不动：`assets/dictionary/ecdict.sqlite` 25.5 MB（asset）、BouncyCastle 的后量子算法参数表约 4 MB（jar 内 `org/` 下资源，`shrinkResources` 只管 `res/`）。

| # | 检查项 | 为什么要查 | 结果 |
| --- | --- | --- | --- |
| 1 | 冷启动 | 基线 | ✅ 起来了，更新说明弹「1.6.0-verify」 |
| 2 | 打开书 + 渲染 | 桥的 `onReady` | ✅ 页码指示 `15/48 · 3/52` |
| 3 | 翻页 | `onPageChanged(page,count,origin)` **三参数新签名**过 R8 | ✅ 3/52 → 4/52 |
| 4 | 点词查词 | `onWord` 混淆后会**静默失效**（不崩、不报错、点了没反应） | ✅ less → 词典释义 |
| 5 | 译本对照 | 5 MB 对齐档案读取 | ✅ 「魔戒首部曲 · 句级 · 置信度 93%」+ 整句中文 |
| 6 | **MiMo 预置音色名** | `res/raw/keep.xml` 保 `@string/tts_mimo_voice_*`，被裁掉会退化成原始 key | ✅ 显示「MiMo 默认（mimo_default）」，**未出现 `tts_mimo_voice_*`** |
| 7 | 听书 | 新桥方法 `onSpeakingOffscreen` + 新按钮 | ✅ 听书条齐全（上一句/暂停/下一句/**回到朗读处**/设置起点/语速/停止），播放正常 |
| 8 | 存储占用页面 | 本版新增 UI + `formatStorageBytes` | ✅ 合计 8.2 MB，分项与孤儿区正确 |
| 9 | 复习卡 | BUG-032 修复在 release 下 | ✅ 「第 1 / 2 张 · run through」 |
| 10 | 崩溃 | — | ✅ 全程 pid 不变，crash 缓冲无本应用记录 |

**方法学变化（记一笔）**：release 包**不是 debuggable**，`run-as` 直接报 `package not debuggable` —— 这一轮的取证只能靠 `uiautomator dump` + `dumpsys`，读不了 `filesDir`。以后验 release 包时别再指望 run-as。

## 2026-09-02 开机重排复习提醒（D3.2）真机验收 —— **接收器被 ColorOS 拦掉，降级路径有效**

- **结论先写**：`BootRescheduleReceiver` 已正确注册（`dumpsys package` 能看到它挂在 `BOOT_COMPLETED` 上），但 **PKB110 / ColorOS 不把开机广播投递给它**。重启后等到 uptime 3 分钟、用 `logcat -d -s BootReschedule:V` 过滤全量 buffer，**一条日志都没有**；`dumpsys alarm` 里也没有闹钟。应用不处于 stopped 状态（已 `am start` 过），可排除「从未启动过收不到广播」这一常见原因。
- **降级路径按设计生效**：打开 App → `AppViewModel.rescheduleReviewReminders()` 立刻补排，`dumpsys alarm` 出现
  `RTC_WAKEUP … com.linguareader.app.verify`，`origWhen=2026-09-02 13:54:35.836`，与最早待复习词 study 的 `nextReviewAt=1788328475836` **完全一致**，tag 指向 `ReviewReminderReceiver`。✅
- **前置条件（踩过一次）**：通知权限没授予时 `ReviewReminderScheduler.schedule()` 会**主动 cancel 闹钟**而不是排它 —— 所以「没有闹钟」既可能是没到期词，也可能是没权限。本轮先测得 `granted=false` 时无闹钟，用户在系统设置里授予后才排得起来。
- **`pm grant` 在这台机器上直接抛异常**：`SecurityException: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS` —— 比记忆里记的「grant 报成功但检查仍 false」更硬，通知权限只能人工在系统设置里开。
- **接收器保留**：非 ColorOS 设备、或将来 ROM 放宽自启限制时它就有用，代价只是一个类 + 一条权限。但**不能把「重启后提醒还在」当成已实现的行为**——在本项目的主力真机上它不成立。
- 未再做的一步：ColorOS 的「自启动」白名单里放行 verify 包后重测，可区分「自启限制」与「其它原因」。需要再重启一次，本轮未做。

## 2026-09-02 存储占用页面与缓存键引擎维度（D2.4b / D2.2）真机验收（PKB110 / Android 16 / ColorOS）

入口：书架顶栏新增「存储占用」图标（打开即扫盘，启动时不扫）。

- **各处占用（实测）**：合计 8.9 MB — 译本与对照档案 4.9 MB、书籍正文与进度 3.8 MB、音频缓存 256 KB、生词本 2 KB。数字与 `du -sk` 相符。✅
- **孤儿对账**：用 `run-as` 造两处无人认领的数据（`books/ghost-1234567890` 512 KB + `tts_cache/ghost-1234567890` 256 KB）→ 页面报「**2 项，共 768 KB**」并提示「只列不删」。✅
- **一键清理**：点「清理这些数据」→ 盘上两处均 GONE → 页面自动重扫，合计降为 **8.2 MB**（-768 KB），孤儿区变为「没有发现无人认领的数据」；**两本真书完好**，进程存活。✅
- **D2.2 缓存键加引擎维度**：目录名改为 `e<引擎哈希8>~<音色段>`（引擎身份复用 `VoiceLibraryLoader.engineKey`）。同名音色在两台不同服务器上不再共用缓存。JVM 用例 `cacheKeyDistinguishesEngines` 覆盖；真机侧因缺云 TTS 配置只验证了「旧格式目录仍被占用统计与清理覆盖」。
- 单测：**511 tests / 0 failures / 1 skipped**。

## 2026-09-02 BUG-032 复习卡索引归零修复（D3.1）真机验收（PKB110 / Android 16 / ColorOS）

入口：生词本 →「复习」（verify 包，4 个待复习）。

| 步骤 | 界面 | 盘上 `vocabulary.json` |
| --- | --- | --- |
| 打开复习 | `第 1 / 4 张` · study | — |
| 评第一张（认识） | **`已完成 1 / 4`** · 下一张 app | study reviewLevel=1 reviewCount=1 |
| 评第二张（认识） | **`已完成 2 / 4`** · 下一张 run through | study、app 均已复习 |

修复前的表现是索引归零：评完一张又回到第 1 张、待复习数看着永远不变。

根因：`ReviewSheet` 把**卡组本身**（`List<SavedWord>`）当 `rememberSaveable` 的键，而复习会改词的 `reviewLevel`/`nextReviewAt` → 上游 `savedWords` 更新 → 阅读页那边现算的 deck 变成一个「不相等」的新列表 → `index`/`completedCount`/`allDone` 全部归零。修法是键换成**卡组身份**（有序 id 串）。

这与刚修完的「阅读位置」是同一个错误形状：**派生量被上游重建，把用户进度冲掉**。

## 2026-09-02 音频缓存占用与清理（D2.4a）真机验收（PKB110 / Android 16 / ColorOS）

入口：阅读页 →「Aa」→ 滚到底「听书设置」→ 弹层底部「音频缓存」。

- **占用显示准确**：`run-as` 用 `dd` 造 3 个 1 MB 假缓存文件（3 章 × 1 音色）→ 界面显示「当前占用 **3.0 MB**」，与 `du -sk` 的 3100 KB 相符。✅
- **上限档位**：256 / 512 / 1024 / 不限四档可选，选中态高亮；点 256 MB → 保存 → `shared_prefs/cloud_tts_settings.xml` 出现 `<int name="cache_limit_mb" value="256" />`。✅
- **清空**：点「清空音频缓存」→ 内联提示「已清空 **3.0 MB**，下次播放会重新合成」→ 占用变为「当前没有缓存音频」→ 盘上 `files/tts_cache` 只剩空目录（`ls -A | wc -l` = 0）。✅
- 稳定性：pid 不变，crash 缓冲无记录。✅
- **本轮只做了缓存这半块**（D2.4a）。全局「各类占用 + 孤儿数据清理」（D2.4b）需要复用 `AppViewModel` 里那份 per-book 存储清单 —— 在弹层里再抄一份清单，等于把刚删掉的重复又请回来，所以单开一轮做。
- **D2.3 的淘汰仍未真机验**：需要云 TTS 真实合成才能填满配额；本轮用假文件只验了占用/清空这条链路。

## 2026-09-01 数据所有权第 1 刀（D1）真机验收（PKB110 / Android 16 / ColorOS）

包 `com.linguareader.app.verify`（分支 `feat/book-data-ownership` @ `7496d4f`）。判据全部走 `run-as` 读盘 + `uiautomator dump` 读对话框。

- **D1.4 删除对话框显示条数**：真实测试书（`vocabulary.json` 里实有 4 条该书生词）→ 对话框正文「本地书籍副本、阅读进度与该书的 **4 条生词**会一并删除，且不可恢复。」条数与盘上实际一致。✅
- **D1.1 级联 + D1.4 生词清理 + D1.5 字段无关**（用一本 `run-as` 播种的一次性假书 `verify-cascade-01`，9 处落点全部造齐、生词加 2 条、`translationBookId` **故意留空**）：
  - 对话框正文「…该书的 **2 条生词**…」，条数正确；
  - 确认删除后 **9 处落点全部 GONE**（books / ai\book-context / ai\glossary / ai\speaker-tags / translation-memory / **translations\ai-…** / ai\ai-translations / tts_cache / voice_maps）；
  - 其中 `translations/ai-verify-cascade-01` 是在 `translationBookId` 为空的情况下被清掉的 —— **D1.5「AI 译本正文清理不再依赖该字段」在真机上成立**；
  - `vocabulary.json` 由 6 条降为 4 条，**只清掉假书的 2 条，真书的 4 条一条不动**；
  - 真书目录与数据完好，进程 pid 不变，crash 缓冲无记录。✅
- **过程中抓到的一个测试脚本缺陷（不是产品缺陷）**：书架是**两列网格**，第一版脚本按 y 坐标找「移除」按钮，选中了**另一本书**（对话框标题显示的是真书）。靠「确认前先核对对话框标题」这一步拦下，未误删。教训：多列布局里定位控件必须用 x 区分列，且**破坏性操作前一定要核对确认框里的对象名**。
- **未能在本轮验证**（缺前置条件，非跳过）：
  - **D1.8 已于同日补验**（用户在书架上导入了《The Fellowship of the Ring》+ 中文译本《魔戒首部曲》）：
    - `files/translation-memory/7a4f2091de4e1051d906.json` = **5,138,352 字节**，含 `enParagraphs`/`zhParagraphs` 段落表与 **13,646 个句对**（`"zs"` 计数）；
    - `files/translations/` **空目录**（`ls -A | wc -l` = 0）—— 出版译本正文已在 attach 完成时丢弃，`du` 显示该目录仅 4 KB；
    - **关键判据（正文没了还能不能用）**：打开第 15 章（书内下标 14，档案里 705 对）点正文 → 查词卡显示「译本对照 / 總之，你一定得離開這裡… / 魔戒首部曲 · **句级** · 置信度 **88%** / 整句对照」。✅ 档案自带译文这一前提在真机成立。
    - 附带观察：前几章（封面/版权/目录）未参与对齐，点词显示「本句未对上译本（所在段落或章节没有参与对齐）」—— 降级提示准确，不是回归。
  - **D2.3**（缓存配额与淘汰）：`files/tts_cache` 为空，云 TTS 未配置（属记忆里「待验证清单：需真实 Key」那一档）；JVM 侧有 5 条淘汰用例。
- 单测：**510 tests / 0 failures / 1 skipped**。

## 2026-09-01 M2 第 2 刀（因果标记 + 分页跟随解禁）真机验收（PKB110 / Android 16 / ColorOS）

- 包 `com.linguareader.app.verify`（1.5.1-verify），测试书 id `8712e2f68447c3460f34`。判据：`uiautomator dump` 的页码指示 + `run-as cat metadata.json`。
- **B1 播放中手动翻页不再拉回首句**：结构上根除——引擎侧 `onReaderPositionChanged` 连同阅读器侧回报链路整条删除，已无任何「按阅读位置重定位朗读」的入口。真机：播放中连按两次「上一页」（5/12 → 3/12），朗读照常推进，10 秒后页面自己跟回朗读处（6/12）。✅
- **B2 分页模式自动跟随 + 章末不死循环**：分页模式下页面随朗读推进（7/12 章内 1→2→3→4→5 页，约 45s/页，无抖动）。连续跨 **3 个章界**（7→8、9→10、10→11）均正常落到新章，无回跳、无重启本章、无卡死；全程 pid 不变，logcat 无 FATAL/ANR。✅
- **跟随同步阅读锚点**：跟随翻页时把 `anchorLocus` 挪到正在朗读的那句。首轮验收发现漏了这一步（听到第 4 页、`locusBlockIndex` 仍是 0），补上后：听书中旋转 竖 5/12(块 27) → 横 8/19(块 30) → 竖 5/12(块 30)，位置跟着朗读走且旋转不弹回章首。✅
- **接管窗口与跟随的关系**：手动翻页后 ~10s 内不跟随（页面停在用户翻到的 3/12），到期后跟回朗读页（6/12）。✅
- **真机发现并修复的缺陷（非本次改动引入，但被跟随放大）**：切到别的 App 再回来，页码指示变成 `6/12 · 3/216` 且不自愈——后台那一刻 `innerWidth` 掉到 0/极小，`pageCount = ceil(scrollWidth/innerWidth)` 炸成两百多页。修复：`updateMetrics` 增加退化尺寸守卫（`document.hidden || innerWidth <= 1 || innerHeight <= 1` 直接不测量）+ `visibilitychange` 回前台补测；跟随与合并计时器同样在不可见时不动。复测正常（`9/12 · 1/12`）。
- **B3 重排不写盘：本轮不做**。理由：M1 之后落盘的是语义锚点而非页码，重排写回的是同一份锚点，不再是「派生量覆写真相」，风险与收益都已大幅下降；留作独立小项。
- 单测：**493 / 0 failures / 1 skipped**。
- 文档：`AGENTS.md` 的「分页跟随是禁区」条目已改写为「已解禁 + 解禁前提 + 三道护栏」。

## 2026-09-01 删除 LAST_PAGE 页码哨兵后的真机回归（PKB110 / Android 16 / ColorOS）

- 背景：M1 的 T1.6 只做了一半——语义锚点已接线，但「本章最后一页」仍同时用 `restorePage = Int.MAX_VALUE` 哨兵表达（JS 内部译成 `restoreTarget = -1`）。本次把哨兵删干净，末页语义只由 `anchor='chapter-end'` 承担。
- 包：`com.linguareader.app.verify`（1.5.1-verify，versionCode 11），测试书 id `8712e2f68447c3460f34`。
- **回翻停末页**：章 5 第 `1/12` 页按「上一页」→ 落到 `4/12 · 12/12`（`pageIndex=11`，块 76）；t+3s / t+6s / t+10s 三次采样均保持 `12/12`，无「重排后回弹首页」。✅ 这正是哨兵当年要防的失败模式，锚点路径同样挡住了。
- **章内回翻**：末页再按「上一页」→ `4/12 · 11/12`（块 69），未误触发换章。✅
- **旋转不漂移**：竖 `11/12` → 横 `16/19` → 竖 `11/12` → 横 `16/19` → 竖 `11/12`，`locusBlockIndex` 恒 69。✅
- **冷启动续读**：force-stop 后重开，落回 `5/12 · 1/12`，与退出时一致。✅
- 稳定性：pid 不变，crash 缓冲无本应用记录。✅
- 单测：**492 / 0 failures / 1 skipped**（用例数不变：3 条断言哨兵的用例改断言锚点，1 条哨兵专项测试换成「哨兵别回来」的反向守卫）。

## 2026-09-01 听书跟随「用户接管窗口 + 回到朗读处」真机验收（PKB110 / Android 16 / ColorOS）

- 包：`com.linguareader.app.verify`（1.5.1-verify，versionCode 11，含 commit `d186373`），测试书《The Lantern Library (Verify 12ch)》（id `8712e2f68447c3460f34`）。分工：用户看屏幕验交互，agent 用 `uiautomator dump` + `run-as cat metadata.json` 验后端状态。
- **按钮存在性**：听书条出现 `content-desc='回到朗读处'` 的可点节点（在「下一句」与「设置起点」之间），仅当 `highlightBlockIndex >= 0` 时渲染。✅
- **按下跳回朗读处且不拽动引擎**（暂停态隔离，排除引擎自然推进的干扰）：暂停于 `ttsSentenceIndex=21`（块 15）→ 用户往后翻 3 页到块 20（page 3）→ 按下 → 落到块 15 / page 2，`ttsSentenceIndex` 恒 21。✅ 说明落位引发的位置回报被抑制窗口（`TTS_JUMP_REPORT_SUPPRESS_MS=1500`）挡住了。
- **接管窗口 ≈10s**（滚动模式，两次独立测量；采样分辨率受 uiautomator dump 限制约 2.5s）：
  - 运行 A（朗读在 92%）：拖回章首 0% → 到 ~10s 仍 0% → ~14s 跳到 92%。
  - 运行 B（朗读在 4%）：拖回 0% → t=10.4s / 12.9s 仍 0% → t=15.7s 跳到 4%。
  - 旧行为是下一句（1–3s）就被拽回；两次均未在 <8s 内被拽回。✅
- **按钮立刻结束窗口**（对照组）：朗读在 22% 时拖回 10% → 立刻按「回到朗读处」→ 2.4s 内跳到 24%（朗读处），远早于窗口自然到期。✅
- **稳定性**：全程 pid `12183` 不变，crash 缓冲无本应用记录，主日志无 `FATAL`/异常；测试中途设备被旋到横屏（ROTATION_270，2374×1080）仍正常，按钮 bounds `[1732,732][1876,876]`。✅
- 前端（用户实测）：按下后页面跳到正在朗读的段落、高亮立刻可见（不用等下一句）、朗读未被打断或跳句——均符合预期。✅
- **本轮未解禁分页跟随**：`followRangeIntoView` 的 `if (!scrollMode) return;` 原样保留（AGENTS.md 禁区），所以分页模式下「朗读在念、页面不动」仍是预期现象，「回到朗读处」正是该场景的手动补偿。播放中手动翻页把引擎拽到该页首块首句（BUG-034）本轮未修，属 M2/T2.3 范围——测试中复现过一次（回翻 2 页后 `ttsSentenceIndex` 21→1），已确认由翻页触发而非新按钮。
- 单测：**492 tests / 0 failures / 1 skipped**。

## 2026-09-01 M1 语义锚点真机验收（PKB110 / Android 16 / ColorOS）

- 包：`com.linguareader.app.verify`（1.5.1-verify，versionCode 11），测试书《The Lantern Library (Verify 12ch)》（id `8712e2f68447c3460f34`，每段带 `[Cnn-Pnnn]` 标记）。判据以 metadata.json 的 `locusBlockIndex`/`locusCharOffset` 为主，页指示器/可见段落标记为辅（模型不看图，全靠 run-as + uiautomator dump）。
- **A1 旋转不漂移**：竖屏 `3/12 · 4/21`（块 11）→ 横屏 `3/12 · 7/41`（块 11）→ 竖屏 `3/12 · 4/21`（块 11）→ 再横屏 `3/12 · 7/41`（块 11）→ 再竖屏 `3/12 · 4/21`（块 11）。页码随方向重排变化，块恒 11。✅
- **A2 改字号不漂移**：140%→100%→130%→110%→140%，`locusBlockIndex` 恒 11（页码随字号 3→1→2→1 变化属预期）。✅
- **A3 回翻停末页**：章 3 往前翻，落到章 2 末页 `2/12 · 12/12`（块 77）。✅
- **A4 进滑动不跳章首**：章 3 第 10/12 页（≈82%，块 62）慢拖进滑动，指示器 `3/12 · 章节进度 84%`（块 62）。✅
- **A5 冷启动续读**：滚动模式杀进程重开，`locusBlockIndex` 恒 13，退出与重进一致。✅（已知边界：`scrollMode`/`scrollRatio` 未落盘，冷启动默认回到分页模式再按锚点落到同一块——模式记忆不在 M1 范围，锚点语义不受影响。）
- 验收当场发现并修复 **两处「锚点被派生量反向覆写」缺陷**（均是真机才暴露、单测之前全绿）：
  1. **旋转丢失锚点**：`ReaderPositionSaver`（`ReaderScreen.kt`）还存旧十项，M1 新增的 `locusBlock/Offset/Anchor` 三项没进存档 → 旋转后锚点被还原成 -1，退回按页码兜底，漂移复现。修复：存档格式迁入 `ReaderPosition.toSaveList()/fromSaveList()`，新增 `SAVE_SLOTS` 与反射字段数守卫（`saveListCoversEveryDeclaredField`）+ 全字段 round-trip 用例。
  2. **重排/还原每次向下取整一块**：`applyPage()` 无条件 `refreshAnchorLocus()`，分页视口起点常落在页首块中间，重取就把锚点取整到该页第一块；`lrLocusHere()` 也从视口重取而非回报权威锚点。两者叠加 → 每重排一次倒退一块，实测转两次屏从块 5→3→0（章首）。修复：`applyPage(reanchor)` 只由用户动作传 true；`lrLocusHere()` 分页模式回报 `anchorLocus` 本身；`lrSetPage(value, keepAnchor)` 用户跳页作废锚点、重排/还原保留锚点。新增 `anchorIsRefreshedOnlyByUserInitiatedMovement` 等 3 条守卫。
- 单测：**490 tests / 0 failures / 1 skipped**（M1-2 后为 486，本轮 +4）。
- 结论：位置真相（章、块下标、块内偏移）在旋转/改字号/回翻/进滑动/冷启动五条路径上均稳定，M1 验收通过。

## 2026-08-06 F-122 短语识别逻辑修复（增量验证）

- 根因：短语匹配只看“点击词是否在词条中”，词组后部宾语/补足语（如 `good day` 的 `day`、`out of time` 的 `time`）或点击偏移漂移到相邻词时，仍会短语优先，导致点到单词却显示词组释义。
- 修复：短语优先仅由“首个实义词或紧随动词的小品词”触发；点击词与短语词条按词形还原对齐（支持 `have got to` 等变形词条）；同位置多候选优先核心命中；点击词定位以表面词为准防偏移漂移。
- 验证：`testDebugUnitTest` 通过（59 个，新增 3 个 F-122 回归用例）；`lintDebug` 通过；`assembleDebug` 通过；`connectedDebugAndroidTest` 通过（Android 15，32/32，含新增 2 个相邻词回退/变形词头仪器用例）。
## 2026-08-06 F-138 自定义复习节奏 + F-137 提醒方式更新（增量验证）

- F-138 自定义节奏：设置面板新增“自定义”入口，在示意遗忘曲线上取点（约 70%–95% 记忆保留率）换算间隔倍率（×0.5–×2.0，30 分钟下限），并可调整首次复习（5 分钟 / 30 分钟 / 2 小时 / 次日）、每日主动提示上限（1/2/4 次）与单次最多复习词数（3/5/10）；以 `review_mode_custom` JSON 持久化，旧数据无需迁移。
- F-137 提醒方式独立化：语境浮现、停顿点提示、工具栏角标、定时轻提醒可自由组合，另有“仅手动”一键全关；`review_reminders` JSON 持久化，升级时按当前节奏预设的经典组合兜底；选择预设会恢复其经典提醒组合，之后仍可单独调整。
- `testDebugUnitTest`：通过（56 个），新增 ReviewPace 曲线换算/JSON 往返/倍率缩放与 ReviewReminders 组合用例。
- `lintDebug`：通过，0 错误。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：30/30 通过；ReviewUiTest 覆盖提醒开关组合、预设选择、自定义曲线编辑与保存。

## 2026-08-06 增量验证（P4 多格式 + P5 复习提醒 + P6 全量收尾 + P7 启动问候/更新提示）## 2026-08-06 增量验证（P4 多格式 + P5 复习提醒 + P6 全量收尾 + P7 启动问候/更新提示）

- `testDebugUnitTest`：通过。新增 PdfImporterTest（Robolectric 模拟 Android 环境，共 6 个用例）：书签分章、无书签标题启发式分章、无标题按页块分章、扫描版/无文字层拒绝、标题行识别、单次提取按页切分。
- `lintDebug`：通过，0 错误；新增警告仅来自 PDFBox 依赖内的 Bouncy Castle `TrustAllX509TrustManager` 提示（离线使用不受影响）。
- `assembleDebug`：通过。APK 由约 44.4MB 增至 53.2MB（+8.7MB），新增 pdfbox-android 2.0.27.0 与 Bouncy Castle 1.72。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：26/26 通过。P4 覆盖 EPUB/TXT/FB2/PDF 导入（PDF 含真机 PDFBox 文本提取、书签分章、扫描版拒绝、文件名回退）、离线词典查词、生词持久化与复习状态、书架冒烟；P5 新增复习设置面板/停顿点提示 Compose 测试（3）、新词首次延迟（1）、通知链路（授权发送/每日上限/拒绝不崩溃，2）。本次回归发现并修复 TXT/FB2/PDF 书名回退误用临时文件名的问题（新增 ImportSupport.displayName/baseName）。
- `testDebugUnitTest`（P5 新增）：ReviewModeTest 5 个（预设参数、倍率缩放、掌握等级、遗忘重启）、ReviewReminderPolicyTest 3 个（仅勤学+授权+限额内提醒、每日上限、日期键）、ReaderScriptsTest 高亮脚本 2 个。
- `ReaderAcceptanceTest`（P6 新增，端到端）：打开种入的 EPUB → 翻页 → 配置变更（旋转）后保持当前页；点击页码输入 3 跳转成功；点击正文常用词打开查词面板。
- `LaunchPromptUiTest`（P7 新增，2 个）：问候弹窗（标题/文案/开始阅读）与更新说明弹窗（版本标题/条目/知道了）渲染正确。
- P7 实测（adb）：清数据模拟旧版本升级后首次启动显示“版本更新 1.1.0”说明，点“知道了”后重启仅显示时间问候，更新说明不再出现。
- `PdfImporterInstrumentedTest`：3 个用例全部通过（带书签 PDF 导入、无文字层拒绝、元数据缺失时用文件名做书名）。

## 2026-08-10 F-150 听书（增量验证）

- 新增听书：整章/全书连续朗读（系统 TTS，中英句级切分），朗读时当前句在阅读页高亮并自动翻到所在页；点击句子从此句开始听，手动切章/翻页后播放位置跟随。
- 播放控制：暂停/继续、上一句/下一句、0.5×–2.0× 语速（滑块 6 档）、停止；前台媒体服务支持后台播放，通知栏提供上一句/播放暂停/下一句/停止。
- 进度记忆：`metadata.json` 新增 `ttsChapterIndex`/`ttsSentenceIndex`，旧数据缺失按 0 兼容；停止/暂停/切章时保存。
- 架构：播放层仅依赖 `TtsSynthesizer` 接口，`TtsSynthesizerFactory` 为云端 TTS 预留替换点；当前实现为 Android 系统 TTS，不申请联网权限。
- `testDebugUnitTest`：通过（77 个），新增中英句切分（缩写/引号/省略号/中文无空格边界）、章节文本提取与句定位、听书进度 JSON 兼容、阅读器脚本高亮/点击跟读桥接用例。
- `lintDebug`：通过，0 错误（仅原有 `allowBackup` 弃用提示）。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：32/32 通过；ReaderAcceptanceTest 翻页/跳页/点词回归通过（模拟器预热后全量通过）。

## 2026-08-10 F-151 外置 TTS（增量验证）

- 新增三引擎听书设置：系统语音（默认）/ Azure 云 TTS（世纪互联 `chinanorth3`，音色列表实时拉取）/ 自建 OpenAI 兼容服务器（`/v1/audio/speech`，可接 Fish Speech S2、GPT-SoVITS 适配服务）。
- 整章首次预生成（并发 3 路）并缓存到 `files/tts_cache/`，首句就绪即播、其余后台生成；语速本地变速不重复合成；失败自动回退系统语音；Key/Token 经 Android Keystore AES-GCM 加密存储；删除书籍时清理对应缓存。
- `testDebugUnitTest`：通过（100 个），新增 Azure 音色 JSON 解析/默认音色选择/SSML 转义、OpenAI 兼容请求体与音色回退用例。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：32/32 通过（预热后全量；冷启动首轮偶发 2 条 ReaderAcceptanceTest 超时，重跑即绿，与云 TTS 改动无关）。
- 说明：云 TTS 合成依赖真实服务器，仪器测试仅覆盖现有离线链路；Azure/自建服务器联调需真实 Key/服务器后人工验证。

## 2026-08-10 F-151 火山引擎（豆包语音）增量验证

- 新增第四引擎“火山引擎（豆包语音）”：V3 HTTP SSE 单向流式接口 `https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse`；鉴权支持新版控制台 API Key（`X-Api-Key`，推荐）与旧版 App ID + Access Token（`X-Api-App-Id` + `X-Api-Access-Key`）；Resource ID 默认 `seed-tts-2.0`（也可选 `seed-tts-1.0` / `seed-tts-1.0-concurr` 使用 `BV*_streaming` 音色）；中文/英文音色分别配置，支持声音复刻 speaker ID；设置页“测试连接”中英文各合成一次。
- `testDebugUnitTest`：通过（113 个），新增火山引擎用例 10 个：请求体（文本/音色/采样率/mp3 参数）、中英文语音路由、配置校验（新版 API Key 或旧版 AppID+Token）、SSE 多帧音频 base64 解码、结束帧（code 20000000）停止、错误帧/HTTP 错误失败、新版/旧版鉴权头与请求 ID。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：32/32 通过（首轮 2 条 ReaderAcceptanceTest 冷启动偶发超时，预热后单独重跑 3/3 通过，与火山改动无关）。
- 说明：火山引擎联调需用户提供真实 API Key（或 App ID + Access Token）后人工验证；仪器测试仍只覆盖离线链路。

## 2026-08-12 F-150 真机问题修复（增量验证）

- 修复手动翻页后旧句高亮残留：`onPageChanged` 时立即清除 `lr-tts-overlay`（[ReaderScreen.kt](src/app/src/main/java/com/linguareader/app/ReaderScreen.kt)），下一句朗读时重新定位高亮。
- 高亮不再自动滚到对应页：移除 `lrHighlightSentence` 中的滚动逻辑与 `ReaderBridge.onTtsPage` 桥接（[ReaderScripts.kt](src/app/src/main/java/com/linguareader/app/reader/ReaderScripts.kt)、[EpubPage.kt](src/app/src/main/java/com/linguareader/app/reader/EpubPage.kt)），页面始终停留在用户当前位置。
- 新增“起点”按钮（[ListeningBar.kt](src/app/src/main/java/com/linguareader/app/ListeningBar.kt)）：进入起点模式后点击正文任意单词/句子即从该句开始朗读（只设起点、读到手动停止）。
- `testDebugUnitTest`：通过（113 个），ReaderScriptsTest 更新为断言“高亮不再自动滚页/不再调用 onTtsPage”。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- `connectedDebugAndroidTest`（Android 15 / API 35 模拟器）：全量 32/32 通过（首轮与预热轮偶发 1–2 条 ReaderAcceptanceTest 冷启动超时，单跑 1/1 通过，与本次改动无关）。

## 2026-08-12 F-150 起点交互重做（增量验证）

- 打开听书不再自动播放：`TtsPlaybackService` 新增待选起点状态（`ACTION_STANDBY`，[TtsPlaybackService.kt](src/app/src/main/java/com/linguareader/app/tts/TtsPlaybackService.kt)），播放条提示点击正文选择起点；首次正文点击经 `startFromBlockOffset` 从该句开始。
- 播放条“起点”按钮只消费其后第一次正文点击：JS 端 `lrSetChoosingStart` 标志在首次点击即被消费（[ReaderScripts.kt](src/app/src/main/java/com/linguareader/app/reader/ReaderScripts.kt)），正常播放中点击正文恢复点词查词，不再误触重定位。
- 顶部“听书”按钮在播放中仅暂停，不再进入选择状态；待选/暂停状态再次点击才重新进入选择。
- `testDebugUnitTest`：通过（113 个），ReaderScriptsTest 断言“选择起点标志首次点击即消费”。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。

## 2026-08-12 F-150 选择起点从本章开头播放（增量验证）

- 根因：JS `textAtPoint` 的 `closest` 选择器漏掉 `section/article/pre/h5/h6` 等叶子块；书内用这些标签作段落时，点词传回的段落文本在 Kotlin `TtsChapter` 中匹配不到，`sentenceIndexAt` 返回 null 并回退第 0 句。
- 修复：[ReaderScripts.kt](src/app/src/main/java/com/linguareader/app/reader/ReaderScripts.kt) 的 `textAtPoint` 改用与 `ttsBlocks()`/`TtsTextExtractor` 相同的 `TTS_BLOCK_SELECTOR`；[TtsTextExtractor.kt](src/app/src/main/java/com/linguareader/app/tts/TtsTextExtractor.kt) 的 `locateBlock` 增加“祖先段落包含多个叶子块时按点击偏移命中最长叶子并重定位偏移”的回退。
- `testDebugUnitTest`：通过（127 个），新增 `TtsTextExtractorTest.sentenceIndexAtRebasesOffsetFromAncestorParagraph` 与 `ReaderScriptsTest.tapToStartUsesSameBlockSelectorAsTtsExtractor`。
- `lintDebug`：通过，0 错误；`assembleDebug`：通过。
- 说明：本次工作区与另一会话的 AI/系统语音改动并存，文档记录为增量追加；仪器测试沿用既有 32/32 基线（冷启动偶发 1–2 条 ReaderAcceptanceTest 超时，单跑通过）。

# Validation report

Validated on 2026-07-30 with an Android 15 / API 35 ARM64 Pixel 6 emulator.

## Automated checks

- `testDebugUnitTest`: passed
  - book metadata and reading-position JSON round trip
  - tokenization preserves abbreviations, possessives and hyphenated words
  - phrase-window generation and context POS ordering
  - reader JavaScript contains word/sentence extraction and sentence offsets
  - reader preferences are safely encoded
  - saved-word JSON round trip, CSV escaping and review scheduling
- `connectedDebugAndroidTest`: 8/8 passed
  - EPUB metadata/spine parsing and script sanitization
  - bundled ECDICT lookup with irregular inflection (`carried` → `carry`)
  - lemmatized phrases (`looked forward to` and `took off`)
  - abbreviation lookup retaining internal periods (`U.S.`)
  - saved-word persistence and review-state update
  - bookshelf Compose UI smoke test
  - vocabulary screen navigation and controls
- `lintDebug`: passed with 0 errors
  - remaining notices are dependency-update and optional KTX-style suggestions
- `assembleDebug`: passed

## Manual emulator checks

Using [`测试电子书-TheLanternLibrary.epub`](测试电子书-TheLanternLibrary.epub):

1. Imported through Android's system file picker.
2. Verified title, author, three chapters and generated bookshelf card.
3. Opened chapter one and confirmed four-page pagination.
4. Used the right edge to move from page 1 to page 2.
5. Opened the table of contents and reading appearance controls.
6. Restarted the app and confirmed the stored book and reading position.
7. Tapped `carried`; the app selected `carry`, inferred a verb context and moved
   `vt.` / `vi.` Chinese senses ahead of the noun sense.
8. Tapped `forward` in `They looked forward to working together.`; the app matched
   the complete phrase `look forward to` and returned its Chinese phrase meaning.
   Tapping the function word `to` now shows the word meaning plus a
   “相关短语：look forward to” entry point instead of replacing the word lookup.
9. Added `carry` to the vocabulary list and confirmed its pronunciation button,
   three context-ranked senses, source sentence, book and chapter.
10. Opened review mode, revealed the answer and verified both “again” and
    “remembered” grading actions.
11. Confirmed the app manifest does not request network access.

## Artifact

- APK: [GitHub Releases](https://github.com/xyu20071224-design/reader/releases)（本地副本：`artifacts/LinguaReader-1.2.0-debug.apk`）
- Version: 1.2.0 (version code 5)
- Size: approximately 53 MB
- SHA-256: `60815E4E4A941CD24DD656DABBA0F043F77E8C0DECAA0BBEE84F63A11833BE03`（2026-08-07 更新说明精简后重建刷新）

The APK is debug-signed for direct installation and evaluation. A Play Store
release still requires a production signing key and release configuration.

## 2026-08-15 1.3.1 缺陷修复（增量验证）

本次对 1.3.0 听书链路做代码审查后，修复三类问题（详见 `更新报告-1.3.0到1.3.1.md` 与 FEATURE_SPEC 规约 1.6.9）：

- 听书进程稳定性：待选（standby）态前台服务化、进度写盘竞态、快速切句跳章、章节握手白等、utteranceId 复用漏句、句尾卡死。
- 凭证安全：Keystore 并发覆盖密钥、AI 凭证明文、解密健壮性。
- 高亮定位：叶子块判定不等价导致的内联样式章节高亮错位、滚动恢复闪跳。

验证情况：

- `assembleDebug`：通过（JDK 17 / Gradle 8.11.1 / AGP 8.9.1，`compileDebugKotlin` 通过，无编译错误）。构建环境用 `android.overridePathCheck=true` 放行含中文的项目路径。
- `testDebugUnitTest`：未在本环境全量重跑；本次新增 `TtsTextExtractorTest` 两个用例（内联叶子块判定 + 嵌套块防回归），`LaunchPromptTest` 不受新增 1.3.1 更新说明分支影响。
- `lintDebug` / `connectedDebugAndroidTest`：未重跑，需完整 Android 环境（模拟器/真机）补做最终回归。
- 产物：`LinguaReader-1.3.1-debug.apk`（调试签名，约 56 MB，SHA-256 `ECE3E30B36EDA4004949E9EF92A553E53B85FDF4D79EEB9D9895E7D47814B234`），已发布至 GitHub Releases tag `v1.3.1`。

## 2026-08-20 F-152 多角色听书 M2（LLM 说话人打标，增量验证）

本轮完成 `PLAN-MULTI-VOICE.md` 的 **M2 里程碑**（M1 规则层双音色已在前一轮落地）：

- 书级角色画像：`CharacterProfile`（姓名/别名/性别/年龄段/风格/重要性/语言/置信度）随 AI 语境档案一次产出（D1，共用 `AiChatClient` DeepSeek 调用框架），并按「手动优先、别名取并集」并入本书术语表 `kind=character` 条目。
- 章节 LLM 打标：`SpeakerLlmTagger` 按「段索引 + 引文序号」对齐（`SpeakerRuleTagger.index()` 提供槽位），角色表 + 别名归一 + `confidence ≥ 0.6` 校验，未通过者退回规则层标签；长章节按 12k 字符分窗，无引文窗口不请求。
- 缓存与增量：`files/ai/speaker-tags/<bookId>/<chapter>.json`（Mutex + 原子写），已打标章节不再请求，句数不匹配的旧缓存作废；删除书籍或重建语境档案时清除。
- 播放接线：章节先用规则层结果开播，LLM 结果返回后热替换标签（`TtsPlaybackEngine.applySpeakerTags` + `TtsTextExtractor.applySpeakers`），队列/`utteranceId`/进度/高亮零影响；每章只解析一次，避免逐句重复读缓存或重复请求。
- 降级：联网 AI 关闭、无 DeepSeek Key、角色表为空、请求失败/超时均保持 M1 规则层结果，失败章节不落缓存。D2 限制保留：Piper/系统语音以及未配置对白音色时不发起任何打标请求。

验证情况：

- `testDebugUnitTest`（JDK 17 / Gradle 8.11.1 / AGP 8.9.1，`--offline`）：**206 个全部通过**，其中本轮新增 44 个：
  - `SpeakerLlmTaggerTest` 18 个：角色表/别名/大小写归一、引文与段落级对齐、未知角色与低置信度退回规则层、缺字段与非法 JSON、分窗策略（无引文不请求、长章节切窗）、提示词携带绝对段索引与引文编号、后端失败/部分窗口失败的降级与可缓存性、标签与句子长度一致。
  - `SpeakerTagRepositoryTest` 9 个（Robolectric）：缓存读写与增量（同章第二次零请求、新章节才请求）、长度不匹配缓存作废、无 Key/总开关关闭/空角色表零请求降级、后端失败不落缓存且下次重试、`delete` 清空、越表角色名不被写成角色。
  - `CharacterProfileTest` 11 个：`CharacterProfile`/`BookContextProfile`/`GlossaryEntry`/`ChapterSpeakerTags` JSON 往返与缺省值、多段档案合并、`mergeProfile` 手动优先与别名并集。
  - `SpeakerRuleTaggerTest` +4 个：段落/引文槽位索引、`index()` 与 `tag()` 一致、跨段引文在本段内编号、空章节。
  - `TtsPlaybackEngineTest` +2 个：播放中热替换说话人标签后下一句改用角色音色；跨书/跨章/长度不符的标签被丢弃。
- `lintDebug`：通过，0 错误（40 条提示中 39 条为既有警告，无一来自本轮新增文件）。
- `assembleDebug`：通过（调试 APK 打包成功）。
- `connectedDebugAndroidTest`：本轮未重跑（需模拟器/真机）。
- 真实链路人工验证仍待做：需真实 DeepSeek Key + 云 TTS 服务器，按 `PLAN-MULTI-VOICE.md` §9「真机/服务器验证」抽验归属准确率（目标 LLM ≥ 90%）。

## 2026-08-20 F-152 多角色听书 M3（角色 → 音色自动分配，增量验证）

本轮完成 `PLAN-MULTI-VOICE.md` 的 **M3 里程碑**，让 M2 的逐句角色标签真正发出不同声音：

- 音色库画像：`VoiceInfo`/`VoiceLibrary`/`VoiceNaming` + `VoiceLibraryLoader`。Azure 取 `voices/list` 的性别与 `StyleList`；自建服务器新增 `GET /voices`（本地 Kokoro 包装）与 `/v1/audio/voices`（Kokoro-FastAPI）音色列表拉取并缓存（`ServerVoiceStore`）；火山用已配置音色；裸音色 ID 由命名先验补全语言/性别（`zf_/zm_/af_/am_/bf_/bm_`、`zh_female_*`、`zh-CN-*`）。
- 分配算法：`VoiceAssigner` = 硬过滤（语言必配、性别不冲突）→ 软评分（性别/年龄/风格/重要性×音质）→ 按重要性与共现度贪心并施加区分度惩罚 `λ·Σ sim` → 交换一次 → **仅在不共现角色之间复用** → 旁白兜底；旁白音色先选（偏中性风格）并计入已占用，天然与角色拉开。
- 共现统计：`SpeakerCooccurrence` 直接复用 M2 的章节打标缓存（跳过旁白/未归属对白、折叠连续同一说话人），不新增存储。
- 持久化与一致性：`BookVoiceMap` + `VoiceMapRepository`（`files/voice_maps/<bookId>.json`，Mutex + 原子写）：跨章跨会话不重算、新角色增量分配、`userLocked` 永不被覆盖、切换引擎重算但保留锁定；删除书籍时一并删除映射。
- 播放接线：`TtsPlaybackService.resolveVoice`（手动旁白音色 → 角色/旁白映射 → 对白音色 → 引擎默认）同时供播放队列与云引擎整章预生成使用（`TtsSynthesizerFactory.create(..., voiceResolver)`），避免预生成把音频缓存到播放端不会请求的音色下；解析器签名扩展为 `(speaker, text)`，按句语言选择中/英旁白音色；设置快照随 ACTION_RECONFIGURE 失效。

验证情况：

- `testDebugUnitTest`（`--offline`）：**246 个全部通过**，其中本轮新增 40 个：
  - `VoiceAssignerTest` 15 个：硬过滤（语言/性别/放宽顺序）、软评分（风格与年龄细化）、旁白中性选择与保留位、共现拉开（同一输入有/无共现得到不同结果）、复用仅限不共现且相邻角色宁退旁白、交换、锁定不动、增量不动旧映射、换引擎重算保留锁定、空音色库不改动、中英旁白各一个、同重要性顺序确定。
  - `VoiceMapRepositoryTest` 7 个（Robolectric）：落盘与重载、增量新增角色、锁定项在重分配后保留、切换引擎在新库内重算、无音色库返回空、保留位、删除。
  - `BookVoiceMapTest` 7 个：JSON 往返、大小写无关查找、旁白按语言回退、未知说话人回退调用方默认、锁定/解锁、空输入不产生垃圾条目。
  - `VoiceLibraryTest` 6 个 + `SpeakerCooccurrenceTest` 4 个 + `OpenAiCompatTtsBackendTest` +1（音色列表三种返回形状与异常载荷）。
- `lintDebug`：通过，0 错误（40 条提示：1 条信息 + 39 条既有警告；本轮新增文件无任何告警，`ServerVoiceStore` 用 `edit {}` KTX 写法）。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`：本轮未重跑（需模拟器/真机）。
- 真实链路人工验证仍待做：需真实云 TTS/自建服务器，人工确认「主要角色音色区分度」与「同书跨章音色一致」，并核对 Kokoro `/voices` 实际返回的音色数量是否足够 10+ 角色。

## 2026-08-20 F-152 多角色听书 M4（角色音色界面，增量验证）

本轮完成 `PLAN-MULTI-VOICE.md` 的 **M4 里程碑**，多角色听书自此形成完整闭环（M1 规则层 → M2 LLM 打标 → M3 自动分配 → M4 人工可调）：

- 显式开关：`CloudTtsSettings.multiVoiceEnabled`（默认关，持久化）。关闭时不发任何打标请求、不生成音色映射，播放等同单音色 + 手填旁白/对白音色；Piper 与系统语音下开关置灰并给出 D2 说明。
- 多角色面板 `MultiVoiceSection`（`MultiVoiceSettings.kt`）：书目选择（阅读页自带当前书）、状态提示（无音色库 / 无角色表 / 无 Key 规则模式 / 音色不足已共用 / 已分配 N 个）、中英旁白音色下拉、按重要性排序的角色列表（角色名 + 画像摘要 + 音色下拉 + 试听 + 🔒 锁定标记）。
- 试听：`VoiceAudition` 用当前引擎按指定音色合成一句样句并用 MediaPlayer 播放（中文音色说中文样句、英文说英文），播放前停掉上一条避免叠音。
- 共用装配：`MultiVoiceSupport` 收敛服务与 UI 的公共逻辑（音色库刷新、角色表读取、保留音色、D2 判定、状态文案、样句），`TtsPlaybackService` 改为复用它。
- 解析优先级调整：角色/旁白映射（M3，M4 面板直接编辑）→ 手填旁白/对白音色（M1）→ 引擎默认，避免面板改了旁白却被旧手填值覆盖。
- 立即生效：任何手动选择写入 `userLocked` 后触发 `onCloudSettingsChanged`（ACTION_RECONFIGURE），服务重载映射，下一句起使用新音色。

验证情况：

- `testDebugUnitTest`（`--offline`）：**255 个全部通过**，其中本轮新增 9 个：
  - `MultiVoiceSupportTest` 6 个：D2 引擎判定、开关/总开关/引擎三重门控（默认关）、保留音色集合、中英样句与旁白样句、状态文案五种分支、共用音色计数与「音色不足」提示。
  - `CloudTtsSettingsTest` 3 个（Robolectric）：多角色开关默认关、听书设置（含开关与旁白/对白音色）保存-加载往返、关闭后仍持久化。
- `lintDebug`：通过，0 错误（40 条提示：1 条信息 + 39 条既有警告；本轮新增文件零告警——UI 的 `AutoboxingStateCreation` 提示已按建议改用 `mutableIntStateOf`）。
- `assembleDebug`：通过。
- `connectedDebugAndroidTest`：本轮未重跑；多角色面板的交互（下拉选择、试听播放）需真机/模拟器 + 真实云 TTS 服务人工验证。
- 仍待人工验证：开关打开后端到端听感（角色音色区分度、跨章一致性）、试听按钮在各引擎下的成功率、Kokoro `/voices` 返回的音色数量是否足够 10+ 角色。

## 2026-08-20 F-152 多角色听书 M1.5（IndexTTS 2.5 克隆音色引擎，收尾验证）

M1.5 此前只有「服务能跑」的既成事实（8001 上的 IndexTTS 2.5、手机端已成功合成过 LOTR 正文），本轮把它补成完整里程碑：

- 服务纳管：`tts-server/indextts/indextts_server.py` 作为仓库权威副本（与安装目录副本一致）。新增 `INDEX_VOICES_DIR` 克隆音色目录与 `voices/voices.json` 画像清单；`GET /voices` 在保持兼容的 `voices` 字符串数组之外新增 `voice_profiles`（id/label/language/gender/style）；`voice` 解析顺序为「绝对路径 → 克隆音色目录 → 安装目录 examples/ → 默认参考音频」。已重启实测：`/voices` 返回 16 个音色 + 16 条画像，登记后的 `voice_03.wav` 正确带出 `language=zh, gender=female, style=[calm]`。
- App 贯通：`ServerVoice`（新）承接服务器画像 → `ServerVoiceStore` 缓存（兼容旧的纯 id 缓存）→ `VoiceLibraryLoader` 与命名先验合并，克隆音色因此能参与 M3 的语言/性别硬过滤；`VoiceNaming` 识别 `clone_<角色>_<lang>_<m|f>` 并在匹配先验前去掉 `.wav/.mp3/...` 扩展名（IndexTTS 用文件名当音色 id）。
- 工具：`scripts/make_clone_voice.py`（从任意音频剪 3–10 秒参考音频、按约定命名、写入清单，**强制 `--consent`** 声明素材来源）；`scripts/tts_compare.py`（同批中英句子逐句实测两引擎，产出 `artifacts/tts-compare/report.md` 与可试听样音）。
- 中英实测（RTX 5070 Ti，两服务本机运行）：

| 引擎 | 语种 | 句数 | 平均每句 | 每字 |
|---|---|---|---|---|
| Kokoro（CPU） | en | 6 | 0.45 s | 0.010 s |
| Kokoro（CPU） | zh | 2 | 0.77 s | 0.029 s |
| IndexTTS 2.5（GPU 克隆） | en | 6 | 2.58 s | 0.057 s |
| IndexTTS 2.5（GPU 克隆） | zh | 4 | 3.17 s | 0.119 s |

  结论：性能上 Kokoro 快 5–6 倍且纯 CPU；IndexTTS 单句 1.5–4.7 秒可用于在线逐句合成，全书缓存对其保持禁用（App 按 `/v1/models` 自动隐藏按钮）。**人工试听后（2026-08-20）选定中英文默认引擎均为 IndexTTS 2.5**：英文 `first_3s_1.wav`、中文 `voice_03.wav`（样音 `indextts_first_3s_1_en_0.mp3` / `indextts_voice_03_zh_4.mp3`），Kokoro 转为快速/无 GPU 兜底。
- 落地该结论：自建服务器新增「英文音色 / 中文音色」两个可选字段（`serverEnVoice`/`serverZhVoice`，按句语言路由，留空回落通用音色），听书设置面板同步；这两个音色同时进入音色库并计入多角色分配的保留位。选定的两个音色已登记进 `tts-server/voices/voices.json`（带语言/性别/风格画像），其中 `first_3s_1.wav` 标注为「仅本机自用，参考音频取自商业音乐轨，发布前须替换」。
- 合规：核对 IndexTTS2 的《bilibili 模型使用许可协议》——免费、非独占、不可转让，仅在「月活 > 1 亿或上一自然年营收 > 1 亿人民币」时需另行申请商业许可（本项目无需）；须保留版权声明与许可副本、不得用其输出改进其他商用 AI 模型、禁止高风险场景、输出合规责任自负。多角色面板已常驻「仅用自备/授权素材 + AI 合成」提示；仓库不内置任何克隆参考音频（`tts-server/voices/` 音频已 gitignore），历史测试素材 `artifacts/first_3s.wav`（取自商业音乐轨）标记为不得用于发布。

验证情况：

- `testDebugUnitTest`：**259 个全部通过**（新增 4 个：`/voices` 画像解析、`clone_*` 命名先验、音频扩展名不影响先验、自建服务器中英音色路由；`CloudTtsSettingsTest` 往返用例同步覆盖新字段）。
- `lintDebug` / `assembleDebug`：通过。
- 服务侧实测：Kokoro 8000 与 IndexTTS 8001 均在本机启动成功，`scripts/tts_compare.py` 18 次合成全部 200 成功。
- 仍待人工：中英音质对比结论、以「角色专属克隆音色」跑一遍端到端听书（目前 `voices/` 只登记了 IndexTTS 自带示例，未放任何真人参考音频）。

## 2026-08-20 UI 评审整改第一批（外壳夜间模式 + 多角色面板打磨）

代码级 UI 评审后先修两处影响最大的：

- **外壳夜间模式**：`ThemeColors.kt` 由「一组固定颜色」重构为 `LinguaPalette` 日间/夜间双调色板 + `LocalLinguaPalette`，13 个语义色改为 `@Composable @ReadOnlyComposable` 取值——**所有界面调用点（`Ink`/`Paper`/`Accent`…）零改动**即可跟随主题；`colorSchemeFor(palette)` 派生 Material 配色，`ApplySystemBars` 让状态栏/导航栏颜色与图标亮度跟随（此前 themes.xml 把状态栏钉死浅色）。切换规则 `chromeIsDark`：正文阅读主题为「夜间」→ 外壳变暗，其他主题→日间，从未设置过→跟随系统深色；阅读设置里改主题会通过 `onAppearanceChanged` 即时切换外壳。夜间底色与正文夜间主题同为 `#171717`，消除了「暗色正文里打开目录/听书设置白屏闪光」的问题。强调色在夜间提亮为棕金，其上文字改用新的 `OnAccent`（日间白/夜间墨），替换了 9 处硬编码 `Color.White`；`ReviewCurvePicker` 的 Canvas 颜色改为在 composable 作用域先取值。
- **多角色面板整改**（我上一轮欠的债）：
  1. 新增「恢复自动」——行内解锁图标与选择弹层里的按钮，调用 `VoiceMapRepository.unlock` 后立刻重跑分配（此前锁了就回不到自动）；
  2. 音色选择改为**可搜索分组弹层** `VoicePickerDialog` + 纯逻辑 `VoicePicker`（推荐＝同语言且性别不冲突置顶，其余按「语言 · 性别」分组、角色语言优先；支持按 id/语言/性别/风格搜索），替代原来 `take(60)` 的裸下拉（Kokoro 有 100+ 音色）；
  3. 试听有状态：`VoiceAudition` 增加完成回调与 `isPlaying`，按钮在播放时变「停止」，选择弹层里每个音色都能单独试听；
  4. 锁定标记与下拉箭头改用 `Icons.Default.Lock/LockOpen/ArrowDropDown`（替换 emoji 与 `"▾"` 文本，顺带修掉 AI 中心与听书设置里另外 3 处 `▾`）；
  5. 角色列表不再在滚动 sheet 内嵌同方向滚动列。

验证情况：

- `testDebugUnitTest`：**269 个全部通过**（新增 10 个：`ThemeColorsTest` 5 个——跟随阅读主题/跟随系统/调色板选择/夜间对比度与底色对齐/配色方案派生；`VoicePickerTest` 5 个——搜索命中 id/语言/性别/风格、推荐组置顶与性别不冲突、分组标签与角色语言优先、搜索收窄与空结果、行文案）。
- `lintDebug`：通过，0 错误（40 条提示：1 信息 + 39 既有警告，本轮改动无新增告警）。
- `assembleDebug`：通过。
- 仍待人工：真机看夜间外壳观感（尤其 sheet/弹层与状态栏过渡）、音色选择弹层在 100+ 音色下的滚动手感。
- 评审里其余未做项（留待第二批）：全局 Snackbar 替代散落的状态文字、文案抽到 `strings.xml`、统一听书设置/术语表入口、`Typography` 定制、平板/横屏适配、部分图标补 `contentDescription`。

## 2026-08-20 UI 评审整改第二批（全局 Snackbar + 文案资源化）

- **全局 Snackbar**：新增 `AppSnackbar` + `LocalAppSnackbar`（同一时刻只显示一条，新提示顶掉旧的），`MainActivity` 用 `SnackbarHost` 承载并浮在书架/阅读页之上；`AppUiState.notice` 作为一次性提示通道（`clearNotice()` 弹过即清），导入成功提示「已导入《X》」、删除提示「已删除《X》」——此前这两个操作**完全没有反馈**。多角色面板的「已锁定音色 / 已恢复自动 / 试听失败」也从面板内一行状态字改为轻提示。
- **文案资源化（首批）**：`values/strings.xml` 建立命名规范（`common_* / notice_* / shelf_* / glossary_* / player_* / multivoice_*`），并新增 `values-en/strings.xml` —— **英文界面首次真正可用**，i18n 机制已被验证而不只是「留了路」。已迁移三个完整界面 + ViewModel 提示：`ListeningBar`（13）、`MultiVoiceSettings`（32）、`BookshelfScreen`（29）、导入/删除提示（2），并对「N 本书 / N 个生词 / N 个角色已分配」使用 `plurals` 正确处理英文单复数。
- **纯逻辑不再产出界面文案**：`MultiVoiceSupport.statusMessage(...)` 改为 `status(...)` 返回 `MultiVoiceStatus`（`NO_LIBRARY / NO_ROSTER / RULE_MODE / NO_MAP / SHARED_VOICES / READY` + 计数），界面映射到资源字符串；对应单测改为断言状态与计数而不是中文措辞。

验证情况：

- `testDebugUnitTest`：**269 个全部通过**（`MultiVoiceSupportTest` 改为断言状态枚举/计数）。
- `lintDebug`：通过，0 错误；资源侧无 `MissingTranslation`、无 `PluralsCandidate`（两处计数文案已改 `plurals`），本轮文件无新增告警。
- `assembleDebug`：通过。
- 迁移进度（剩余中文字面量，可作为下一批的度量）：`ListeningSettingsSheet` 105、`ReviewUi` 63、`ReaderScreen` 44、`AiDrawerSheet` 34、`VocabularyScreen` 24、`AppViewModel` 12、`LaunchPromptDialog` 2。
- **协作提示**：本轮**有意跳过 `ListeningSettingsSheet.kt`**（以及 `CloudTtsSettings/TtsSynthesizer/SherpaTtsSynthesizer/PiperVoice*`）——同一工作区另有会话正在这些文件上开发 Piper 音色导入功能，避免互相覆盖；这批提交只包含本会话改动的文件。
- 仍待人工：真机确认 Snackbar 位置不挡听书条（当前底部留白 72dp）、切到英文系统语言看 `values-en` 文案排版。

## 2026-08-20 本地 Piper 音色导入：评审问题修复（H1–H3 + M1–M4）

对「导入 Piper 英文音色」这批改动做代码评审后，直接修掉了 7 个问题：

- **H1 导入音色可能加载不了（asset 与文件路径混用）**：`SherpaTtsSynthesizer` 一直用 `OfflineTts(assets, config)` 构造，传 AssetManager 时 sherpa-onnx 会把路径当 asset 解析，而导入音色是 `filesDir` 下的绝对路径。新增 `PiperAssets` 收口加载逻辑：内置音色走 asset 构造、导入音色走 `OfflineTts(config = …)` 文件构造；且导入音色加载失败时**回退内置 Ryan**，不再让一个坏音色把整个 Piper 引擎（含中文）拖死。espeak-ng-data 的复制也从合成器搬到 `PiperAssets.ensureEspeakData`，与导入校验共用同一条路径。
- **H2 30–90 MB 模型在主线程复制（ANR）**：`PiperVoiceImporter.import` 改为 `suspend` + `withContext(Dispatchers.IO)`；设置页在协程里调用，期间按钮禁用并显示「正在导入并校验模型…」。
- **H3「离线引擎」里内嵌联网试听且绕过总开关**：样例试听与「下载」按钮现在受 `networkAiEnabled` 总开关控制（关闭时置灰并说明「已导入音色仍可离线使用」），文案明确标出「试听/下载需联网（HuggingFace / GitHub），导入后的朗读依然完全离线」；播放失败与打不开下载页从静默改为明确提示。
- **M1 失效记录会让英文朗读整体哑掉**：`PiperVoiceStore.imported()` 过滤掉模型/tokens 文件已不存在的记录，并把清理结果写回持久化数据。
- **M2 导入失败残留半个模型**：改为「先写 `.tmp` → 校验 → rename」的原子流程，失败时删临时文件、新建目录整体回滚。
- **M3 文件名直接当目录名（路径穿越）**：新增 `sanitizeId()`——取纯文件名、白名单字符（字母数字与 `_ . -`，CJK 作为合法字母保留）、去掉开头点、折叠连续点、限长 64、空名兜底。
- **M4 只校验扩展名**：新增体积区间（1 MB–400 MB）+ ONNX 结构探测（protobuf 首字段 `0x08` 或前 8 KB 出现 `onnx` 标识）+ **真加载校验**（`PiperAssets.canLoad`，通不过就不登记并提示「可能不是 Piper VITS 模型，或需要匹配的 tokens.txt」）。
- 顺手：`PiperVoiceStore` 与下载跳转改用项目统一的 KTX 写法（`edit {}`、`String.toUri()`），Piper 相关文件的 lint 告警清零。

验证情况：

- `testDebugUnitTest`：**274 个全部通过**（新增 `PiperVoiceImportTest` 5 个：id 净化与路径穿越、ONNX 结构探测、失效记录过滤与回写、未知 id 回退内置音色、官方目录 URL 形态）。
- `lintDebug`：通过，0 错误，Piper/Sherpa/听书设置三个文件 0 告警；`assembleDebug`：通过。
- **仍需真机验证（评审里的 H1 只能靠真机确认）**：导入一个 lessac/amy 模型后英文能出声、切回内置音色也正常、导入过程中界面不卡；以及联网关闭时试听/下载确实置灰。
- 未修的评审项（留给后续）：M5 多说话人模型固定 `sid = 0`（`libritts_r`/`l2arctic` 只能听到第一个说话人，需要 speaker 选择或明确标注）、M6 tokens 复用仅覆盖 en_US（现在靠加载校验兜底报错）、L1 该界面文案尚未资源化（`ListeningSettingsSheet` 仍有约 105 条中文字面量）、L5 可考虑放开 Piper 的多角色音色（有了多音色后 D2 的前提已不成立）。

## 2026-08-21 真机验证（OnePlus PKB110 / Android 16 / SDK 36 / arm64-v8a）

手机上已装的是另一台机器的调试签名，直接覆盖安装会要求卸载（清数据）。为不动用户的书与设置，
`app/build.gradle.kts` 增加了 `-PverifyBuild` 开关：加上它时 debug 包变成并存的
`com.linguareader.app.verify`（`versionNameSuffix=-verify`），验证完再卸载即可，正式包与数据全程未动。

**1. Piper 导入加载路径（评审 H1，只能真机验证）——新增仪器测试 4/4 通过**

`PiperVoiceLoadInstrumentedTest` 把内置 ryan 模型复制到 `filesDir`，再按「导入音色」的文件路径方式加载，
因此不需要下载任何外部模型就能覆盖同一条代码路径：

| 用例 | 耗时 | 结论 |
|---|---|---|
| `importedVoiceLoadsFromAbsoluteFilePaths` | 1.62 s | **H1 已修复**：文件路径构造能加载 63 MB 模型并真的合成出音频 |
| `bundledVoiceStillLoadsFromAssets` | 0.96 s | 重构未破坏内置音色的 asset 加载 |
| `garbageModelIsRejectedByLoadValidation` | 0.05 s | M4 的「真加载校验」确实拦下伪造 ONNX（0x08 头能骗过魔数探测） |
| `engineFallsBackToBundledVoiceWhenSelectionIsBroken` | 0.002 s | M1 过滤失效记录 + resolve 回退内置音色 |

**2. 外壳夜间模式（UI 第一批）——用截图平均亮度客观验证**

系统深色开关切换 + 重启应用，`adb exec-out screencap` 截图后用 ffmpeg `signalstats` 取平均亮度（0–255）：

| 区域 | 浅色 | 深色 | 结论 |
|---|---|---|---|
| 整屏 YAVG | 124.8 | **33.4** | 外壳整体变暗（改动前只有 `lightColorScheme`，此处会保持 ~125） |
| 状态栏（顶部 90px） | 105.6 | **32.9** | `ApplySystemBars` 生效 |
| 导航栏（底部 60px） | 104.6 | **31.2** | 同上 |

验证后已把系统深色恢复为 `auto`，截图已删除。

**3. Compose 界面回归（真机）**：`BookshelfSmokeTest` + `LaunchPromptUiTest` **5/5 通过** ——
说明调色板改成 `LocalLinguaPalette` 取值、以及三个界面的文案资源化都没有破坏 Compose 树与既有断言。

**4. 启动日志**：多次冷启动后 logcat 无本应用的 FATAL/异常；进程常驻正常。

**5. 文案本地化**：新增 `StringResourcesTest`（Robolectric，`@Config(qualifiers = "zh"/"en")`）断言中英资源与 `plurals`
实际取值（`导入`/`Import`、`1 book`/`2 books`、`1 character has a voice.`）——英文界面不是"留了路"而是可验证可用。

当前总计：**单元测试 277 个通过**（新增 `StringResourcesTest` 3 个）、真机仪器测试 9 个通过（Piper 4 + UI 5）、
`lintDebug` 0 错误、`assembleDebug` 通过。

仍需人工用眼确认（无法自动化）：夜间配色的观感与对比度、Snackbar 是否挡住听书条、英文界面长句在窄屏的换行；

## 2026-08-21 真机测试反馈的两处修复

用户在真机（PKB110 / Android 16）实测反馈两个问题，均已修复并在真机复验：

**1. 句高亮向下偏移约 3.8 行（分页模式）**

表现是「高亮框落在正在朗读那句下方几行的文字上」，看起来像选错了句子。加临时诊断日志后在真机取到确切数据：

| 量 | 实测值 |
|---|---|
| 文字位置 `rect.top` | 617.4 / 641.2 / 688.7 |
| 滚动容器 `rect.top` | 136.0（而 CSS/JS 都设的是 `top: 104px`） |
| 覆盖层 `rect.top` | 168.0 |
| 绝对定位子元素的实际原点 | **200.0** |
| 结果偏差 `deltaY` | **+64.0px** ≈ 2.7 行（行高 23.76px） |

根因：原实现按 `rect - scrollerRect + scrollLeft/scrollTop` 推算位置，隐含假设「包含块＝滚动容器的 padding box」。
在 WebView 的分页多列（`column-fill: auto` + 横向滚动）布局下该假设不成立——每层定位元素都多出 32px，
最终高亮框比文字低 64px。修法改为**自校准**：插入一个零尺寸探针，实测 `left:0;top:0` 究竟渲染在视口何处，
再按「文字视口坐标 − 原点视口坐标」摆放高亮框；与包含块、滚动偏移、多列分栏、WebView 视口怪癖都无关。
真机复验：连续三句 `deltaY` 均为 **0.0**、`deltaX` 0.0，随后移除诊断代码再跑一遍，无 JS 错误、高亮正常。

**2. AI 中心填完 DeepSeek Key 点「保存」没有任何反馈**

易被当成「点了没反应」。抽屉是独立窗口（全局 Snackbar 会被遮住），因此在按钮旁加行内 ✓ 确认，
3 秒后自动消失，并且说明保存后的实际效果：已就绪 / 未填 Key 走本地轻量语境 / 联网总开关关闭暂不生效 / AI 语境已关闭。
同时对 API Key、接口地址、模型做 `trim()`——粘贴时带的空格或换行以前会直接存进去，导致 401 却看不出原因。

验证：`testDebugUnitTest` **279 个通过**（新增 2 个脚本回归测试：探针定位不得回退成旧算法、诊断代码不得入库）；
真机用 uiautomator 驱动「书架 → 开书 → 听书 → 点正文选起点」全流程复验，日志无异常。

以及用**真实第三方 Piper 模型**（如 lessac/amy）走一遍完整导入流程（本轮用内置模型覆盖了加载路径，但没走文件选择器 UI）。

## 2026-08-21 F-128 中文译本对照（引擎实测 + 整合验证 + 真机验证）

对照引擎来自另一台机器的交付包（`对照模块-运行包.zip`：纯 Kotlin 引擎 + 可运行 `align-cli` + 魔戒中英测试书，
不含 UI、构建脚本与单测）。本轮做了「先量后并」：先在 JVM 上实测，再按实测结论整合。

**1. 引擎独立复现（`align-cli`，JDK 17 Temurin 17.0.20，无 Android SDK）**

| 指标 | 本机实测 | 交付包 README 自述 |
|---|---|---|
| 章节数 | 英 36 / 中 29 | 同 |
| 对齐句对 | **12,697** | 12,697（复现一致） |
| 平均置信度 | **0.79** | 0.79（复现一致） |
| 耗时 | 读英 199ms + 读中 74ms + 对齐 **29.0s** | 对齐 21.9s（机器差异） |
| 档案体积 | **15,025 KB**（pretty） | README 未提 |

**2. `TermLexiconLearner` 内存实测（决定砍掉它的依据）**

自写 JVM 探针（`artifacts/alignment-package/bench/Bench.java`，直接调 `core.jar` 里的实现）跑同一批 12,697 句对：

| 堆上限 | 结果 |
|---|---|
| 4 GB | 6.3s / **峰值堆 1,728 MB** / 产出 10,234 条 |
| 512 MB | **OutOfMemoryError**（`zhNGrams` 处） |
| 256 MB | **OutOfMemoryError**（`positionDistance` 处） |

累加器真实键数 **9,087,640** 个（英文词 × 中文 2–4gram，独立计数验证）。Android 单应用堆通常 128–512 MB，
故该路径在手机上不可行；且高频条目是 `about / after / again / all` 配 2 字 gram，属共现噪声而非专名译法。
结论：v1 不生成术语表（`terms` 恒空，字段与 `WordAligner.prefer` 入口保留）。

**3. 档案格式 v2 实测（同一本书、同一批句对、同等紧凑度对比）**

| 布局 | 体积 |
|---|---|
| v1（每条句对内联段落全文），pretty | 15,025 KB |
| v1，紧凑 | 14,206 KB |
| **v2（段落表 + 句对下标），紧凑** | **4,847 KB（34.1%）** |

去重后英文段落 3,469 / 中文 3,468，平均 3.66 句/段 —— 段落按句重复正是旧格式膨胀的原因。

**4. 整合后的构建与测试**

- `testDebugUnitTest`：**308 个通过、0 失败**（新增 27 个：对齐 5 / 格式 4 / 索引 11 / 词级 5 / `Book` 译本字段 2，
  其中含两条回归：两章正文相同时章节下标不得错配、旧元数据缺译本字段必须按「未配译本」读出）。
- `assembleDebug`：成功，`app-debug.apk` 386.1 MB。
- 同步交付包的两处共享文件改动后既有测试全绿：`SentenceSplitter` 新增 `spacedInitials`（保护 `J. R. R.`）、
  `ContextAnalyzer` token 命中改右端排他（`until`）。

**真机验证见下一节**（同日完成，含新增仪器测试与 UI 回归）。

## 2026-08-21 F-128 真机验证（OnePlus PKB110 / Android 16，设备 ZXJRNJVWY9C6BYDA）

**安装方式**：直接装 `com.linguareader.app` 失败——设备上已装的包是**另一台机器的调试签名**
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`）。按项目约定改用 `-PverifyBuild` 装并存的
`com.linguareader.app.verify`（`versionName=1.4.0-verify`，安装于 2026-08-21 05:21）。

> ⚠️ **事故记录**：验证结束后复查设备，原包 `com.linguareader.app` 已**不存在** —— `pm list packages -u`
> （含「已卸载但保留数据」）、`dumpsys package`、`/sdcard/Android/data/` 三处都查不到，只剩 `.verify`，
> 设备也只有一个用户（0/机主）。即原包连应用数据（书库、生词、复习进度）一起没了。
> 本次流程未执行任何 `pm uninstall`；怀疑是 AGP/UTP 在签名冲突时自行「卸载后重装」，但当时 gradle 输出
> 只保留了尾部，无法证实。**结论：面向真机的第一条 install / connectedAndroidTest 命令就必须带
> `-PverifyBuild`**，并先 `adb shell pm list packages -u` 看清设备上装了什么、签名来源是否可控。

**1. 新增 `TranslationAttachInstrumentedTest`（1 个，通过）** —— 在真机上跑完整链路：
构造中英两本小 EPUB → `BookImporter` 导入 → `TranslationMemoryRepository.attach` → 落盘 → 点词查对照 → `remove`。
断言（都是单测覆盖不到的部分：真实解压清洗、Android 上的 Jsoup 叶级抽取、真实文件 IO、只读 ECDICT SQLite）：

- 对齐句对非空，`sourceBookId` / `translationBookId` 正确，`terms` 为空（v1 约定）；
- 译本落在 `files/translations/<id>/`，且**不出现在** `files/books/<id>/`；
- 档案是 v2：有 `enParagraphs` 段落表、句对只有 `ep`/`zp` 下标、**没有**内联的 `enParagraph`；
- 点「1420」→ 句级命中 + `ANCHOR` 词级对齐，且 `start/endExclusive` 能在中文句里正好切出「1420」；
- 点普通词「left」→ 词级可能失败但**必须保住句级对照**；未知章节（7）→ 返回 null；
- `remove()` 把对齐档案与译本目录一起清掉，`hasMemory` 变 false。

**2. UI 回归（5 个，通过）**：`BookshelfSmokeTest`（2）+ `ReaderAcceptanceTest`（3）在真机跑通，
覆盖本轮改过的 `BookshelfScreen`（卡片多了「加译本」按钮与译本状态行）与 `ReaderScreen`（查词面板新增区块）。

**仍未验证（需要人手或真实图书）**

- 手工点「加译本」走 SAF 系统文件选择器的交互（自动化会被系统选择器挡住）。
- 「译本对照」区块在查词面板里的实际观感，以及真实图书（英文原版 vs 中译本）的命中率。
- 两处共享文件改动对**听书分句**与**既有点词落词**的主观影响：`ReaderAcceptanceTest` 通过说明没崩、
  章节与页码恢复正常，但分句/落词的差异要真人听、看才能判断。

## 2026-08-21 F-128 整本书真机实测 → 对齐性能修复（64×）

**真机整本书 attach（用户手工操作，魔戒首部曲英文原版 + 中譯本，各约 2.3/3.2 MB EPUB）**

- 结果：**成功**，但慢到不可用 —— 卡片「对齐中…」持续 **5 分钟以上**（约 6 分钟算完）。
  过程中 `top` 显示单线程 92–103% CPU、进程 RES 609 MB，说明是纯计算瓶颈而非卡死。
- 产物（`run-as com.linguareader.app.verify` 直接查）与设计完全一致：
  `files/translation-memory/67078feac33f4502f7e0.json` = **4,913,529 字节（4.69 MB）**，
  头部 `{"version":2,...,"sourceTitle":"The Fellowship of the Ring","translationTitle":"魔戒首部曲"...}`；
  译本落在 `files/translations/9fe0ec36567aba25f201/`，书架目录 `files/books/` 里只有英文原书。
  —— 这同时验证了 v2 格式在真实整本书上的体积（PC 上预测 4.8 MB）。

**根因与修复**（详见 `.agents/memory/known-pitfalls.md` §20）

代价函数 `pairCost` 在 O(n·m) 的 DP 内层重新扫描文本：每格重跑正则切分词数/字数，
每格 `Regex.findAll(英文片段)` + `zh.lowercase()` 拷贝**整章**文本 + 对每个锚点做一次 `contains`。
章节级 DP 的片段就是整章正文，因此最贵。修法：进 DP 前把每个片段预计算成
`Span(words, chars, anchors, latin)`，章节特征由段落特征聚合（不再拼接整章文本），
锚点命中改为在中文侧「拉丁/数字词集合」里哈希查表；dp 只保留最近三行 + 每格 1 字节走法矩阵。

| 指标 | 优化前 | 优化后 |
|---|---|---|
| PC 整本对齐 | 29,033 ms | **454 ms（64×）** |
| 句对数 | 12,697 | 12,696（锚点由子串匹配改为词表匹配的预期差异） |
| 平均置信度 | 0.79 | 0.79（不变） |
| 真机整本 attach | >5 分钟（约 6 分钟算完） | **约 10 秒**（用户真机复测确认） |

护栏：新增 `TranslationAlignerBenchmarkTest` —— 用 `artifacts/alignment-package/` 里的魔戒中英 EPUB
跑整本，断言句对 > 10,000 且耗时 < 3s（素材缺失自动跳过）。它的作用是下次有人再把文本扫描塞回
DP 内层时立刻失败。`testDebugUnitTest` **311 个通过**；对齐完成提示改为带「耗时 N 秒」。

## 2026-08-21 F-128 覆盖率兜底 + 交互整理（用户真机确认有效）

**点词命中率**（从查询侧量：模拟在整本魔戒每个英文段落的首句点词，共 4,069 个段落）

| | 命中 | 未命中 | 句级 | 段级 |
|---|---|---|---|---|
| 兜底前 | 3554/4069 = **87.3%** | 515 | 3435 | 119 |
| 兜底后 | 3955/4069 = **97.2%** | **114** | 3435 | 520 |

主因不是「DP 跳过段落」（真实只跳了 11 段），而是**2:1 合并**：合并后 `enParagraph` 存的是两段拼接文本，
用户点其中一段时段落文本对不上；标题、诗行这类不以句末标点结尾的段落连句子都切不出来，5 级降级全落空。

> ⚠️ **测量教训**：最初用「不同 `enParagraph` 文本数」当覆盖率，把 2:1 合并误算成漏掉，
> 得出「13.3% 段落被跳过」的**错误结论**。覆盖率必须从查询侧量（`TranslationMemoryIndex.lookup`），
> 基准测试现在输出 `[lookup]` 命中率。

两条兜底：① 合并段落对的每个成分段落补一条段级条目（置信度 ×0.85）；② DP 跳过的段落挂到下标最近的
已对齐段落对应的中文段落（基准 ×0.55 ÷ 距离），低于查询门槛 0.30 直接不落盘。
剩余 114 个未命中的大头是**未参与对齐的 7 个结构性章节**（封面/目录/附录，合计 67 段）。

**交互整理**：整句翻译按钮改为只在真配好提供方时渲染（原来是置灰按钮 + 一行「未配置」提示，
每次点词都是噪音）；命中译本时在同一位置给「整句对照 / 收起对照」开关，展开显示配对到的英文原句
与译文段落；配了译本但本句没对上时显示「本句未对上译本（所在段落或章节没有参与对齐）」。

**验证**：`testDebugUnitTest` **312 个通过**、`lintDebug` 无告警；
**用户在真机（PKB110 / Android 16）确认以上改动有效，整本书 attach 耗时约 10 秒**。

**仍未做**：手工 SAF 选择器交互的自动化、对齐 DP 在手机上的内存峰值、真实图书上词级高亮的主观准确度。

## 2026-08-21 F-110/F-111 底栏遮挡正文最后一行修复（真机验证）

- 现象：分页模式每章最后一行被半透明底栏盖住（PKB110 / Android 16，targetSdk 35 强制 edge-to-edge）。
- 根因（两层）：① 底栏含导航栏 inset，实测 78px > 写死的 70px CSS 预留；② 绝对定位滚动容器实际渲染位置比 style.top 整体下移约 32px（known-pitfalls #8 的容器位移，此前只修了高亮定位未修容器），正文实际底缘因此低于底栏上沿。
- 修复：① Compose 用 onSizeChanged 实测顶/底栏高度，经 ReaderController.applyChromeInsets → JS lrSetChromeInsets 动态注入替换写死的 104px/174px（bootstrap 带初值首帧即正确，值变化才重排且保页）；② updateMetrics 设完样式后实测 getBoundingClientRect() 自校准，把超出「视口高 − 底栏预留」的部分从列高里扣掉。
- 验证：testDebugUnitTest 通过（316 个，新增 4 条 chrome insets/自校准防回归）；真机并存包实测：最后一行完整显示在栏上方、无半透明残影，页数随列高变化正确重排（7/13 → 7/14）。lintDebug / assembleDebug 通过。

## 2026-08-21 M5 系统引擎多角色（M5a/M5b/M5c 代码完成，真机矩阵待做）

按 `PLAN-MULTI-VOICE.md` §13 实施系统引擎多角色（用户标注音色库 + Google TTS 优先）：

- **M5a 数据层**：`SystemVoiceStore`（标注 + 音色快照按引擎包名隔离存储 + 当前引擎指针取自 `Settings.Secure.TTS_DEFAULT_SYNTH`）；`VoiceLibraryLoader` SYSTEM 分支（探测落盘快照、空探测不覆盖旧缓存、engine key = `system:<包名>` 触发换引擎重分配）；门控 D2'——`engineSupportsMultiVoice(settings, systemUsableVoices)` 默认参数保证云引擎路径零改动，SYSTEM 需 ≥2 个「已标注+启用+性别已知」音色。
- **M5b 标注 UI**：`MultiVoiceSection` SYSTEM 条件置灰 + 「标注系统音色」对话框（快照优先/空则探测、性别三态胶囊、启用开关、逐行试听、保存后即时重算门控）；`VoiceAudition` 新增 SYSTEM 直播路径（专用 TextToSpeech 实例、与播放侧一致的 setVoice→setLanguage 回退链）。验收修正两处：`onFinished` 统一走字段注册（原系统路径未存字段，stop() 的通知拿不到回调）、删除与 `TtsLanguage.of` 重复的 `isHan`。
- **M5c 引擎引导**：`SystemTtsEngines.guideState` 三态纯函数（当前即 Google TTS=推荐 / 已装未启用=提示+跳系统 TTS 设置 / 未安装=纯文案）；实施时核实 `Settings.ACTION_TTS_SETTINGS` 不在公开 SDK（javap 查证 android.jar），改用字面值 `"com.android.settings.TTS_SETTINGS"`；绝不自动切换引擎。

验证情况：

- `testDebugUnitTest`：**325 个全部通过**（新增 9 个：`SystemVoiceStoreTest` 6——round-trip/按引擎隔离/坏 JSON/locale 归一化含 cmn·chn·usa/merge 过滤/指针跟随；`MultiVoiceSupportTest` +1——D2' 门控两态与云引擎不受默认参数影响；`SystemTtsEnginesTest` 2——三态映射/探测容错）。
- `assembleDebug` 与 `-PverifyBuild` 并存包构建通过。

**仍未验证（真机矩阵，需设备连接后执行；安装一律先 `-PverifyBuild`，见上方事故记录）**：

1. Google TTS 基准：标注 ≥2 音色 → 开关解灰 → 多角色逐句切换生效、角色声可辨、无串声；
2. 国产 ROM 自带引擎：回退链不静音、`getVoices()` 延迟重试路径、试听能听到 setVoice 是否被服从；
3. ColorOS（`chn` 非标码）：中文音色归一化为 zh 并参与分配；
4. M5b 遗留交互：标注对话框关闭时试听仍在播的观感；从系统 TTS 设置返回后引导态是否刷新（`remember` 缓存，可能需重进面板）。

**真机首测反馈修复（2026-08-21 晚）**：点「标注系统音色」无反应——`MultiVoiceSection` 里对话框渲染点在 `if (!engineSupported || !multiVoiceEnabled) return` 之后，SYSTEM 未标注时必然提前返回，对话框永不组合。已把 `SystemVoiceAnnotateDialog` 块移到提前 return 之前。同轮发现另一会话并发改仓库致 strings.xml 丢 14 个 key（从 APK 资源恢复）；修复后 325 单测通过、并存包重新构建。

## 2026-08-23 legacy 线修复移植（真机验收，含一次回归发现与修复）

背景：另一开发线（`legacy/remote-main-20260820` 分支）的 26 项缺陷修复移植到本线（bug收集/ 文档 31 篇、服务端/工作室并发安全 9 处、听书链路 11 项、AI/翻译 4 项）。真机验收于 PKB110 / Android 16。

- **仪器测试**：`connectedDebugAndroidTest` 39 项 0 失败 1 跳过。两处环境适配：SystemTtsVoices 裸探测按 `isNetworkConnectionRequired` 对齐 loader 的离线过滤（设备上报 network 音色时不再误报）；ReviewReminder 在 OEM 无法授予通知权限时 assumeTrue 跳过（`pm grant` 成功但权限检查仍 false，legacy 线同款已知问题）。
- **MediaSession（BUG-003）A/B 实证**：main 线播放中 `active=false`（外部媒体控制不可达）；移植线 `ensureForeground` 激活后 `active=true`，`cmd media_session dispatch pause/play` 均正确驱动应用（状态翻转 + 应用内按钮同步）。
- **回归发现（移植③分页跟随）**：移植的 `followRangeIntoView` 分页分支在章末触发翻页 → `onPageChanged` → 阅读器位置回报（`reportTtsPositionDelayed`，350ms）把引擎拽回页面首个可见块——跨界瞬间引擎 sentenceIndex 越界，两次回报依次拽到 block 4 → block 0，第 1 章从头重播死循环，随后假 PLAYING 卡死。WebView 桥接插桩（CDP 挂 `lrHighlightBlock`/`lrClearHighlight`/`lrFirstVisibleBlock` 记录器）捕获完整轨迹；main 线对照同书同流程 ~110s 干净跨章，确认移植回归。
- **回归修复**：`followRangeIntoView` 只保留滚动模式分支，分页模式维持移植前行为（高亮可画在屏外，等下一轮用桥接标记方案再做分页跟随）；修复后真机 122s 干净跨 ch1→ch2→ch3，快速连按下一句×3 精确推进 3 句无重复（BUG-006），章内连按上一句逐句回退正常，「停止听书」后会话释放 10s+ 无复活（BUG-009），全程无崩溃；`testDebugUnitTest` 344 项通过。
- 验证截图：`验证截图/legacy移植-启动冒烟.png`、`legacy移植-播放中1/2.png`（本地工件不入库）。
- 设备通知栏被 OEM 应用级 importance=NONE 压制（与 ReviewReminder 同源），不影响媒体会话与播放；后续人工验收通知栏时可复核。

## 2026-08-23 UI 全面分析：10 项问题定位，5 项修复 + 真机验证 2 项定性

- 背景：子代理对全部 Compose UI（17 文件约 8,870 行）只读分析，报告存 `artifacts/ui-analysis-2026-08-23.md`（本地工件不入库）。10 项问题中 5 项代码证据明确当日修复，2 项真机验证定性，1 项验证后不成立，其余为欠账（文案迁移、死状态、冷启动待验）。
- 修复（`feat/port-legacy-fixes` → main，`testDebugUnitTest` 全绿）：① 收藏/移出生词补全局 Snackbar 反馈（`notice_word_saved/removed`，zh+en）；② AI 查词失败不再静默，查词面板内联降级提示（`reader_ai_lookup_failed`）；③ 复习卡组/查词结果等旋转屏关键状态改 `rememberSaveable`（自定义 `WordLookupSaver`）；④ 听书设置反馈改显式 `StatusTone` 枚举，删除 `startsWith("已获取")` 文案嗅探，「已保存」误红改绿（新增 `SettingsStatusTest` 3 用例）；⑤ 书架两导入入口统一 `IMPORT_MIME_TYPES` 常量（空态入口补 `application/zip`）。
- 真机验证（PKB110 / Android 16，时为覆盖安装前的 v1.4.0 旧包；证据 `artifacts/device-verify-20260823/`）：
  - 弹层下反馈不可见**坐实**：听书设置弹层内保存设置、查词弹层内收藏生词，0.5/1.2/2.2s 连拍均无任何 Snackbar 可见（无论被遮还是未弹，用户实际得不到反馈）；后者同时复现「收藏无反馈」缺陷。
  - 夜间写死颜色**不成立**（默认主题下）：系统夜间模式 WebView 阅读页维持自带浅米色主题，`#8D5535` 生词下划线与链接色对比清晰；深色阅读主题组合未测。
- 未验：夜间冷启动闪白（录屏流程未执行）；含上述修复的新包真机回归（进行中）。

## 2026-08-24 UI 修复真机人工回归（用户逐项反馈）

新包（main@3ce1055 含 5 项修复）装真机后用户手动逐项验证，结论：

- **#2 弹层遮挡：成立（人工确认）**。听书设置弹层开着时保存，完全看不到提示条；而关弹层后生词本「导出」提示条正常出现——反馈通道本身正常，是 ModalBottomSheet 层级盖住 Snackbar。修复方向：弹层内联反馈（沿用 SettingsStatus 行内状态模式）。
- **#9 冷启动闪白：成立**。系统深色模式下杀进程冷启动，头一秒先白屏再变深色。修复方向：values-night 启动主题。
- **#5 夜间标记对比度：重新定性为成立**。夜间阅读主题（#171717 底）下生词棕红下划线/收藏高亮「费劲」辨认（此前视觉模型初判「可辨」被人工推翻）。修复方向：标记色随 ReaderTheme 深色变体提亮。
- **新发现：Snackbar 提示条米色底对比弱**。成功提示条颜色与背景同为米色（既非预期绿也非红），可见性差。
- **#3 AI 失败提示：真机「毫无动静」，与预期不符**，需代码调查失败路径覆盖（可能用户场景走的分支未被 runCatching 覆盖）。
- 主题切换为**立即生效**（此前怀疑的 CSS 注入延迟不成立，关闭该疑点）。
- #10 zip 导入、#6 旋转屏：本轮人工未测（前者无现成文件；后者沿用回归代理截图证据，待复核）。

## 2026-08-24 UI 修复批次真机回归（F1~F5 + aiExplanation，main@27becad 装机验证）

- **F1 听书设置保存反馈 PASS**：弹层内点「保存」→ 行内「已保存，即将关闭…」→ 约 800ms 后面板自动关闭（连拍时间轴完整）。查词面板收藏/移出 PASS：行内「已收藏『clear』」/「已移出生词『clear』」（uiautomator 文本确证，非视觉判读）。
- **F2 夜间冷启动 PASS**：系统深色 + force-stop 冷启动，am start 后零延迟连拍三帧（首帧/1.2s/1.8s）全部深色或中灰（最亮带 #58554C 系窗口过渡混色），全程无白色帧，旧版白闪消失。
- **F3 夜间标记提亮 PASS（像素级）**：夜间主题即时生效（外壳与 WebView 同帧变 #171717 系，无延迟）；下划线 44,616 像素精确等于 #C98A5E（进入全屏 top5 颜色），旧色 #8D5535 仅剩 7,129 个抗锯齿边缘像素。
- **F4 Snackbar 配色**：本轮未触发中性条场景；语义色对比由 `snackbarColorsFor` 纯函数单测全组合覆盖（≥4.9:1），留日常使用观察。
- **F5 AI 降级提示**：真机未实测（需改用户 AI 配置），根因修复附 4 条单测；待日常配错 Key 时观察行内「已降级为本地轻量语境」提示。
- **aiExplanation 复习卡区块**：条件渲染正确（无 AI 数据的词不渲染）；当前生词库无含 AI 解释的记录，带数据的视觉演示待有 AI 数据后复核。
- 过程坑（已写入记忆库）：主题选项**色块预览可点、文字标签不可点**；ColorOS 对 shell 的 `screenrecord` 全目录 Permission denied（含 /data/local/tmp），冷启动验证改用连拍+分带亮度法。
- 设备还原：阅读主题回「纸张」、系统夜间回 auto、自动旋转恢复、测试用生词 clear 已移除。

## 2026-08-26 MiMo 云 TTS 接入（mimo-v2.5-tts / voicedesign / voiceclone，多角色服务）

- 新增引擎 `MiMo`（`CloudTtsSettings`：mimoApiKey 加密持久化、mimoModel/zh/en 音色、风格指令；`TtsEngineMode.MIMO`）。
- 三态音色 id：裸预置 id（9 个官方预置，zh/en × 男女）/ `mimo-design:<key>` / `mimo-clone:<key>`，对多角色管线透明——音色库、角色分配、试听、缓存播放全部复用既有链路，只有 `MiMoTtsBackend.synthesize` 按前缀分派模型。
- `MiMoTtsBackend`：OpenAI 兼容 `POST /v1/chat/completions`，鉴权头 `api-key`，目标文本放 `role:assistant`，风格指令/设计描述放 `role:user`（voicedesign 必填、克隆样本 `data:audio/mpeg;base64,…` 放 `audio.voice`，≤10 MB），非流式 wav 经 `choices[0].message.audio.data` base64 返回（文档规定）。
- `MiMoVoiceStore`：SharedPreferences JSON 登记 + 样本落 `filesDir/mimo-voices/`；`MiMoVoiceCatalog.library(context)` 把预置+自定义并成音色库喂 `VoiceLibraryLoader`。
- UI：听书设置新增 MIMO 引擎行 + 配置区（API Key/中英音色/风格指令/「合成测试」）；多角色面板新增「MiMo 专属音色」区块（设计对话框：名称/语言/性别/描述；复刻对话框：名称/语言/性别/系统文档选样本）；列表行可试听、可删除，全部走弹层内联 `SettingsStatus` 反馈。
- 文案：zh + en 两册同步新增 45 个 key（模块前缀 `tts_mimo_` / `multivoice_mimo_`）。
- 单测：`testDebugUnitTest` 全绿（369 个）。新增 `MiMoTtsBackendTest`（buildRequestBody 预置/设计/克隆三态、无描述拒绝、decodeAudioData、voiceFor 中英路由、modelForVoice）；`CloudTtsSettingsTest` 增 MIMO 字段 roundtrip（API key 属 Keystore 加密，按项目惯例不回读）与 isConfigured；`MultiVoiceSupportTest` 增 MIMO 多角色支持断言。

## 2026-08-26 MiMo 多角色服务（接入 + 真机修复批次）

- 接入：新增 MiMo 引擎（`TtsEngineMode.MIMO` / `MiMoTtsBackend` / `MiMoVoice`），`chat/completions`+`api-key` 鉴权、目标文本走 assistant 消息、非流式 wav 经 base64 返回；三态音色 id（预置裸 id / `mimo-design:<key>` / `mimo-clone:<key>`）对多角色管线透明。
- 真机修复（PKB110/Android 16）：① 引擎区「朗读引擎」由每行两个 `fillMaxWidth` 项挤成竖排 → 改单列全宽；② 中/英音色下拉 `PresetField` 的 `(id, 显示名)` 顺序写反 → 修正；③ 中文预置音色 Voice ID 应为中文名（冰糖/茉莉/苏打/白桦），拼音/Bingtang 报 Unknown voice → 改官方中文 id，`mimo_default` 保留；④ 预置音色补官方风格/年龄元数据（分配器风格、年龄评分由中性分变为真实分）。
- 角色管理（方向 B/C）：角色行可编辑（性别/年龄组/重要程度/风格词）+ 删除（清 glossary 条目、音色映射与锁定）+ 别名管理；无书明确提示；底层新增 `BookVoiceMap.removeCharacter`/`VoiceMapRepository.removeCharacter`/`BookGlossaryRepository.updateCharacter`。
- 角色管理入口（方向 A）：书架书卡片新增「角色」入口（术语表右侧），「移除(删书)」挪到「译本」左侧；点「角色」经 `ModalBottomSheet` 复用 `MultiVoiceSection` 打开该书角色管理。
- 单测：`testDebugUnitTest` 全绿（369）。新增 `MiMoTtsBackendTest`；扩展 `CloudTtsSettingsTest`（MIMO roundtrip/isConfigured）与 `MultiVoiceSupportTest`（MIMO 多角色支持）。
- 文案：zh+en 两册同步新增 MiMo 引擎、MiMo 专属音色、角色管理、书卡片「角色」等 key。
- 待办：MiMo 合成/音色试听的真机音频链路（规则层 vs LLM 归属、design/clone 合成）已由用户真机人工验证通过；分配的进一步校准与 `MiMoVoiceStoreTest`（Robolectric）留作后续。

## 2026-08-28 页顶大空白 + 底栏遮字根因修复（用户真机确认）

- 现象：部分书籍分页模式下页顶出现大空白（正文整体下移），且末行被不透明底栏「遮挡」；时有时无（与书相关）。
- 根因：书内 EPUB CSS 的裸 `div` 规则（如 `margin-top: 2em`）泄漏到注入的 `#lr-scroller`/`#lingua-reader-content`/`#lr-spacer` 上。绝对定位 `top` 定位 margin box，多出的 margin 让 scroller 比设置值下移（known-pitfalls #8 实测「每层 +32px」= 2em×16px 的谜底）。TTS overlay 早有同款 reset，三个布局骨架漏了；F-110/F-111 的 overshoot 自校准只补 scroller 自身 margin，补不到 content 的，故顶部空白残留、末行被裁在底栏上沿。
- 修复：`ReaderScripts.installStyle` 给三骨架加 `margin/padding/border: 0 !important` reset；`#lr-scroller` 的 `padding-left: 28px` 同改 `!important`（`!important` 不看顺序与特异性，否则被 reset 清零 → 正文贴左，中途真机暴露此回归当日修复）。overshoot 自校准保留作保险。
- 验证：`testDebugUnitTest` 全绿；覆盖安装真机（PKB110 / Android 16）用户确认页顶空白、底栏遮字、左侧边距三项均正常。

## 2026-08-28 AI 整本翻译生成译本对照（第一期，JVM 层验证完毕、真机待验）

- 功能：对没有中文译本的英文书，书架「加译本 → AI 生成译本」用 DeepSeek 逐章整本翻译（术语表注入 + 上一批译文衔接 + 批后自检重试），译文按段落 1:1 写成 XHTML 到 `files/translations/ai-<bookId>/`，复用 `attachGenerated` 对齐落盘；点词对照、词级高亮全部走既有离线管线。
- 检查点：每批成功即原子写 `files/ai/ai-translations/<bookId>/<chapter>-<batch>.json`（含源文本 SHA-256 指纹），取消/断网/进程被杀只损失当前一批，重进续跑；指纹不符（书变了）自动作废重翻。
- 质量设施：术语表缺失时先自动生成语境档案并停下让用户审阅；批后自检 = 段数完整 + 数字锚点保留 + 「保留原文」术语存活 + 长度比区间，失败带原因重试一次。
- HTTP 层：`translateSegments` 显式 `max_tokens=8192`（原请求体未设，长批会被服务端默认值截断）、读超时 300s（原 60s 对分钟级长生成偏紧）。
- 验证：`testDebugUnitTest` 全绿（395，新增 26：AiBookTranslatorTest 16 + AiTranslationRepositoryTest 7，含检查点续跑/指纹失效/带原因重试/端到端 attachGenerated 对齐命中）；CI push 兜底。
- 待真机（需真实 Key，见下）：整本翻译进度/取消/续跑、生成后点词命中与词级高亮主观质量、与人工译本对齐质量对比。真机装包按老规矩 `-PverifyBuild` 并存包。
- 已知边界：进度粒度是批次百分比（章内无细分）；两阶段润色、段级定点重翻、few-shot 风格范例留第二期；错误熔断/速率限制沿用 DeepSeek 既有语义（无新增）。

## 2026-08-28 「获取可用模型」探测端点模型列表（复刻 dsh，JVM 层验证完毕、真机待验）

- 功能：AI 中心 → 翻译设置 DeepSeek 区块模型字段下新增「获取可用模型」——复刻 DeepSeek Harness（`C:\work\deepseek-harness`，`packages/llm/llm-pi-ai/src/discovery.ts` + Models 设置页）的同名能力：用表单**当前草稿**（未保存的接口地址 + 未存储的 Key）`GET {baseUrl}/models` 探测 OpenAI 兼容端点，弹可搜索选择器点选填入模型字段；只填字段不自动保存，保存仍走原按钮。
- 实现：新增 `ai/ModelDiscovery.kt`——`listingUrl` 去尾斜杠按前缀拼 `/models`（网关路径前缀不丢段）；Key 允许为空（匿名探测 Ollama/LM Studio 类本地网关），非 ASCII/含换行的 Key 本地预检拦下（防 HttpURLConnection 抛看不出原因的 IOException）；响应按实际字节 4MB 有界读取；401/403 报错附「请检查 API Key」；解析按 dsh 宽容规则（`data` 数组缺失报「手动填写」提示、无 id 行跳过、`display_name`/`context_length`/`max_tokens` 别名逐候选取值、容量只收正整数、id 去重保首）。UI 复用「测试连接」的行内瞬态模式与 `VoicePickerDialog` 结构（搜索 + 限高 360dp LazyColumn + 点行即选高亮）。
- 文案：zh + en 两册同步新增 9 个 key（`aidrawer_fetch_models_*` × 6 / `aidrawer_model_picker_*` × 3）。
- 验证：`testDebugUnitTest` 全绿（403，新增 8：`ModelDiscoveryTest`——正常解析/别名字段/坏行跳过/非法容量/data 缺失报错/重复 id/URL 拼接/Key 校验三分支）；`assembleDebug` 通过；CI push 兜底。
- 待真机（或模拟器）：对话框长列表滚动与搜索、点选回填、空 Key 探测本地网关、错误路径（坏地址/坏 Key）行内提示。真实联网探测仅由用户点按钮触发，且复用既有 AI 出网边界（`enabled` + Key，本地网关匿名探测是 dsh 同款设计）。

## 2026-08-29 AI 整本翻译真机验收（PKB110 / Android 16，用户操作通过）

- 环境：`-PverifyBuild` 并存包 `com.linguareader.app.verify`（1.4.0-verify），测试书为 3 章英文样本（`artifacts/ai-translation-sample.txt`，含专名 Tom/London 与数字锚点 1926/7/12 等）。
- 链路：用户真机完整走通「导入 TXT → AI 中心配 Key → 加译本 → AI 生成译本 → 开始生成」，全程无障碍；**用户确认验收通过**。
- 设备侧产物核实（`run-as` 检查）：`files/translations/ai-<bookId>/` 生成、`files/translation-memory/<bookId>.json` 落盘、逐批检查点 `files/ai/ai-translations/<bookId>/{0,1,2}-0.json` 与章结构一一对应。
- 对齐质量：档案 35 个句对 **100% 句级命中**（0 段落兜底）；样例「Tom Parker stepped off the train at London Station in 1926.」→「汤姆·帕克于1926年在伦敦车站下了火车。」——数字锚点保留、专名译名一致。档案副本存 `artifacts/ai-translation-memory-device.json`。
- 截图：`验证截图/AI生成译本-验收-阅读页.png`。
- 本日安装过程踩坑记录：① `-PverifyBuild` 构建产物曾被**并行会话的普通 `assembleDebug` 覆盖同一输出文件**（app-debug.apk 装成主包），构建后必须 `aapt dump badging` 验包名再装；② ColorOS 对 adb 重装静默拦截（报 Success 但包未安装），需开发者选项开启「USB 安装」类放行；③ `pm clear` 对应用目录报 SecurityException（ColorOS），重置 verify 包用卸载重装。

## 2026-08-29 多服务商接入：URL + API Key + 协议（复刻 dsh 自定义服务商，JVM 层验证完毕、真机待验）

- 功能：AI 中心「翻译」Tab 的 DeepSeek 专属区块升级为通用「联网语境翻译」——可添加多个模型服务商（名称 + 接口地址 + API Key + 协议 + 模型），单选切换生效者，可编辑可删除（确认弹窗）。协议三选：OpenAI 兼容（原实现）/ Anthropic（x-api-key + /v1/messages + system 顶层 + max_tokens 必填）/ Gemini（x-goog-api-key + :generateContent + system_instruction + 剥 models/ 前缀）。旧 DeepSeek 配置首次加载自动迁移为首个服务商。
- 交互：编辑卡内「获取可用模型」（仅 OpenAI 兼容协议——Anthropic/Gemini 官方无通用列表接口，与 dsh 同款限制，提示手填）与「测试连接」都作用于**未保存的草稿**；点行切换生效者；全部草稿态由底部「保存」一次落盘。
- 架构：业务与线路分离——`JsonChatTranslator` 基类承载全部 prompt/解析/合并/重试编排，`OpenAiCompatTranslator`（原 `DeepSeekTranslator` 改名，id 保留 "deepseek"）、`AnthropicCompatTranslator`、`GeminiCompatTranslator` 只实现各自线路与 JSON 模式重试（openai 的 response_format 降级、gemini 的 responseMimeType 降级、anthropic 仅解析失败重问）；`AiTranslators.forSettings/forProvider` 按协议分派，接替 5 处硬编码构造；翻译器构造参数改为显式连接四元组（baseUrl/apiKey/model/displayName），线路层不再依赖 AiSettings，草稿探测因此可行。
- 兼容桥：`AiSettings.providers/activeProviderId` 为新字段，旧 apiKey/baseUrl/model 语义变为「生效服务商镜像值」（UI 保存与 store.save 都走 `withActiveMirrored()`）——所有只认旧字段的读者（AppViewModel 各 gate、BookContextRepository、SpeakerTagRepository、MultiVoiceSupport.taggingReady、降级安装）零改动；`remoteReady` 判定不变；`BookContextProfile.source` 沿用 "deepseek" 标签（状态播种依赖）。
- 存储：`providers_v1`（JSON 数组，Key 逐个 CloudKeyStore 加密）+ `active_provider_id`；load 时旧单插槽合成 default 服务商（内存态，下次 save 落盘）；activeProviderId 失效回退首个。
- 文案：zh+en 同步 +21/−3（`aidrawer_remote_*`、`aidrawer_provider_*`、`aidrawer_protocol_*`；删除 DeepSeek 专属 title/on_hint/off_hint）。
- 单测：`testDebugUnitTest` 全绿（422，+19：OpenAi 5 / Anthropic 4 / Gemini 5 / AiTranslators 分派 4 / Store 迁移镜像 4——Robolectric 下 Keystore 不可用，按项目惯例不回读 Key 值）；`assembleDebug` 通过；CI push 兜底。
- 待真机（需真实 Key 或本地网关）：三协议测试连接与真实请求（OpenAI 兼容 / Anthropic / Gemini 各一）、服务商增删切换与生效服务商标签（点词来源名）、旧配置迁移显示、模型拉取长列表滚动搜索；隐私边界未变——出网仍由「总开关 + enabled + Key」三重门控。

## 2026-08-29 删除 Azure 整句翻译（用户真机验收多服务商后指示）

- 范围：仅 Azure **Translator** 整句翻译——删除 `AzureSentenceTranslator` 及其测试、`AiSettings` 的 azureTranslationEnabled/azureKey/azureRegion/azureEndpoint 四字段与 `azureReady`、store 的 azure_* 持久化、`SentenceTranslatorFactory` 的 azureReady 分支、AI 中心翻译 Tab 的 Azure 区块 UI 与 `aidrawer_azure_*` 四个文案 key（zh/en 同步）。**Azure TTS（听书语音引擎）不受影响**，tts 包全部原样。
- 行为变化：整句翻译现一律走当前生效的模型服务商（OpenAI 兼容/Anthropic/Gemini，术语表 prompt 注入与来源标签机制不变）；未配置服务商时报错文案改为「未启用整句翻译（先在 AI 中心配置并保存一个服务商）」。旧版本存的 azure_* prefs 键成为无害残留（不再读写）。
- 验证：`testDebugUnitTest` 全绿（418，−4：AzureSentenceTranslatorTest 3 + 工厂 Azure 分支用例重写）；`assembleDebug` 通过；覆盖安装 PKB110 真机（同 versionCode 1.4.0 覆盖安装成功）并启动，用户人工验收。

## 2026-08-29 官方预设服务商：DeepSeek v4-flash 只需填 Key（用户真机验收通过后追加）

- 功能：服务商列表新增「官方预设（只需填 API Key）」区块——点「DeepSeek 官方」打开预填好的编辑卡（baseUrl=https://api.deepseek.com、协议 OpenAI 兼容、模型 deepseek-v4-flash，目录另备 deepseek-v4-pro），用户只需补 Key 保存。复刻 dsh 的 catalog route 语义：预设是默认值不是锁定，字段仍可改。
- 实现：`AiProviderPresets` 目录（`AiProviderSettings.kt`，UI 层持有 nameRes 便于 i18n）+ 列表区块（同端点+协议的预设已加过则自动隐藏，避免重复）+ `AiProviderPresetsTest`。
- 验证：`testDebugUnitTest` 全绿（420，+2）；`assembleDebug` 通过；覆盖安装 PKB110 并启动，真机交互待用户确认（预设出现/预填正确/保存后成为服务商）。

## 2026-08-29 修复服务商编辑卡协议行大间隔（FlowRow 替换普通 Row）

- 现象：编辑卡「协议」chips 与模型字段之间出现 ~228dp 的隐形大间隔，且 Gemini chip 从界面消失，卡片被撑高需要滚动。
- 根因：三个 `FilterChip` 放在普通 `Row` 里——前两颗（OpenAI 兼容 + Anthropic（Claude））占满对话框宽度后，第三颗被挤进 ~12dp 剩余宽度、标签纵向堆叠撑出隐形高块（uiautomator 树里 Gemini 完全缺席佐证）。
- 修复：chips 容器换 `FlowRow`（水平 6dp/垂直 4dp spacedBy），放不下的 chip 整颗换行；去掉逐 chip 的 end padding。
- 验证：真机（PKB110）uiautomator 边界数据 + 截图确认三 chip 两行排布、模型字段紧随其后、卡片一屏放下；`testDebugUnitTest` 全绿、`assembleDebug`/覆盖安装通过。

## 2026-08-29 AI 整本翻译第二期（精译/句级重翻/风格说明）JVM 验证完毕，真机待验

- 功能：①「精译」模式（每批初翻→对照原文修订一遍，耗时费用×2，确认框选择并记住上次，检查点带 mode 字段）；②句级定点重翻（查词面板「重译此句」→反馈可留空→单请求替换档案该句对 zs + 原子写 + 索引整体重建；仅句级命中开放，进行态保留旧译文，失败不动档案）；③风格说明（确认框可选文本，书级 style.json，随初翻/精修/重翻 prompt 注入）。
- 实现：`TranslationLookupResult` 新增 `pairIndex`/`englishParagraph`（重翻定位与上下文）；`Index.build()` 改 `withIndex()` 分组；替换用 `copy(zhSentence=…)`，共享段落 String 契约与 v2 格式不变。
- 验证：`testDebugUnitTest` 全绿（400）。新增测试：润色 prompt 配对/风格注入、重翻 prompt 与自检（空回复/丢锚点/术语被译掉/长度比）、精译两遍流程与检查点 mode、style.json 往返、重翻端到端（替换后查询返回新译文+新词级对齐、自检失败档案不动）、pairIndex 全局下标正确性。
- **提交混杂记录**：`1e7239c` 因并行会话的 `git rm`（Azure/火山 TTS 删除）已在暂存区而被一并带入——该提交 = 本特性 + 对方 TTS 引擎移除的删除半场；推送前全量测试在此状态通过。并行 TTS 重构的其余文件仍在工作区由对方会话收尾。
- 待真机：精译模式小样本、重翻（无反馈/带反馈/失败保旧）、风格说明主观效果；装前 aapt 验包名。

## 2026-08-29 TTS 引擎收敛：移除 Piper/sherpa、Azure、火山（JVM 验证完毕，真机待验）

- 结论：`TtsEngineMode` 由 6 值收敛为 3 值（SYSTEM / OPENAI_COMPAT / MIMO），离线优先与多角色能力不受影响（系统 TTS 兜底、自建/MiMo 走同一 `CloudTtsSynthesizer` 多角色管线）。
- 删除：Piper 系 6 文件 + sherpa-onnx 1.13.5 依赖 + `assets/sherpa/` 内置模型（374 个文件）+ `scripts/download_tts_models.ps1`；Azure 3 文件 + `CloudVoiceStore`（Azure 专属缓存）；火山 1 文件；对应 5 个 JVM 测试 + 1 个仪器测试。
- 共享面清理：工厂/试听/多角色门控/音色库摘除三分支；听书设置面板删两处 UI 区块与火山预设；`CloudTtsSettings` 摘除 region/apiKey/volc*/piper*/multilingual 字段与持久化键；strings 双语各删 48 键（531 对齐）。
- 兼容性：prefs 残留的 PIPER/AZURE/VOLC 模式值经 `runCatching { valueOf }.getOrDefault(SYSTEM)` 自动回落系统 TTS，不会崩；`cloud_tts_voices`/`piper_voice_store` 等旧数据残留无害。
- 保留决策：`VoiceNaming` 的火山/Azure 命名先验（`zh_female_*`、`zh-CN-XxxNeural`）是通用 id 形状解析器，自建服务端音色仍可能命中，故保留。
- 验证方式：并行会话（AI 整本翻译第二期）同时占用工作区且其半成品一度编译不过，故采用 `git worktree` 隔离验证——把本次改动打成 patch 应用到 HEAD 干净副本跑 `testDebugUnitTest`，两次均全绿（Piper 移除后 43b5c2c、Azure/火山移除后 e67afe4）。
- **提交混杂说明**：引擎实现文件与 strings 键删除因并行会话先 `git commit` 而被带入 `1e7239c`（对方已在 3d1d67d 记录）；`43b5c2c` 为 Piper 全量，`e67afe4` 为 Azure/火山剩余引用清理。
- 待真机：系统 / 自建 / MiMo 三引擎各过一遍播放、语速、多角色、试听；确认包体缩减（预期 -100MB 级，native .so + 模型）。

- **2026-08-29 05:46 部署**：第二期构建已覆盖安装到真机两个包（主包 + verify 包，安装前 aapt 验包名，数据/Key/译本均保留，用户确认无异常）。重译入口的 AI 译本限定（`isAiTranslation` 门禁）已含在本次构建内（96f892b）。精译/重翻/风格说明的功能性真机验证待用户日常使用反馈。

## 2026-08-29 开屏恒米白纸色 + 书架自定义背景 + 护眼绿/莫兰迪/纯黑阅读主题（JVM 验证完毕，真机被锁屏阻塞）

- 开屏根因与修复：`values-night/themes.xml` 把启动窗口背景钉在 #171717 且跟随系统深色，而外壳配色跟随阅读主题——「系统深色 + 浅色阅读主题」冷启动即黑屏。修复：**删除 values-night 覆盖**，系统开屏（Android 12+ 默认 splash 背景取 windowBackground）恒为纸色 #F7F3EA；`values/themes.xml` 新增 `Theme.LinguaReader.Dark` 变体，`MainActivity.onCreate` 在 super 之前按 `storedReaderTheme` + 系统夜间模式程序化选用，深色阅读主题用户开屏后窗口仍与外壳一致不闪白。代价（用户知情选择）：深色阅读主题用户会看到纸色开屏→深色界面的一瞬过渡。
- 书架自定义背景：新增 `ShelfAppearance.kt`（预设 青竹/暖沙/豆沙/海盐 + prefs `"shelf_settings"` 持久化 + 背景图导入 `filesDir/shelf_background/background.jpg`，导入时降采样最长边 2048 存 JPEG）与 `ShelfAppearanceSheet.kt`（ModalBottomSheet，受控编辑，导入成败行内提示）；`BookshelfScreen` 顶栏新增调色板图标入口，背景层图片(Crop)/预设渐变 + 纸色蒙版（浓度滑杆 0–0.8，默认 0.35），有自定义背景时 Scaffold/TopAppBar 透明、书卡 0.9 透明度。只做浅色预设：外壳文字颜色由日/夜调色板驱动，深色底无法保证日间外壳可读性。
- 阅读主题：`ReaderTheme` 新增 GREEN(#CCE8CF)/MORANDI(#E2D8D2)/AMOLED(#000000)；`chromeIsDark` 把 AMOLED 与 DARK 一并映射为夜间外壳；选择器按 `entries` 枚举自动纳入（ReaderScreen.kt:1017）；prefs 按枚举 name 存，老数据 `runCatching` 兜底不受影响。strings zh/en 同步 +20 键（reader_theme_* 3 + shelf_appearance/shelf_preset/shelf_background 17）。
- 验证：`testDebugUnitTest` 全绿（新增 `ShelfAppearanceTest` 6 项：默认值/往返/未知预设兜底/图片导入落盘/坏 Uri 失败/重置清文件+清 prefs；`ThemeColorsTest` 扩 AMOLED/GREEN/MORANDI 断言）；`assembleDebug` 通过；已覆盖安装真机。
- **真机待验（设备锁屏，adb 截图全黑，mDreamingLockscreen=true 无法越过）**：①系统深色开/关下冷启动开屏均纸色、深色阅读主题不闪白；②顶栏调色板入口→预设/选图/蒙版/重置全流程（OpenDocument 选图需真机交互）；③三个新主题正文渲染与外壳联动。已备份真机 `reader_preferences.xml`（artifacts 外，会话内 /tmp），未注入修改任何应用数据。
- 设备状态变更说明：验证时执行了 `adb shell cmd uimode night yes`（系统深色），结束时保持该状态——用户「开屏黑」的反馈场景即系统深色，如需改回请手动切浅色。

## 2026-08-29 追加：ColorOS 开屏仍黑的第二轮修复 + 顶栏标题改单行

- 用户反馈冷启动仍黑。录屏/连拍复现受限（screenrecord 在该机 Permission denied；开屏窗口在 adb 冷启动下 <250ms 抓不住），但连拍第 1 帧抓到启动窗口：应用图标背后是黑底且带 ColorOS「此应用内容已隐藏」遮罩——ColorOS 不沿用 windowBackground 作 splash 背景。
- 修复：themes.xml 显式声明 `android:windowSplashScreenBackground`（Light #F7F3EA / Dark #171717，API<31 忽略，无需 v31 限定符）——这是系统开屏背景唯一可靠的显式控制点。
- 顶栏「语境阅读」因动作过多被挤成两行（用户指示删应用名）：TopAppBar 标题改单行计数（「N 本书」/「生词本 N」），调色板入口保留。真机截图确认单行生效。
- 待用户点图标正常冷启动复核开屏颜色；若仍黑，需区分是 ColorOS 启动动画深色垫底（应用无法控制）还是隐私遮罩（仅 adb/快照场景出现）。
- **验收通过（2026-08-29）**：用户点图标冷启动确认开屏为米白纸色、顶栏单行计数满意。合并 main。

## 2026-08-29 自动更新（GitHub Release 检查/下载/引导安装，模拟器验证完毕）

- 功能：`update/` 新包——`UpdatePolicy`（纯逻辑版本比较，tag↔versionName 语义化）、`GitHubReleaseParser`（org.json 解析 `/releases/latest`，无 .apk 资产返回 null）、`GitHubUpdateChecker`（HttpURLConnection，10s/15s 超时，带 GitHub 要求的 UA 头）、`AppUpdateRepository`（检查+下载编排，下载到 `getExternalFilesDir("updates")`，进度回调、可取消、失败/取消均清半截 APK）、`ApkInstaller`（FileProvider + ACTION_VIEW；`canRequestPackageInstalls()` 为 false 时引导去系统「安装未知应用」授权页）、`AppUpdateSettings`（prefs `update_settings`，`auto_check_enabled` **默认 false**，遵守出网默认关闭的隐私边界）。
- UI：书架顶栏新增刷新图标入口（本会话发现新版时红点）；`UpdateSheet`（ModalBottomSheet）：当前版本、自动检查开关、手动检查、状态行（检查中/已是最新/新版+更新说明+下载按钮/下载进度条+取消/已下载+安装/失败+重试）。发现新版且启动自动检查命中时 Snackbar 提示一次。strings zh/en 各 +20 键。
- Manifest：新增 `REQUEST_INSTALL_PACKAGES`（实际授权仍由用户逐应用授予）+ FileProvider（authority 用 `${applicationId}.fileprovider`，verify 并存包天然兼容；`res/xml/file_paths.xml` 暴露 `external-files-path updates/`）。
- 验证（lr_api35 模拟器，Android 16）：`testDebugUnitTest` 全绿（新增 UpdatePolicyTest 6 / GitHubReleaseParserTest 3 / AppUpdateSettingsTest 2）；`assembleDebug` 通过；装包实测：顶栏入口→UpdateSheet 渲染正确（版本 1.5.0 (versionCode 10)、开关默认关）→手动检查真实请求 GitHub API 返回「已是最新」（curl 旁证 API 形状：tag_name=`v1.5.0`、资产 `LinguaReader-v1.5.0.apk`）→开开关→force-stop 重启后开关保持开启、启动静默检查无崩溃无打扰。截图见 `验证截图/验证截图-自动更新-*.png`（5 张）。
- **待验证（需 v1.6.0 发布后才可触发）**：下载进度→下载完成→安装按钮拉起系统安装器→覆盖安装成功；「安装未知应用」未授权时的引导跳转；发现新版时的红点与 Snackbar。发布 v1.6.0 时记得附 `LinguaReader-v1.6.0.apk` 资产（解析器只认 .apk 资产）。
- 已知边界：Release 无 versionCode，靠 versionName 语义比较（仓库惯例两者同步递增，够用）；畸形 tag 一律按「不是新版」处理，宁可漏报。
- 模拟器备注：该 AVD 的 uiautomator dump 会返回陈旧的幽灵窗口（词典/阅读器节点），与真实屏幕不符——自动化验证时以 `screencap` 截图为准，别信 dump。

## 2026-08-29 自动更新真机全链路验收 + 书架顶栏图标化

- **自动更新全链路真机验收通过（PKB110）**：先覆盖安装 1.5.0（versionCode 10）→ GitHub 发 v1.5.1 测试 Release（附 LinguaReader-v1.5.1.apk，56,925,409 字节；注意 `/releases/latest` 不含 prerelease/draft，所以标正式发布）→ 手机端顶栏红点亮起 → 手动检查发现新版 1.5.1 → 应用内下载（下载完成后 `updates/` 文件字节数与资产完全一致）→ 安装 → 重启后「当前版本 1.5.1（versionCode 11）」「已是最新版本」。下载/安装的用户侧操作由用户本人完成，端到端收敛正确。
- 顶栏图标化：书架顶栏动作全部改纯图标（调色板/刷新/生词本切换/AI 中心/导入），删除「生词本 N」「AI 中心」「导入」文字——此前「2 本书」计数被挤成竖排两行。名字去处的兑现：生词本视图标题本来就显示「N 个生词/我的生词」，AI 抽屉头部有自己的标题；新增 `shelf_vocabulary` 文案（zh/en）只作无障碍 contentDescription，视觉零文字。`shelf_tab_words`（带计数）不再被顶栏使用，key 保留。真机确认：书架视图单行计数 + 5 图标，生词本视图 3 图标且次级页自带完整操作区（复习/导出/设置）。
- 验证：`testDebugUnitTest` 全绿；改动后覆盖安装真机（同 versionCode 11 覆盖装成功，sideload 同版本号覆盖安装的坑未复现——此前记录的坑主要在跨签名/降级场景）。截图：验证截图/自动更新真机链路-*.png、顶栏图标化-*.png（本地）。
- 发版备注：下次发版记得 versionCode 递增到 12（v1.5.1 已用 11）。

## 2026-09-01 阅读进度两个精确 bug 修复（簇 E：进滑动丢进度 / 回翻跳上一章开头）

- **进滑动模式丢进度跳章首**（`ReaderScripts.kt` `enterScrollMode`）：函数先 `scrollMode = true` 再取 `currentRatio()`，而 `currentRatio()` 第一句就是 `if (scrollMode) return currentScrollRatio()`；此刻布局仍是分页布局（`overflow-y:hidden`，`scrollTop` 恒 0）→ 比例被算成 0 → `syncScroll()` 把 `scrollTop` 设成 0 → 跳回章首。同文件 `exitScrollMode()` 是「先取值后置位」的正确写法，两者不对称即笔误。修法：把取值提到置位之前（`const entryRatio = ...`）。
- **章节回翻跳到上一章开头**（`ReaderScripts.kt` `updateMetrics` + `ReaderScreen.kt` `selectChapter`）：回翻用 `initialPage = Int.MAX_VALUE` 当「最后一页」哨兵，但 JS 把它当普通页码 `clamp(page, 0, pageCount - 1)`；首次测量常跑在字体/图片就绪前，`pageCount` 偏小甚至为 1，页码被拍成 0，而那条专为「测量变准后恢复」写的救援分支判据是 `restoreTarget <= pageCount - 1`，`Int.MAX_VALUE` 永远不满足 → 页码永久停在 0。修法：哨兵显式化（Kotlin `ReaderScripts.LAST_PAGE`，JS 侧翻译成 `restoreTarget = -1`），`updateMetrics` 单独处理该分支（每次重排重新取末页），`lrSetPage` 认同一约定；普通页码的 clamp + 救援路径保持不变。
- **顺带加固** `lrSyncPage`：还原尚未落地时（`restoreTarget < 0` 或 `restoreTarget > page`）不再按「当前渲染页」重新播种 `restoreTarget`，否则恢复过程中改字号/行距/主题会把还原目标抹成临时页，进度永久丢失（与上面第二条同源）。
- **JVM 验证（已跑，全绿）**：`testDebugUnitTest` **428 个用例 0 失败 0 错误**（`ReaderScriptsTest` 31 个，含本次新增 4 个）；`assembleDebug` 通过，`app-debug.apk` 58.4 MB。
  - **工具链位置更正**：本机 JDK/SDK/gradle-home 都在 **`C:\work\toolchain`**（`jdk\jdk-17.0.20.1+1`、`android-sdk`、`gradle-home`），入口是 `C:\work\toolchain\build.ps1`（它设好 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME 后 `cd src` 调 gradlew）；`src/local.properties` 也写的是 `sdk.dir=C:\work\toolchain\android-sdk`。记忆里旧记的 `C:\work\reader\jdk` + 用户级 `~/.gradle/gradle.properties` 已不成立——本次一度据此误判成「本机无 JDK」。注意 gradle-home 在工作区外，agent 沙箱按 workspace-write 跑时 gradlew 会在 wrapper 锁文件上报「拒绝访问」，需放宽文件权限。
- **静态验证（JVM 之外的补充）**：①把 `bootstrap()` 注入的整段 JS 抽出、替换 Kotlin 插值后 `new Function()` 解析通过（含并行会话在同文件的改动）；②按源码逻辑仿真「测量序列 pageCount 1→12→12」：旧实现哨兵轨迹 `[0,0,0]`（章首，即缺陷），新实现 `[0,11,11]`（章末）；普通页码 7 两版同为 `[0,7,7]` 不变；③仿真进滑动比例：旧实现任何起始页都得 0，新实现第 1 页 0.000、第 10 页 0.818、末页 1.000 —— 与「只在非首页复现」的预测一致。
- **新增回归用例**（`ReaderScriptsTest.kt`，4 个）：`enteringScrollModeReadsThePagedRatioBeforeFlippingTheMode`、`lastPageSentinelIsNeverClampedLikeAnOrdinaryPageIndex`、`preferenceSyncDoesNotOverwriteAPendingRestoreTarget`、`ordinaryRestoredPageKeepsTheClampAndRescuePath`。
- **真机验证仍欠着（当前被设备侧阻塞）**：PKB110 插上后只以 MTP 出现（Windows PnP 只见 `OPPO Find X8`，无 Android ADB Interface），`adb devices` 全空——手机端 USB 调试未开，装包未能进行。APK 已就绪（`src/app/build/outputs/apk/debug/app-debug.apk`）。待验清单（WebView 行为单测覆盖不到）：①分页模式翻到第 10 页附近，慢速竖拖进入滑动模式 → 停在原位置而不是章首（第 1 页进入本来就无感，别拿它当判据）；②从第 N 章第 1 页向前翻/慢滑到章首再上翻 → 落在第 N-1 章**最后一页**而不是开头；③回翻落位后改字号 → 仍停在（新分页的）章末；④普通打开续读、章内翻页、滑动模式内跨章前后翻不受影响。

## 2026-09-01 真机全量验收（PKB110 / Android 16 / ColorOS PKB110_16.0.9.400）：簇 E + 生词划线 + 进度互覆盖 + 崩溃兜底

- **设备侧阻塞已解除**：上一条记的「只以 MTP 出现」就是 USB 调试未开。开启开发者选项 + USB 调试 + **ColorOS 的「USB 安装」**（不开则 `adb install` 被系统拒），首次连接为 `unauthorized`，`adb kill-server && adb start-server` 重新握手后在机上勾「一律允许」即转为 `device`（序列号 `ZXJRNJVWY9C6BYDA`）。
- **验证包**：`assembleDebug -PverifyBuild` → `com.linguareader.app.verify` / 1.5.1-verify，与日常包 `com.linguareader.app`（同 versionCode 11）并存，数据互不干扰。分支 `feat/refactor`（含 2658db6 阅读页状态重构 + 30c55ce 元数据回写 + 52977a9 TTS 异常兜底）。
- **测试素材**：脚本生成 12 章 EPUB（每章 80 段、约 9 KB），**每段带 `[Cnn-Pnnn]` 标记**，并在每章 P010/P050 埋同一句探针：`The app ... an apple. She likes to study, and yesterday she studied ... he was running through the city, past two other cities.` 产物 `artifacts/TheLanternLibrary-verify-12ch.epub`（未入库）。
- **方法学（本轮新增，后续照抄即可）**：模型看不了截图时，用三条文本化证据代替肉眼——① `uiautomator dump` 读页码指示与控件 contentDescription；② `run-as <pkg> cat files/books/<id>/metadata.json` 直接读盘上进度字段；③ 段落标记定位内容位置。手势用 `input swipe x1 y1 x2 y2 <ms>`（>700ms 即「慢速」，触发进滑动模式而非翻页）。

| 验证项 | 判据 | 结果 |
| --- | --- | --- |
| 簇 E-① 进滑动不跳章首 | 第 10/12 页慢拖 1000ms → 章节进度应 ≈82% | **`1/12 · 章节进度 83%`** ✅ |
| 簇 E-② 回翻停末页 | 第 2 章第 1 页按上一页 → 应落 1/12 · **12**/12 | **`1/12 · 12/12`**，`pageIndex=11` ✅（两次复现） |
| 簇 E-③ 落位后改字号 | 字号 100%→140%，章内 12 页→21 页 | **`1/12 · 21/21`**，仍在新分页章末 ✅ |
| B1 生词整词高亮 | app/apple、study/studied、running、city/cities | 用户肉眼确认全部正确 ✅；UI 树旁证：高亮节点精确为 `"Running"` 整词且保留正文原大小写 |
| B3 听书/阅读进度互不覆盖 | 交替写盘不得互相打回 | 播 25s → `ttsSent 2→16`；**翻一页后 `page 4→5` 且 `ttsSent` 仍 16**；再播 25s 后 **`page` 仍 5**、`ttsSent=47` ✅ |
| B5 章节损坏不崩溃 | 删掉 `OPS/ch03.xhtml` 后跳该章起播 | WebView 报 `ERR_FILE_NOT_FOUND`，听书条**退回「播放」态（降级为暂停）**，**PID 17355 全程未变、无 FATAL** ✅ |
| BUG-033 听书条遮挡正文 | 状态条出现时正文末行是否被盖 | 用户确认**不遮挡** ✅ |
| 返回键优先级 | 目录/设置 → 返回逐层关闭，再返回退到书架 | 三级依次正确 ✅ |

- **本轮新发现（尚未编号，建议记为观察项）：旋转后按「页码」而非「阅读比例」还原，内容位置发生漂移。** 竖屏 `3/12 · 4/21` → 横屏 `3/12 · 4/41` → 转回竖屏 `3/12 · 4/21`。页码守住了，但横屏 41 页时的第 4 页 ≈ 章内 10%，而竖屏 21 页时的第 4 页 ≈ 章内 19%——即旋转一次内容位置倒退约一成。修法方向与簇 E 同源：跨重排应还原比例（或末页哨兵那样的语义锚点），不是裸页码。
- **`BUG-034`（次要候选，未修）真机确认存在**：播放中手动翻一页，`ttsSentenceIndex` 从 16 直接跳到 45 左右（被拉到新页首句）。
- **数据层旁证（`files/vocabulary.json`）**：`study` → `surfaceForms=[]`（点的就是原型，靠 JS 规则展开命中 `studied`）；`city` → `surfaceForms=["cities"]`；点 `running` 时词典判为短语 `run through` 并记下 `surfaceForms=["running"]`。说明整词高亮是「入库表面形 + JS 词形展开」两条腿一起走。
- **未做**：R8 release 包的真机冒烟（`feat/release-r8`）。原因是 release 没有 `signingConfig`，产物是 `app-release-unsigned.apk` 装不上，需先用 debug keystore 补签再装；合入 main 前必须补这一轮（点词/翻页/滚动/听书通知栏/PDF 导入/MiMo 音色名）。
- **设备状态变更说明**：验证期间打开了「充电时保持唤醒」（`stay_on_while_plugged_in=3`），并临时锁过横屏（`accelerometer_rotation 0 / user_rotation 1`），结束时已还原 `accelerometer_rotation=1`；自动旋转设置回到系统默认。验证包与测试书未卸载，如需清理：`adb uninstall com.linguareader.app.verify`。

## 2026-09-02 对齐器 V2「词义锚点」实现（JVM 全绿 + 整本重放，第三轮人工重测待做）

- **背景**：100 样本两轮人工评估后确认剩余错配本质是「同段句序偏移」，用户拍板走词义锚定路线：像数字/拉丁锚点那样，用 ECDICT 释义把英文词义与中文译文词锚定，命中数参与 `pairCost`。实验（完整句重测）误配组 76% 零锚点、阈值 0.15 下 FP=0。
- **实现**：`TranslationAligner.VERSION` 1→2；新 `MeaningIndex` 接口（英文词→中文释义短语集合）；`EcdictMeaningIndex`（复用词典 ecdict 副本，只读打开，单词缓存，forms 表回退 lemma）；`MeaningPhraseParser`（词性白名单 n./v./vt./vi./a./adv./adj.，虚词行整行跳过，高频虚词黑名单，行内容取 2–4 字汉字子串）；`TraditionalSimplified`（约 230 对繁→简硬编码表）。加分公式 `min(命中数,4)×0.12`，与现有锚点并列进 `pairCost`；`meaning=null` 时行为与 V1 完全一致。中文侧预计算繁简归一后的 2–4 字子串集合，DP 内层仍只做哈希查表（性能护栏守住，`TranslationAlignerBenchmarkTest` 通过）。
- **JVM 验证**：`testDebugUnitTest` **521 个用例 0 失败 1 跳过**（新增 `MeaningPhraseParserTest` 3 个 + `TranslationAlignerTest.meaningAnchorsSteerTheDpToTheRightSentence`）。
- **修测试时踩的两个坑**：① 旧会话用 JS `String.raw` 生成测试文件，Kotlin 里 `\n` 变成了字面反斜杠-n，分行解析没生效（测试输入自身错了）；② 该词义锚测试场景里 `'…!' he cried` 会被 R3 规则**故意**留成一句（说话人引导语归属），锚点无从发力——场景里 `he` 必须大写让英文切成两句，锚点才是决胜项。
- **整本重放（魔戒首部曲，Robolectric 跑真 EcdictMeaningIndex 一次性工具，用完已删）**：句对 11,674 → 11,532；按（章，英文句）键对比 R3 基线：**8,538 键不变 / 1,592 键重配 / 无一例「有中文变没中文」**；平均置信度 0.829 → **0.906**。抽查重配对以改善为主（如 "Bilbo left his place and stood on a chair" 从错配句改配「比爾博離開座位……爬到椅子上」）。
- **100 样本定位**：只有 **15/100** 条对照相对判定基线实质变化，其中 12 条原判「错」——s47/s67/s73/s76 等直接变为正确句对（f=1.0），s2/s87 等「U-漏配」类变保守（空对照）。45 条「错」原样未动，即同段句序偏移类，锚点不治本，仍留给语义路线。
- **产物**（artifacts/，gitignored）：`realigned-v3.json`（V2 重放档案）、`alignment-eval3.csv/.html`（第三轮重测工具，15 条待用户判定）、`lotr-wordmap.json`、`eval3_compare.py`。
- **注意**：`alignerVersion` 只写不读——已存在的设备档案不会自动重对齐，用户重新挂译本时才会用 V2。真机行为（对齐耗时是否仍在十秒级）未复测，下一次真机验收时顺带看。

## 2026-09-02 对齐器 V3（句级 1:N 带门槛 + 兜底段句级 DP + 切句卫生）JVM 全绿 + 整本重放，第 4 轮人工重测待做

- **背景**：P0 修正评估工具链定位 bug 后确认——三轮评估的样本匹配都存在「空 `es` 条目优先命中」缺陷（`'' in s` 恒真），P1/P2 层样本从未见过真正的句级展示；V2 词义锚实际已修复约 12 条而工具未显示。实验台（Python 忠实移植分句/代价/DP）拟合出 1:N 合并门槛后落地 V3。
- **实现**（`TranslationAligner.kt`，VERSION 2→3）：① 句级 DP 开 1:N，三道合并门槛——词义锚命中 ≥1、尺寸保护（en 词 ≥2 / zh 字 ≥6，标题禁合并）、边际要求（合并须比局部最优 1:1 便宜 0.12 以上，防「本来就配得好被抢走」）；合并句对置信度 ×0.85。② 邻接兜底段升级为**先跑句级 DP**（置信度 ×0.55÷距离，<0.30 不落盘），段级条目保留。③ 切句卫生：纯标点英文句不入 DP。门槛在 100 样本集上拟合（G0 无门槛 23 修/9 ok 变化 → G5 边际 0.12 22 修/6 ok 变化且 1N 净引入仅 s25 一条粒度权衡）。
- **JVM 验证**：`testDebugUnitTest` **533 个用例 0 失败 1 跳过**（新增 6 个：合并拆分译文、标题吸收负样本、无命中禁合并、边际负样本、兜底句级、切句卫生）。`TranslationAlignerBenchmarkTest` 素材不在本机自动跳过；性能以重放计时为准。
- **整本重放**（魔戒，Robolectric 真 `EcdictMeaningIndex`，一次性工具用完已删）：句对 11,532 → 11,417，句级 10,861 → 10,746（含合并句对），平均置信度 0.906 → 0.899（合并对 ×0.85 所致）。全无句级对照的样本从 28 → **10**。
- **100 样本定位**：57 条 bad 中 35 条 v4 展示已变化（待第 4 轮判定，目测多数转好——s33「feet felt like lead」→「腳像鉛一樣重」、s61 被单调性锁死的正确句也解开了）、10 条展示未变（真顽固）、7 条仍无句级对、5 条切句残渣。ok 层变化 6/35 + ok2 3/8，待重判确认无退化。
- **产物**（artifacts/，gitignored）：`realigned-v4.json`、`alignment-eval4.html/.csv`（第 4 轮重测工具，54 条待判定）、`eval_tool.py`（修正版正式评估工具）、`anchor_experiment.py`/`gate_fit.py`（门槛拟合实验台）、`alignment-v3-corrected.csv`（P0 修正报告）。
- **待办**：用户第 4 轮判定 → 若 S1<3%、总量<10% 达标且全零类 ≤10 条，句向量（M-B）继续缓行；真机 attach 耗时复测一次。

## 2026-09-03 对齐器 V4（低置信句对不落盘 + 段级兜底显示整段译文）：第 4 轮判定 57%→32% 后的两个主诉修复

- **第 4 轮判定结果**（`alignment-eval4.html`，54 条重判）：总错配 **57%→32%**；S2 层 7% 达标，S1 22%、P2/P3 层仍高。用户主诉聚类明确：①长句只显示其中一句译文；②一些句子完全没有译文。
- **解剖**（v4 重放 + 全书 14,791 次查询模拟）：①的主身是 **302 条（2%）低置信残渣句对**——DP 产出的 27 词英文↔「啊！」这类错对（f≈0.19），查询侧 L1 精确命中**不受置信度门槛限制**（1–3 级是文本精确命中，门槛只管 4/5 级），落盘即被原样展示；②全书 MISS=0，主因是评估卡片定位残影 + L5 段落兜底命中句对条目时只显示单句 `zhSentence`（第二个「只显示一句」来源）。
- **修复**：① `TranslationAligner`（VERSION 3→4）：句对置信度 < `MIN_ACCEPT_CONFIDENCE`(0.30) 不落盘，降级为段级条目走段落兜底——「宁可不产，不可错配」；② `TranslationMemoryIndex` L5 段落兜底改显示完整 `zhParagraph`（兜底命中句对条目时单句必是错位句，残句比整段更误导）；③ 评估工具（`eval5_tool.py`）改为**模拟应用内真实 5 级查询**，卡片展示即用户将看到的。
- **JVM 验证**：`testDebugUnitTest` **536 用例 0 失败**（新增 2 个：低置信句对不落盘、L5 显示整段）。期间踩坑一次：并行会话共享 gradle 项目锁与测试结果目录，我的后台套件任务空转 20 分钟、读到了对方跑出的 XML——已停掉重跑，以 14:41:55 时间戳的本机结果为准；暴露的 1 个测试场景错误（单句英文对双句中文时 DP 选对了，丢弃路径未触发）已改为「中文段只有超短句」场景。
- **整本重放 v5**：句对 11,417→11,395（句级 10,722 / 段级 673），**低置信句对清零**，长句残缺（en≥8 词且 zh 比<0.35）302→64，平均置信度 0.900。第 4 轮主诉簇验证：s6/s36/s43/s51/s62/s72 等全部拿到整段译文；残余 MISS 12 条中 7 条为切句残渣/版权页（本该无对照），真漏配 ≤4 条。
- **待用户第 5 轮判定**（`alignment-eval5.html`，19 条变化）确认修复无退化；真机 attach 耗时复测仍欠。
