package com.linguareader.app.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.UiTags
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.data.SavedWord
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 复习卡片的**内容体**（正面 / 背面 / 结算态），与 sheet 外壳解耦。
 *
 * ## 职责
 *
 * 只负责画：给定一张卡片的快照（[word]）、进度（[cardIndex]/[deckSize]/[completedCount]）、
 * 两个布尔态（[allDone]/[revealed]）和一个忙碌位（[busy]），渲染出对应画面。
 * **它不持有任何复习状态**——卡片翻没翻、评到第几张、卡组走没走完，全部由外壳
 * [com.linguareader.app.ReviewSheet] 用 `rememberSaveable` 持有并通过参数传进来。
 *
 * ## 为什么可以独立
 *
 * 纯展示：没有 `rememberSaveable`、没有 Activity 副作用、不读卡组列表本身
 * （只看当前这一张）。回调只有三个：揭晓、朗读、评分。
 * 因此它能被渲染台直接画出三种态做迁移前后对比（见 `ReviewCardRenderTest`）。
 *
 * ⚠️ **单独使用时不自带宽度/背景约束**：真实场景继承 `LinguaSheet` 的纸色与留白；
 * 渲染台必须自己套 `Box(fillMaxSize).background(Paper)`。
 *
 * ## 🔴 评分语义不可动
 *
 * [onFinish] 的 `true`/`false` 分别表示「认识 / 再学一次」，直接决定下次复习间隔。
 * 这是有 700+ 条单测守护的核心逻辑，本文件只透传，不做任何判断或换算。
 */

/** 结算态那颗大对勾的直径（视觉锚点，比正文图标大一圈）。 */
private val DoneIconSize = 48.dp

/** 卡片内「朗读」与评分按钮里的行内图标。 */
private val SpeakIconSize = 18.dp

/** 「知道了」按钮里的对勾图标。 */
private val ButtonIconSize = 18.dp

/**
 * 复习卡片的**内容体**，与 sheet 外壳解耦。
 *
 * 理由同其余已迁弹层：`ModalBottomSheet` 在渲染台里画不进位图，抽出内容体才能做
 * 迁移前后对比。代价也一样——**不再自带宽度/背景约束**，单独使用时调用方必须补齐。
 *
 * 状态流转（index / completedCount / allDone / busy）与 `finish()` 的评分回调
 * 全部留在外壳 [ReviewSheet]：那是复习卡组的核心逻辑，有 700+ 条单测守着；
 * 这里只收快照 + 回调，评分语义一个字都不改。
 */
