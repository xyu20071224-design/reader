# 簇 E 工作总结：阅读进度两处精确 bug 的修复

> 范围：仅本会话负责的「簇 E —— 阅读进度」两条（进滑动模式丢进度跳章首、回翻跳到上一章开头），
> 不涉及其它会话的 B1/B2/B3（生词高亮 / 整本翻译 / 数据安全，见同目录 `工作总结.md`）。
> 生成时间：2026-09-01。

---

## 一句话

把阅读页两个「精确小 bug」按根因修掉并补了回归测试：`testDebugUnitTest` **428 个用例 0 失败**，
`assembleDebug` 通过；修复已随 `5c36f2c` / `625fe72` 落在 `origin/main`。真机实测仍欠着（设备侧未开 USB 调试）。

---

## 修复内容

### ① 进滑动模式丢进度、跳章首（补充#6）

- **根因**（`ReaderScripts.kt` `enterScrollMode`）：先 `scrollMode = true` 再取 `currentRatio()`，
  而 `currentRatio()` 第一句是 `if (scrollMode) return currentScrollRatio()`。此刻布局仍是分页布局
  （`overflow-y:hidden`，`scrollTop` 恒 0）→ 比例被算成 0 → `syncScroll()` 把 `scrollTop` 设成 0 → 跳回章首。
  同文件 `exitScrollMode()` 一直是「先取值、后置位」的正确写法，两者不对称即笔误。
- **修法**：把取值提到置位之前（`const entryRatio = ratio == null ? currentRatio() : ...`，再 `scrollMode = true`）。
- **特征**：只在「非首页」进滑动才复现（第 1 页进，比例本来就是 0，无感）。

### ② 回翻跳到上一章开头（正文#3）

- **根因**（`ReaderScreen.kt` `selectChapter` + `ReaderScripts.kt` `updateMetrics`）：回翻用 `initialPage = Int.MAX_VALUE`
  当「最后一页」哨兵，但 JS 把它当普通页码 `clamp(page, 0, pageCount - 1)`。首次测量常跑在字体/图片就绪前，
  `pageCount` 偏小甚至为 1，页码被拍成 0；而那条专为「测量变准后恢复」写的救援分支判据
  `restoreTarget <= pageCount - 1` 对 `Int.MAX_VALUE` 永远不成立 → 页码永久停在 0 → 落在上一章开头。
- **修法**：哨兵显式化——Kotlin 侧 `ReaderScripts.LAST_PAGE`，JS 入口翻译成 `restoreTarget = -1`；
  `updateMetrics` 单独处理该分支（每次重排重新取末页），`lrSetPage` 认同一约定；普通页码的 clamp + 救援路径原样保留。

### ③ 顺带加固 `lrSyncPage`

- 还原尚未落地时（`restoreTarget < 0` 或 `restoreTarget > page`）不再按「当前渲染页」重新播种 `restoreTarget`，
  否则恢复过程中改字号/行距/主题会把还原目标抹成临时页——从另一条路复现同一类进度丢失。

---

## 验证

| 项 | 结果 |
|---|---|
| `testDebugUnitTest` | **428 用例，0 失败 0 错误**（`ReaderScriptsTest` 31 个） |
| `assembleDebug` | 通过，`app-debug.apk` ≈ 58.4 MB |
| 新增回归用例 | 4 个（见下） |

新增用例（`ReaderScriptsTest.kt`）：

1. `enteringScrollModeReadsThePagedRatioBeforeFlippingTheMode`
2. `lastPageSentinelIsNeverClampedLikeAnOrdinaryPageIndex`
3. `preferenceSyncDoesNotOverwriteAPendingRestoreTarget`
4. `ordinaryRestoredPageKeepsTheClampAndRescuePath`

JVM 之外的静态验证：把 `bootstrap()` 注入的整段 JS 抽出、替换 Kotlin 插值后 `new Function()` 解析通过；
并按源码逻辑仿真「测量序列 pageCount 1→12→12」——旧实现哨兵轨迹 `[0,0,0]`（章首，缺陷），新实现 `[0,11,11]`（章末）；
普通页码 7 两版同为 `[0,7,7]` 不变；进滑动比例旧实现任何起始页都是 0，新实现第 1 页 `0.000`、第 10 页 `0.818`、末页 `1.000`。

---

## 提交记录

- `5c36f2c` `fix(reader): 生词整词高亮 + 章节回翻停末页 + 进滑动模式不丢进度` —— 本会话两处修复与另一会话的
  生词高亮重写同批合入（双方 hunk 不重叠）。
- `625fe72` `chore: 修正阅读进度双 bug 的验证记录……` —— 补上真实单测结果（428 全绿）与工具链实际位置。

> 说明：`625fe72` 提交时工作区被并行会话切到了 `feat/reader-state-holder`，后经 `git branch -f main` 快进回 main 并
> push，无 force-push、无历史改写。

---

## 尚未完成：真机验证

WebView 渲染/手势单测覆盖不到，按 AGENTS.md 必须真机实测。APK 已就绪（`src/app/build/outputs/apk/debug/app-debug.apk`），
但设备端卡在「USB 调试未开」：手机插上后只以 MTP 出现（PnP 无 Android ADB Interface），`adb devices` 全空。

待验清单：

1. 翻到某章第 10 页附近 → 慢速竖拖进滑动模式 → 底栏应为「章节进度 ~80%」而**不是 0%**（第 1 页进入本来就无感，不作数）。
2. 停在第 N 章第 1 页 → 右滑回上一章 → 应落在「N-1 章 · 末页/末页」而**不是 1/末页**。
3. 回翻落位后改字号 → 仍停在新分页的章末。
4. 普通续读、章内翻页、滑动模式内跨章前后翻无回归。

---

## 环境备注（接续者快速上手）

- 工作区 `C:\work\reader`（全英文路径）；工具链在 `C:\work\reader\toolchain`（`jdk` / `android-sdk` / `gradle-home`），
  构建入口 `toolchain\build.ps1`，它设好 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME 后 `cd src` 调 gradlew。
- `src/local.properties` 指向 `sdk.dir=C:\work\reader\toolchain\android-sdk`。
- 本会话曾在工具链位于 `C:\work\toolchain` 时跑出 428 全绿；之后工具链被迁进仓库根并被 `.gitignore` 忽略（`c1a56fb`）。
- 中文路径下 `testDebugUnitTest` 起不来 worker（GBK/UTF-8 argfile 乱码），务必在 `C:\work\reader\src` 下跑。
