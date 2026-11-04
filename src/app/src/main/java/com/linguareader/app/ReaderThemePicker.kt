package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.app.res.resolve
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.data.ReaderTheme

/**
 * 阅读主题选择器（7 项）。
 *
 * 从 `ReaderScreen` 的设置面板里抽出来，供**书架「书架外观」弹层**复用（第四轮审查 5-10：
 * 主题入口原本只在阅读页、约 4 次点击；书架处此前没有入口）。
 *
 * 每个选项是「色块 + 标签」整列可点 —— 真机实测过只让色块可点会「点文字落空且易误触相邻项」，
 * 所以 `clickable` 必须放在外层容器上。
 *
 * ## 配色来源：色块跟 ReaderTheme，选中/文字跟 token
 *
 * 这是本项目那条判断标准的一个特例（见 `ListeningBar` / `ReaderLookupSheet` 的注释）：
 * - **色块本体的颜色必须来自 [ReaderTheme]**（`theme.background` / `theme.foreground`）——
 *   它预览的就是「正文长什么样」，换 token 会让 7 个色块全变成一个颜色，选择器就没意义了；
 * - **选中边框、未选中边框、标签文字跟 `LocalDesignTokens`** —— 它们表达的是「当前状态」，
 *   属于外壳观感，应当跟随皮肤。
 *
 * ## 几何值为什么仍用常量
 *
 * 48dp 色块 / 56dp 列宽 / 18dp 列距是按「7 项一行放不下、拆成 4+3」算出来的整体比例：
 * 7×48 + 6×18 = 444dp > 411dp 常见屏宽。**改任何一个都会破坏“刚好放不下”这个前提**，
 * 所以它们集中在这里命名，而不是套间距梯（间距梯没有 18/56/48 这几个档位，强套会改像素）。
 */
@Composable
internal fun ReaderThemePicker(
    selected: ReaderTheme,
    onSelect: (ReaderTheme) -> Unit
) {
    // 7 个主题一行放不下（7×48 + 6×18 = 444dp），用两行排布：4 + 3。
    ReaderTheme.entries.chunked(4).forEach { rowThemes ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(ThemeSwatchGap),
            verticalAlignment = Alignment.Top
        ) {
            rowThemes.forEach { theme ->
                ReaderThemeSwatch(
                    theme = theme,
                    selected = theme == selected,
                    onSelect = { onSelect(theme) }
                )
            }
        }
        Spacer(Modifier.height(SpacingAfterRow))
    }
}

@Composable
private fun ReaderThemeSwatch(
    theme: ReaderTheme,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val t = LocalDesignTokens.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(SwatchColumnWidth).clickable(onClick = onSelect)
    ) {
        Box(
            Modifier
                .size(SwatchSize)
                .clip(CircleShape)
                // 色块本体：必须来自 ReaderTheme（预览正文配色），不能换 token。
                .background(Color(android.graphics.Color.parseColor(theme.background)))
                .border(
                    width = if (selected) SelectedRingWidth else UnselectedRingWidth,
                    color = if (selected) t.colors.accent else t.colors.ink.copy(alpha = RingAlpha),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    // 对勾颜色跟色块自身的前景色（与主题同源），否则深色主题上会看不清。
                    tint = Color(android.graphics.Color.parseColor(theme.foreground)),
                    modifier = Modifier.size(CheckIconSize)
                )
            }
        }
        Spacer(Modifier.height(SpacingBeforeLabel))
        Text(
            stringResource(theme.labelRes.resolve()),
            style = t.typography.caption,
            color = if (selected) t.colors.accent else t.colors.inkSoft,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

// ── 几何常量 ────────────────────────────────────────────────
//
// 这组数值是「7 项 4+3 分两行放得下」这个整体比例的一部分，间距梯没有对应档位：
// 7×48 + 6×18 = 444dp，换任何一档都会破坏「刚好放不下」的前提（见类 KDoc）。

/** 色块直径。48dp 是 Material 的最小可点目标下限。 */
private val SwatchSize = 48.dp

/** 整列宽（色块 + 左右余量），要能容下最长主题名。 */
private val SwatchColumnWidth = 56.dp

/** 同行色块间距。 */
private val ThemeSwatchGap = 18.dp

/** 两行之间的间距。 */
private val SpacingAfterRow = 8.dp

/** 标签与色块之间的间距。 */
private val SpacingBeforeLabel = 6.dp

/** 选中态描边宽度。 */
private val SelectedRingWidth = 3.dp

/** 未选中态描边宽度。 */
private val UnselectedRingWidth = 1.dp

/** 未选中态描边的不透明度（压暗一档，避免和选中态抢注意力）。 */
private const val RingAlpha = .18f

/** 选中对勾的图标尺寸。 */
private val CheckIconSize = 22.dp