@Composable
internal fun ReviewCardContent(
    word: SavedWord,
    cardIndex: Int,
    deckSize: Int,
    completedCount: Int,
    allDone: Boolean,
    revealed: Boolean,
    busy: Boolean,
    onDismissRequest: () -> Unit,
    onReveal: () -> Unit,
    onSpeak: () -> Unit,
    onFinish: (Boolean) -> Unit
) {
    val t = LocalDesignTokens.current
    // REVIEW_SHEET 原本挂在 ModalBottomSheet 上（LinguaSheet 不接受 modifier）；
    // 内容体是它的子树，语义查找照样命中，故改挂在这里，不丢可达性。
    // 不再自带水平/底部留白：[LinguaSheet] 的 content 槽已经给了 horizontal = spacing.l
    // 与 bottom = spacing.xl，这里再加一层会变成双倍内边距（实测会左右各多 24dp）。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.REVIEW_SHEET),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (allDone) {
            Spacer(Modifier.height(t.spacing.l))
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(DoneIconSize),
                tint = t.colors.success
            )
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = stringResource(R.string.review_done_title),
                style = t.typography.display,
                color = t.colors.ink,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = stringResource(R.string.review_done_body, completedCount, deckSize),
                style = t.typography.body,
                color = t.colors.inkSoft,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(t.spacing.m))
            PrimaryButton(
                text = stringResource(R.string.common_done),
                onClick = onDismissRequest
            )
            Spacer(Modifier.height(t.spacing.m))
        } else {
            Text(
                text = if (completedCount == 0) {
                    stringResource(R.string.review_card_position, cardIndex + 1, deckSize)
                } else {
                    stringResource(R.string.review_card_progress, completedCount, deckSize)
                },
                style = t.typography.label,
                color = t.colors.inkSoft
            )
            Spacer(Modifier.height(t.spacing.m))
            Text(
                text = word.headword,
                style = t.typography.display,
                color = t.colors.ink,
                textAlign = TextAlign.Center
            )
            if (word.phonetic.isNotBlank()) {
                Text(
                    text = "/${word.phonetic}/",
                    style = t.typography.caption,
                    color = t.colors.inkSoft
                )
            }
            SpeakButton(text = stringResource(R.string.common_speak), onClick = onSpeak)
            Spacer(Modifier.height(t.spacing.s))
            Text(
                text = word.sentence,
                // 例句保持衬线：它是书里的原文，与释义在观感上必须分开（与生词本同口径）。
                style = t.typography.body.copy(fontFamily = FontFamily.Serif),
                color = t.colors.ink
            )
            Spacer(Modifier.height(t.spacing.m))
            if (revealed) {
                Text(
                    text = word.meaning,
                    style = t.typography.body,
                    color = t.colors.ink,
                    modifier = Modifier.fillMaxWidth()
                )
                if (word.aiMeaning.isNotBlank()) {
                    Spacer(Modifier.height(t.spacing.s))
                    Text(
                        text = if (word.aiSource.isBlank()) {
                            stringResource(R.string.common_book_context, word.aiMeaning)
                        } else {
                            stringResource(
                                R.string.common_book_context_source,
                                word.aiSource,
                                word.aiMeaning
                            )
                        },
                        style = t.typography.body,
                        color = t.colors.accent,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (word.aiExplanation.isNotBlank()) {
                    Spacer(Modifier.height(t.spacing.s))
                    Text(
                        text = stringResource(R.string.review_context_explanation, word.aiExplanation),
                        style = t.typography.caption,
                        color = t.colors.inkSoft,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(t.spacing.m))
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
                    LinguaTextButton(
                        text = stringResource(R.string.review_learn_again),
                        onClick = { onFinish(false) },
                        enabled = !busy
                    )
                    // 「知道了」是这张卡上的主动作，但语义色是 success 而非 accent。
                    // PrimaryButton 固定取 accent，硬套会把按钮从绿变棕，
                    // 与「评对了」这件事的反馈脱钩，故保留一颗 token 驱动的按钮。
                    Button(
                        onClick = { onFinish(true) },
                        enabled = !busy,
                        modifier = Modifier.defaultMinSize(minHeight = t.controlHeight),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = t.colors.success,
                            contentColor = t.colors.successOn,
                            disabledContainerColor = t.colors.paperDeep,
                            disabledContentColor = t.colors.inkFaint
                        ),
                        shape = t.pillShape,
                        contentPadding = PaddingValues(
                            horizontal = t.spacing.l,
                            vertical = t.spacing.s
                        )
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonIconSize)
                        )
                        Spacer(Modifier.width(t.spacing.xs))
                        Text(stringResource(R.string.review_known), style = t.typography.body)
                    }
                }
            } else {
                PrimaryButton(
                    text = stringResource(R.string.review_reveal),
                    onClick = onReveal
                )
            }
        }
    }
}


/**
 * 「朗读这个单词」的行内文字按钮（图标 + 文字）。
 *
 * 这一处**故意保留本地实现**：它是复习卡片内的行内动作，横向空间与卡片内边距都很窄，
 * 而 [LinguaTextButton] 的默认 `PAGE` 几何（48dp 最小高 + 16dp 横向留白）会把它撑大。
 * 若将来要统一，应传 `layout = TextButtonLayout.INLINE` —— 这正是「窄横向槽位」的处理方式
 * （见 `手册-P3屏幕迁移.md` 的槽位表），而不是等组件补能力。
 *
 * ~~等组件层补出 icon 版再换~~ ← 该理由已过时：`LinguaIconTextButton` 自 `01461c0` 起已存在。
 * 保留代码的真实原因是**布局角色**，不是缺组件。
 */
@Composable
private fun SpeakButton(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = t.colors.accent),
        contentPadding = PaddingValues(horizontal = t.spacing.m, vertical = t.spacing.xs)
    ) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(SpeakIconSize)
        )
        Spacer(Modifier.width(t.spacing.xs))
        Text(text, style = t.typography.body)
    }
}

