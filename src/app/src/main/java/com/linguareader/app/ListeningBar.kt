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
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.linguareader.app.data.ReaderTheme
import com.linguareader.app.tts.TtsPlaybackState
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = accent,
            trackColor = accent.copy(alpha = .12f)
        )

        // ── 信息行：当前句，或「朗读已跑出视口」的提示 ──────────────────
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 朗读处未知（还没开始念）时不给「回到朗读处」：按下也没地方去。
            if (speakingOffscreen && state.highlightBlockIndex >= 0) {
                // 主动提示：朗读跑出视口了。不弹窗、不硬翻页（用户接管期间
                // 页面归他）。提示整行占宽，控制行不再受它膨胀影响——此前它在
                // 控制行里会从 48dp 图标撑成 ~110dp 的文字按钮，是挤爆的元凶。
                Icon(
                    Icons.Filled.MyLocation,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    offscreenHint,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
                )
                TextButton(onClick = onBackToSpeaking) {
                    Text(backToSpeakingLabel, color = accent)
                }
            } else {
                Text(
                    when {
                        choosingStart -> stringResource(R.string.player_choose_start_hint)
                        state.currentSentence.isNotBlank() -> state.currentSentence
                        state.isPlaying -> stringResource(R.string.player_preparing)
                        else -> stringResource(R.string.player_paused)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground.copy(alpha = .65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ── 控制行：传输键 + 停止 + 溢出菜单 ──────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
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
                    .size(44.dp)
                    .semantics {
                        contentDescription = if (state.isPlaying) pauseLabel else playLabel
                    }
            ) {
                Icon(
                    if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(32.dp)
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
                    tint = foreground.copy(alpha = .6f)
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
                        tint = foreground.copy(alpha = .6f)
                    )
                }
                DropdownMenu(
                    expanded = overflowMenuOpen,
                    onDismissRequest = { overflowMenuOpen = false }
                ) {
                    // 朗读处未知（还没开始念）时不显示：按下也没地方去。
                    // 菜单是独立窗口层，MaterialTheme 取的是外壳调色板（只有日/夜两套）。
                    // 这里按阅读主题重设配色，菜单内的文字/滑块才跟着主题走。
                    MaterialTheme(
                        colorScheme = MaterialTheme.colorScheme.copy(
                            surface = background,
                            onSurface = foreground,
                            surfaceVariant = background,
                            onSurfaceVariant = foreground.copy(alpha = .7f),
                            primary = accent,
                            onPrimary = background,
                            outline = accent
                        )
                    ) {
                    if (state.highlightBlockIndex >= 0 && !speakingOffscreen) {
                        DropdownMenuItem(
                            text = { Text(backToSpeakingLabel) },
                            onClick = {
                                overflowMenuOpen = false
                                onBackToSpeaking()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.MyLocation,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(startLabel) },
                        onClick = {
                            overflowMenuOpen = false
                            onChooseStart()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Flag,
                                contentDescription = null,
                                tint = if (choosingStart) accent else Color.Unspecified,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    HorizontalDivider()
                    var rate by remember(state.speechRate) { mutableFloatStateOf(state.speechRate) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.player_speed_value, rateLabel(rate)),
                            style = MaterialTheme.typography.labelLarge
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
                            style = MaterialTheme.typography.labelSmall,
                            color = foreground.copy(alpha = .55f)
                        )
                    }
                    }
                }
            }
        }
    }
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
