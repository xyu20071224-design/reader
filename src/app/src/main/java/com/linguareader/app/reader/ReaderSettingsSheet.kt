package com.linguareader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.linguareader.app.Accent
import com.linguareader.app.Ink
import com.linguareader.app.InkSoft
import com.linguareader.app.R
import com.linguareader.app.UiTags
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.SettingRow
import com.linguareader.app.components.SheetDivider
import com.linguareader.app.data.ReaderFont
import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.ReaderTheme
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.res.resolve
import com.linguareader.app.skin.LocalDesignTokens
import kotlin.math.roundToInt

/**
 * 阅读设置弹层（字号 / 行距 / 阅读主题 / 字体 + 两个二级入口）。
 *
 * ## 职责
 *
 * 把用户的排版偏好写回 `ReaderPreferences`。**没有「确定」按钮**：拖动 Slider 即写盘，
 * 由调用方的 [onChange] 落到 SharedPreferences 并重排正文 —— 这是既有交互，不是遗漏。
 *
 * ## 依赖的外部状态
 *
 * - [preferences]：当前排版偏好（字号百分比 / 行距倍数 / 主题 / 字体）；
 * - [reviewPace]：**只读**，用于在「复习节奏」那一行显示当前档位名；
 * - 四个回调：偏好变更、两个二级入口、关闭。
 *
 * 不读 `ReaderOverlays`、不碰 `ReaderController`、不参与 WebView 协议与 TTS 高亮。
 * 真正改变正文的是 `ReaderScreen.persistPreferences()`（写 prefs + `controller.applyPreferences`
 * + 通知外壳换色），那三步**全部留在 `ReaderScreen`**。
 *
 * ## 为什么可以独立
 *
 * 它只做「把用户的选择翻译成一个 `ReaderPreferences` 值」这一件事。外壳换成 [LinguaSheet]
 * 之后，调用点只剩 `if (overlays.settings) { ReaderSettingsSheet(...) }`。
 *
 * @param preferences 当前排版偏好
 * @param reviewPace 当前复习节奏（仅用于显示档位名）
 * @param onOpenReviewSettings 进入「复习节奏」设置
 * @param onOpenListeningSettings 进入「听书设置」
 * @param onChange 偏好变更（拖动即写盘）
 * @param onDismiss 关闭弹层
 */
@Composable
internal fun ReaderSettingsSheet(
    preferences: ReaderPreferences,
    reviewPace: ReviewPace,
    onOpenReviewSettings: () -> Unit,
    onOpenListeningSettings: () -> Unit,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit
) {
    LinguaSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.reader_settings_title),
        contentArrangement = Arrangement.Top
    ) {
        ReaderSettingsBody(
            preferences = preferences,
            reviewPace = reviewPace,
            onOpenReviewSettings = onOpenReviewSettings,
            onOpenListeningSettings = onOpenListeningSettings,
            onChange = onChange
        )
    }
}

/**
 * 阅读设置的**内容体**，与 sheet 外壳解耦（理由同 [ContentsBody]）。
 *
 * 保持 `internal`（不私有化）：它只依赖一个参数对象与回调，
 * 将来若 AI 抽屉或平板双栏要复用同一份设置内容，无需再拆一次。
 *
 * ## ⚠️ 主题色块**刻意不换成 token**（改之前先读这段）
 *
 * 七个色块画的是 `theme.background` / `theme.foreground` —— 它们是**阅读主题自身的预览色**，
 * 表达的是「正文长什么样」，不是「外壳皮肤长什么样」。
 *
 * **如果把它们换成 `t.colors.paper` / `t.colors.ink`，七个色块会全部变成同一个颜色**，
 * 这个选择器就废了。这与 `ListeningBar` 刻意不从 token 取色是同一个理由
 * （皮肤描述外壳、阅读主题描述正文；两者在护眼绿等主题下**必然不同源**）。
 *
 * 同组的 `Accent` / `Ink` / `InkSoft` 也来自外壳调色板而非 token：它们要与色块上的对勾、
 * 与弹层其余部分保持同源。
 *
 * **要推翻这个决定，先回答**：换掉之后用户怎么看出「纸张 / 明亮 / 护眼 / 夜间」的区别？
 * 回答不了就别换。
 *
 * ## 布局约定
 *
 * 横向留白由 [LinguaSheet] 给（`spacing.l`），这里不再自带。
 * 滚动容器 `verticalScroll` **由本组件自己拥有**：这一屏在手机上会超出弹层可视高度，
 * 丢了它真机就拖不动（PacksSheet 当年踩过的坑）。
 */
