package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.app.data.ReaderTheme
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.tts.TtsPlaybackState
import com.linguareader.shared.tts.DegradedReason
import java.util.Locale

/**
 * Bottom playback bar shown while a book is being read aloud.
 *
 * **两行布局**：上行放「当前朗读句 / 离屏提示」，下行只放传输键、停止与溢出菜单。
 * 原因：单行塞 9 个控件在 360dp 宽的屏上必然溢出——真机上表现为多一个「缓存全书」
 * 键就把末尾的「停止」顶出屏幕。现在控制行固定宽度最大约 300dp（含滑动模式的
 * 「分页」），窄屏不再挤爆。
 *
 * **配色完全跟随阅读主题**（背景/前景/强调色都取自 [ReaderTheme]，与底部翻页栏
 * 同一口径）：此前这里用外壳调色板（只有米白/夜间两套），选「护眼」「护眼绿」
 * 等浅色主题时底栏跟主题、听书条却是米白，两者不一致。
 *
 * ## P3 迁移的边界（**这一屏有意不动配色来源**）
 *
 * 迁移只改：布局间距（走 token 间距梯）、字号字重（走 token 排版梯）、提示行的
 * 组件复用。**底色/字色/强调色仍然从 [ReaderTheme] 取，不换成
 * [LocalDesignTokens]** —— 两者是两套语义：皮肤描述的是「外壳」（书架/弹层）长什么样，
 * 阅读主题描述的是「正文 + 贴正文的这条栏」长什么样。护眼绿主题配纸色皮肤时，
 * 听书条必须跟正文一样是绿的，否则一条米白横杠会横在绿色正文上（这就是当年的
 * 真实事故）。`ListeningRenderTest` 用像素断言把这条钉住了。
 *
 * ⚠️ **若未来要做「听书条也皮肤化」，必须同时改掉 `ListeningRenderTest` 里那条
 * 底色像素断言，并在提交信息里说明为什么护眼主题可以放弃跟正文同色。**
 * 只改实现不改断言 = 测试红；只删断言不改理由 = 这条设计判断就此失传。
 * 把「怎么推翻它」也写在这里，是因为下一个想统一配色的人多半读不到这段历史。
 *
 * 同理，**播放控制回调的语义一个字没动**：这些不是普通按钮，背后是
 * `TtsPlaybackController` 的播放状态机（上一句/下一句要 stop 再播、章节握手、
 * 代际号隔离都在引擎里），这里只负责画与转发。
 */
