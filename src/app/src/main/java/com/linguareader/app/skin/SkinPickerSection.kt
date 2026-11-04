package com.linguareader.app.skin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.linguareader.app.R
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SecondaryButton
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinOrigin
import com.linguareader.shared.ui.skin.SkinSpec

/**
 * **皮肤选择区**（P9：让用户真正能换皮）。
 *
 * ## 为什么单独一个组件
 *
 * 皮肤系统在本次改动前是「建好但不可达」的：`SkinStore` / `ImageSkinImporter` 是新补的，
 * 而在此之前连入口都没有。把它做成独立可渲染的内容体（而不是塞进 `ShelfAppearanceContent`）
 * 有两个好处：
 * 1. 它能单独出图、单独做视觉基线（弹层本体是 `ModalBottomSheet`，画不进位图）；
 * 2. 将来如果要在「设置」主页面也放一份，不用复制。
 *
 * ## 不做的事
 *
 * 不在这里做图片解码（那是 [ImageSkinImporter] 的活），也不做持久化（[SkinStore]）。
 * 本组件**只收数据与回调**，是纯展示的 —— 这样它才能进渲染台。
 */
@Composable
internal fun SkinPickerSection(
    builtIns: List<SkinSpec> = SkinBuiltIns.all,
    selectedId: String,
    customSkin: SkinSpec?,
    onSelectBuiltIn: (String) -> Unit,
    onPickImage: () -> Unit,
    onRemoveCustom: () -> Unit
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.m)
    ) {
        SectionHeader(text = stringResource(R.string.skin_section_title))

        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.m)) {
            builtIns.forEach { skin ->
                SkinSwatch(
                    skin = skin,
                    selected = skin.id == selectedId,
                    onClick = { onSelectBuiltIn(skin.id) }
                )
            }
            // 自定义皮肤始终占一个位置：没有时画成「+」，有时画成它的真实配色。
            // 固定占位而不是条件插入，避免用户选图后整排色块突然重排。
            SkinSwatch(
                skin = customSkin,
                selected = customSkin != null && customSkin.id == selectedId,
                onClick = onPickImage
            )
        }

        Text(
            text = if (customSkin != null) {
                stringResource(R.string.skin_custom_active, customSkin.name)
            } else {
                stringResource(R.string.skin_custom_hint)
            },
            style = t.typography.caption,
            color = t.colors.inkSoft
        )

        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.m)) {
            SecondaryButton(text = stringResource(R.string.skin_pick_image), onClick = onPickImage)
            if (customSkin != null) {
                SecondaryButton(
                    text = stringResource(R.string.skin_remove_custom),
                    onClick = onRemoveCustom
                )
            }
        }
    }
}

/**
 * 一个皮肤色块：外圈是纸色、内圈是墨色与强调色，让用户一眼看出「这套皮肤长什么样」。
 *
 * `skin == null` 表示「还没有自定义皮肤」，画成虚线感的中性占位 + 「+」。
 */
@Composable
private fun SkinSwatch(
    skin: SkinSpec?,
    selected: Boolean,
    onClick: () -> Unit
) {
    val t = LocalDesignTokens.current
    val ring = if (selected) t.colors.accent else t.colors.inkFaint.copy(alpha = 0.35f)
    val paper = skin?.let { Color(it.colors.paper) } ?: t.colors.paperDeep
    val ink = skin?.let { Color(it.colors.ink) } ?: t.colors.inkFaint
    val accent = skin?.let { Color(it.colors.accent) } ?: t.colors.inkFaint.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .size(t.spacing.xxl)
            .clip(CircleShape)
            .background(paper)
            .border(width = if (selected) t.hairline * 2 else t.hairline, color = ring, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(t.spacing.m)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(ink, accent)))
        )
    }
}

/** 是否为「图片生成的皮肤」（设置页据此显示来源说明）。 */
internal fun SkinSpec.isFromImage(): Boolean = origin == SkinOrigin.IMAGE