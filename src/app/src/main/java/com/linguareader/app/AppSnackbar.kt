package com.linguareader.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 全局轻提示（Snackbar）通道。
 *
 * 替代散落在各个面板里的一行状态文字：那些文字在弹层关闭后就消失、也不区分
 * 「操作成功」与「持续状态」。界面任意位置都可以 `LocalAppSnackbar.current.show(...)`。
 *
 * 提示带 [StatusTone] 语义（成功/失败/中性），渲染端按语义取容器配色
 * （见 ThemeColors.snackbarColorsFor）；默认 NEUTRAL 保持历史观感
 * （日间深容器浅字、夜间浅容器深字）。
 */
internal class AppSnackbar(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope
) {
    fun show(
        message: String,
        tone: StatusTone = StatusTone.NEUTRAL,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        if (message.isBlank()) return
        scope.launch {
            // 同一时刻只显示一条：新提示直接顶掉旧的，避免排队等好几秒。
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(
                AppSnackbarVisuals(message = message, duration = duration, tone = tone)
            )
        }
    }
}

/** 携带语义色调的 Snackbar 视觉参数；渲染端用 `as? AppSnackbarVisuals` 取回 tone。 */
internal class AppSnackbarVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    val tone: StatusTone = StatusTone.NEUTRAL
) : SnackbarVisuals

/** 默认实现什么都不做，方便预览/测试里不必提供宿主。 */
internal val LocalAppSnackbar = staticCompositionLocalOf {
    AppSnackbar(SnackbarHostState(), CoroutineScope(kotlinx.coroutines.SupervisorJob()))
}

@Composable
internal fun rememberAppSnackbar(): AppSnackbar {
    val hostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    return remember(hostState, scope) { AppSnackbar(hostState, scope) }
}