@Composable
internal fun ListeningBar(
    state: TtsPlaybackState,
    /** 阅读主题：背景/前景/强调色全由它决定（见类注释）。 */
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onRateChange: (Float) -> Unit,
    choosingStart: Boolean = false,
    onChooseStart: () -> Unit = {},
    /**
     * 朗读句已跑出视口。用户接管期间我们不硬把页面翻回去（那样他就没法往后
     * 看了），改成在上行主动提示。
     */
    speakingOffscreen: Boolean = false,
    /** 「回到朗读处」：把视口挪回正在朗读的那句，并恢复自动跟随。 */
    onBackToSpeaking: () -> Unit = {},
    /** 退出滑动模式（仅滑动模式下给出；它是滑动模式的唯一出口，不能藏进菜单）。 */
    onExitScrollMode: (() -> Unit)? = null
) {
    val t = LocalDesignTokens.current
    var overflowMenuOpen by remember { mutableStateOf(false) }
    // semantics{} 不是 composable 作用域，无障碍标签先在这里取出来。
    val previousLabel = stringResource(R.string.player_previous)
    val pauseLabel = stringResource(R.string.player_pause)
    val playLabel = stringResource(R.string.player_play)
    val nextLabel = stringResource(R.string.player_next)
    val startLabel = stringResource(R.string.player_set_start)
    val backToSpeakingLabel = stringResource(R.string.player_back_to_speaking)
    val stopLabel = stringResource(R.string.player_stop)
    val offscreenHint = stringResource(R.string.player_offscreen_hint)
    // 6-6：降级/暂停原因的可见文案（null = 正常，不显示）。
    val degradedHint = degradedHintRes(state.degradedReason)?.let { stringResource(it) }
    val paginationLabel = stringResource(R.string.reader_pagination)
    val overflowLabel = stringResource(R.string.player_more)

    val background = themeColor(theme.background)
    val foreground = themeColor(theme.foreground)
    // 主题的「标记色」（生词下划线）与日/夜调色板的强调色同源同值：日间 #8D5535、
    // 夜间 #C98A5E（见 Models.kt 的 ReaderTheme 注释）。复用它，强调色随主题走。
    val accent = themeColor(theme.markColor)

    val progress = if (state.sentenceCount > 0) {
        state.sentenceIndex.toFloat() / state.sentenceCount
    } else {
        0f
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = BarHorizontalPadding, vertical = BarVerticalPadding)
    ) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(ProgressBarHeight),
            color = accent,
            trackColor = accent.copy(alpha = ProgressTrackAlpha)
        )

        // ── 降级提示（6-6）：云合成失败回退系统语音 / 连错暂停，都要说清原因 ──
        // 整行占宽，避免挤进控制行（与下方「跑出视口」提示同一处理方式）。
        degradedHint?.let { hint ->
            BarHintRow(
                icon = Icons.Filled.Info,
                text = hint,
                tint = accent,
                maxLines = 2
            )
        }

        // ── 信息行：当前句，或「朗读已跑出视口」的提示 ──────────────────
        // 朗读处未知（还没开始念）时不给「回到朗读处」：按下也没地方去。
        if (speakingOffscreen && state.highlightBlockIndex >= 0) {
            // 主动提示：朗读跑出视口了。不弹窗、不硬翻页（用户接管期间
            // 页面归他）。提示整行占宽，控制行不再受它膨胀影响——此前它在
            // 控制行里会从 48dp 图标撑成 ~110dp 的文字按钮，是挤爆的元凶。
            BarHintRow(
                icon = Icons.Filled.MyLocation,
                text = offscreenHint,
                tint = accent,
                maxLines = 1,
                trailing = {
                    TextButton(onClick = onBackToSpeaking) {
                        Text(backToSpeakingLabel, color = accent)
                    }
                }
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(top = t.spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        choosingStart -> stringResource(R.string.player_choose_start_hint)
                        state.currentSentence.isNotBlank() -> state.currentSentence
                        state.isPlaying -> stringResource(R.string.player_preparing)
                        else -> stringResource(R.string.player_paused)
                    },
                    style = t.typography.caption,
                    color = foreground.copy(alpha = SentenceAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── 控制行：传输键 + 停止 + 溢出菜单 ──────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(top = ControlRowTopGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevious,
                modifier = Modifier.semantics { contentDescription = previousLabel }
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = foreground)
            }
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(PlayButtonSize)
                    .semantics {
                        contentDescription = if (state.isPlaying) pauseLabel else playLabel
                    }
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(PlayIconSize)
                )
            }
            IconButton(
                onClick = onNext,
                modifier = Modifier.semantics { contentDescription = nextLabel }
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, tint = foreground)
            }
            Spacer(Modifier.weight(1f))
            if (onExitScrollMode != null) {
                // 刻意仍是 Material TextButton 而不是 LinguaTextButton：这个按钮的文字色
                // 必须等于**阅读主题**的前景色（真机上它压在绿色/米色正文底上），而原语
                // 只有 accent 一种主色，套上去在护眼绿主题下会变成棕褐字压绿底。
                // 与 PacksSheet 的 DangerTextButton 同一类取舍：语义由阅读主题决定，
                // 组件原语表达不了，故保留 Material 组件但配色全取主题色、不写裸色值。
                TextButton(onClick = onExitScrollMode) {
                    Text(paginationLabel, color = foreground)
                }
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier.semantics { contentDescription = stopLabel }
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = foreground.copy(alpha = IconAlpha)
                )
            }
            Box {
                IconButton(
                    onClick = { overflowMenuOpen = true },
                    modifier = Modifier.semantics { contentDescription = overflowLabel }
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = null,
                        tint = foreground.copy(alpha = IconAlpha)
                    )
                }
                DropdownMenu(
                    expanded = overflowMenuOpen,
                    onDismissRequest = { overflowMenuOpen = false }
                ) {
                    // 朗读处未知（还没开始念）时不显示：按下也没地方去。
                    // 菜单是独立窗口层，MaterialTheme 取的是外壳调色板（只有日/夜两套）。
                    // 这里按阅读主题重设配色，菜单内的文字/滑块才跟着主题走。
                    // ⚠️ 这段重设的是 **MaterialTheme 的颜色槽**（不是设计 token）：
                    // Slider 与 DropdownMenuItem 是 Material 组件、读的是 colorScheme。
                    MaterialTheme(
                        colorScheme = MaterialTheme.colorScheme.copy(
                            surface = background,
                            onSurface = foreground,
                            surfaceVariant = background,
                            onSurfaceVariant = foreground.copy(alpha = MenuHintAlpha),
                            primary = accent,
                            onPrimary = background,
                            outline = accent
                        )
                    ) {
                    if (state.highlightBlockIndex >= 0 && !speakingOffscreen) {
                        DropdownMenuItem(
                            text = { Text(backToSpeakingLabel, style = t.typography.body) },
                            onClick = {
                                overflowMenuOpen = false
                                onBackToSpeaking()
                            },
                            leadingIcon = {
                                MenuItemIcon(Icons.Filled.MyLocation)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(startLabel, style = t.typography.body) },
                        onClick = {
                            overflowMenuOpen = false
                            onChooseStart()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Flag,
                                contentDescription = null,
                                tint = if (choosingStart) accent else Color.Unspecified,
                                modifier = Modifier.size(MenuItemIconSize)
                            )
                        }
                    )
                    HorizontalDivider()
                    var rate by remember(state.speechRate) { mutableFloatStateOf(state.speechRate) }
                    Column(Modifier.padding(horizontal = t.spacing.m, vertical = t.spacing.s)) {
                        Text(
                            stringResource(R.string.player_speed_value, rateLabel(rate)),
                            style = t.typography.body
                        )
                        Slider(
                            value = rate,
                            onValueChange = {
                                rate = it
                                onRateChange(it)
                            },
                            valueRange = 0.5f..2f,
                            steps = 5
                        )
                        Text(
                            stringResource(R.string.player_speed_hint),
                            style = t.typography.label,
                            color = foreground.copy(alpha = MenuHintAlpha)
                        )
                    }
                    }
                }
            }
        }
    }
}

