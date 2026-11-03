# P4 · 阅读页注入 JS/CSS 重构设计

> 前提：`ReaderScripts` 已在 `:shared`（1590 行 / 81,856 字节），**不是**在 `:app`。
> `:app` 侧只剩 13 行转发壳 `reader/ReaderScriptsCompat.kt`。
> 桌面 `DesktopReaderPane` 直接 import 它，所以**协议不能动**。

## 现状

| 项 | 值 |
| --- | --- |
| `bootstrap()` 函数体 | ≈1510 行 / 69KB 内嵌字符串（HTML + CSS + JS） |
| CSS 段 | 4,414 字符（`:122-212`） |
| `window.lrXxx` 公开 API | **16 个** |
| CSS 变量 | **12 个** `--lr-*` |
| 内部 JS function | 41 个 |
| 消费方 | `:app` 的 `EpubPage`（WebView）、`:desktopApp` 的 `DesktopReaderPane`（JCEF） |

12 个 CSS 变量：
`--lr-bg` `--lr-fg` `--lr-font` `--lr-size` `--lr-line`
`--lr-link` `--lr-selection` `--lr-mark` `--lr-due` `--lr-highlight`
`--lr-chrome-top` `--lr-chrome-bottom`

## 重构目标

### 1. 拆分巨型字符串（可维护性）

```
shared/reader/
├── ReaderScripts.kt        // 只剩装配逻辑（bootstrap = html + css + js 拼装）
├── html/ReaderHtml.kt      // 骨架 HTML
├── css/ReaderCss.kt        // 4414 字符的 CSS，按区块分常量
└── js/
    ├── ReaderCoreJs.kt     // 分页/滚动/坐标
    ├── ReaderHighlightJs.kt// 高亮/定位
    └── ReaderApiJs.kt      // 16 个 window.lrXxx 的装配
```

**风险**：把 69KB 字符串拆开时，任何一处多/少一个反引号都会让整页白屏，而**这类错误单测很难抓到**（现有 `ReaderScriptsTest` 只做字符串包含断言）。

**对策**：先写一个**结构不变性测试**——把 bootstrap 拆成 N 段前后，对拼装结果的**字符级等价**做断言（`before == after`）。这一步做在拆分之前，作为安全网。

### 2. CSS 变量与 Compose 皮肤同源

现在：`ReaderTheme`（7 套 hex，硬编码在 `Models.kt:247`）
改后：`--lr-*` 由当前 `SkinSpec` 派生，外壳与正文同一张色表。

**但要注意**：`ReaderTheme` 是用户可见的 7 个预设（纸白/米黄/护眼绿/夜间/纯黑…），
不能因为换皮肤就把用户的选择弄丢。设计：

```
SkinSpec (外壳皮肤)  ──┐
                       ├──→ ReaderCssTokens（12 个变量）
ReaderTheme (用户偏好) ─┘
```

- `--lr-bg`/`--lr-fg` 仍跟 `ReaderTheme`（用户的阅读偏好优先）
- `--lr-mark`/`--lr-link`/`--lr-due`/`--lr-highlight` 的**色调**跟 SkinSpec 的 accent/gold/success
- `--lr-chrome-top/bottom` 跟布局常量不变

即：**骨架跟用户偏好，点缀跟皮肤**。

### 3. 不动的部分

16 个 `window.lrXxx` 全保留，签名不变。理由：桌面 JCEF 依赖同一份协议，
改协议等于同时改两端，收益（几乎没有）远小于风险。

## 分步计划

| 步 | 内容 | 验证 |
| --- | --- | --- |
| P4-1 | 写「拆分前后字符等价」的护栏测试 | 拆前后 `bootstrap()` 输出 `==` |
| P4-2 | 拆成 html/css/js 三段常量 | 同上测试绿 + 渲染台截图 |
| P4-3 | CSS 区块化（12 个变量集中一处声明） | 同上 |
| P4-4 | `--lr-*` 从 SkinSpec 派生（可选开关） | 单测 + **待真机** |
| P4-5 | 补 `ReaderScriptsTest` 的结构断言（16 个 API 都存在） | 单测 |

## 验证限制（必须写明）

WebView 在 Robolectric 里**不会真正执行 JS**，所以：

- 「拆完之后 JS 还能跑」**渲染台测不出来**，只有真机能证明
- 因此 P4 的每一步都要在提交信息里标注「**待真机验证**」
- 真机验证清单：
  1. 打开一本书，正文正常显示（不是白屏）
  2. 左右翻页正常
  3. 点词能弹出释义（`lrLookupAt`）
  4. TTS 播放时高亮跟随（`lrHighlightSentence`）
  5. 切主题后正文背景/文字色变化（`preferenceScript`）
  6. 滚动模式进出（`lrEnterScrollMode`/`lrExitScrollMode`）
