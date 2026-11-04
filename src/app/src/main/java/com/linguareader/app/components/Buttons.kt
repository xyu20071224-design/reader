package com.linguareader.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 主按钮：页面上的**唯一**主动作（导入、保存、开始复习）。
 *
 * 替换现有 121 个 `TextButton` 里承担主操作的那部分 —— 那些实现没有最小高度、
 * 长文案换行后高度会跳，且选中态/禁用态颜色各写一遍。
 *
 * 一屏只放一个主按钮：主次不分时用户会犹豫，这也是把「取消」做成 [SecondaryButton]
 * 而不是主按钮的原因。
 *
 * 硬约束：样式只来自 [LocalDesignTokens]，不接受 `color`/`shape` 参数。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val t = LocalDesignTokens.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = t.controlHeight)
            .semantics { role = Role.Button },
        shape = t.pillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = t.colors.accent,
            contentColor = t.colors.onAccent,
            disabledContainerColor = t.colors.paperDeep,
            disabledContentColor = t.colors.inkFaint
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = t.spacing.l,
            vertical = t.spacing.s
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(t.iconSize))
            androidx.compose.foundation.layout.Spacer(
                Modifier.size(t.spacing.s)
            )
        }
        Text(text = text, style = t.typography.body)
    }
}

/**
 * 次按钮：与主按钮并排的次要动作（取消、稍后、重置）。
 *
 * 描边样式而不是实心：实心会和主按钮抢注意力。
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val t = LocalDesignTokens.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minHeight = t.controlHeight)
            .semantics { role = Role.Button },
        shape = t.pillShape,
        border = BorderStroke(t.hairline, if (enabled) t.colors.outline else t.colors.paperDeep),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = t.colors.ink,
            disabledContentColor = t.colors.inkFaint
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = t.spacing.l,
            vertical = t.spacing.s
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(t.iconSize))
            androidx.compose.foundation.layout.Spacer(Modifier.size(t.spacing.s))
        }
        Text(text = text, style = t.typography.body)
    }
}

/**
 * 文字按钮：低优先级动作（「了解更多」「查看全部」）。
 *
 * 刻意不加底色与描边 —— 现有代码里最常被滥用的就是这个：把「删除」做成 TextButton，
 * 用户看不出它是危险操作。危险动作用 [tone] = [TextButtonTone.DANGER] 或二次确认。
 *
 * @param icon 前置图标；传入即为「图标 + 文字」形态（[LinguaIconTextButton] 是它的
 *   显式别名，语义相同，两者取其一即可——**新增代码用 [LinguaIconTextButton]**）
 * @param tone 动作的语义强度。默认 [TextButtonTone.DEFAULT]（accent）。
 *   这是**动作语义**（这个操作是什么性质），不是样式选择，因此不算破坏
 *   「样式只来自 token」的约定。
 * @param layout 按钮的**布局角色**，默认 [TextButtonLayout.PAGE]。
 *   与 [tone] 同理：它表达的是「这个按钮处在什么布局位置」，不是自由样式。
 *   详见 [TextButtonLayout] 的说明与那条实证。
 */
@Composable
fun LinguaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tone: TextButtonTone = TextButtonTone.DEFAULT,
    layout: TextButtonLayout = TextButtonLayout.PAGE
) {
    val t = LocalDesignTokens.current
    val contentColor = when {
        !enabled -> t.colors.inkFaint
        else -> when (tone) {
            TextButtonTone.DEFAULT -> t.colors.accent
            TextButtonTone.DANGER -> t.colors.danger
            TextButtonTone.SUCCESS -> t.colors.success
        }
    }
    // 两种布局角色的几何差异是这份组件唯一按调用场景分叉的地方：
    // PAGE 有 48dp 最小高度与 16dp 横向留白（可点性与视觉呼吸）；
    // INLINE 两者都收紧，好让多个按钮并排时不吃掉相邻内容的宽度。
    val minHeight = if (layout == TextButtonLayout.PAGE) t.controlHeight else 0.dp
    val hPad = if (layout == TextButtonLayout.PAGE) t.spacing.m else t.spacing.s
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.then(
            if (layout == TextButtonLayout.PAGE) Modifier.defaultMinSize(minHeight = minHeight)
            else Modifier
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = hPad,
            vertical = t.spacing.xs
        )
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(t.iconSize)
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(t.spacing.xs))
        }
        Text(text = text, style = t.typography.body, color = contentColor)
    }
}

