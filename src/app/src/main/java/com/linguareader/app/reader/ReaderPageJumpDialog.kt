package com.linguareader.app.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.linguareader.app.CardShape
import com.linguareader.app.CardSurface
import com.linguareader.app.R
import com.linguareader.app.UiTags
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 页码跳转对话框。
 *
 * ## 职责
 *
 * 让用户输入页码并跳到该页。纯 UI 状态（输入框内容、校验错误）都留在这里，
 * 对外只暴露两个回调：跳转与关闭。
 *
 * ## 依赖的外部状态
 *
 * 只有两个数：[currentPage]（初始化输入框为「当前页 + 1」，因为界面上页码是 1-based）
 * 与 [pageCount]（校验上界，同时用于错误文案里的「1 到 N」）。**不持有任何阅读器状态**，
 * 也不碰 [com.linguareader.app.reader.ReaderController] —— 跳转由调用方的 [onJump] 执行。
 *
 * ## 为什么可以独立
 *
 * 它是这一屏里**最干净的一块**：不读 `ReaderOverlays`、不读 `ReaderPosition` 之外的任何
 * 东西、不参与 WebView 协议、不参与 TTS 高亮。抽出后 `ReaderScreen` 只保留一个
 * `if (overlays.pageJump) { ReaderPageJumpDialog(...) }` 调用点，行参数与原来一一对应。
 *
 * ## 迁移状态
 *
 * 本文件是从 `ReaderScreen.kt` **原样搬出**的（P3 拆解第二笔），逻辑一行未改；
 * 样式已在阅读页第一笔迁到 [LocalDesignTokens]。注意 `containerColor`/`shape`
 * 仍取外壳调色板的 `CardSurface`/`CardShape`：对话框是外壳的一部分，不跟阅读主题。
 *
 * @param currentPage 当前页码（0-based，内部会 +1 显示）
 * @param pageCount 总页数（用于校验与文案）
 * @param onJump 校验通过时回调，参数是 **0-based** 页码
 * @param onDismiss 关闭对话框（取消 / 点击外部 / 返回键）
 */
@Composable
internal fun ReaderPageJumpDialog(
    currentPage: Int,
    pageCount: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    var input by remember { mutableStateOf((currentPage + 1).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    // onClick 不是 @Composable 作用域，错误文案先在这里取出来。
    val jumpErrorText = stringResource(R.string.reader_jump_error, pageCount)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(UiTags.READER_PAGE_JUMP_DIALOG),
        title = { Text(stringResource(R.string.reader_jump_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.reader_jump_hint, pageCount, pageCount),
                    color = t.colors.inkSoft,
                    style = t.typography.caption
                )
                Spacer(Modifier.height(t.spacing.s))
                OutlinedTextField(
                    value = input,
                    onValueChange = { raw ->
                        input = raw.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.reader_jump_page_label)) },
                    isError = error != null,
                    supportingText = {
                        val message = error
                        if (message != null) Text(message)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val target = parsePageInput(input, pageCount)
                    if (target == null) {
                        error = jumpErrorText
                    } else {
                        onJump(target)
                        onDismiss()
                    }
                }
            ) { Text(stringResource(R.string.reader_jump_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        containerColor = CardSurface,
        shape = CardShape
    )
}

/**
 * 解析用户输入的页码。返回值是 **0-based** 页码，非法输入返回 null。
 *
 * 保持 `internal` 且留在本文件（而不是挪进 `ReaderScreen.kt`）：它只服务于
 * [ReaderPageJumpDialog] 的校验，单测 `ReaderPageJumpTest` 直接盯它。
 * 包名变更后该单测需改为 import —— 见提交说明。
 */
internal fun parsePageInput(raw: String, pageCount: Int): Int? {
    if (pageCount <= 0) return null
    val page = raw.toIntOrNull() ?: return null
    if (page < 1 || page > pageCount) return null
    return page - 1
}
