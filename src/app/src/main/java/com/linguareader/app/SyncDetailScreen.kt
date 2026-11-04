package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.LinguaCard
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.SecondaryButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.data.formatStorageBytes
import com.linguareader.shared.sync.CloudBook
import com.linguareader.shared.sync.SyncBookItem
import com.linguareader.shared.sync.SyncDetail
import com.linguareader.shared.sync.SyncItemState
import com.linguareader.shared.sync.SyncSettings
import com.linguareader.shared.sync.SyncWordItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步「同步明细」页（F-160 二级界面）：逐项列出哪些内容已经同步、哪些还在等推送、
 * 哪些只存在本机，并给出云端独有的可下载书籍。
 *
 * **离线优先**：本页自身不发任何网络请求，只渲染 `AppUiState.syncDetail` 快照。注意宿主传进来的
 * [onRefresh] 做两件事——重建本地快照（纯本地）+ 已启用同步时拉云端书单（**会出网**）；
 * 因此「刷新」按钮在已启用云同步时会产生一次网络访问。返回与标题由宿主（BookshelfScreen 的
 * 顶栏）提供，本页不再渲染第二个返回箭头或重复标题。
 *
 * P3 补迁（漏网屏幕）：样式全部改由 [LocalDesignTokens] 提供，颜色/排版/间距不再走旧调色板
 * 与裸 dp。**纯样式改动，行为一行未动**（排序、分段、状态判定都在 `SyncDetailBuilder` 里，
 * 本文件只渲染）。视觉判据见 `SyncDetailRenderTest` 的三张像素基线。
 */
@Composable
internal fun SyncDetailScreen(
    detail: SyncDetail?,
    loading: Boolean,
    settings: SyncSettings,
    status: String?,
    lastSyncAt: Long,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSyncNow: () -> Unit,
    onDownloadCloudBook: (CloudBook) -> Unit
) {
    val t = LocalDesignTokens.current
    val books = detail?.books.orEmpty()
    val words = detail?.words.orEmpty()
    val remoteOnlyBooks = detail?.remoteOnlyBooks.orEmpty()

    // 排序/切分在 AppViewModel 侧完成（SyncDetailBuilder），这里只按段渲染，不重排。
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = t.spacing.gutter)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(SyncRowGap),
            contentPadding = PaddingValues(top = t.spacing.s, bottom = t.spacing.xl)
        ) {
            // 1) 概览：三态计数 + 账号 + 服务器 + 上次同步；未登录/未启用时给引导文案。
            item("overview") {
                SyncOverviewCard(detail = detail, settings = settings, lastSyncAt = lastSyncAt)
            }

            // 2) 动作行：刷新（本地重建 + 拉云端列表）与立即同步；busy/loading 时禁用。
            item("actions") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(t.spacing.s),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.sync_detail_refresh),
                        onClick = onRefresh,
                        enabled = !busy && !loading
                    )
                    SecondaryButton(
                        text = stringResource(R.string.sync_detail_sync_now),
                        onClick = onSyncNow,
                        enabled = !busy
                    )
                    if (loading) {
                        CircularProgressIndicator(
                            color = t.colors.accent,
                            strokeWidth = t.hairline,
                            modifier = Modifier.size(SyncSpinnerSize)
                        )
                    }
                }
            }

            if (status != null) {
                item("status") {
                    Text(status, style = t.typography.caption, color = t.colors.inkSoft)
                }
            }

            // 3) 书籍段：阅读进度 + 书籍正文两个状态标记。
            item("books_header") {
                SyncSectionTitle(stringResource(R.string.sync_detail_section_books))
            }
            if (books.isEmpty()) {
                item("books_empty") {
                    SyncEmptyLine(stringResource(R.string.sync_detail_empty_books))
                }
            } else {
                items(books, key = { "book_" + it.bookId }) { book ->
                    SyncBookRow(book)
                }
            }

            // 4) 生词段：生词可能很多，走 LazyColumn items，不一次性铺开。
            item("words_header") {
                SyncSectionTitle(stringResource(R.string.sync_detail_section_words))
            }
            if (words.isEmpty()) {
                item("words_empty") {
                    SyncEmptyLine(stringResource(R.string.sync_detail_empty_words))
                }
            } else {
                items(words, key = { "word_" + it.wordId }) { word ->
                    SyncWordRow(word)
                }
            }

            // 5) 云端独有书籍：远端有完整正文、本机没有，点「下载」落到本地书库。
            item("remote_header") {
                SyncSectionTitle(stringResource(R.string.sync_detail_section_remote_books))
            }
            if (remoteOnlyBooks.isEmpty()) {
                item("remote_empty") {
                    SyncEmptyLine(stringResource(R.string.sync_cloud_empty))
                }
            } else {
                items(remoteOnlyBooks, key = { "remote_" + it.bookId }) { cloud ->
                    SyncRemoteBookRow(
                        cloud = cloud,
                        enabled = !busy,
                        onDownload = { onDownloadCloudBook(cloud) }
                    )
                }
            }
        }
    }
}