/**
 * 文字按钮的动作语义强度。
 *
 * 为什么需要它：设计系统原本只有一种文字按钮颜色（accent），于是「删除」「确认完成」
 * 这类动作只能靠调用方自己包一层颜色 —— 复习屏、生词本、听书屏各自封装了一遍。
 * 这个枚举把那件事收进组件层，同时**仍然是枚举而非 Color 参数**：
 * 调用方表达的是「这是什么性质的动作」，具体颜色由 token 决定。
 */
enum class TextButtonTone {
    /** 常规动作（accent）。 */
    DEFAULT,

    /** 危险/不可撤销（danger）。删除、清空、卸载。 */
    DANGER,

    /** 完成/通过（success）。「知道了」这类正向确认。 */
    SUCCESS
}

/**
 * 文字按钮的**布局角色**。
 *
 * ## 为什么需要它（有实证，不是预防性设计）
 *
 * 2026-11-04，生词本顶栏三个图标按钮从本地紧凑封装换成本组件的 PAGE 形态，
 * **视觉基线立刻红了三条**（list 差异 19.2%）：标题「My words」被压成三行竖排。
 * 原因是 PAGE 形态的 `defaultMinSize(minHeight = 48dp)` 与
 * `contentPadding(horizontal = spacing.m)` 在**窄横向槽位**里吃掉了
 * `weight(1f)` 标题列的宽度。
 *
 * 当时的处置是撤销改动、保留本地封装。但真正的问题是**组件缺一个布局变体** ——
 * 于是每个屏遇到同一个槽位都会重新写一遍紧凑封装（生词本、AiProviderSettings 都写过）。
 *
 * ## 两个取值的含义
 *
 * - [PAGE]：页面上的动作（默认）。48dp 最小高度保证可点性，横向 16dp 留白保证呼吸。
 * - [INLINE]：挤在**行内/顶栏/表格行**里的动作。去掉最小高度、横向留白减半，
 *   代价是可点目标变小 —— 只在「多个按钮并排且有相邻内容要争宽度」时用。
 *
 * **这不是样式参数**：调用方表达的是「这个按钮处在什么布局位置」，
 * 具体几何仍由 token 决定。（与 [TextButtonTone] 同一设计原则。）
 */
enum class TextButtonLayout {
    /** 页面级动作：48dp 最小高度 + 横向 spacing.m。默认值。 */
    PAGE,

    /** 行内/顶栏动作：无最小高度 + 横向 spacing.s。多个并排时用。 */
    INLINE
}

/**
 * 图标 + 文字按钮。
 *
 * **存在理由**：图标与文字的间距、图标尺寸、按下态三处，复习屏 / 生词本 / 听书屏
 * 各自封装了一遍（三份实现，尺寸 18/20/24 各不相同）。这个组件把那些收敛到一处。
 *
 * 与 [LinguaTextButton] 带 [LinguaTextButton.icon] 的形态等价 —— 保留两个名字是因为
 * 调用点读起来更清楚：有图标的写 [LinguaIconTextButton]，纯文字写 [LinguaTextButton]。
 */
@Composable
fun LinguaIconTextButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: TextButtonTone = TextButtonTone.DEFAULT,
    layout: TextButtonLayout = TextButtonLayout.PAGE
) {
    LinguaTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        tone = tone,
        layout = layout
    )
}

/** 占满整行的主按钮（空状态引导、表单提交）。 */
@Composable
fun PrimaryButtonFullWidth(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    PrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        icon = icon
    )
}