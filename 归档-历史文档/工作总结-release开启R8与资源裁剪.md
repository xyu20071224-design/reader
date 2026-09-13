# 工作总结：release 开启 R8 与资源裁剪

## 一句话

`release` 之前 `isMinifyEnabled = false`、`proguard-rules.pro` 全文只有一行注释——
压缩 / 混淆 / 资源裁剪三样全丢。现已打开并补齐 keep 规则，
**实测发版包 45.38 MB → 33.3 MB，省 12.07 MB（26.6%）**。

---

## 1. 改动与去处

已提交在 **`feat/release-r8`** 分支（**从 `main` 切出，只含这 1 个提交**，可独立合入）：

| 文件 | 改动 |
| --- | --- |
| `src/app/build.gradle.kts` | `isMinifyEnabled = true` + 新增 `isShrinkResources = true` |
| `src/app/proguard-rules.pro` | 从"一行注释"改成真正的 keep 规则（JS 桥 + pdfbox `-dontwarn`） |
| `src/app/src/main/res/raw/keep.xml` | **新增**，资源裁剪白名单 |
| `发版瘦身-R8-现状与方案.md` | 核实过程、风险清单、完整验证矩阵 |

> 注意：上面那份方案文档**只存在于该分支的提交里**，当前工作区在 `feat/refactor`，
> 树上看不到它。`git show feat/release-r8:发版瘦身-R8-现状与方案.md` 可查看。

---

## 2. 实测收益

| 构建 | 体积 |
| --- | --- |
| R8 off（临时关掉重跑的基线） | 47,581,770 B / **45.38 MB** |
| R8 on | 34,922,590 B / **33.3 MB** |
| **净省** | **12,659,180 B / 12.07 MB / 26.6%** |

- 构建首轮即 `BUILD SUCCESSFUL`（1m34s），没有出现预期中的 pdfbox missing class 报错。
- `usage.txt`：74,666 行类 / 成员被移除。

---

## 3. 两个"开了就静默炸"的坑（已堵 + 已验证）

R8 在本项目的真正风险不是崩溃，而是**不崩溃、不报错、功能悄悄失效**——
单测和"能装能启动"的冒烟都测不出来。

### 3.1 WebView JS 桥

`reader/EpubPage.kt` 的 `ReaderBridge` 是 `private class`，8 个 `@JavascriptInterface` 方法，
JS 侧**按方法名**调用。混淆成 `a/b/c` 后，点词查词、翻页回报、滚动进度、工具栏唤起会全部静默失效。

规则：

```proguard
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
```

验证（`mapping.txt` 的 `ReaderBridge` 类块）：

- ✅ 8 个方法**全部保留原名**：`onWord -> onWord`、`onPageChanged -> onPageChanged`、
  `onReady`、`onChapterRequested`、`onScrollModeChanged`、`onScrollProgress`、
  `onSentenceTapped`、`onToolbarRequested`。
- ✅ 私有的 `post` 被正常压成 `a`（说明规则精准，没有过度 keep）。
- ✅ 类本身混淆成 `F1.l`——无妨，注册用的是 `addJavascriptInterface(..., "ReaderBridge")` 那个字符串。

### 3.2 动态引用的资源

`ListeningSettingsSheet.kt:482` 的 `mimoPresetName()` 用
`resources.getIdentifier(nameKey, "string", packageName)` 取 MiMo 预置音色名，
key 来自 `tts/MiMoVoice.kt` 的 `Preset.nameKey`，**这 9 条 string 没有任何静态引用**。
不管的话资源裁剪会直接裁掉，UI 上音色名退化成 `tts_mimo_voice_bingtang` 这种原始 key
（代码里 `id == 0` 时回退 key，同样不报错）。

新增 `res/raw/keep.xml`：

```xml
<resources xmlns:tools="http://schemas.android.com/tools"
    tools:keep="@string/tts_mimo_voice_*" />
```

验证：`resources.txt` 中 9 条 `tts_mimo_voice_*` 全部 marked used，未被裁。

---

## 4. 没确认的（重要，别当成已验证）

1. **真机冒烟一项没做**——本环境无 adb、无设备。
   按 `AGENTS.md` 验证纪律，R8 属于影响 WebView / TTS 的全局改动，
   **真机实测是合入 `main` 前的硬要求**。待测清单：
   点词查词、翻页、滚动模式、听书通知栏媒体控制、复习提醒、PDF 导入、MiMo 音色名显示。
   参考设备：PKB110 / Android 16。
