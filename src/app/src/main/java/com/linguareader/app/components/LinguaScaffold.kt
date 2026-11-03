package com.linguareader.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.linguareader.app.R
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 统一的屏幕外壳：顶栏（返回箭头 + 标题 + 尾部动作）+ 内容区。
 *
 * 替换现有代码里每屏各搭一遍的顶栏 —— 那些实现的行高、图标尺寸、返回箭头语义
 * （`Icons.Filled.ArrowBack` 与 `AutoMirrored` 混用）都不一致，RTL 语言下还会镜像反。
 *
 * 硬约束：样式只来自 [LocalDesignTokens]，不接受 `color`/`shape` 参数。
 * 唯一的例外是 [containerColor]（见其注释）：它表达的是「这一屏要不要给外壳着色」，
 * 属于屏幕级事实，而不是「用什么颜色」——默认值仍是 token。
 *
 * ## 窗口 insets（别删！）
 *
 * 本组件从 M3 `Scaffold` 手里接过外壳职责时，必须**一并接过它免费提供的 insets**：
 * 项目 `targetSdk = 35`，Android 15+ 强制 edge-to-edge，没有 insets 的后果是
 * 顶栏顶进状态栏、列表最后一行伸到手势条底下。
 *
 * **渲染台与单测都发现不了**：Robolectric 里 `WindowInsets` 恒为 0，出图永远「正常」。
 * 因此这条约束由 `ScaffoldInsetsContractTest` 读源码守着，真机验收仍需单独做。
 * 刻意**不用** `systemBarsPadding`：它会连左右 insets 一起吃，横屏/折叠屏下
 * 与 `ReaderScreen` 的既有写法（L592 statusBars / L670 navigationBars）不一致。
 *
 * @param title 标题（顶栏左侧，返回箭头之后）
 * @param onBack 传 null 表示没有返回动作（根屏幕）；传入则渲染返回箭头
 * @param actions 顶栏右侧的动作区，横向排列
 * @param containerColor 外壳底色；null（默认）= token 的 paper。只有「这一屏要露出
 *   自己的自定义背景」（书架的外观设置就是这种）时才传 `Color.Transparent`。
 *   如果从外部拿不到透明化能力，那一屏只能复制一份外壳出来——那正是本组件要消灭的东西。
 */
@Composable
fun LinguaScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalDesignTokens.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = containerColor ?: t.colors.paper,
        contentColor = t.colors.ink
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = if (onBack == null) t.spacing.gutter else t.spacing.xs,
                        end = t.spacing.s,
                        top = t.spacing.s,
                        bottom = t.spacing.s
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(t.iconSize)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            // 返回箭头是图标不是文字，用 inkSoft 与标题拉开层级
                            contentDescription = stringResource(R.string.reader_back),
                            tint = t.colors.inkSoft,
                            modifier = Modifier.size(t.iconSize)
                        )
                    }
                    Spacer(Modifier.size(t.spacing.xs))
                }
                Text(
                    text = title,
                    style = t.typography.title,
                    color = t.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(t.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                content = content
            )
        }
    }
}

/**
 * 顶栏动作图标按钮。给 [LinguaScaffold] 的 `actions` 槽用，保证尺寸/颜色一致。
 *
 * 为什么图标尺寸**不收参数**：现有代码里同一排顶栏动作在 20/22/24 之间随机，
 * 排在一起肉眼可见地不齐，那正是本组件要解决的问题。把尺寸变成可选参数，
 * 等于允许每个调用点把这个缺陷重新写回来。
 *
 * @param tint 激活态颜色；null（默认）= 未激活，走 ink / inkFaint 的常规层级。
 *   「当前生效」与「未生效」是**状态**，不是样式选择，所以这里收 Color 而不是收枚举——
 *   但调用方只应传 token 里的颜色（accent 等），不得自造色值。
 * @param badge true 时在图标右上角画一个提示圆点（有新版本 / 有新内容）。
 *   尺寸取 `spacing.s`，不新造 dp 常量。
 * @param modifier 测试/布局用（如 testTag）。
 */
@Composable
fun LinguaTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color? = null,
    badge: Boolean = false
) {
    val t = LocalDesignTokens.current
    val resolvedTint = tint ?: if (enabled) t.colors.ink else t.colors.inkFaint
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(t.iconSize)
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = resolvedTint,
                modifier = Modifier.size(t.iconSize)
            )
            if (badge) {
                // 圆点只是视觉提示：真正给读屏用户的信息必须写在 contentDescription 里，
                // 否则「只画红点」等于对无障碍用户静默（调用方责任，见 @param badge 说明）。
                Box(
                    Modifier
                        .size(t.spacing.s)
                        .background(t.colors.danger, CircleShape)
                )
            }
        }
    }
}

/** 顶栏下的细分割线（现有 `HorizontalDivider` 的 token 版）。 */
@Composable
fun LinguaDivider(modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Box(
        modifier
            .fillMaxWidth()
            .height(t.hairline)
    ) {
        Surface(color = t.colors.outline, modifier = Modifier.fillMaxSize(), shape = CircleShape) {}
    }
}