/**
 * 听书条上的「图标 + 说明」提示行（降级原因 / 朗读离屏）。
 *
 * 两处原本各抄一遍（图标尺寸、图标与文字的间隙、顶端留白都一样），迁移时收成一处：
 * 它们的**唯一差别**是有没有尾部动作，[trailing] 那个槽就够，不必再抄第二份。
 * 抽出来的另一个好处是这两处历史事故（挤爆控制行）的布局只在一个地方成立。
 */
@Composable
private fun BarHintRow(
    icon: ImageVector,
    text: String,
    tint: Color,
    maxLines: Int,
    trailing: @Composable (() -> Unit)? = null
) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth().padding(top = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(HintIconSize)
        )
        Text(
            text = text,
            style = t.typography.label,
            color = tint,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = HintIconGap)
        )
        if (trailing != null) trailing()
    }
}

/** 溢出菜单里的行内图标（与菜单文字同一档大小）。 */
@Composable
private fun MenuItemIcon(icon: ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        modifier = Modifier.size(MenuItemIconSize)
    )
}

/** 阅读主题里的 hex 色值（`#RRGGBB`）→ Compose [Color]。 */
internal fun themeColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Unspecified)

private fun rateLabel(rate: Float): String =
    if (rate % 1f == 0f) {
        "${rate.toInt()}×"
    } else {
        String.format(Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.') + "×"
    }

/**
 * 降级原因 → 文案资源（第四轮审查 6-6）。null = 一切正常，不显示任何提示。
 *
 * 抽成纯函数是为了能在 JVM 单测里直接断言"哪种状态显示哪句"—— Compose 渲染本身
 * 需要设备（本仓库的 compose ui-test 依赖只在 androidTest classpath）。
 */
internal fun degradedHintRes(reason: DegradedReason?): Int? = when (reason) {
    null -> null
    DegradedReason.FALLBACK_TO_SYSTEM -> R.string.player_degraded_fallback
    DegradedReason.PAUSED_AFTER_ERRORS -> R.string.player_degraded_paused
}

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 这几组值**不进间距梯**，理由与 ReviewUi / ShelfAppearanceSheet 同一口径：它们不是
// 「间距」，而是这一条栏自己的形状参数。间距梯只有 4/8/16/24/32/48：
//
//  * 12/6（条的内边距）是「贴屏幕底缘的细条」的专属比例；硬套 16/8 会让这条栏变厚
//    约 4dp，而它压缩的是正文高度，属于用户可感知的变化；
//  * 2（进度条厚度）比 spacing.xs(4) 细一半，加厚会让顶部多出一条明显横杠；
//  * 16（提示图标）、6（图标与文字间隙）、2（控制行顶部间隙）、44/32（播放键与其图标）、
//    18（菜单图标）都是**图形尺寸**，token 里只有 iconSize=24 与 controlHeight=48 两档。
//
// P3 的验收前提是「迁移前后视觉等价」，所以这些值逐字照抄原实现、只做「起名」这一件事。

/** 听书条左右内边距。 */
private val BarHorizontalPadding = 12.dp

/** 听书条上下内边距（条要薄，别抢正文的高度）。 */
private val BarVerticalPadding = 6.dp

/** 顶部朗读进度线的粗细（2dp：可见但不喧哗）。 */
private val ProgressBarHeight = 2.dp

/** 提示行图标尺寸（16dp：比正文图标小一档，避免比当前句更抢眼）。 */
private val HintIconSize = 16.dp

/** 提示行图标与文字的间隙。 */
private val HintIconGap = 6.dp

/** 控制行在信息行下方的顶部间隙（2dp：两行要贴在一起才像一条控制条）。 */
private val ControlRowTopGap = 2.dp

/** 播放/暂停键的触达尺寸（44dp：比 IconButton 默认 48 略小，给三段传输键视觉节奏）。 */
private val PlayButtonSize = 44.dp

/** 播放/暂停图标尺寸（32dp：控制行里最大的符号，是主操作）。 */
private val PlayIconSize = 32.dp

/** 溢出菜单行内图标尺寸。 */
private val MenuItemIconSize = 18.dp

// ── 主题色透明度 ──────────────────────────────────────────────────
//
// 这些不是「颜色」而是**同一个主题色的深浅档**：底色/前景色由阅读主题决定，深浅只能
// 靠 alpha 从它派生（另配一份颜色会在换主题时对不上）。所以留在文件里而不进 token。

/** 当前朗读句相对前景色的透明度（次要信息，但不至于读不清）。 */
private const val SentenceAlpha = .65f

/** 停止/更多这两个「次要动作」图标的透明度。 */
private const val IconAlpha = .6f

/** 进度条轨道 = 强调色的极浅一档。 */
private const val ProgressTrackAlpha = .12f

/** 菜单里说明文字的透明度。 */
private const val MenuHintAlpha = .55f
