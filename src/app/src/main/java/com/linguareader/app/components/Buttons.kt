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
 * 用户看不出它是危险操作。危险动作用 [StatusChipTone.DANGER] 语义的按钮或二次确认。
 */
@Composable
fun LinguaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val t = LocalDesignTokens.current
    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = t.controlHeight),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = t.spacing.m,
            vertical = t.spacing.xs
        )
    ) {
        Text(
            text = text,
            style = t.typography.body,
            color = if (enabled) t.colors.accent else t.colors.inkFaint
        )
    }
}

/** 并排按钮行：主按钮右、次按钮左（与系统对话框的按钮顺序一致）。 */
@Composable
fun ButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(t.spacing.s, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content
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
