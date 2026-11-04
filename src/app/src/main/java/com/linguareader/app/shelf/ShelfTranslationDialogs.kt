package com.linguareader.app.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.AiTranslationPrepare
import com.linguareader.app.ManualTranslationPrepare
import com.linguareader.app.R
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 书架的**菜单类 / 选择类 / 表单类对话框**（P3 结构拆解：从 BookshelfScreen.kt 抽出）。
 *
 * 负责：AI 译本管理（菜单）、加译本来源（选择）、手动翻译（表单）、AI 生成译本确认（表单）。
 * 依赖：设计 token、`RealignTranslationEntry`（ShelfDialogs.kt）。
 *
 * **刻意没有共用 `ShelfNoticeDialog`**：这 4 个各自带不同的正文结构与正文内动作。
 * 硬套会得到一个要传菜单项 / 输入框 / 单选组的万能接口，比直白写更难读
 * —— 与「该抽的是真正的重复结构」同一口径。
 *
 * **本文件不含任何屏幕状态**：候选书、回调、准备数据的对象都由屏幕传进来。
 */

/**
 * AI 译本的管理对话框（**菜单类**）：重新生成（检查点复用）/ 手动补翻 / 删除。
 *
 * 正文里有 3 个动作，所以它的按钮槽（重新生成 + 取消）与确认类结构不同，不套统一封装。
 */
