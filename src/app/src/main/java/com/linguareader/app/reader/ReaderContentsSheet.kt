package com.linguareader.app.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.linguareader.app.Accent
import com.linguareader.app.Ink
import com.linguareader.app.R
import com.linguareader.app.UiTags
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.SheetDivider
import com.linguareader.app.data.Book
import com.linguareader.app.skin.LocalDesignTokens

/**
 * 章节目录弹层。
 *
 * ## 职责
 *
 * 列出全书章节，当前章高亮；点一行跳到那一章。它是一个**只读列表 + 单选回调**，
 * 不持有状态：选中哪一章由调用方通过 [onSelect] 决定，弹层自己不记录「我正在选什么」。
 *
 * ## 依赖的外部状态
 *
 * - [book]：只读 `chapters` 的标题列表（**不读** `book.id` / `progress` / 译本字段）；
 * - [currentChapter]：仅用于高亮当前行；
 * - [onSelect] / [onDismiss]：两个回调。
 *
 * 不读 `ReaderOverlays`、不碰 `ReaderController`、不参与 WebView 协议与 TTS 高亮。
 * 换章的真实动作（`selectChapter` + 落盘 + 通知 TTS）**全部留在 `ReaderScreen`**，
 * 这也是它能独立出来的原因：**这里只负责「用户点了第几章」这一个事实**。
 *
 * ## 为什么可以独立
 *
 * 目录是阅读页里唯一一个「纯数据展示」的弹层。抽出后调用点只剩：
 * `if (overlays.contents) { ReaderContentsSheet(...) }`。
 *
 * ## 迁移状态
 *
 * 本文件从 `ReaderScreen.kt` **原样搬出**（P3 拆解第二笔），逻辑零改动。
 * 样式已在阅读页第一笔迁到 [LocalDesignTokens]。
 *
 * @param book 当前书（只用章节标题）
 * @param currentChapter 当前章索引（0-based），用于高亮
 * @param onSelect 点击某章时回调，参数是 **0-based** 章索引
 * @param onDismiss 关闭弹层
 */
@Composable
internal fun ReaderContentsSheet(
    book: Book,
    currentChapter: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    LinguaSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.reader_contents),
        // 内容体自己带 LazyColumn（列表自身不产生块间距），关掉容器的默认 spacedBy，
        // 否则标题与列表之间会多出一份 spacing.m。
        contentArrangement = Arrangement.Top
    ) {
        ContentsBody(
            book = book,
            currentChapter = currentChapter,
            onSelect = onSelect
        )
    }
}

/**
 * 目录的**内容体**，与 sheet 外壳解耦。
 *
 * 理由与其余已迁弹层一致：`ModalBottomSheet` 在渲染台里画不进位图，抽出内容体才能
 * 出迁移前后的对照图。代价也一样——**不再自带纸色底**，单独使用时调用方必须补齐
 * （渲染台见 `ReaderShellRenderTest`）。
 *
 * 横向留白不再自己写：由 [LinguaSheet] 的 content 槽提供 `spacing.l`。
 * 滚动容器 `LazyColumn` **仍然由本组件自己拥有**——换外壳时最容易丢的就是它
 * （PacksSheet 迁移踩过：`verticalScroll` 挂在外壳上，换壳后长内容拖不动）。
 */
@Composable
internal fun ContentsBody(
    book: Book,
    currentChapter: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val t = LocalDesignTokens.current
    // ⚠️ 高度上限必须保留：迁移前是 `LazyColumn(Modifier.fillMaxHeight(.72f))`。
    // 拿掉上限后列表会「撑到弹层满高」，章节多的书目录会顶到状态栏下面 —— 那是
    // 可见的布局变化，不属于「只换外壳」。这里原样保留 72%，只把字面量命名化。
    // READER_CONTENTS_SHEET 原本挂在 ModalBottomSheet 上（LinguaSheet 不收 modifier，
    // 与 ReviewUi 的 REVIEW_SHEET 同一处理）；内容体是它的子树，语义查找照样命中。
    LazyColumn(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(ContentsMaxHeightFraction)
            .testTag(UiTags.READER_CONTENTS_SHEET)
    ) {
        itemsIndexed(book.chapters) { index, chapter ->
            Row(
                Modifier.fillMaxWidth()
                    .clickable { onSelect(index) }
                    .defaultMinSize(minHeight = t.controlHeight)
                    .padding(vertical = t.spacing.s)
            ) {
                // 序号作为标题文本一部分渲染（书源标题常不带编号，
                // 纯序号列太淡易被当成没有序号）。
                Text(
                    "${index + 1}. ${chapter.title}",
                    color = if (index == currentChapter) Accent else Ink,
                    style = t.typography.body,
                    fontWeight = if (index == currentChapter) FontWeight.Bold else FontWeight.Normal
                )
            }
            SheetDivider()
        }
    }
}

/**
 * 目录列表相对弹层可用高度的占比（迁移前就是 `.72f`）。
 *
 * 必须保留：不加上限时 LazyColumn 会把弹层撑到满高，长目录会顶进状态栏。
 * 这是**布局**约束不是样式值，因此待在调用点、不进 token。
 */
private const val ContentsMaxHeightFraction = .72f
