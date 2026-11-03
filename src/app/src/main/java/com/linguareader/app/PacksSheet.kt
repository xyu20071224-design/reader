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
import androidx.compose.material3.ButtonDefaults
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
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.data.formatStorageBytes
import com.linguareader.app.packs.LoadWarning
import com.linguareader.app.packs.PackUiItem
import com.linguareader.app.packs.PackUiState
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.update.GitHubReleaseParser

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
 *
 * P3 迁移：外壳换成 [LinguaSheet]（拖柄/圆角/边距统一由组件给），分区标题换成
 * [SectionHeader]，「当前使用中」换成 [StatusChip]，按钮换成
 * [PrimaryButton]/[LinguaTextButton]，样式一律取 [LocalDesignTokens]。
 * **安装/切换/卸载/校验的语义、离线优先边界、文案零改动。**
 *
 * 标题刻意**不**进 [LinguaSheet] 的 title 槽：这一屏的标题行右侧还挂着「已安装 N 个」
 * 与「磁盘实测 X」，是一整行三段式信息条；拆成「容器标题 + 内容行」会让计数与占用
 * 失去归属，且多占一行高度。与 [AiDrawerSheet] 同一处理方式。
 */
@Composable
internal fun PacksSheet(
    state: PackUiState,
    onInstall: (Uri) -> Unit,
    onSetActiveDictionary: (String?) -> Unit,
    onUninstall: (String) -> Unit,
    onVerify: (String) -> Unit,
    /** 7-9：清理未登记目录 / `.tmp` 残留。 */
    onCleanResiduals: () -> Unit,
    /** Q2-c03：从 GitHub Releases 拉取可用资源包列表（用户显式触发，离线时不点即可）。 */
    onLoadRemotePacks: () -> Unit,
    /** Q2-c03：下载并安装选中的远端资源包。 */
    onInstallRemotePack: (GitHubReleaseParser.PackAsset) -> Unit,
    onDismiss: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onInstall(uri)
    }
    var uninstallCandidate by remember { mutableStateOf<PackUiItem?>(null) }

    LinguaSheet(
        onDismiss = onDismiss,
        // 内容体自带显式 Spacer 排布（已迁移的两个 sheet 同一口径），关掉容器默认块间距。
        contentArrangement = Arrangement.Top
    ) {
        PacksContent(
            state = state,
            onInstallClick = { launcher.launch(PACK_MIME_TYPES) },
            onSetActiveDictionary = onSetActiveDictionary,
            onVerify = onVerify,
            onUninstallRequest = { uninstallCandidate = it },
            onCleanResiduals = onCleanResiduals,
            onLoadRemotePacks = onLoadRemotePacks,
            onInstallRemotePack = onInstallRemotePack
        )
    }

    uninstallCandidate?.let { candidate ->
        PacksUninstallDialog(
            candidate = candidate,
            onConfirm = {
                uninstallCandidate = null
                onUninstall(candidate.packId)
            },
            onDismiss = { uninstallCandidate = null }
        )
    }
}

/**
 * 弹层的**内容体**，与弹层外壳及卸载确认框解耦。
 *
 * 理由与 `UpdateSheetContent` / `ShelfAppearanceContent` 相同：`ModalBottomSheet`
 * 在渲染台（Robolectric）里画不进位图，抽出来才能做迁移前后对比。
 *
 * ⚠️ 抽取的代价相同：**不再自带水平/底部留白、滚动与纸色底**，单独使用时调用方必须补齐
 * （渲染台用法见 `SettingsSheetRenderTest`）。安装入口由外壳给（它持有 SAF launcher），
 * 这里只负责画按钮。
 */
