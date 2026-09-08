package com.linguareader.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.formatStorageBytes
import com.linguareader.app.packs.PackUiItem
import com.linguareader.app.packs.PackUiState
import com.linguareader.shared.packs.PackType

/** 资源包文件选择器的 MIME 白名单（`.lrpack` 是 zip，但各家文件管理器报的 MIME 不一）。 */
private val PACK_MIME_TYPES = arrayOf(
    "application/zip",
    "application/octet-stream",
    "application/x-zip-compressed",
    "*/*"
)

/**
 * 资源包页面（方案-资源包系统 §5 M1）。
 *
 * 三件事：**装**（SAF 选 `.lrpack`）、**切**（词典包设为当前 / 恢复内置）、**卸**（含校验）。
 * 界面本身不做任何校验 —— 全部走 `PackRepository`，这里只负责把结果画出来。
 * 反馈遵循项目纪律：成功/切换走全局 Snackbar，装包失败走对话框（用户要看原因）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PacksSheet(
    state: PackUiState,
    onInstall: (Uri) -> Unit,
    onSetActiveDictionary: (String?) -> Unit,
    onUninstall: (String) -> Unit,
    onVerify: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onInstall(uri)
    }
    var uninstallCandidate by remember { mutableStateOf<PackUiItem?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.packs_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.packs_count, state.items.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatStorageBytes(state.totalBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
            }
            Spacer(Modifier.height(12.dp))

            if (state.items.isEmpty()) {
                Text(
                    stringResource(R.string.packs_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.packs_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint
                )
                Spacer(Modifier.height(14.dp))
            }

            Button(
                onClick = { launcher.launch(PACK_MIME_TYPES) },
                enabled = !state.installing,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent)
            ) {
                Text(
                    stringResource(
                        if (state.installing) R.string.packs_installing else R.string.packs_install
                    )
                )
            }
            Spacer(Modifier.height(18.dp))

            val dictionary = state.items.filter { it.type == PackType.DICTIONARY }
            val audio = state.items.filter { it.type == PackType.AUDIO }
            val voice = state.items.filter { it.type == PackType.VOICE }

            if (dictionary.isNotEmpty()) {
                SectionHeader(stringResource(R.string.packs_section_dictionary))
                dictionary.forEach { item ->
                    PackRow(item, state.verifying) {
                        DictionaryActions(
                            item = item,
                            onSetActiveDictionary = onSetActiveDictionary,
                            onVerify = onVerify,
                            onUninstall = { uninstallCandidate = item }
                        )
                    }
                }
            }
            if (audio.isNotEmpty()) {
                SectionHeader(stringResource(R.string.packs_section_audio))
                audio.forEach { item ->
                    PackRow(item, state.verifying) {
                        PackActions(
                            item = item,
                            onVerify = onVerify,
                            onUninstall = { uninstallCandidate = item }
                        )
                    }
                }
            }
            if (voice.isNotEmpty()) {
                SectionHeader(stringResource(R.string.packs_section_voice))
                voice.forEach { item ->
                    PackRow(item, state.verifying) {
                        PackActions(
                            item = item,
                            onVerify = onVerify,
                            onUninstall = { uninstallCandidate = item }
                        )
                    }
                }
            }
        }
    }

    uninstallCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { uninstallCandidate = null },
            title = { Text(stringResource(R.string.packs_uninstall_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.packs_uninstall_message,
                        packDisplayName(candidate),
                        formatStorageBytes(candidate.bytes)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    uninstallCandidate = null
                    onUninstall(candidate.packId)
                }) {
                    Text(stringResource(R.string.packs_uninstall), color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { uninstallCandidate = null }) {
                    Text(stringResource(R.string.packs_cancel))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = InkSoft,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun PackRow(
    item: PackUiItem,
    verifying: String?,
    actions: @Composable () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                packDisplayName(item),
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (item.active) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.packs_active),
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.packs_version, item.version),
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            packDetail(item),
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatStorageBytes(item.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint
            )
            Spacer(Modifier.weight(1f))
            if (verifying == item.packId) {
                Text(
                    stringResource(R.string.packs_verifying),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkFaint
                )
            }
            actions()
        }
    }
}

@Composable
private fun DictionaryActions(
    item: PackUiItem,
    onSetActiveDictionary: (String?) -> Unit,
    onVerify: (String) -> Unit,
    onUninstall: () -> Unit
) {
    TextButton(onClick = { onVerify(item.packId) }) {
        Text(stringResource(R.string.packs_verify))
    }
    if (item.active) {
        TextButton(onClick = { onSetActiveDictionary(null) }) {
            Text(stringResource(R.string.packs_restore_builtin))
        }
    } else {
        TextButton(onClick = { onSetActiveDictionary(item.packId) }) {
            Text(stringResource(R.string.packs_set_active))
        }
    }
    TextButton(onClick = onUninstall) {
        Text(stringResource(R.string.packs_uninstall), color = Danger)
    }
}

@Composable
private fun PackActions(
    item: PackUiItem,
    onVerify: (String) -> Unit,
    onUninstall: () -> Unit
) {
    TextButton(onClick = { onVerify(item.packId) }) {
        Text(stringResource(R.string.packs_verify))
    }
    TextButton(onClick = onUninstall) {
        Text(stringResource(R.string.packs_uninstall), color = Danger)
    }
}

@Composable
private fun packDisplayName(item: PackUiItem): String {
    val zh = java.util.Locale.getDefault().language.startsWith("zh")
    return if (zh) item.nameZh.ifBlank { item.nameEn } else item.nameEn.ifBlank { item.nameZh }
}

@Composable
private fun packDetail(item: PackUiItem): String = when {
    item.dictionary != null -> {
        val words = item.dictionary.wordCount?.let {
            stringResource(R.string.packs_dictionary_words, it)
        } ?: stringResource(R.string.packs_dictionary_words_unknown)
        val source = item.dictionary.source.takeIf { it.isNotBlank() }
        if (source != null) "$words · $source" else words
    }

    item.audio != null -> {
        val book = item.audio.bookTitle
            ?: stringResource(R.string.packs_audio_book_missing)
        stringResource(
            R.string.packs_audio_detail,
            book,
            item.audio.chapters,
            item.audio.voice,
            item.audio.pipelineVersion
        )
    }

    item.voice != null -> stringResource(
        R.string.packs_voice_detail,
        item.voice.count,
        item.voice.cloneCount
    )

    else -> ""
}
