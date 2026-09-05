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

## 已搬入

- `com.linguareader.shared.update` — `GitHubReleaseParser` / `UpdatePolicy` / `AppUpdateUiState` / `GitHubUpdateChecker`（原 `app/update/` 的四个纯文件；`AppUpdateRepository`、`AppUpdateSettings`、`ApkInstaller` 因依赖 `Context`/`BuildConfig`/`FileProvider` 留在 `:app`）。随迁测试 8 个（`GitHubReleaseParserTest` / `UpdatePolicyTest`）。