/**
 * 概览卡：三态计数（来自 [SyncDetail.summary]）、账号、服务器与上次同步时间。
 *
 * `settings.enabled == false` 或 [detail] 为 null（未登录/未启用）时不显示计数，改给
 * [R.string.sync_detail_not_logged_in] 引导——用户反馈「登录后没有界面」正是这一页要解决的。
 */
@Composable
private fun SyncOverviewCard(detail: SyncDetail?, settings: SyncSettings, lastSyncAt: Long) {
    val t = LocalDesignTokens.current
    val lastSyncText = lastSyncText(lastSyncAt)
    LinguaCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SyncCardInnerGap)) {
            if (!settings.enabled || detail == null) {
                Text(
                    text = stringResource(R.string.sync_detail_not_logged_in),
                    style = t.typography.body,
                    color = t.colors.inkSoft
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.sync_detail_summary,
                        detail.summary.syncedCount,
                        detail.summary.pendingCount,
                        detail.summary.localOnlyCount
                    ),
                    style = t.typography.title,
                    fontWeight = FontWeight.SemiBold,
                    color = t.colors.ink
                )
                Text(lastSyncText, style = t.typography.caption, color = t.colors.inkSoft)
                if (settings.username.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.sync_username_label) + " · " + settings.username,
                        style = t.typography.caption,
                        color = t.colors.inkSoft
                    )
                }
                if (settings.serverUrl.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.sync_server_label) + " · " + settings.serverUrl,
                        style = t.typography.caption,
                        color = t.colors.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** 上次同步时间：0 表示从未同步过，绝不把 epoch 数字直接给用户。 */
@Composable
private fun lastSyncText(lastSyncAt: Long): String {
    if (lastSyncAt <= 0L) return stringResource(R.string.sync_detail_last_sync_never)
    val formatted = remember(lastSyncAt) {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastSyncAt))
    }
    return stringResource(R.string.sync_detail_last_sync, formatted)
}

/**
 * 分段标题（Books / Vocabulary / Cloud books to download）。
 *
 * **刻意不用 `SectionHeader`**：那个组件是「小号灰色 eyebrow」（`typography.label` + `inkSoft`），
 * 用来在一个屏幕里划分大区块；而这一页的分段标题原来是 `titleSmall + SemiBold + Ink`，
 * 是**醒目的小标题**。换成 SectionHeader 会让「Books」「Vocabulary」从深色粗体缩成灰色小字，
 * 层级直接塌掉——那不是「迁移到设计系统」，是改设计。迁移期像素不变优先，故保留原有层级，
 * 只把取值从旧调色板换到 token（`t.typography.title` ≈ 原 titleSmall 的语义位）。
 */
@Composable
private fun SyncSectionTitle(text: String) {
    val t = LocalDesignTokens.current
    Text(
        text = text,
        // 原实现 `titleSmall` = 14sp/SemiBold/无衬线。排版梯五档里最近的是 `body`（15sp，无衬线），
        // 差 1sp；用 `title`（18sp/衬线）会明显变大且换字族，层级压过卡片正文。
        style = t.typography.body,
        fontWeight = FontWeight.SemiBold,
        color = t.colors.ink,
        modifier = Modifier.padding(top = SyncSectionTitleTopPad)
    )
}

@Composable
private fun SyncEmptyLine(text: String) {
    val t = LocalDesignTokens.current
    Text(text, style = t.typography.caption, color = t.colors.inkSoft)
}

