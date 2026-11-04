package com.linguareader.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.EmptyState
import com.linguareader.app.components.LinguaCard
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.data.ReviewMode
import com.linguareader.app.data.ReviewPace
import com.linguareader.app.data.ReviewReminders
import com.linguareader.app.data.ReviewScheduler
import com.linguareader.app.data.SavedWord
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 生词本屏幕（P3 第三屏）。
 *
 * 迁移范围：**只动本屏内部结构**。它现在仍是 `BookshelfScreen` 里的次级视图
 * （`showVocabulary` 布尔），那层接线属于书架迁移（P3 第 4 屏），此处不动。
 *
 * 回调签名与语义零改动：复习队列的形成规则（有到期的用到期词、没有则弹确认框
 * 让用户显式选择「复习全部」）、导出、删除、朗读全部原样保留。
 *
 * 拆成 [VocabularyScreen]（状态与副作用）+ [VocabularyContent]（纯展示）两层，
 * 理由与两个设置弹层相同：`ModalBottomSheet`/`AlertDialog` 之外的部分要能单独渲染，
 * 否则这一屏的迁移前后对比只能靠真机手点。
 */
@Composable
internal fun VocabularyScreen(
    words: List<SavedWord>,
    reviewPreset: ReviewMode?,
    customReview: ReviewPace,
    reminders: ReviewReminders,
    onReviewModeChange: (ReviewMode) -> Unit,
    onCustomReviewChange: (ReviewPace) -> Unit,
    onRemindersChange: (ReviewReminders) -> Unit,
    onRemove: (String) -> Unit,
    onReview: (String, Boolean, (Boolean) -> Unit) -> Unit,
    onExport: (android.net.Uri) -> Unit,
    onSpeak: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var reviewDeck by remember { mutableStateOf<List<SavedWord>?>(null) }
    var confirmReviewAll by remember { mutableStateOf(false) }
    var showReviewSettings by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val state = vocabularyUiState(words, query, now)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) onExport(uri)
    }

    VocabularyContent(
        state = state,
        onQueryChange = { query = it },
        onReviewClick = {
            // Due words form the review queue. When nothing is due,
            // reviewing everything is an explicit opt-in so words that
            // were just reviewed never silently re-enter the queue.
            if (state.dueWords.isNotEmpty()) {
                reviewDeck = state.dueWords
            } else if (words.isNotEmpty()) {
                confirmReviewAll = true
            }
        },
        onExportClick = { exportLauncher.launch("LinguaReader-vocabulary.csv") },
        onSettingsClick = { showReviewSettings = true },
        onSpeak = onSpeak,
        onRemove = onRemove
    )

    if (showReviewSettings) {
        ReviewSettingsSheet(
            preset = reviewPreset,
            custom = customReview,
            reminders = reminders,
            onChangePreset = onReviewModeChange,
            onChangeCustom = onCustomReviewChange,
            onChangeReminders = onRemindersChange,
            onDismiss = { showReviewSettings = false }
        )
    }

    reviewDeck?.let { deck ->
        ReviewSheet(
            deck = deck,
            onReview = onReview,
            onSpeak = onSpeak,
            onDismiss = { reviewDeck = null }
        )
    }

    if (confirmReviewAll) {
        VocabReviewAllDialog(
            wordCount = words.size,
            onConfirm = {
                reviewDeck = words
                confirmReviewAll = false
            },
            onDismiss = { confirmReviewAll = false }
        )
    }
}

/** 生词本一屏的全部**展示数据**。派生量（到期/已掌握/筛选结果）只在这里算一次。 */
internal data class VocabularyUiState(
    val words: List<SavedWord>,
    val query: String,
    /** 当前时刻；到期判定与「还有多久复习」用它，避免一屏内用两个 `now`。 */
    val now: Long,
    val dueWords: List<SavedWord>,
    val masteredWords: List<SavedWord>,
    val filtered: List<SavedWord>
) {
    val isSearching: Boolean get() = query.isNotBlank()
    /** 词库为空 vs 搜不到：两种空态文案与引导完全不同。 */
    val isEmptyLibrary: Boolean get() = words.isEmpty()
}