@Composable
internal fun ReaderSettingsBody(
    preferences: ReaderPreferences,
    reviewPace: ReviewPace,
    onOpenReviewSettings: () -> Unit,
    onOpenListeningSettings: () -> Unit,
    onChange: (ReaderPreferences) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    var fontSize by remember(preferences.fontPercent) {
        mutableFloatStateOf(preferences.fontPercent.toFloat())
    }
    var lineHeight by remember(preferences.lineHeight) { mutableFloatStateOf(preferences.lineHeight) }

    // READER_SETTINGS_SHEET 原本挂在 ModalBottomSheet 上（LinguaSheet 不收 modifier）；
    // 内容体是它的子树，语义查找照样命中。
    // 滚动容器在这里：这一屏在手机上会超出弹层可视高度，丢了它真机就拖不动。
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag(UiTags.READER_SETTINGS_SHEET)
    ) {
        Text(
            text = stringResource(R.string.reader_font_size, fontSize.roundToInt()),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.height(t.spacing.xs))
        Slider(
            value = fontSize,
            onValueChange = {
                fontSize = it
                onChange(preferences.copy(fontPercent = it.roundToInt()))
            },
            valueRange = 80f..150f,
            steps = 6,
            colors = SliderDefaults.colors(
                thumbColor = t.colors.accent,
                activeTrackColor = t.colors.accent,
                inactiveTrackColor = t.colors.paperDeep
            )
        )
        Text(
            text = stringResource(R.string.reader_line_height, "%.1f".format(lineHeight)),
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
        Spacer(Modifier.height(t.spacing.xs))
        Slider(
            value = lineHeight,
            onValueChange = {
                lineHeight = it
                onChange(preferences.copy(lineHeight = it))
            },
            valueRange = 1.2f..2.2f,
            steps = 4,
            colors = SliderDefaults.colors(
                thumbColor = t.colors.accent,
                activeTrackColor = t.colors.accent,
                inactiveTrackColor = t.colors.paperDeep
            )
        )

        SectionHeader(text = stringResource(R.string.reader_theme_label))
        // FlowRow：7 个主题一行放不下（7×48 + 6×18 = 444dp，而面板左右各留
        // spacing.l 后只有约 312dp），普通 Row 会把溢出部分压成堆叠的文字。换行后
        // 全部选项可见——主题是「选一次就很少动」的设置，可发现性优先于省高度。
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(t.spacing.m),
            verticalArrangement = Arrangement.spacedBy(t.spacing.s),
            itemVerticalAlignment = Alignment.Top
        ) {
            ReaderTheme.entries.forEach { theme ->
                val selected = theme == preferences.theme
                // 命中区盖住「色块 + 文字标签」整列：真机曾实测只有色块可点、
                // 点文字落空还容易误触相邻选项，clickable 必须放在外层容器。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable { onChange(preferences.copy(theme = theme)) }
                ) {
                    Box(
                        Modifier
                            .size(t.spacing.xxl)
                            .clip(CircleShape)
                            // 见本组件 KDoc「主题色块刻意不换成 token」：
                            // 这里画的是正文预览色，不是外壳皮肤色。
                            .background(Color(android.graphics.Color.parseColor(theme.background)))
                            .border(
                                // 选中描边加粗一档（原 3dp）——取 token 间距梯的最小档，
                                // 不新造 dp 常量：换肤时间距梯跟着皮肤走。
                                width = if (selected) t.spacing.xs else t.hairline,
                                color = if (selected) Accent else Ink.copy(alpha = .18f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color(android.graphics.Color.parseColor(theme.foreground)),
                                modifier = Modifier.size(t.iconSize)
                            )
                        }
                    }
                    Spacer(Modifier.height(t.spacing.xs))
                    Text(
                        stringResource(theme.labelRes.resolve()),
                        style = t.typography.label,
                        color = if (selected) Accent else InkSoft,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        SectionHeader(text = stringResource(R.string.reader_font_label))
        // 五款字体横向可滚：窄屏放不下，滚动容器是内容体自己带的（不依赖外壳）。
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.xs)
        ) {
            ReaderFont.entries.forEach { font ->
                val fontLabel = stringResource(font.labelRes.resolve())
                val selected = font == preferences.fontFamily
                // 选中标记「●」本来就在 reader_font_selected 这条文案里，
                // 所以这里**不能**再加图标（会变成「● ✓ 字体」双标记）。
                LinguaTextButton(
                    text = if (selected) {
                        stringResource(R.string.reader_font_selected, fontLabel)
                    } else {
                        fontLabel
                    },
                    onClick = { onChange(preferences.copy(fontFamily = font)) }
                )
            }
        }

        Spacer(Modifier.height(t.spacing.s))
        SheetDivider()
        // 整行（文字 + 右侧值/箭头）都是命中区：此前只有箭头按钮可点，
        // 真机两次点文字均无反应。
        SettingRow(
            title = stringResource(R.string.review_pace_section),
            onClick = onOpenReviewSettings,
            trailing = {
                Text(
                    text = "${stringResource(reviewPace.labelRes.resolve())} ›",
                    style = t.typography.caption,
                    color = t.colors.accent
                )
            }
        )
        SettingRow(
            title = stringResource(R.string.tts_settings_title),
            onClick = onOpenListeningSettings,
            trailing = {
                Text(text = "›", style = t.typography.caption, color = t.colors.accent)
            }
        )
    }
}
