package com.linguareader.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
 *
 * @param title 标题（顶栏左侧，返回箭头之后）
 * @param onBack 传 null 表示没有返回动作（根屏幕）；传入则渲染返回箭头
 * @param actions 顶栏右侧的动作区，横向排列
 */
@Composable
fun LinguaScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalDesignTokens.current
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = t.colors.paper,
        contentColor = t.colors.ink
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                modifier = Modifier.fillMaxWidth().weight(1f),
                content = content
            )
        }
    }
}

/**
 * 顶栏动作图标按钮。给 [LinguaScaffold] 的 `actions` 槽用，保证尺寸/颜色一致。
 */
@Composable
fun LinguaTopBarAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val t = LocalDesignTokens.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(t.iconSize)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) t.colors.ink else t.colors.inkFaint,
            modifier = Modifier.size(t.iconSize)
        )
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
