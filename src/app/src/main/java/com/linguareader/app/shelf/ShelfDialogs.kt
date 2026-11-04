package com.linguareader.app.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.R
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 书架的**信息类与确认类对话框**（P3 结构拆解：从 BookshelfScreen.kt 抽出）。
 *
 * 负责：4 个「按钮槽结构相同」的对话框 —— 通知、删书确认、删译本确认、手动导入被拒原因。
 * 依赖：`ShelfNoticeDialog`（同文件）、`RealignTranslationEntry`（同文件）、设计 token。
 *
 * **本文件不含任何状态**：调用方持有 `xxxCandidate` 之类的状态变量，这里只收数据与回调，
 * 所以是纯展示的、可单独渲染的（这也是从巨型屏幕里拆出来的前提）。
 */

/**
 * 书架的**确认 / 通知类对话框**统一封装。
 *
 * ## 覆盖范围（只有这几处同构）
 *
 * 删书确认、删译本确认、通知提示、手动导入被拒原因（正文可换成自定义内容）。
 * 它们的按钮槽结构完全一样：一到两个 TextButton（确认 + 可选取消），
 * 容器色/圆角/标题字梯走 token。
 *
 * ## 为什么放在 `app/shelf/` 而不是 `components/`
 *
 * 本组件是**书架这一屏的几处同构收敛**，不是通用件。全项目的确认框形态差异很大
 * （生词本的「复习全部」只有一个确认按钮；资源包卸载确认带文件名与体积；
 * 多角色面板 7 个各有正文结构），现在抽到 components/ 等于拍脑袋定一个万能接口。
 *
 * **升级判定条件**：其它屏出现**按钮槽结构相同**（不是文案相似）的用法时，
 * 把这个类整体搬到 `components/LinguaConfirmDialog.kt` 并删除这里 —— 那是一次纯搬移，
 * 不是重新设计。
 *
 * @param body 纯文本正文；需要自定义正文（列表、输入框、额外动作）时改用 [bodyContent]
 * @param dangerConfirm 确认按钮用 danger 色。这是**状态表达**（该动作不可撤销 /
 *   会连带删除别的东西），不是样式选择 —— 与 `LinguaTopBarAction.tint` 同口径。
 * @param dismissText null = 只有确认按钮（通知类）
 */
