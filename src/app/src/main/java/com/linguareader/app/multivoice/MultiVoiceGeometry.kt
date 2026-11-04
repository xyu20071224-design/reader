package com.linguareader.app.multivoice

import androidx.compose.ui.unit.dp

/**
 * 多角色音色屏的**组件专有几何常量**（跨文件共享）。
 *
 * ## 为什么单独一个文件
 *
 * 这些值原本散在 MultiVoiceSettings.kt 底部，属于「7 个对话框 + 主列表 + 音色面板」
 * 三块共用的形状参数。拆文件后如果各自留一份拷贝，第一处改、第二处忘，就会出现
 * 「同一个胶囊在弹层里和列表里不一样宽」这种只有真机才看得出的漂移。
 * 所以它们集中在这里，由所有 MultiVoice* 文件共享**同一个**定义。
 *
 * ## 为什么它们不落进间距梯
 *
 * 能落进 token 的已经在样式迁移时落进去了（4/8/16/24 -> spacing.xs/s/m/l）。剩下这些：
 *
 *  * 10 / 6 / 2 — 间距梯只有 4/8/16/24/32/48。10dp 是「标签与其控件」的专用档
 *    （套 8 会挤、套 16 会散），6dp 是「标题与其说明」的档，2dp 是同一行两小元素的缝。
 *  * 12 / 14 / 18 — 分区之间与横向分割线两侧，是这一屏的三级层次节奏。
 *  * 13 / 16 / 18 / 20 / 26 — 图标尺寸。token 只有 iconSize=24 一档，而锁徽标（13）
 *    必须比编辑/删除（16）小，否则行内分不出主次。
 *  * 360 / 380 — 两个 LazyColumn 的**最大高度**，是「弹层里能露出几行」的容量参数，
 *    不是间距。
 *
 * 逐条理由见下方每个常量的 KDoc。
 */

/** 横向分割线（「多角色」区块的入口线）上下留白。 */
internal val SectionRuleGap = 18.dp

/** 同一区块内相邻两块之间（如「标题行 → 角色列表」）的留白。 */
internal val SectionGap = 12.dp

/** MiMo 专属音色面板与其他区块之间的留白（比 SectionGap 大一档，它是独立的二级面板）。 */
internal val MiMoPanelGap = 14.dp

/** 「标签 / 说明文字」与其下方控件之间的留白（间距梯无 10 档）。 */
internal val FieldGap = 10.dp

/** 「标题」与其紧邻的说明文字之间的留白（间距梯无 6 档）。 */
internal val LabelGap = 6.dp

/** 同一行内两个小元素之间的缝（如「+」图标与其文字）。 */
internal val InlineIconGap = 2.dp

/** 列表行之间的竖向留白（比 spacing.xs(4) 紧一档：这一屏行很密）。 */
internal val RowVerticalGap = 2.dp

/** 列表分组标题的下留白。 */
internal val ListHeaderBottomGap = 2.dp

/** 列表行内左右内边距（间距梯无 6 档）。 */
internal val ListRowHorizontalPadding = 6.dp

/** 系统音色标注列表里每一行（音色名 + 开关 + 性别胶囊）的上下留白。 */
internal val AnnotateRowVerticalPadding = 6.dp

/** 性别/语言胶囊的横向内边距（纵向取 spacing.xs）。 */
internal val PillHorizontalPadding = 12.dp

/** 角色名旁「已锁定」小锁的尺寸（13dp：必须比行内其它图标小，否则与本行操作键混淆）。 */
internal val LockIconSize = 13.dp

/** 行内小图标：编辑 / 删除 / 关闭（16dp）。 */
internal val SmallIconSize = 16.dp

/** 行内中图标：试听 / 恢复自动 / 菜单（18dp）。 */
internal val RowIconSize = 18.dp

/** 行内大图标：试听主键（20dp）。 */
internal val MediumIconSize = 20.dp

/** 加载圈的直径（26dp：比正文图标大一圈，明确是「正在工作」而非装饰）。 */
internal val LoadingSpinnerSize = 26.dp

/** 音色选择弹层里列表的最大高度（360dp：约 7~8 行，再多会盖住搜索框）。 */
internal val VoicePickerMaxHeight = 360.dp

/** 系统音色标注弹层里列表的最大高度（380dp：比音色选择器略高，它每行有两排控件）。 */
internal val AnnotateListMaxHeight = 380.dp

/**
 * 未选中胶囊的淡底透明度（`ink.copy(alpha = …)`）。
 *
 * 这是**同一个语义色的深浅档**而不是独立颜色：底必须是当前墨色的极浅一档，
 * 另配一份颜色在换肤时会与文字脱节。token 里没有「wash alpha」这一项
 * （只有 chipWashAlpha 那类给状态徽章用的），所以留在这里。
 */
internal const val PillWashAlpha = .08f