/**
 * 从原始词表派生 [VocabularyUiState]。
 *
 * 抽成顶层函数的理由：这三段 filter 是这一屏唯一的「逻辑」，原先内联在 composable 里，
 * 单测够不着。现在它是纯函数，`VocabularyScreenStateTest` 直接断言。
 */
internal fun vocabularyUiState(words: List<SavedWord>, query: String, now: Long): VocabularyUiState {
    val dueWords = words.filter { it.nextReviewAt <= now }
    val masteredWords = words.filter { it.reviewLevel >= ReviewScheduler.masteredLevel }
    val filtered = words.filter {
        query.isBlank() ||
            it.headword.contains(query, ignoreCase = true) ||
            it.meaning.contains(query, ignoreCase = true) ||
            it.aiMeaning.contains(query, ignoreCase = true) ||
            it.sentence.contains(query, ignoreCase = true)
    }
    return VocabularyUiState(
        words = words,
        query = query,
        now = now,
        dueWords = dueWords,
        masteredWords = masteredWords,
        filtered = filtered
    )
}

/**
 * 生词本的纯展示层。
 *
 * 只读 [VocabularyUiState] + 回调，不持有任何 `remember` 之外的状态，
 * 也不碰 SAF —— 那是外壳的事。所以它能被渲染台直接画出来做迁移前后对比。
 */
@Composable
internal fun VocabularyContent(
    state: VocabularyUiState,
    onQueryChange: (String) -> Unit,
    onReviewClick: () -> Unit,
    onExportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSpeak: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxSize().padding(horizontal = t.spacing.gutter)) {
        VocabHeader(
            state = state,
            onReviewClick = onReviewClick,
            onExportClick = onExportClick,
            onSettingsClick = onSettingsClick
        )
        VocabStats(state)
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.vocab_search_hint)) },
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = t.colors.inkFaint)
            },
            shape = t.smallShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = t.colors.accent,
                focusedLabelColor = t.colors.accent,
                cursorColor = t.colors.accent,
                unfocusedBorderColor = t.colors.outline,
                unfocusedLabelColor = t.colors.inkFaint
            )
        )
        Spacer(Modifier.height(VocabGap))
        // LazyColumn 必须在剩余的纵向空间里滚动 —— 列表区占满余下高度。
        Box(Modifier.fillMaxSize()) {
            VocabList(state = state, onSpeak = onSpeak, onRemove = onRemove)
        }
    }
}

@Composable
private fun VocabHeader(
    state: VocabularyUiState,
    onReviewClick: () -> Unit,
    onExportClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth().padding(top = t.spacing.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // 排版梯只有 5 档（28/18/15/13/12），没有 24 这一档。
            // 原标题是 Material 的 headlineSmall（24sp/400）。取最近的 display（28sp/600）
            // 而不是 title（18sp）——差 4sp 比差 6sp 更小，且「屏幕标题」与「弹层标题」
            // 本就是两个语义角色（后者用 title）。这是与原来最接近的选择，不是随手换的。
            Text(
                text = stringResource(R.string.vocab_title),
                style = t.typography.display,
                color = t.colors.ink
            )
            Text(
                text = if (state.dueWords.isEmpty()) {
                    stringResource(R.string.vocab_due_done)
                } else {
                    pluralStringResource(R.plurals.vocab_due_count, state.dueWords.size, state.dueWords.size)
                },
                color = if (state.dueWords.isEmpty()) t.colors.inkSoft else t.colors.accent,
                style = t.typography.label
            )
        }
        // ⚠️ 这里**刻意不用** [LinguaTextButton]，尽管它已有 icon 与 tone。
        //
        // 原因不是「组件缺能力」，而是**页面级几何与这个窄槽位硬冲突**：
        // LinguaTextButton 内部强制 defaultMinSize(minHeight = 48dp) +
        // contentPadding(horizontal = spacing.m, vertical = spacing.xs)，三个按钮并排
        // 会吃掉过多横向空间，把 weight(1f) 的标题列挤到只剩两三个字宽。
        //
        // 实证：2026-11-04 换成 LinguaTextButton 后，标题「My words」被压成
        // **三行竖排**（视觉基线抓到的，差异 19.2%）；改回紧凑封装后恢复。
        //
        // 这与手册「按槽位判」是同一条：这个位置要的是**行内动作**，
        // 不是页面级按钮。若将来要统一，得先让 LinguaTextButton 支持紧凑变体。
        VocabIconTextButton(
            icon = Icons.Filled.School,
            text = stringResource(R.string.common_review),
            enabled = state.words.isNotEmpty(),
            onClick = onReviewClick
        )
        VocabIconTextButton(
            icon = Icons.Filled.FileDownload,
            text = stringResource(R.string.vocab_export),
            enabled = state.words.isNotEmpty(),
            onClick = onExportClick
        )
        VocabIconTextButton(
            icon = Icons.Filled.Settings,
            text = stringResource(R.string.common_settings),
            enabled = true,
            onClick = onSettingsClick
        )
    }
}