@Composable
internal fun PacksContent(
    state: PackUiState,
    onInstallClick: () -> Unit,
    onSetActiveDictionary: (String?) -> Unit,
    onVerify: (String) -> Unit,
    onUninstallRequest: (PackUiItem) -> Unit,
    onCleanResiduals: () -> Unit,
    onLoadRemotePacks: () -> Unit,
    onInstallRemotePack: (GitHubReleaseParser.PackAsset) -> Unit
) {
    val t = LocalDesignTokens.current

    // 这一屏的内容在 891dp 高的手机上远超一屏（四个分区 + 远端列表 + 残留），
    // 滚动**必须由内容体自己拥有**：LinguaSheet 的内容槽只是普通 Column，不给滚动；
    // 迁移前是外壳的 Column 上挂 verticalScroll，换成弹层组件后那层没了 ——
    // 若不在这里补回来，长列表会直接溢出到屏幕外且拖不动（渲染台的静态 PNG 看不出来，
    // 这是「像素对 ≠ 行为对」的典型坑，真机必现）。
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.packs_title),
                style = t.typography.title,
                color = t.colors.ink
            )
            Spacer(Modifier.width(PacksTitleGap))
            Text(
                text = stringResource(R.string.packs_count, state.items.size),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
            Spacer(Modifier.weight(1f))
            // 6-14/7-13：注明这是**磁盘实测**（与存储页同源），而非各条清单声明之和。
            Text(
                text = stringResource(R.string.packs_total_on_disk, formatStorageBytes(state.totalBytes)),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
        }
        Spacer(Modifier.height(PacksHeaderGap))

        state.loadWarning?.let { warning ->
            Text(
                text = stringResource(
                    when (warning) {
                        LoadWarning.REGISTRY_DAMAGED -> R.string.packs_load_warning_registry_damaged
                        LoadWarning.ACTIVE_DICTIONARY_MISSING -> R.string.packs_load_warning_active_missing
                    }
                ),
                style = t.typography.caption,
                color = t.colors.danger
            )
            Spacer(Modifier.height(PacksSectionGap))
        }

        if (state.items.isEmpty()) {
            Text(
                text = stringResource(R.string.packs_empty),
                style = t.typography.body,
                color = t.colors.inkSoft
            )
            Spacer(Modifier.height(PacksHintGap))
            Text(
                text = stringResource(R.string.packs_empty_hint),
                style = t.typography.caption,
                color = t.colors.inkFaint
            )
            Spacer(Modifier.height(PacksEmptyGap))
        }

        PrimaryButton(
            text = stringResource(
                if (state.installing) R.string.packs_installing else R.string.packs_install
            ),
            onClick = onInstallClick,
            enabled = !state.installing
        )
        Spacer(Modifier.height(PacksBigGap))

        // 7-9：未登记目录 / .tmp 残留 —— 它们已计入上方「磁盘实测」占用，
        // 但不在包列表里，用户看得到占用却找不到可删的东西。这里显式列出 + 给清理入口。
        if (state.residuals.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.packs_residuals_title))
            Text(
                text = stringResource(
                    R.string.packs_residuals_found,
                    state.residuals.size,
                    formatStorageBytes(state.residuals.sumOf { it.bytes })
                ),
                style = t.typography.body,
                color = t.colors.ink
            )
            Text(
                text = stringResource(R.string.packs_residuals_hint),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
            DangerTextButton(
                text = stringResource(R.string.packs_residuals_clean),
                onClick = onCleanResiduals
            )
            Spacer(Modifier.height(PacksSectionGap))
        }

        // Q2-c03：从 GitHub Releases 获取资源包。离线优先不破 —— 不点这个按钮
        // 就不会联网；下载完成后复用同一条安装管线。
        SectionHeader(text = stringResource(R.string.packs_remote_title))
        Text(
            text = stringResource(R.string.packs_remote_hint),
            style = t.typography.caption,
            color = t.colors.inkFaint
        )
        Spacer(Modifier.height(t.spacing.s))
        PrimaryButton(
            text = stringResource(
                if (state.remote.loading) R.string.packs_remote_loading else R.string.packs_remote_load
            ),
            onClick = onLoadRemotePacks,
            enabled = !state.remote.loading && state.remote.downloading == null
        )
        state.remote.error?.let { error ->
            Spacer(Modifier.height(PacksHintGap))
            Text(
                text = stringResource(R.string.packs_remote_failed, error),
                style = t.typography.caption,
                color = t.colors.danger
            )
        }
        if (!state.remote.loading && state.remote.error == null && state.remote.assets.isEmpty()) {
            Spacer(Modifier.height(PacksHintGap))
            Text(
                text = stringResource(
                    if (state.remote.fetched) R.string.packs_remote_empty else R.string.packs_remote_none_yet
                ),
                style = t.typography.caption,
                color = t.colors.inkFaint
            )
        }
        state.remote.assets.forEach { asset ->
            Spacer(Modifier.height(t.spacing.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = asset.name, style = t.typography.body, color = t.colors.ink)
                    if (asset.bytes > 0) {
                        Text(
                            text = formatStorageBytes(asset.bytes),
                            style = t.typography.label,
                            color = t.colors.inkFaint
                        )
                    }
                }
                LinguaTextButton(
                    text = stringResource(
                        if (state.remote.downloading == asset.name) {
                            R.string.packs_remote_downloading
                        } else {
                            R.string.packs_remote_download
                        }
                    ),
                    onClick = { onInstallRemotePack(asset) },
                    enabled = state.remote.downloading == null && !state.installing
                )
            }
        }
        Spacer(Modifier.height(PacksBigGap))

        val dictionary = state.items.filter { it.type == PackType.DICTIONARY }
        val audio = state.items.filter { it.type == PackType.AUDIO }
        val voice = state.items.filter { it.type == PackType.VOICE }
        val bundle = state.items.filter { it.type == PackType.BUNDLE }

        if (dictionary.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.packs_section_dictionary))
            dictionary.forEach { item ->
                PackRow(item, state.verifying) {
                    DictionaryActions(
                        item = item,
                        onSetActiveDictionary = onSetActiveDictionary,
                        onVerify = onVerify,
                        onUninstall = { onUninstallRequest(item) }
                    )
                }
            }
        }
        if (audio.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.packs_section_audio))
            audio.forEach { item ->
                PackRow(item, state.verifying) {
                    PackActions(
                        item = item,
                        onVerify = onVerify,
                        onUninstall = { onUninstallRequest(item) }
                    )
                }
            }
        }
        if (voice.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.packs_section_voice))
            voice.forEach { item ->
                PackRow(item, state.verifying) {
                    PackActions(
                        item = item,
                        onVerify = onVerify,
                        onUninstall = { onUninstallRequest(item) }
                    )
                }
            }
        }
        // Q2-c02：集合包 —— 一个包捆绑多个成员包，安装时逐个装好。
        if (bundle.isNotEmpty()) {
            SectionHeader(text = stringResource(R.string.packs_section_bundle))
            bundle.forEach { item ->
                Column(Modifier.fillMaxWidth()) {
                    PackRow(item, state.verifying) {
                        PackActions(
                            item = item,
                            onVerify = onVerify,
                            onUninstall = { onUninstallRequest(item) }
                        )
                    }
                    item.bundleMembers?.let { count ->
                        Text(
                            text = stringResource(R.string.packs_bundle_members, count),
                            style = t.typography.label,
                            color = t.colors.inkFaint,
                            modifier = Modifier.padding(bottom = PacksHintGap)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个资源包的三行小卡：名称 +（生效徽章）+ 版本 / 明细 / 占用 + 操作。
 *
 * 四种包类型共用同一布局，差异只在 [actions] 与 [packDetail] 的文案。
 */
@Composable
private fun PackRow(
    item: PackUiItem,
    verifying: String?,
    actions: @Composable () -> Unit
) {
    val t = LocalDesignTokens.current
    Column(Modifier.fillMaxWidth().padding(vertical = PacksRowVerticalPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = packDisplayName(item),
                style = t.typography.body,
                color = t.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (item.active) {
                Spacer(Modifier.width(t.spacing.s))
                // 「当前使用中」是个状态徽章，用 StatusChip 表达比裸文字更不容易看漏
                // （全局只有一个生效词典包，看漏的后果是查词结果与预期不符）。
                StatusChip(
                    text = stringResource(R.string.packs_active),
                    tone = StatusChipTone.ACCENT
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.packs_version, item.version),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
        }
        Spacer(Modifier.height(PacksRowGap))
        Text(
            text = packDetail(item),
            style = t.typography.caption,
            color = t.colors.inkFaint
        )
        Spacer(Modifier.height(PacksRowGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatStorageBytes(item.bytes),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
            Spacer(Modifier.weight(1f))
            if (verifying == item.packId) {
                Text(
                    text = stringResource(R.string.packs_verifying),
                    style = t.typography.label,
                    color = t.colors.inkFaint
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
    LinguaTextButton(
        text = stringResource(R.string.packs_verify),
        onClick = { onVerify(item.packId) }
    )
    if (item.active) {
        LinguaTextButton(
            text = stringResource(R.string.packs_restore_builtin),
            onClick = { onSetActiveDictionary(null) }
        )
    } else {
        LinguaTextButton(
            text = stringResource(R.string.packs_set_active),
            onClick = { onSetActiveDictionary(item.packId) }
        )
    }
    DangerTextButton(text = stringResource(R.string.packs_uninstall), onClick = onUninstall)
}

@Composable
private fun PackActions(
    item: PackUiItem,
    onVerify: (String) -> Unit,
    onUninstall: () -> Unit
) {
    LinguaTextButton(
        text = stringResource(R.string.packs_verify),
        onClick = { onVerify(item.packId) }
    )
    DangerTextButton(text = stringResource(R.string.packs_uninstall), onClick = onUninstall)
}

/**
 * 破坏性动作的文字按钮（清理残留、卸载资源包）。
 *
 * 刻意**不是** [LinguaTextButton]：原语只有一种主色（accent），套上去「这是删除操作」
 * 的语义就丢了 —— 而本项目的设计约定正是「危险动作用独立色调，别让用户点错」。
 * 所以这里保留 Material `TextButton`，但配色全部取 token（danger），不写裸色值。
 * 组件层缺口：`components/` 还没有 danger 语义的按钮原语，已在提交信息里记给 Lead。
 */
@Composable
private fun DangerTextButton(text: String, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = t.colors.danger)
    ) {
        Text(text = text, style = t.typography.body)
    }
}

/**
 * 卸载确认框。
 *
 * 没有「弹层确认」原语，沿用 Material `AlertDialog`，但容器色/形状/按钮配色全部取 token
 * （与 `VocabularyScreen` 的确认框同一打法）。**确认按钮是危险动作**，故走
 * [DangerTextButton] 而不是 [LinguaTextButton]。
 */
@Composable
private fun PacksUninstallDialog(
    candidate: PackUiItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val t = LocalDesignTokens.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.packs_uninstall_title), style = t.typography.title) },
        text = {
            Text(
                text = stringResource(
                    R.string.packs_uninstall_message,
                    packDisplayName(candidate),
                    formatStorageBytes(candidate.bytes)
                ),
                style = t.typography.body
            )
        },
        confirmButton = {
            DangerTextButton(text = stringResource(R.string.packs_uninstall), onClick = onConfirm)
        },
        dismissButton = {
            LinguaTextButton(text = stringResource(R.string.packs_cancel), onClick = onDismiss)
        },
        containerColor = t.colors.cardSurface,
        shape = t.cardShape
    )
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

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 这一屏的纵向节奏沿用迁移前的实测值。间距梯只有 4/8/16/24/32/48，下面这些档位
// （10/12/6/14/18/2）在梯子里没有对应值，硬套会改变像素，而 P3 的验收前提是
// 「迁移前后视觉等价」。它们又都是这一屏自己的排布常量（不是「同一语义跨屏」的间距），
// 所以集中命名，而不是往梯子里塞 —— 梯子一旦被塞满特例，就没人能判断某个值该取哪档了。
//
// 唯一走 token 的是 8dp（= spacing.s）：它**在**梯子里，没有理由不用。
private val PacksTitleGap = 10.dp
private val PacksHeaderGap = 12.dp
private val PacksHintGap = 6.dp
private val PacksSectionGap = 10.dp
private val PacksEmptyGap = 14.dp
private val PacksBigGap = 18.dp
private val PacksRowGap = 2.dp
private val PacksRowVerticalPadding = 6.dp