@Composable
internal fun ShelfAiTranslationManageDialog(
    book: Book,
    outdated: Boolean,
    onRegenerate: () -> Unit,
    onImportManual: () -> Unit,
    onRemove: () -> Unit,
    onRealign: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onRegenerate) {
                Text(stringResource(R.string.shelf_translation_manage_regenerate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = {
            Text(
                stringResource(R.string.shelf_translation_manage_title),
                style = t.typography.title,
                color = t.colors.ink
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                Text(
                    stringResource(R.string.shelf_translation_manage_body),
                    style = t.typography.body,
                    color = t.colors.ink
                )
                RealignTranslationEntry(outdated = outdated, onRealign = onRealign)
                // 手动补翻/修正入口：agent 返工后的新结果文件从这里导入
                //（检查点按批覆盖，齐批自动重新生成），不必删掉译本重来。
                TextButton(onClick = onImportManual) {
                    Text(stringResource(R.string.shelf_translation_manage_import))
                }
                TextButton(onClick = onRemove) {
                    Text(
                        stringResource(R.string.shelf_translation_manage_remove),
                        color = t.colors.danger
                    )
                }
            }
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

/**
 * 「加译本来源」选择对话框（**选择类**）。
 *
 * 两个按钮都是「选一条路」，没有主次之分，所以都走 TextButton 的 accent
 *（迁移前 cancel 槽用的是 Material 默认色；这里与生词本「复习全部」同口径：
 * 文字按钮只有一种主色，主次由位置与文案承担）。
 */
@Composable
internal fun ShelfTranslationChoiceDialog(
    onImportFile: () -> Unit,
    onAiGenerate: () -> Unit,
    onManualAi: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onImportFile) {
                Text(stringResource(R.string.shelf_translation_choice_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onAiGenerate) {
                Text(stringResource(R.string.shelf_translation_choice_ai))
            }
        },
        title = {
            Text(
                stringResource(R.string.shelf_translation_choice_title),
                style = t.typography.title,
                color = t.colors.ink
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                Text(
                    stringResource(R.string.shelf_translation_choice_body),
                    style = t.typography.body,
                    color = t.colors.ink
                )
                // 第三条路：手动 AI 翻译——导出任务文件交给外部 agent，再把
                // 结果文件导回来。不要求应用内配置 API Key。
                TextButton(onClick = onManualAi) {
                    Text(stringResource(R.string.shelf_translation_choice_manual))
                }
            }
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

/**
 * 「手动 AI 翻译」对话框（**表单类**）：覆盖进度 + 风格说明 + 导入结果入口。
 *
 * 风格说明是**局部可编辑状态**（`remember(prepare.book.id)`）：书换了就重置，
 * 与屏幕的其它状态无关，所以状态就留在本对话框里。
 */
@Composable
internal fun ShelfManualTranslationDialog(
    prepare: ManualTranslationPrepare,
    onExport: (book: Book, styleNotes: String) -> Unit,
    onImportResults: () -> Unit,
    onDismiss: () -> Unit
) {
    var styleNotes by remember(prepare.book.id) { mutableStateOf(prepare.styleNotes) }
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onExport(prepare.book, styleNotes) }) {
                Text(stringResource(R.string.shelf_translation_manual_export))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = {
            Text(
                stringResource(R.string.shelf_translation_manual_title),
                style = t.typography.title,
                color = t.colors.ink
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                Text(
                    stringResource(R.string.shelf_translation_manual_body, prepare.batches),
                    style = t.typography.body,
                    color = t.colors.ink
                )
                if (prepare.coveredBatches > 0) {
                    Text(
                        stringResource(
                            R.string.shelf_translation_manual_coverage,
                            prepare.coveredBatches,
                            prepare.batches
                        ),
                        style = t.typography.caption,
                        color = t.colors.inkSoft
                    )
                }
                Spacer(Modifier.height(t.spacing.xs))
                Text(
                    stringResource(R.string.shelf_translation_ai_style_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                OutlinedTextField(
                    value = styleNotes,
                    onValueChange = { styleNotes = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.shelf_translation_ai_style_placeholder),
                            style = t.typography.caption
                        )
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                // 导入入口放在正文区（AlertDialog 只有两个按钮槽位，加第三个
                // 动作的管理对话框先例见「AI 译本管理」）。
                TextButton(onClick = onImportResults) {
                    Text(stringResource(R.string.shelf_translation_manual_import))
                }
            }
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

/**
 * 「AI 生成译本」确认对话框（**表单类**）：单选翻译模式 + 风格说明输入框。
 *
 * 翻译模式的两个取值（`"standard"` / `"polish"`）是**与 ViewModel 的契约字符串**，
 * 原样保留，不要换成枚举 —— 那会改动跨层协议。
 */
@Composable
internal fun ShelfAiTranslationPrepareDialog(
    prepare: AiTranslationPrepare,
    providerDisplayName: String,
    onStart: (book: Book, mode: String, styleNotes: String) -> Unit,
    onDismiss: () -> Unit
) {
    // 确认框内的可编辑项：翻译模式（记住上次）与风格说明（书级，随开随改）。
    var mode by remember(prepare.book.id) { mutableStateOf(prepare.mode) }
    var styleNotes by remember(prepare.book.id) { mutableStateOf(prepare.styleNotes) }
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onStart(prepare.book, mode, styleNotes) }) {
                Text(stringResource(R.string.shelf_translation_ai_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        title = {
            Text(
                stringResource(R.string.shelf_translation_ai_title),
                style = t.typography.title,
                color = t.colors.ink
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                Text(
                    stringResource(
                        R.string.shelf_translation_ai_confirm_body,
                        prepare.book.title,
                        providerDisplayName,
                        prepare.chapters,
                        prepare.batches,
                        prepare.glossaryTerms,
                        prepare.glossaryInjected
                    ),
                    style = t.typography.body,
                    color = t.colors.ink
                )
                if (prepare.oversizedParagraphs > 0) {
                    Text(
                        stringResource(
                            R.string.shelf_translation_ai_oversized,
                            prepare.oversizedParagraphs
                        ),
                        style = t.typography.caption,
                        color = t.colors.inkSoft
                    )
                }
                Spacer(Modifier.height(DialogTightGap))
                Text(
                    stringResource(R.string.shelf_translation_ai_mode_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = mode == "standard",
                        onClick = { mode = "standard" }
                    )
                    Text(
                        stringResource(R.string.shelf_translation_ai_mode_standard),
                        style = t.typography.caption
                    )
                    Spacer(Modifier.width(t.spacing.s))
                    RadioButton(
                        selected = mode == "polish",
                        onClick = { mode = "polish" }
                    )
                    Text(
                        stringResource(R.string.shelf_translation_ai_mode_polish),
                        style = t.typography.caption
                    )
                }
                Spacer(Modifier.height(DialogTightGap))
                Text(
                    stringResource(R.string.shelf_translation_ai_style_label),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
                OutlinedTextField(
                    value = styleNotes,
                    onValueChange = { styleNotes = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.shelf_translation_ai_style_placeholder),
                            style = t.typography.caption
                        )
                    },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

/** 对话框正文里「模式/风格标签」与输入框之间的小间距（比 spacing.xs 更松一点）。 */
private val DialogTightGap = 6.dp
