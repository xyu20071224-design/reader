package com.linguareader.app.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 设置行：标题 + 副标题 + 尾部插槽（Switch / 值文本 / 箭头）。
 *
 * 替换现有 7 处手搓的开关行 —— 那些实现的行高、副标题颜色、开关选中色
 * （有的用 Material 默认紫、有的用 accent）都不一致。
 *
 * 硬约束：样式只来自 [LocalDesignTokens]。
 *
 * @param checked 传入即渲染 Switch；null 则只用 [trailing] 自定义尾部
 * @param onCheckedChange 与 [checked] 配对使用
 */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val t = LocalDesignTokens.current
    val rowModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = t.controlHeight)
        .then(
            if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick)
            else Modifier
        )
        .padding(vertical = t.spacing.xs)

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.m)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(t.spacing.xs)
        ) {
            Text(text = title, style = t.typography.body, color = t.colors.ink)
            if (subtitle != null) {
                Text(text = subtitle, style = t.typography.caption, color = t.colors.inkSoft)
            }
        }
        when {
            checked != null && onCheckedChange != null -> Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = t.colors.onAccent,
                    checkedTrackColor = t.colors.accent,
                    checkedBorderColor = t.colors.accent,
                    uncheckedThumbColor = t.colors.inkSoft,
                    uncheckedTrackColor = t.colors.paperDeep,
                    uncheckedBorderColor = t.colors.outline
                )
            )
            trailing != null -> trailing()
        }
    }
}

/**
 * 设置分组：一组 [SettingRow] 外面套一张 [LinguaCard]，行间自动加分割线。
 *
 * 这样「组与组之间的间距、组内行距」在整屏范围内只定义一次。
 */
@Composable
fun SettingGroup(
    title: String? = null,
    content: @Composable () -> Unit
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = t.typography.label,
                color = t.colors.inkSoft,
                modifier = Modifier.padding(start = t.spacing.s)
            )
        }
        LinguaCard { content() }
    }
}

/** 尾部「当前值 + 箭头」那种设置的纯文本值。 */
@Composable
fun SettingValue(text: String) {
    val t = LocalDesignTokens.current
    Text(text = text, style = t.typography.caption, color = t.colors.inkSoft)
}
