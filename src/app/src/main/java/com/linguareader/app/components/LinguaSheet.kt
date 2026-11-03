package com.linguareader.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 统一弹层：ModalBottomSheet + 纸色容器 + 顶部拖柄 + 可选标题。
 *
 * 替换现有 14 处 `ModalBottomSheet(onDismissRequest = ..., containerColor = Paper)` ——
 * 那些调用点各写一遍内边距、标题字重、底部留白，深浅色下的圆角还各不相同。
 *
 * 拖柄由组件提供，调用方不需要也不能自己画：它是**可发现性**的一部分
 * （用户得知道能往下拖关掉）。
 *
 * 硬约束：样式只来自 [LocalDesignTokens]。
 *
 * @param title 面板标题；null 表示无标题面板（内容自己顶格）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinguaSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    /**
     * 内容区块之间的间距。
     *
     * 默认给 `spacedBy(spacing.m)`：14 处弹层的内容几乎都是纵向堆叠块，原实现各自都写了
     * `spacedBy(16.dp)`；封装后若还要每处写一遍，这层就没封到位（Lead 裁决：改）。
     *
     * 传 null 用默认值——**不能用 `t.spacing.m` 做默认参数**，因为 `t` 要靠
     * `LocalDesignTokens.current` 取，在参数默认值处拿不到 CompositionLocal。
     * 内容自带完整布局的弹层可以传 `Arrangement.Top` 关掉间距。
     */
    contentArrangement: Arrangement.Vertical? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val t = LocalDesignTokens.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = t.colors.paper,
        contentColor = t.colors.ink,
        shape = t.sheetShape,
        dragHandle = { SheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = t.spacing.l)
                .padding(bottom = t.spacing.xl),
            verticalArrangement = contentArrangement ?: Arrangement.spacedBy(t.spacing.m)
        ) {
            if (title != null) {
                Text(text = title, style = t.typography.title, color = t.colors.ink)
                Box(Modifier.height(t.spacing.m))
            }
            content()
        }
    }
}

/** 面板拖柄：一根短圆角横条。抽出来是为了让「能拖」这件事在每处弹层里长得一样。 */
@Composable
fun SheetDragHandle() {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = t.spacing.s),
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .width(t.spacing.xxl)
                .height(t.spacing.xs)
                .background(t.colors.outline, RoundedCornerShape(t.radii.pill))
        )
    }
}

/** 面板内的一条分割线（比 `LinguaDivider` 更淡，用于行与行之间）。 */
@Composable
fun SheetDivider() {
    val t = LocalDesignTokens.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(t.hairline)
            .background(t.colors.outline)
    )
}
