package com.linguareader.app.shelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linguareader.app.PreviewScaffold
import com.linguareader.app.R
import com.linguareader.app.ShelfAppearance
import com.linguareader.app.ShelfBackgroundStore
import com.linguareader.app.UiTags
import com.linguareader.app.chromeIsDark
import com.linguareader.app.components.EmptyState
import com.linguareader.app.components.PrimaryButton
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.storedReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架的**背景层与空态**（P3 结构拆解：从 BookshelfScreen.kt 抽出）。
 *
 * 负责：自定义背景（图片 Crop / 预设渐变 + 纸色蒙版）与「空书架」引导页。
 * 依赖：`ShelfAppearance`、`decodeSampledCover`（ShelfBookCard.kt）、`EmptyState`、设计 token。
 *
 * 这两块放在一起的理由：它们都是「书架内容为空或有背景时」的整屏铺满层，
 * 与屏幕的状态编排无关，只吃数据与一个回调。
 */

/**
 * 深色外壳 + 自定义背景时的蒙版下限。0.35（用户默认档）在深色墨色下不足以把浅色背景
 * 压到可读；0.72 与无书空态所用的遮罩同档（审查 5-1 建议「提到能保 4.5:1 需 ~0.8，
 * 等于预设失去意义」，故这里仅对图片背景兜底，不作用于预设）。
 */
private const val DARK_CHROME_MIN_SCRIM = 0.72f

// ── 空态 ──────────────────────────────────────────────────────────

/**
 * 空书架引导。
 *
 * 换成原语 `EmptyState`：图标底盘、标题/说明的字号与行宽、动作按钮的间距全部由
 * 设计系统统一。**文案一个字没改**（三条都在 strings.xml 里）。
 *
 * 保留了外层这个 Column：它负责「在剩余空间里垂直居中」，而 `EmptyState` 自己只
 * fillMaxWidth（与生词本空态同一处理）。
 */
@Composable
internal fun ShelfEmptyState(onImport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.SHELF_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyState(
            icon = Icons.AutoMirrored.Filled.MenuBook,
            title = stringResource(R.string.shelf_empty_title),
            body = stringResource(R.string.shelf_empty_body),
            action = {
                PrimaryButton(
                    text = stringResource(R.string.shelf_empty_action),
                    onClick = onImport,
                    icon = Icons.Default.Add
                )
            }
        )
    }
}

// ── 自定义背景层 ──────────────────────────────────────────────────

/**
 * 书架自定义背景层：自定义图片（Crop 铺满）优先，否则用预设渐变；
 * 最上面统一盖一层当前调色板纸色蒙版（浓度可在弹层调），保证顶栏与文字可读。
 *
 * 调用方要把它画在 `LinguaScaffold` **下面**，并给外壳传 `containerColor = Transparent`
 * —— 否则纸色外壳会把这层整块盖住（用户设了背景却什么都看不到）。
 */
@Composable
internal fun ShelfBackgroundLayer(appearance: ShelfAppearance) {
    val context = LocalContext.current
    val t = LocalDesignTokens.current
    // 深色外壳下浅色预设不可读（审查 5-1：叠加后对比度低到 1.02–2.56:1），
    // 此时预设整体不渲染，只留蒙版，等同回到调色板纸色；见 ShelfAppearanceSheet 的同类判定。
    val dark = chromeIsDark(storedReaderTheme(context), isSystemInDarkTheme())
    // 深色外壳 + 自定义背景时加重蒙版，保住顶栏与文字可读。
    val scrimAlpha = if (dark) maxOf(appearance.dimOpacity, DARK_CHROME_MIN_SCRIM) else appearance.dimOpacity
    val scrim = t.colors.paper.copy(alpha = scrimAlpha)
    Box(Modifier.fillMaxSize()) {
        val imageFile = appearance.imageFile
            ?.let { ShelfBackgroundStore.backgroundFile(context, it) }
            ?.takeIf { it.isFile }
        if (imageFile != null) {
            val bitmap by produceState<android.graphics.Bitmap?>(null, imageFile.lastModified()) {
                value = withContext(Dispatchers.IO) {
                    runCatching { decodeSampledCover(imageFile, 1080) }.getOrNull()
                }
            }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        } else if (!dark) {
            appearance.preset?.let { preset ->
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color(preset.topColor), Color(preset.bottomColor)))
                    )
                )
            }
        }
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

// ── 预览（纯设计稿，不参与运行）────────────────────────────────────
//
// 这两个 preview 原在 BookshelfScreen.kt 底部，拆解时随 EmptyBookshelf 一起搬过来。
// `PreviewScaffold` 是 internal，同模块可用。

@Preview(name = "空书架 · 日间", showBackground = true, widthDp = 380, heightDp = 620)
@Composable
private fun ShelfEmptyStateLightPreview() {
    PreviewScaffold { ShelfEmptyState(onImport = {}) }
}

@Preview(name = "空书架 · 夜间", showBackground = true, widthDp = 380, heightDp = 620)
@Composable
private fun ShelfEmptyStateDarkPreview() {
    PreviewScaffold(dark = true) { ShelfEmptyState(onImport = {}) }
}
