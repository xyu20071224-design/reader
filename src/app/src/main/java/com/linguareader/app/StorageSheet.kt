package com.linguareader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.StorageReport
import com.linguareader.app.data.formatStorageBytes

/**
 * 存储占用（方案 D2.4b）。
 *
 * 两件事：**看得见**（各处实际占用）与**清得掉**（无人认领的数据）。
 *
 * 数据全部来自 `AppViewModel.scanStorage()`，它遍历的是那份 per-book 存储权威清单
 * （`bookDataStores`）—— 这里刻意不自己列一遍路径：手写第二份清单正是当初「删书
 * 漏清生词本」的成因。
 *
 * 孤儿**只报不删**，清理要用户按下面那个按钮；按下时删的是报告里那些路径，不重新
 * 推断（推断与点击之间可能已经导入了新书）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StorageSheet(
    report: StorageReport?,
    scanning: Boolean,
    onRescan: () -> Unit,
    onCleanOrphans: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.storage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink
            )
            Spacer(Modifier.height(10.dp))

            if (scanning && report == null) {
                Text(
                    stringResource(R.string.storage_scanning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft
                )
            }

            report?.let { data ->
                Text(
                    stringResource(R.string.storage_total, formatStorageBytes(data.totalBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink
                )
                Spacer(Modifier.height(8.dp))
                // 占用从大到小排；0 字节的不列，免得一屏噪音。
                data.usages.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.forEach { usage ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            storeLabel(usage.storeId),
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft
                        )
                        Text(
                            formatStorageBytes(usage.bytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.storage_orphans_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = InkSoft
                )
                Spacer(Modifier.height(4.dp))
                if (data.orphans.isEmpty()) {
                    Text(
                        stringResource(R.string.storage_orphans_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint
                    )
                } else {
                    Text(
                        stringResource(
                            R.string.storage_orphans_found,
                            data.orphans.size,
                            formatStorageBytes(data.orphanBytes)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                    Text(
                        stringResource(R.string.storage_orphans_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkFaint
                    )
                    TextButton(onClick = onCleanOrphans) {
                        Text(stringResource(R.string.storage_orphans_clean), color = Danger)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRescan, enabled = !scanning) {
                    Text(stringResource(R.string.storage_rescan))
                }
            }
        }
    }
}

/** storeId → 用户看得懂的名字。认不出的直接显示 id（新增存储忘了加文案时至少不瞎编）。 */
@Composable
private fun storeLabel(storeId: String): String = when (storeId) {
    "books" -> stringResource(R.string.storage_store_books)
    "vocabulary" -> stringResource(R.string.storage_store_vocabulary)
    "ai/book-context" -> stringResource(R.string.storage_store_book_context)
    "ai/glossary" -> stringResource(R.string.storage_store_glossary)
    "ai/speaker-tags" -> stringResource(R.string.storage_store_speaker_tags)
    "translations" -> stringResource(R.string.storage_store_translations)
    "ai/ai-translations" -> stringResource(R.string.storage_store_ai_translations)
    "tts_cache" -> stringResource(R.string.storage_store_tts_cache)
    "voice_maps" -> stringResource(R.string.storage_store_voice_maps)
    else -> storeId
}
