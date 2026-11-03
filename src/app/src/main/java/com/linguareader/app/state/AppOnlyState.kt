package com.linguareader.app.state

import com.linguareader.app.AiTranslationPrepare
import com.linguareader.app.AiTranslationProgress
import com.linguareader.app.LaunchPromptUi
import com.linguareader.app.ManualTranslationPrepare
import com.linguareader.app.PendingManualExport
import com.linguareader.app.packs.PackUiState

/**
 * 暂时只能留在 :app 的那部分状态。
 *
 * 为什么不能一起下沉到 :shared/ui/state：这些字段的载荷类型本身还在 :app——
 * LaunchPromptUi 里带 @StringRes Int（:shared 拿不到 R），PackUiState 里带
 * java.io.File 与 :app/packs 专属枚举。它们是「下一步该搬家」的清单，不是永久设计：
 * 等 SharedString 与资源包契约下沉完成后，这些字段应当并入对应切片。
 *
 * AppUiStateSplitTest 用反射盯着 AppUiState 的每个字段都必须有归属，
 * 所以「新增字段忘了拆」不可能悄悄发生。
 */
data class AppOnlyState(
    /** 启动提示（问候 / 版本更新说明）。 */
    val launchPrompt: LaunchPromptUi? = null,
    /** AI 整本翻译进行中的书 → 进度。 */
    val aiTranslationProgress: Map<String, AiTranslationProgress> = emptyMap(),
    /** 非空时显示「AI 生成译本」确认框。 */
    val aiTranslationPrepare: AiTranslationPrepare? = null,
    /** 非空时显示「手动 AI 翻译」对话框。 */
    val manualTranslationPrepare: ManualTranslationPrepare? = null,
    /** 手动任务文件已构建、等用户在 SAF 面板里选保存位置。 */
    val pendingManualExport: PendingManualExport? = null,
    /** 资源包（词典/预生成音频/音色）列表与安装状态。 */
    val packs: PackUiState = PackUiState()
)
