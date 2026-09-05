# `:shared` — 跨端共享的纯 Kotlin/JVM 模块（M1 起步）

桌面（Windows）版迁移的落点，见根目录 `迁移方案-桌面Windows版.md`。

## 它现在是什么

**纯 `kotlin("jvm")` 库**，不是 KMP 模块。它被两条线同时消费：

- `src/app`（Android）以 `api(project(":shared"))` 依赖它；
- `desktopApp`（M2 起）以普通 JVM 依赖消费它。

选纯 JVM 库而非 KMP 是有意的：M1–M2 要抽的东西（更新检查、对齐算法、词表、TTS 状态机）本就零 Android 依赖，一个 target 就够；KMP + Compose Multiplatform 的插件与 native 产物是 M4 渲染层的事，届时再升级，别提前背成本。

## 三条硬规则

1. **不许 `import android.*` / `androidx.*`**。这是本模块存在的全部意义；破了就退化成第二个 `:app`。
2. **不新增运行时依赖**。`org.json` 与 `kotlinx-coroutines-core` 都写成 `compileOnly`：Android 运行时由平台/现有传递依赖提供，若以 `implementation` 进 APK 会和 `android.jar` 撞重复类（dex 报错）。桌面壳自己声明 runtime。
3. **搬入 = 只改包名 + 同步测试**，不改一行语义。每搬一块必须 `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（Android 是唯一真相的守门人）。

## 包名

`com.linguareader.shared.<域>`（如 `com.linguareader.shared.update`）。刻意**不**复用 `com.linguareader.app.*`：那是 Android 的 applicationId/namespace，与桌面无关。

旧包路径 `com.linguareader.app.data.*` 的类型由 `src/app/.../data/SharedDataCompat.kt` 的 typealias 兜底，既有 import 零改动；**新代码请直接 import 新包**，M2 全量替换后删掉那个兼容文件。

## 资源间接层（决策 4）

共享模型需要携带「用户可见名称」的引用时，用 `com.linguareader.shared.res.SharedString` 符号，**不碰 `R`**（`:shared` 拿不到 `:app` 生成的 `R`）。Android 侧在 `src/app/src/main/java/com/linguareader/app/res/AndroidStrings.kt` 提供穷举 `when` 的 `resolve(): Int`，UI 用法 `stringResource(theme.labelRes.resolve())`。`:shared` 新增符号时两端映射都会被编译器强制补全，不会留下运行时才炸的资源空洞。桌面侧（M2+）另建对端映射。

## 跨模块的编译器差异（搬入时必踩，已知）

类型一旦进了 `:shared`，`:app` 侧就**不再对它的属性做智能转换**（跨模块 public 属性）。例：`if (entry?.matchedPhrase != null) { use(entry.matchedPhrase) }` 会报 "Smart cast is impossible"，改成先 `val x = entry?.matchedPhrase` 捕获局部量再判空即可，语义不变。

## 已搬入

- `com.linguareader.shared.update` — `GitHubReleaseParser` / `UpdatePolicy` / `AppUpdateUiState` / `GitHubUpdateChecker`（原 `app/update/` 的四个纯文件；`AppUpdateRepository`、`AppUpdateSettings`、`ApkInstaller` 因依赖 `Context`/`BuildConfig`/`FileProvider` 留在 `:app`）。随迁测试 8 个（`GitHubReleaseParserTest` / `UpdatePolicyTest`）。
- `com.linguareader.shared.data` — `Models.kt`（`Book`/`Chapter`/`SavedWord`/`WordLookup`/`ReaderPreferences`/`ReaderTheme`/`ReaderFont`）+ `ContextAnalyzer.kt`（`PartOfSpeech` 与分词/短语窗口/义项排序全套）。`labelRes` 从 `@StringRes Int` 改为 `SharedString`。随迁测试 20 个（`ModelsTest` / `ContextAnalyzerTest`）。
- `com.linguareader.shared.res` — `SharedString` 枚举（词性 5 + 主题 7 + 字体 5）。

**下一刀的拦路石**：`ReviewMode.kt` 另带 `SharedPreferences` 依赖，要先落 `AppContext`/prefs 抽象（方案 §4）；`DictionaryRepository.kt` 需要 sqlite 驱动抽象；`translation/` 与 `ai/` 包大多零 Android 依赖，是较顺的后续目标。