@Composable
internal fun ShelfNoticeDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    bodyContent: (@Composable () -> Unit)? = null,
    dismissText: String? = null,
    dangerConfirm: Boolean = false
) {
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            // 用 TextButton + token 配色，而不是 LinguaTextButton：后者只有一种主色
            // （accent），而确认按钮需要能表达 danger（不可撤销动作）。
            // 「给 LinguaTextButton 加 color 参数」属于组件层改动，本轮不越界。
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    // 危险动作用 danger 语义色：与迁移前「删除按钮 color = Danger」一致。
                    contentColor = if (dangerConfirm) t.colors.danger else t.colors.accent
                )
            ) {
                Text(text = confirmText, style = t.typography.body)
            }
        },
        dismissButton = dismissText?.let { text ->
            {
                TextButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.textButtonColors(contentColor = t.colors.accent)
                ) {
                    Text(text = text, style = t.typography.body)
                }
            }
        },
        title = { Text(text = title, style = t.typography.title, color = t.colors.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                if (body != null) {
                    Text(text = body, style = t.typography.body, color = t.colors.ink)
                }
                bodyContent?.invoke()
            }
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

/**
 * 译本菜单里的「重新对齐」入口（出版译本、AI 译本两个管理对话框共用）。
 *
 * 档案由旧版对齐器写出时换成过期提示（[R.string.shelf_translation_realign_outdated]）；
 * 动作在 ViewModel 里跑，期间书卡显示「对齐中…」，完成后出全局 Snackbar。
 */
@Composable
internal fun RealignTranslationEntry(outdated: Boolean, onRealign: () -> Unit) {
    val t = LocalDesignTokens.current
    TextButton(onClick = onRealign) {
        Text(stringResource(R.string.shelf_translation_realign))
    }
    Text(
        stringResource(
            if (outdated) R.string.shelf_translation_realign_outdated
            else R.string.shelf_translation_realign_hint
        ),
        style = t.typography.caption,
        color = if (outdated) t.colors.accent else t.colors.inkSoft
    )
}

/**
 * 通知 / 提示对话框（`AppUiState.message` 对应的那个）。
 *
 * 抽成独立 composable 而不是内联在屏幕里，是为了让「有无消息」这个判断留在屏幕的装配层，
 * 而这个对话框本身不依赖屏幕的任何其它状态。
 */
@Composable
internal fun ShelfMessageDialog(
    message: String,
    messageTitle: String,
    onDismiss: () -> Unit
) {
    ShelfNoticeDialog(
        title = messageTitle.ifBlank { stringResource(R.string.common_notice_title) },
        body = message,
        confirmText = stringResource(R.string.common_got_it),
        onConfirm = onDismiss,
        onDismiss = onDismiss
    )
}

/**
 * 删书确认。
 *
 * [wordCount] 要由调用方算好传进来（屏幕持有 `savedWords`）—— 对话框本身不读全局状态，
 * 这样它才能被单独渲染测试。删书连带删生词且不可恢复，条数必须摆在按下去之前。
 */
@Composable
internal fun ShelfDeleteBookDialog(
    book: Book,
    wordCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ShelfNoticeDialog(
        title = stringResource(R.string.shelf_delete_title, book.title),
        body = if (wordCount == 0) {
            stringResource(R.string.shelf_delete_body)
        } else {
            pluralStringResource(R.plurals.shelf_delete_body_words, wordCount, wordCount)
        },
        confirmText = stringResource(R.string.common_delete),
        dangerConfirm = true,
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss
    )
}

/**
 * 删译本确认（正文里带「重新对齐」入口）。
 *
 * [outdated] 由调用方按 `state.outdatedTranslations` 判定后传入，与迁移前一致。
 */
@Composable
internal fun ShelfDetachTranslationDialog(
    book: Book,
    outdated: Boolean,
    onConfirm: () -> Unit,
    onRealign: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    ShelfNoticeDialog(
        title = stringResource(R.string.shelf_translation_remove_title, book.title),
        confirmText = stringResource(R.string.common_delete),
        dangerConfirm = true,
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
        bodyContent = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                Text(
                    stringResource(R.string.shelf_translation_remove_body),
                    style = t.typography.body,
                    color = t.colors.ink
                )
                RealignTranslationEntry(outdated = outdated, onRealign = onRealign)
            }
        }
    )
}

/**
 * 手动导入结果被拒的逐条原因。
 *
 * 「拒绝 N 条」的条数不告诉用户该改什么；典型错误（agent 把段落编号重新编号）
 * 只有看到具体原因才能定位。列表限高 + 自带滚动。
 */
@Composable
internal fun ShelfManualRejectedDialog(
    rejected: List<String>,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    ShelfNoticeDialog(
        title = stringResource(R.string.shelf_translation_manual_rejected_title),
        confirmText = stringResource(R.string.common_got_it),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
        bodyContent = {
            // 限高 + 自带滚动：拒绝原因可能有几十条。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = ManualRejectedMaxHeight)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.shelf_translation_manual_rejected_hint),
                    style = t.typography.caption,
                    color = t.colors.inkSoft
                )
                Spacer(Modifier.height(t.spacing.s))
                rejected.forEach { reason ->
                    Text(reason, style = t.typography.caption, color = t.colors.ink)
                    Spacer(Modifier.height(t.spacing.xs))
                }
            }
        }
    )
}

/** 「手动导入被拒原因」列表的最高可视高度（超出后列表自己滚）。 */
private val ManualRejectedMaxHeight = 360.dp