/**
 * 图标 + 文字的行内动作。颜色全部来自 token，不读 MaterialTheme 默认值。
 *
 * **为什么不用 [LinguaTextButton]**：它带页面级几何（48dp 最小高度 + 横向 contentPadding），
 * 三个并排会把左边 weight(1f) 的标题列挤成竖排。详见调用处的注释与实证记录。
 */
@Composable
private fun VocabIconTextButton(
    icon: ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = t.colors.accent,
            disabledContentColor = t.colors.inkFaint
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize))
        Spacer(Modifier.width(VocabIconGap))
        Text(text, style = t.typography.label)
    }
}

@Composable
private fun VocabStats(state: VocabularyUiState) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = t.spacing.s),
        horizontalArrangement = Arrangement.spacedBy(VocabStatGap)
    ) {
        StatChip(
            value = "${state.words.size}",
            label = stringResource(R.string.vocab_stat_total),
            color = t.colors.accent,
            modifier = Modifier.weight(1f)
        )
        StatChip(
            value = "${state.dueWords.size}",
            label = stringResource(R.string.vocab_stat_due),
            color = t.colors.goldOnPaper,
            modifier = Modifier.weight(1f)
        )
        StatChip(
            value = "${state.masteredWords.size}",
            label = stringResource(R.string.vocab_stat_mastered),
            color = t.colors.success,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun VocabList(
    state: VocabularyUiState,
    onSpeak: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val t = LocalDesignTokens.current
    if (state.filtered.isEmpty()) {
        // 原实现是 `Column(fillMaxSize, Arrangement.Center)`：空态在剩余空间里居中。
        // EmptyState 自己只 fillMaxWidth，居中要靠宿主给，所以这里补一层。
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = if (state.isEmptyLibrary) Icons.Filled.School else Icons.Filled.Search,
                title = if (state.isEmptyLibrary) {
                    stringResource(R.string.vocab_empty_title)
                } else {
                    stringResource(R.string.vocab_no_match_title)
                },
                body = if (state.isEmptyLibrary) {
                    stringResource(R.string.vocab_empty_body)
                } else {
                    stringResource(R.string.vocab_no_match_body)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(VocabListGap)) {
        itemsIndexed(state.filtered, key = { _, word -> word.id }) { _, word ->
            WordCard(word = word, now = state.now, onSpeak = onSpeak, onRemove = onRemove)
        }
        item { Spacer(Modifier.height(t.spacing.l)) }
    }
}

@Composable
private fun WordCard(
    word: SavedWord,
    now: Long,
    onSpeak: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    val t = LocalDesignTokens.current
    LinguaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = word.headword,
                style = t.typography.title,
                fontWeight = FontWeight.SemiBold,
                color = t.colors.ink,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { onSpeak(word.headword) }) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.common_speak),
                    tint = t.colors.accent,
                    modifier = Modifier.size(WordActionIconSize)
                )
            }
            IconButton(onClick = { onRemove(word.id) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = t.colors.inkFaint,
                    modifier = Modifier.size(WordActionIconSize)
                )
            }
        }
        if (word.phonetic.isNotBlank()) {
            Text("/${word.phonetic}/", style = t.typography.caption, color = t.colors.inkSoft)
        }
        Text(
            text = word.meaning,
            style = t.typography.body,
            color = t.colors.ink,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (word.aiMeaning.isNotBlank()) {
            Spacer(Modifier.height(t.spacing.xs))
            Text(
                text = if (word.aiSource.isBlank()) {
                    stringResource(R.string.common_book_context, word.aiMeaning)
                } else {
                    stringResource(R.string.common_book_context_source, word.aiSource, word.aiMeaning)
                },
                style = t.typography.body,
                color = t.colors.accent,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(t.spacing.xs))
        Text(
            text = word.sentence,
            // 例句保持衬线：它是「书里的原文」，与释义在观感上必须分开。
            style = t.typography.body.copy(fontFamily = FontFamily.Serif),
            color = t.colors.inkSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(
                R.string.vocab_source_line,
                word.bookTitle,
                word.chapterTitle,
                reviewStatus(word, now)
            ),
            style = t.typography.label,
            color = t.colors.accent.copy(alpha = VocabMetaAlpha)
        )
    }
}

