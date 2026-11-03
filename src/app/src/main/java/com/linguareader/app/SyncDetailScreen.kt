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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 * **离线优先**：本页自身不发任何网络请求，只渲染 [AppUiState.syncDetail] 快照。注意宿主传进来的
 * [onRefresh] 做两件事——重建本地快照（纯本地）+ 已启用同步时拉云端书单（**会出网**）；
 * 因此「刷新」按钮在已启用云同步时会产生一次网络访问。返回与标题由宿主（BookshelfScreen 的
 * TopAppBar）提供，本页不再渲染第二个返回箭头或重复标题。
 *
 * 配色只用 [ThemeColors] 的语义色，状态标记的底色用 [PaperDeep]（浅于纸色的卡面/深于纸色的
 * 卡片上都能看清），颜色只用于文字，避免「米色底上米色字」。
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
    val books = detail?.books.orEmpty()
    val words = detail?.words.orEmpty()
    val remoteOnlyBooks = detail?.remoteOnlyBooks.orEmpty()

    // 排序/切分在 AppViewModel 侧完成（SyncDetailBuilder），这里只按段渲染，不重排。
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp)
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp)
        ) {
            // 1) 概览：三态计数 + 账号 + 服务器 + 上次同步；未登录/未启用时给引导文案。
            item("overview") {
                SyncOverviewCard(detail = detail, settings = settings, lastSyncAt = lastSyncAt)
            }

            // 2) 动作行：刷新（本地重建 + 拉云端列表）与立即同步；busy/loading 时禁用。
            item("actions") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onRefresh, enabled = !busy && !loading) {
                        Text(stringResource(R.string.sync_detail_refresh))
                    }
                    OutlinedButton(onClick = onSyncNow, enabled = !busy) {
                        Text(stringResource(R.string.sync_detail_sync_now))
                    }
                    if (loading) {
                        CircularProgressIndicator(
                            color = Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (status != null) {
                item("status") {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = InkSoft)
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
    val lastSyncText = lastSyncText(lastSyncAt)
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!settings.enabled || detail == null) {
                Text(
                    stringResource(R.string.sync_detail_not_logged_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft
                )
            } else {
                Text(
                    stringResource(
                        R.string.sync_detail_summary,
                        detail.summary.syncedCount,
                        detail.summary.pendingCount,
                        detail.summary.localOnlyCount
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Text(lastSyncText, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                if (settings.username.isNotBlank()) {
                    Text(
                        stringResource(R.string.sync_username_label) + " · " + settings.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft
                    )
                }
                if (settings.serverUrl.isNotBlank()) {
                    Text(
                        stringResource(R.string.sync_server_label) + " · " + settings.serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
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

@Composable
private fun SyncSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = Ink,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun SyncEmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = InkSoft)
}

/** 书籍条目：书名 + 阅读进度/书籍正文两个状态标记，云端正文已知时补一行大小。 */
@Composable
private fun SyncBookRow(book: SyncBookItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    formatStorageBytes(book.sourceSizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft
                )
            }
        }
    }
}

/** 生词条目：headword + 状态标记。 */
@Composable
private fun SyncWordRow(word: SyncWordItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                word.headword,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
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
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    cloud.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatStorageBytes(cloud.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft
                )
            }
            TextButton(onClick = onDownload, enabled = enabled) {
                Text(stringResource(R.string.sync_cloud_download))
            }
        }
    }
}

/**
 * 状态标记（chip）：[PaperDeep] 圆角底 + 状态色文字，对比度由调色板保证。
 *
 * [label] 为 null 时只显示状态（生词条目上下文已经由 headword 说明）。
 */
@Composable
private fun SyncStateChip(label: String?, state: SyncItemState?) {
    val text = if (label.isNullOrBlank()) {
        syncStateLabel(state)
    } else {
        label + " · " + syncStateLabel(state)
    }
    Box(
        Modifier
            .background(PaperDeep, PillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = syncStateColor(state))
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
private fun syncStateColor(state: SyncItemState?): Color = when (state) {
    SyncItemState.SYNCED -> Success
    SyncItemState.PENDING -> Accent
    SyncItemState.LOCAL_ONLY -> InkSoft
    null -> InkFaint
}