2. **`keep.xml` 的必要性没做对照实验**。日志显示那 9 条 string 是被 shrinker 的
   string-pool 启发式命中的；`keep.xml` 的作用是把"启发式碰巧命中"变成确定性保证。
   我没有跑"去掉 keep.xml 会不会真被裁"的对照组。
3. **pdfbox 的 `-dontwarn` 是预防性添加**。构建一次过，我无法区分它是"救了场"
   还是"本来就不需要"。保留无害，删之前请先跑一次构建。
4. **分支没有 push**。`AGENTS.md` 要求 feature 分支也推做异地备份，我没有动网络。
5. `release` **没有 signingConfig**，产物是 `app-release-unsigned.apk`；发版签名是另一条链路。
6. 剩余的 33.3 MB 大头是 `assets/dictionary/ecdict.sqlite`——**assets 不参与资源裁剪**，
   要继续瘦身得另想办法（压缩词典 / 拆分下发），不在本次范围。

---

## 5. 一个我造成的事故（已止损，但需要你裁决）

**背景**：本次会话期间有**另一条会话在并行改这个仓库**——工具链目录被从
`C:\work\toolchain` 搬到仓库内 `C:\work\reader\toolchain`、`feat/refactor` 多出 6 个提交、
`bug收集/` 下多份文档持续在改、新增若干 `工作总结*.md`。

**经过**：我在这个**共享工作区**里 `git checkout -b feat/release-r8` 建了自己的分支。
在我于临时 worktree 里整理提交期间，那条会话把提交
`4ea9122 docs(bug收集): 订正 BUG-021 词边界档案状态并同步生词划线族落地情况`（7 个文件）
落在了**我的分支**上。我随后执行 `git branch -D feat/release-r8`，把它删成了游离提交。

**止损**：已建分支 **`rescue/bug021-docs-4ea9122`** 固定该提交，**内容没有丢失**。

**遗留问题（需你决定）**：当前工作区**缺这份内容**——切回 `feat/refactor` 时被还原了，
`git diff 4ea9122 -- bug收集/` 显示相差 137 行删除。我**没有擅自 cherry-pick 回
`feat/refactor`**，原因是那几个文件此刻正被另一条会话继续编辑（工作区里是 `M` 状态），
强行 cherry-pick 很可能撞车或覆盖它们正在写的内容。

建议二选一：

- 让那条会话自己 `git cherry-pick 4ea9122`（或 `git checkout rescue/bug021-docs-4ea9122 -- bug收集/`）；
- 或确认它已停工后，由你指示我来做。

**教训**：`AGENTS.md` 那条"同一时间尽量只开一个会话操作本仓库"是有血的教训的，
这次又验证了一遍。在共享工作区里切分支尤其危险。

---

## 6. 顺带记录的环境事实（`AGENTS.md` 未载）

1. **`JAVA_HOME` 默认没设**，直接 `.\gradlew.bat` 报
   `ERROR: JAVA_HOME is not set`；默认 `GRADLE_USER_HOME`（`C:\Users\nagisa\.gradle`）
   不可用，wrapper 报 `Could not create parent directory for lock file`。
2. **正确构建入口是 `<仓库根>\toolchain\build.ps1`**，它统一设好 JDK17(Temurin)、
   `ANDROID_HOME`、`GRADLE_USER_HOME`（已含 gradle-8.11.1-bin 与依赖缓存）、
   `USERPROFILE`/`HOME`/`TMP` 重定向、代理 `127.0.0.1:7890`。
   命令：`powershell -NoProfile -File <仓库根>\toolchain\build.ps1 assembleRelease`
3. **本机没有 `pwsh`（PowerShell 7）**，只有 Windows PowerShell，调脚本要用 `powershell -File`。
4. **工具链会搬家**（脚本已改用 `$PSScriptRoot` 自解析），别硬编码绝对路径；
   搬家后 `src/local.properties` 的 `sdk.dir` 需同步更新（该文件被 gitignore，不进提交）。
5. **`AGENTS.md` 版本号已漂移**：文档写 `versionCode 9 / versionName 1.4.0`，
   实际是 **11 / 1.5.1**。按"以代码为准"应修正文档。
