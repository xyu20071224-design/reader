package com.linguareader.app.shelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.GlossaryEditorBody
import com.linguareader.app.MultiVoiceSection
import com.linguareader.app.R
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.CloudTtsSettings
import com.linguareader.app.tts.TtsPlaybackController
import com.linguareader.shared.ai.BookGlossary
import com.linguareader.shared.ai.GlossaryEntry

/**
 * 书架的**编辑器类容器**（P3 结构拆解：从 BookshelfScreen.kt 抽出）。
 *
 * 负责：术语表对话框（内嵌 `GlossaryEditorBody`）与角色管理弹层（内嵌 `MultiVoiceSection`）。
 * 依赖：这两个被复用的编辑器/面板、设计 token、`LinguaSheet`。
 *
 * 两者都**不是**「对话框封装」的候选：正文是整块编辑器，结构与确认/表单类完全不同。
 * 抽出来的唯一目的是让 BookshelfScreen.kt 只留装配代码。
 */

/** 术语表对话框的最大高度（超出后由 GlossaryEditor 自带滚动接管）。 */
private val GlossaryDialogMaxHeight = 480.dp

/**
 * 术语表对话框。编辑器自带滚动，本组件只负责限高与外壳。
 *
 * 挂载/编辑/删除术语的回调原样透传给 `GlossaryEditorBody`（它与 AI 中心共用的那个）。
 */
@Composable
internal fun ShelfGlossaryDialog(
    book: Book,
    onLoad: suspend (String) -> BookGlossary,
    onAdd: suspend (String, String, String) -> BookGlossary,
    onUpdate: suspend (String, GlossaryEntry) -> BookGlossary,
    onRemove: suspend (String, String) -> BookGlossary,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
        },
        title = {
            Text(
                stringResource(R.string.glossary_title, book.title),
                style = t.typography.title,
                color = t.colors.ink
            )
        },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(max = GlossaryDialogMaxHeight)) {
                // 共用编辑器自带滚动；这里只负责限高。
                GlossaryEditorBody(
                    books = listOf(book),
                    lockedBookId = book.id,
                    onLoad = onLoad,
                    onAdd = onAdd,
                    onUpdate = onUpdate,
                    onRemove = onRemove
                )
            }
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

/**
 * 该书的多角色/角色管理弹层（方向 A）：书卡「角色」入口打开，复用 [MultiVoiceSection]。
 *
 * 设置独立加载与保存（不与听书设置弹层抢占状态）。
 *
 * ⚠️ **滚动必须由内容体自己拥有**：`LinguaSheet` 的内容槽只是普通 Column，不给滚动，
 * 而 `MultiVoiceSection` 在手机上远超一屏。迁移前是 `ModalBottomSheet` 里手写
 * `verticalScroll` —— 换容器时最容易把它一起删掉（PacksSheet 就踩过这个坑）。
 */
@Composable
internal fun ShelfRosterSheet(
    book: Book,
    books: List<Book>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 外壳换成统一弹层 LinguaSheet（纸色 / 圆角 / 拖柄 / 底部留白一并统一）。
    LinguaSheet(onDismiss = onDismiss, contentArrangement = Arrangement.Top) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            var settings by remember(book.id) {
                mutableStateOf(CloudTtsSettings.load(context))
            }
            MultiVoiceSection(
                settings = settings,
                onSettingsChange = { next ->
                    settings = next
                    CloudTtsSettings.save(context, next)
                    TtsPlaybackController.onCloudSettingsChanged(context)
                },
                books = books,
                preselectedBook = book
            )
        }
    }
}
