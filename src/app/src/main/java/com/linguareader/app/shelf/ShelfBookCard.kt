package com.linguareader.app.shelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.AiTranslationProgress
import com.linguareader.app.R
import com.linguareader.app.UiTags
import com.linguareader.app.components.StatusChip
import com.linguareader.app.components.StatusChipTone
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.ai.AiBookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 书架上的一张书卡（P3 结构拆解：从 BookshelfScreen.kt 抽出）。
 *
 * 负责：封面（有图 / 无封面兜底渐变）、进度环、标题与作者、三条状态徽章、两行操作按钮。
 * 依赖：设计 token、`StatusChip`、`decodeSampledCover`（同文件）、`ShelfCardAction`（同文件）。
 *
 * **状态语义一行未改**：`aiStatus` / `attachingTranslation` / `aiTranslationProgress` /
 * `translationOutdated` 的判定分支与迁移前逐条对应 —— 拆文件时最容易在这类条件表达式里
 * 「顺手简化」，那属于行为变更。
 *
 * 本组件不接受颜色参数：所有样式取自 [LocalDesignTokens]（与 `LinguaCard` 同一约束）。
 */
@Composable
internal fun ShelfBookCard(
    book: Book,
    aiEnabled: Boolean,
    aiStatus: AiBookStatus?,
    attachingTranslation: Boolean,
    aiTranslationProgress: AiTranslationProgress?,
    /** 对齐档案由更旧版对齐器写出：书卡上要看得见（入口在译本菜单里）。 */
    translationOutdated: Boolean = false,
    containerAlpha: Float = 1f,
    onOpen: () -> Unit,
    onGlossary: () -> Unit,
    onManageRoster: () -> Unit,
    onTranslation: () -> Unit,
    onLongPressDelete: () -> Unit
) {
    // 书卡是本屏最密的区域：书封 + 标题 + 作者/进度 + 至多三条状态徽章 + 两行操作按钮。
    val t = LocalDesignTokens.current
    Column(
        modifier = Modifier
            .testTag(UiTags.bookCard(book.id))
            .clickable(onClick = onOpen)
    ) {
        Box {
            // 书封用 Card 而不是 LinguaCard：LinguaCard 内部固定带内边距、且把内容包进
            // Column；书封需要「图/Crop 铺满整块、无内边距」的纯容器。样式仍然是 token
            // 驱动 —— 圆角、底色、层级全部来自 LocalDesignTokens。
            Card(
                modifier = Modifier.fillMaxWidth().height(BookCoverHeight),
                shape = t.cardShape,
                // 自定义背景时书卡略透，与背景融合；有封面的书本来就完全被图盖住。
                colors = CardDefaults.cardColors(
                    containerColor = t.colors.bookCoverFallback.copy(alpha = containerAlpha)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = t.elevation.card)
            ) {
                val coverFile = book.coverRelativePath?.let { File(book.extractedDir, it) }
                val bitmap by produceState<android.graphics.Bitmap?>(null, coverFile?.absolutePath) {
                    value = withContext(Dispatchers.IO) {
                        coverFile?.takeIf { it.exists() }?.let { decodeSampledCover(it, 300) }
                    }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 无封面：书脊渐变 + 书名/作者。渐变两端都取 token
                    // （accentSoft → bookCoverFallback），换肤时跟着走。
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(t.colors.accentSoft, t.colors.bookCoverFallback)
                                )
                            )
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(BookCoverPadding),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                book.title,
                                // 排版梯的 title 本身就是衬线（SkinTypography.titleFamily =
                                // SERIF），与原来显式指定的 FontFamily.Serif 同义。
                                style = t.typography.title,
                                color = t.colors.ink,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(t.spacing.s))
                            Text(book.author, style = t.typography.label, color = t.colors.inkSoft)
                        }
                    }
                }
            }
            if (book.progress > 0f) {
                // 进度环浮在书封右上角：外面一圈纸色底盘把它与封面图隔开，
                // 否则深色封面上那条 accent 细环几乎看不见。
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(BookProgressInset)
                        .size(BookProgressSize)
                        .background(t.colors.paper.copy(alpha = BookProgressScrimAlpha), CircleShape)
                        .padding(t.spacing.xs)
                ) {
                    CircularProgressIndicator(
                        progress = { book.progress },
                        modifier = Modifier.fillMaxSize(),
                        color = t.colors.accent,
                        trackColor = t.colors.accent.copy(alpha = BookProgressTrackAlpha),
                        strokeWidth = BookProgressStroke
                    )
                }
            }
        }
        Spacer(Modifier.height(t.spacing.xs))
        Text(
            book.title,
            style = t.typography.body.copy(fontWeight = FontWeight.Medium),
            color = t.colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            if (book.progress > 0f) {
                stringResource(R.string.shelf_progress_read, (book.progress * 100).roundToInt())
            } else {
                book.author
            },
            style = t.typography.caption,
            color = t.colors.inkSoft,
            maxLines = 1
        )
        if (aiEnabled) {
            // AiBookStatus 已迁入 :shared：跨模块属性不做智能转换，先捕获局部量。
            val statusError = aiStatus?.error
            val statusLabel = when {
                aiStatus == null -> stringResource(R.string.shelf_ai_pending)
                aiStatus.generating -> stringResource(R.string.shelf_ai_generating)
                // 配了 Key 但生成时降级为本地：不显示「就绪」，留空即可（真正的
                // 诊断交给设置里的「测试连接」）。
                aiStatus.degraded -> ""
                aiStatus.ready -> stringResource(R.string.shelf_ai_ready)
                statusError != null -> stringResource(R.string.shelf_ai_error, statusError)
                else -> stringResource(R.string.shelf_ai_failed)
            }
            if (statusLabel.isNotBlank()) {
                // 状态徽章：成功态用 SUCCESS 语义色、其余（待生成/生成中/失败）
                // 用中性 —— 与原来「ready → Success，其它 → InkFaint」的分档一致。
                //
                // 刻意用 StatusChip 而不是一行小字：书卡上三种状态（AI 语境 / 译本 /
                // 档案过期）会叠加，纯文字颜色相近、挤在一起分不清谁是谁。
                StatusChip(
                    text = statusLabel,
                    tone = if (aiStatus?.ready == true) StatusChipTone.SUCCESS else StatusChipTone.NEUTRAL
                )
            }
        }
        if (attachingTranslation || book.hasTranslation || aiTranslationProgress != null) {
            val aiProgress = aiTranslationProgress
            // 「进行中」与「已就绪」在原来就分了色（InkFaint vs Success），
            // 换徽章后仍按同一分档：进行中 = 中性、已就绪 = 成功。
            StatusChip(
                text = when {
                    attachingTranslation -> stringResource(R.string.shelf_translation_aligning)
                    aiProgress?.preparing == true ->
                        stringResource(R.string.shelf_translation_ai_preparing)
                    aiProgress?.aligning == true ->
                        stringResource(R.string.shelf_translation_ai_aligning)
                    aiProgress?.polish == true ->
                        stringResource(R.string.shelf_translation_ai_polish_progress, aiProgress.percent)
                    aiProgress != null ->
                        stringResource(R.string.shelf_translation_ai_progress, aiProgress.percent)
                    else -> book.translationTitle.ifBlank { stringResource(R.string.shelf_translation_ready) }
                },
                tone = if (attachingTranslation || aiProgress != null) {
                    StatusChipTone.NEUTRAL
                } else {
                    StatusChipTone.SUCCESS
                }
            )
            // 版本闸门（阶段 2）：档案由更旧版对齐器写出。这里只是可见提示，
            // 动作入口在译本菜单里（书卡不放第二个按钮）。
            // 与上面那条徽章**并存**，所以是第二个 chip 而不是替身。
            if (translationOutdated && !attachingTranslation && aiProgress == null) {
                // 用 ACCENT 而不是 WARNING：迁移前这条提示就是 accent 色，
                // P3 的验收前提是「迁移前后视觉等价」；「该不该改成警示色」是设计决策，
                // 不该混在结构迁移里悄悄发生。要改另开一条。
                StatusChip(
                    text = stringResource(R.string.shelf_translation_outdated),
                    tone = StatusChipTone.ACCENT
                )
            }
        }
        // 书卡操作区：两行、每行两个「图标 + 文字」按钮。
        //
        // 刻意**不**用 LinguaTextButton：那个按 token 的 controlHeight(48dp) 给最小高度，
        // 两行就会把书卡撑高一倍，而网格里每张卡的纵向空间是固定的。BookActionHeight
        // 是本屏专有几何常量，与 ReviewUi 保留图标/胶囊尺寸同一口径。
        Row {
            ShelfCardAction(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                text = stringResource(R.string.shelf_glossary),
                tint = t.colors.accent,
                onClick = onGlossary
            )
            ShelfCardAction(
                icon = Icons.Default.Groups,
                text = stringResource(R.string.shelf_roster),
                tint = t.colors.accent,
                onClick = onManageRoster
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShelfCardAction(
                icon = Icons.Default.Delete,
                text = stringResource(R.string.shelf_remove),
                tint = t.colors.inkFaint,
                onClick = onLongPressDelete
            )
            // 译本按钮的三态色（进行中/已有/未加）本来就是语义分档，
            // 原样搬进参数，不改判定。
            ShelfCardAction(
                icon = Icons.Filled.Translate,
                text = when {
                    aiTranslationProgress != null ->
                        stringResource(R.string.shelf_translation_ai_cancel)
                    book.hasTranslation -> stringResource(R.string.shelf_translation_ready)
                    else -> stringResource(R.string.shelf_translation_add)
                },
                tint = when {
                    aiTranslationProgress != null -> t.colors.inkFaint
                    book.hasTranslation -> t.colors.success
                    else -> t.colors.accent
                },
                enabled = !attachingTranslation,
                onClick = onTranslation
            )
        }
    }
}