/** 书籍条目：书名 + 阅读进度/书籍正文两个状态标记，云端正文已知时补一行大小。 */
@Composable
private fun SyncBookRow(book: SyncBookItem) {
    val t = LocalDesignTokens.current
    LinguaCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(SyncCardInnerGap)) {
            Text(
                text = book.title,
                style = t.typography.body,
                color = t.colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(t.spacing.s),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SyncStateChip(
                    label = stringResource(R.string.sync_detail_progress_label),
                    state = book.progressState
                )
                SyncStateChip(
                    label = stringResource(R.string.sync_detail_source_label),
                    state = book.sourceState
                )
            }
            if (book.sourceState == SyncItemState.SYNCED && book.sourceSizeBytes > 0L) {
                Text(
                    text = formatStorageBytes(book.sourceSizeBytes),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
            }
        }
    }
}

/** 生词条目：headword + 状态标记。 */
@Composable
private fun SyncWordRow(word: SyncWordItem) {
    val t = LocalDesignTokens.current
    LinguaCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SyncRowGap)
        ) {
            Text(
                text = word.headword,
                style = t.typography.body,
                color = t.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SyncStateChip(label = null, state = word.state)
        }
    }
}

/** 云端独有书籍：显示名 + 大小 + 下载。 */
@Composable
private fun SyncRemoteBookRow(cloud: CloudBook, enabled: Boolean, onDownload: () -> Unit) {
    val t = LocalDesignTokens.current
    LinguaCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = cloud.displayName,
                    style = t.typography.body,
                    color = t.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatStorageBytes(cloud.sizeBytes),
                    style = t.typography.label,
                    color = t.colors.inkSoft
                )
            }
            LinguaTextButton(
                text = stringResource(R.string.sync_cloud_download),
                onClick = onDownload,
                enabled = enabled
            )
        }
    }
}

/**
 * 状态标记（chip）：[TokenColors.paperDeep][com.linguareader.app.skin.TokenColors] 圆角底 +
 * 状态色文字，对比度由 token 保证。
 *
 * [label] 为 null 时只显示状态（生词条目上下文已经由 headword 说明）。
 */
@Composable
private fun SyncStateChip(label: String?, state: SyncItemState?) {
    val t = LocalDesignTokens.current
    val text = if (label.isNullOrBlank()) {
        syncStateLabel(state)
    } else {
        label + " · " + syncStateLabel(state)
    }
    Box(
        Modifier
            .background(t.colors.paperDeep, t.pillShape)
            .padding(horizontal = t.spacing.s, vertical = SyncChipVPadding)
    ) {
        Text(text, style = t.typography.label, color = syncStateColor(state))
    }
}

@Composable
private fun syncStateLabel(state: SyncItemState?): String = when (state) {
    SyncItemState.SYNCED -> stringResource(R.string.sync_detail_state_synced)
    SyncItemState.PENDING -> stringResource(R.string.sync_detail_state_pending)
    SyncItemState.LOCAL_ONLY -> stringResource(R.string.sync_detail_state_local_only)
    // null = 本次尚未拉到云端正文列表，无从判断（不是「没同步」）。
    null -> stringResource(R.string.sync_detail_state_unknown)
}

@Composable
private fun syncStateColor(state: SyncItemState?): Color {
    val t = LocalDesignTokens.current
    return when (state) {
        SyncItemState.SYNCED -> t.colors.success
        SyncItemState.PENDING -> t.colors.accent
        SyncItemState.LOCAL_ONLY -> t.colors.inkSoft
        null -> t.colors.inkFaint
    }
}

// ── 本页专有尺寸 ──────────────────────────────────────────────────
//
// 间距梯只有 4/8/16/24/32/48（+gutter 20），下面这几个值在梯子里没有对应档位。
// 沿用「聚成命名常量 + 写明理由」的口径，而不是硬套梯子改变观感：
// - SyncRowGap = 10：列表行距（原 10dp；梯子最近的是 8，会让卡片贴太紧）
// - SyncCardInnerGap = 6：卡片内多行文本的行距（原 6dp；梯子最近的是 4）
// - SyncChipVPadding = 3：chip 纵向内边距（原 3dp；梯子最小档是 4，chip 会变胖）
private val SyncRowGap = 10.dp
private val SyncCardInnerGap = 6.dp
// 分段标题的上内边距（原 6dp；梯子最近的是 4，标题会贴住上一块）
private val SyncSectionTitleTopPad = 6.dp
private val SyncChipVPadding = 3.dp

// 这两个是「元素尺寸」而不是间距，同理不进间距梯：
// spinner 18dp（原 18；token 没有 spinner 尺寸档）、
private val SyncSpinnerSize = 18.dp
