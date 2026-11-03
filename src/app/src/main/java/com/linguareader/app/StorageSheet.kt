package com.linguareader.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.LinguaTextButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.data.StorageReport
import com.linguareader.app.data.formatStorageBytes
import com.linguareader.app.skin.LocalDesignTokens

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
 *
 * P3 迁移：外壳换成 [LinguaSheet]（拖柄/圆角/边距统一由组件给，标题走 title 槽），
 * 分区标题换成 [SectionHeader]，样式一律取 [LocalDesignTokens]。
 * **交互、文案、扫描/清理语义零改动。**
 */
@Composable
internal fun StorageSheet(
    report: StorageReport?,
    scanning: Boolean,
    /** 资源包数量与占用：存储页只给入口，管理在 [PacksSheet]。 */
    packCount: Int,
    packBytes: Long,
    onRescan: () -> Unit,
    onCleanOrphans: () -> Unit,
    onManagePacks: () -> Unit,
    onDismiss: () -> Unit
) {
    LinguaSheet(
        onDismiss = onDismiss,
        // 标题由容器统一渲染（一致性 + 可访问性：title 槽之后会挂 heading 语义）。
        title = stringResource(R.string.storage_title),
        // 内容体自带显式 Spacer 排布（迁移前就是这样），关掉容器的默认块间距，
        // 否则会 16（默认 spacing.m）+ 自带 Spacer 双倍叠加。
        contentArrangement = Arrangement.Top
    ) {
        StorageContent(
            report = report,
            scanning = scanning,
            packCount = packCount,
            packBytes = packBytes,
            onRescan = onRescan,
            onCleanOrphans = onCleanOrphans,
            onManagePacks = onManagePacks
        )
    }
}

/**
 * 弹层的**内容体**，与弹层外壳解耦。
 *
 * 抽出来的理由：`ModalBottomSheet` 在渲染台（Robolectric）里画不进位图，于是迁移前/后的
 * 视觉对比根本没法做。抽出来之后它也可以在没有 sheet 的场景（例如以后的平板双栏）里复用。
 *
 * ⚠️ **抽取是有代价的：本组件不再自带宽度/背景约束。**
 * 真实使用时它继承弹层 `Column` 的宽度与纸色底；单独使用（渲染台、其它宿主）时必须
 * 由调用方提供——否则文字会按可用的窄宽度排版而被裁断、内容底部以下会露出画布底色。
 * 渲染台里的正确用法见 `SettingsSheetRenderTest`：外面套
 * `Box(Modifier.fillMaxSize().background(Paper))` 并用 `renderDp(411, 891)`。
 *
 * 标题不在内容体里：由 [LinguaSheet] 的 title 槽渲染，避免「容器标题 + 内容标题」两份。
 */
@Composable
internal fun StorageContent(
    report: StorageReport?,
    scanning: Boolean,
    packCount: Int,
    packBytes: Long,
    onRescan: () -> Unit,
    onCleanOrphans: () -> Unit,
    onManagePacks: () -> Unit
) {
    val t = LocalDesignTokens.current

    if (scanning && report == null) {
        Text(
            text = stringResource(R.string.storage_scanning),
            style = t.typography.body,
            color = t.colors.inkSoft
        )
    }

    report?.let { data ->
        Text(
            text = stringResource(R.string.storage_total, formatStorageBytes(data.totalBytes)),
            style = t.typography.body,
            color = t.colors.ink
        )
        Spacer(Modifier.height(t.spacing.s))
        // 占用从大到小排；0 字节的不列，免得一屏噪音。
        data.usages.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.forEach { usage ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = t.spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = storeLabel(usage.storeId),
                    style = t.typography.caption,
                    color = t.colors.inkSoft
                )
                Text(
                    text = formatStorageBytes(usage.bytes),
                    style = t.typography.caption,
                    color = t.colors.ink
                )
            }
        }

        SectionHeader(text = stringResource(R.string.storage_orphans_title))
        if (data.orphans.isEmpty()) {
            Text(
                text = stringResource(R.string.storage_orphans_none),
                style = t.typography.caption,
                color = t.colors.inkFaint
            )
        } else {
            Text(
                text = stringResource(
                    R.string.storage_orphans_found,
                    data.orphans.size,
                    formatStorageBytes(data.orphanBytes)
                ),
                style = t.typography.body,
                color = t.colors.ink
            )
            Text(
                text = stringResource(R.string.storage_orphans_hint),
                style = t.typography.label,
                color = t.colors.inkFaint
            )
            // 危险动作（删数据）刻意不用 [LinguaTextButton]：组件只有一种主色，
            // 套上去「这是破坏性操作」的语义就丢了。保留 Material TextButton，配色取自 token。
            // 组件层缺口，已在提交信息里记给 Lead。
            TextButton(
                onClick = onCleanOrphans,
                colors = ButtonDefaults.textButtonColors(contentColor = t.colors.danger)
            ) {
                Text(text = stringResource(R.string.storage_orphans_clean), style = t.typography.body)
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        LinguaTextButton(
            text = stringResource(R.string.storage_rescan),
            onClick = onRescan,
            enabled = !scanning
        )
    }

    // 资源包入口：它也是存储大户（整书音频包可达 GB 级），放在存储页语义自洽；
    // 书架顶栏已有 6 个图标，再加一个会把标题挤没。
    SectionHeader(text = stringResource(R.string.storage_store_packs))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (packCount == 0) {
                stringResource(R.string.packs_empty)
            } else {
                stringResource(R.string.packs_count, packCount) + " · " + formatStorageBytes(packBytes)
            },
            style = t.typography.caption,
            color = t.colors.inkSoft
        )
        LinguaTextButton(
            text = stringResource(R.string.storage_packs_manage),
            onClick = onManagePacks
        )
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
    "packs" -> stringResource(R.string.storage_store_packs)
    else -> storeId
}