/**
 * 书卡上的紧凑「图标 + 文字」按钮（术语表 / 角色 / 移除 / 译本）。
 *
 * 为什么不用 `components/LinguaTextButton`：那个按 token 的 `controlHeight`(48dp) 给最小
 * 高度，而书卡里这排按钮排两行、每行至多 4 个字，用 48dp 会把书卡撑高一倍，
 * 网格一屏就少放半排书。这是**本屏的形状参数**，与 ReviewUi 保留图标尺寸、
 * ShelfAppearanceSheet 保留色块直径同一口径。
 *
 * 颜色仍然全部由调用方从 [LocalDesignTokens] 取，本组件不自造色值。
 */
@Composable
private fun ShelfCardAction(
    icon: ImageVector,
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val t = LocalDesignTokens.current
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(BookActionHeight),
        contentPadding = PaddingValues(horizontal = t.spacing.xs)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(BookActionIconSize),
            tint = if (enabled) tint else t.colors.inkFaint
        )
        Spacer(Modifier.width(BookActionIconGap))
        Text(
            text = text,
            style = t.typography.label,
            color = if (enabled) tint else t.colors.inkFaint,
            maxLines = 1
        )
    }
}

// ── 书卡的几何常量 ────────────────────────────────────────────────
//
// 不进间距梯：它们是书卡自身的形状参数，梯子里没有对应档位（205/18/38/10/3/30 硬套都会改像素）。

