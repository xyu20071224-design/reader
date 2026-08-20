package com.linguareader.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
 */
internal class AppSnackbar(
    val hostState: SnackbarHostState,
    private val scope: CoroutineScope
) {
    fun show(message: String, duration: SnackbarDuration = SnackbarDuration.Short) {
        if (message.isBlank()) return
        scope.launch {
            // 同一时刻只显示一条：新提示直接顶掉旧的，避免排队等好几秒。
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(message = message, duration = duration)
        }
    }
}

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
