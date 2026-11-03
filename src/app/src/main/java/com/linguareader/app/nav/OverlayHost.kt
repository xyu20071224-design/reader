package com.linguareader.app.nav

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.InkSoft
import com.linguareader.app.Paper
import com.linguareader.app.R
import com.linguareader.shared.ui.state.Overlay
import com.linguareader.shared.ui.state.OverlayKind
import com.linguareader.shared.ui.state.OverlayStack

/**
 * 弹层宿主的三个动作。抽成对象是为了让弹层渲染函数不必收三个 lambda。
 *
 * @param push 叠一层（从存储页钻进资源包：关掉资源包回到存储页）。
 * @param replace 原地换页（AI 中心 → 服务商配置：关掉服务商配置回到 AI 中心）。
 * @param dismiss 关闭栈顶。
 */
class OverlayHost internal constructor(
    private val pushImpl: (Overlay) -> Unit,
    private val replaceImpl: (Overlay) -> Unit,
    private val dismissImpl: () -> Unit
) {
    fun push(overlay: Overlay) = pushImpl(overlay)
    fun replace(overlay: Overlay) = replaceImpl(overlay)
    fun dismiss() = dismissImpl()
}

/**
 * 一个已打开弹层的内容。
 *
 * @param overlay 栈顶弹层本身（渲染端按变体取参数，如 bookId）。
 * @param host 弹层宿主：需要叠层/换页的弹层用它改栈，而不是自己去改状态。
 * @param dismiss 关闭栈顶；`onDismissRequest`（下滑/点遮罩）走这里。
 */
class OverlaySlot(
    val overlay: Overlay,
    val host: OverlayHost,
    val dismiss: () -> Unit
)

/**
 * 弹层注册表：种类 → 渲染。与 [ScreenRegistry] 同一思路，但**不强制穷举**：
 * 弹层的类型会随功能增长，强制每加一个变体就让所有迁移提交改这张表并不划算。
 * 未注册的种类渲染 [FallbackOverlay]（诊断占位），P3 逐项填充。
 */
class OverlayRegistry internal constructor(
    private val renderers: Map<OverlayKind, @Composable (OverlaySlot) -> Unit>
) {

    operator fun get(kind: OverlayKind): (@Composable (OverlaySlot) -> Unit)? = renderers[kind]

    fun registered(): Set<OverlayKind> = renderers.keys

    /** 给定种类里还没注册的那些。 */
    fun missing(kinds: Collection<OverlayKind>): List<OverlayKind> = kinds.filter { it !in renderers }

    companion object {
        val EMPTY = OverlayRegistry(emptyMap())
    }
}

/** [OverlayRegistry] 的构建器（理由同 [ScreenRegistryBuilder]：lambda 需要期望类型）。 */
class OverlayRegistryBuilder internal constructor() {

    private val renderers = LinkedHashMap<OverlayKind, @Composable (OverlaySlot) -> Unit>()

    /** 注册一种弹层的渲染。 */
    fun overlay(kind: OverlayKind, content: @Composable (OverlaySlot) -> Unit) {
        renderers[kind] = content
    }

    internal fun build(): OverlayRegistry = OverlayRegistry(renderers.toMap())
}

fun overlayRegistry(block: OverlayRegistryBuilder.() -> Unit): OverlayRegistry =
    OverlayRegistryBuilder().apply(block).build()

/**
 * 统一弹层宿主：**只渲染栈顶那一个**，按种类查 [OverlayRegistry]。
 *
 * 取代现状的散装写法（`BookshelfScreen.kt:236-248` 的 8 个 `showXxx` 布尔 +
 * 23 个各自 `if (showXxx) ModalBottomSheet(...)`）：那些布尔彼此不知道对方存在，
 * 所以能同时开两个 sheet；而且除了生词本以外没有任何返回键处理。
 *
 * 返回键**不在这里**接：统一由 [AppBackDispatcher] 处理，避免「弹层和导航页各抢一次」
 * （现状 `ReaderScreen.kt:459` 与 `BookshelfScreen.kt:235` 就是各写各的）。
 */
@Composable
fun OverlayHost(
    stack: OverlayStack,
    registry: OverlayRegistry,
    modifier: Modifier = Modifier,
    onPush: (Overlay) -> Unit = {},
    onReplace: (Overlay) -> Unit = {},
    onDismissTop: () -> Unit = {}
) {
    val host = rememberOverlayHost(onPush, onReplace, onDismissTop)
    val top = stack.top ?: return
    val renderer = registry[top.kind]
    Box(modifier) {
        if (renderer != null) {
            renderer(OverlaySlot(top, host, host::dismiss))
        } else {
            FallbackOverlay(top, host::dismiss)
        }
    }
}

/** 记住弹层宿主；lambda 用 [rememberUpdatedState] 跟着最新组合走，引用保持稳定。 */
@Composable
fun rememberOverlayHost(
    onPush: (Overlay) -> Unit,
    onReplace: (Overlay) -> Unit,
    onDismissTop: () -> Unit
): OverlayHost {
    val push by rememberUpdatedState(onPush)
    val replace by rememberUpdatedState(onReplace)
    val dismiss by rememberUpdatedState(onDismissTop)
    return remember { OverlayHost({ push(it) }, { replace(it) }, { dismiss() }) }
}

/**
 * 未注册弹层的占位。用 sheet 形态渲染（而不是什么都不画）：
 * 「点了入口却毫无反应」比「看到一个占位」更糟，也更难排查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FallbackOverlay(overlay: Overlay, onDismiss: () -> Unit) {
    // 与 MissingScreen 同理：真机上要能从 logcat 看出是哪种弹层没接进来。
    LaunchedEffect(overlay) {
        Log.w(TAG, "未注册的弹层：" + overlay.kind.name + " → " + overlay)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = stringResource(R.string.overlay_placeholder_title, overlay.kind.name),
                style = MaterialTheme.typography.titleSmall,
                color = InkSoft
            )
            Text(
                text = stringResource(R.string.overlay_placeholder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
