package com.linguareader.app.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 卡片容器：纸色之上的一层表面，靠**描边 + 色阶**分层，不靠投影。
 *
 * 为什么不用裸 Material `Card`：它的默认 elevation 在深色主题下会画出灰雾，
 * 而这套纸感设计在纯黑（AMOLED）上必须干净。层级由 `SkinElevation.useShadow` 决定，
 * 默认 false → 走描边。
 *
 * 硬约束：样式只来自 [LocalDesignTokens]，不接受 `color`/`shape` 参数。
 */
@Composable
fun LinguaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalDesignTokens.current
    val cardColors = CardDefaults.cardColors(
        containerColor = t.colors.cardSurface,
        contentColor = t.colors.ink
    )
    val cardElevation = CardDefaults.cardElevation(defaultElevation = t.elevation.card)
    // 不用阴影时必须给描边，否则卡片与纸底之间没有边界（层级感全靠这一圈）。
    val border = if (t.elevation.useShadow) null else BorderStroke(t.hairline, t.colors.outline)
    val inner: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth().padding(t.spacing.cardPadding),
            content = content
        )
    }
    if (onClick == null) {
        Card(
            modifier = modifier,
            shape = t.cardShape,
            colors = cardColors,
            elevation = cardElevation,
            border = border
        ) { inner() }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier.semantics { role = Role.Button },
            shape = t.cardShape,
            colors = cardColors,
            elevation = cardElevation,
            border = border
        ) { inner() }
    }
}

