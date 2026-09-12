package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.app.res.resolve
import com.linguareader.shared.data.ReaderTheme

/**
 * 阅读主题选择器（7 项）。
 *
 * 从 `ReaderScreen` 的设置面板里抽出来，供**书架「书架外观」弹层**复用（第四轮审查 5-10：
 * 主题入口原本只在阅读页、约 4 次点击；书架处此前没有入口）。
 *
 * 每个选项是「色块 + 标签」整列可点 —— 真机实测过只让色块可点会「点文字落空且易误触相邻项」，
 * 所以 `clickable` 必须放在外层容器上。
 */
@Composable
internal fun ReaderThemePicker(
    selected: ReaderTheme,
    onSelect: (ReaderTheme) -> Unit
) {
    // 7 个主题一行放不下（7×48 + 6×18 = 444dp），用两行排布：4 + 3。
    ReaderTheme.entries.chunked(4).forEach { rowThemes ->
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
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
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ReaderThemeSwatch(
    theme: ReaderTheme,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(56.dp).clickable(onClick = onSelect)
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(theme.background)))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) Accent else Ink.copy(alpha = .18f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(theme.foreground)),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(theme.labelRes.resolve()),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Accent else InkSoft,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