/**
 * 「复习全部」确认框。样式走 token；按钮是纯文字，直接用原语 [LinguaTextButton]。
 *
 * 一处有意的视觉差异：原实现的确认按钮是 `color = Accent`、取消按钮用 Material 默认色，
 * 迁移后两个都是 [LinguaTextButton]（同取 accent）——设计系统里文字按钮只有一种主色，
 * 不再用「谁是默认色」来表达主次。主次改由**位置与文案**承担
 * （`confirmButton` 槽固定在行尾、`dismissButton` 在其前，这是 Material 的排布约定），
 * 语义未变，但确认按钮确实不再「染色强调」——这是与迁移前可见的差异，已报给 Lead。
 */
@Composable
private fun VocabReviewAllDialog(
    wordCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            LinguaTextButton(text = stringResource(R.string.vocab_review_all), onClick = onConfirm)
        },
        dismissButton = {
            LinguaTextButton(text = stringResource(R.string.common_cancel), onClick = onDismiss)
        },
        title = { Text(stringResource(R.string.vocab_due_done), style = t.typography.title) },
        text = {
            Text(
                text = pluralStringResource(R.plurals.vocab_review_all_body, wordCount, wordCount),
                style = t.typography.body
            )
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
}

@Composable
private fun StatChip(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier
            .background(t.colors.paperDeep, t.smallShape)
            .padding(vertical = StatChipVerticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = t.typography.title, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = t.typography.label, color = t.colors.inkSoft)
    }
}

@Composable
private fun reviewStatus(word: SavedWord, now: Long): String {
    val remaining = word.nextReviewAt - now
    if (remaining <= 0) return stringResource(R.string.vocab_status_due)
    return when {
        remaining < 60_000L -> stringResource(R.string.vocab_status_in_minutes, 1)
        remaining < 3_600_000L ->
            stringResource(R.string.vocab_status_in_minutes, remaining / 60_000L)
        remaining < 86_400_000L ->
            stringResource(R.string.vocab_status_in_hours, remaining / 3_600_000L)
        else ->
            stringResource(
                R.string.vocab_status_in_days,
                (remaining + 86_399_999L) / 86_400_000L
            )
    }
}

// ── 组件专有尺寸 ──────────────────────────────────────────────────
//
// 这几个值不进间距梯：它们不是「区块间距」，而是这一屏自己的排布常量
// （间距梯只有 4/8/16/24/32/48，而这里需要 10）。集中命名，方便日后统一。
private val VocabGap = 10.dp
private val VocabListGap = 10.dp
private val VocabStatGap = 10.dp
private val VocabIconGap = 4.dp
private val IconSize = 18.dp
private val WordActionIconSize = 20.dp
private val StatChipVerticalPadding = 10.dp
private const val VocabMetaAlpha = 0.78f