/** 书封区域的固定高度（网格列宽会变，书封高度不变，保证一排书卡顶部齐平）。 */
private val BookCoverHeight = 205.dp

/** 书封内文字（无封面时的书名/作者）的内边距。 */
private val BookCoverPadding = 18.dp

/** 书封右上角进度环的直径与离书封边缘的距离（内圈的 4dp 用 spacing.xs）。 */
private val BookProgressSize = 38.dp
private val BookProgressInset = 10.dp

/** 进度环笔画宽度。 */
private val BookProgressStroke = 3.dp

/** 书卡操作按钮的行高与行内图标（见 [ShelfCardAction] 的注释：不能用 token 的 48）。 */
private val BookActionHeight = 30.dp
private val BookActionIconSize = 14.dp
private val BookActionIconGap = 3.dp

/** 进度环纸色底盘的透明度：压得住封面图，又不至于把下面的画完全盖住。 */
private const val BookProgressScrimAlpha = 0.94f

/** 进度环未走完那段轨道的透明度（比环本身淡，避免双环打架）。 */
private const val BookProgressTrackAlpha = 0.14f

/**
 * 按目标宽度采样解码封面图（`inSampleSize` 按 2 的幂递减），避免整本大图进内存。
 */
internal fun decodeSampledCover(file: File, targetWidth: Int): android.graphics.Bitmap? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
}
