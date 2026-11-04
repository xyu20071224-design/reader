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

