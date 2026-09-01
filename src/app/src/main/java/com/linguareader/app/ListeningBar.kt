package com.linguareader.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.linguareader.app.tts.TtsPlaybackState
import java.util.Locale

/** Bottom playback bar shown while a book is being read aloud. */
@Composable
internal fun ListeningBar(
    state: TtsPlaybackState,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onRateChange: (Float) -> Unit,
    onCacheBook: () -> Unit = {},
    choosingStart: Boolean = false,
    onChooseStart: () -> Unit = {},
    /** 「回到朗读处」：把视口挪回正在朗读的那句，并恢复自动跟随。 */
    onBackToSpeaking: () -> Unit = {}
) {
    var speedMenuOpen by remember { mutableStateOf(false) }
    // semantics{} 不是 composable 作用域，无障碍标签先在这里取出来。
    val previousLabel = stringResource(R.string.player_previous)
    val pauseLabel = stringResource(R.string.player_pause)
    val playLabel = stringResource(R.string.player_play)
    val nextLabel = stringResource(R.string.player_next)
    val startLabel = stringResource(R.string.player_set_start)
    val backToSpeakingLabel = stringResource(R.string.player_back_to_speaking)
    val cacheLabel = stringResource(R.string.player_cache_book)
    val speedLabel = stringResource(R.string.player_speed)
    val stopLabel = stringResource(R.string.player_stop)
    val progress = if (state.sentenceCount > 0) {
        state.sentenceIndex.toFloat() / state.sentenceCount
    } else {
        0f
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Accent,
            trackColor = Accent.copy(alpha = .12f)
        )
        if (state.isCachingBook) {
            val cacheProgress =
                if (state.cachedTotal > 0) state.cachedSentences.toFloat() / state.cachedTotal else 0f
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { cacheProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(4.dp),
                    color = Accent,
                    trackColor = Accent.copy(alpha = .12f)
                )
                Text(
                    stringResource(R.string.player_cache_progress, (cacheProgress * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevious,
                modifier = Modifier.semantics { contentDescription = previousLabel }
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = Ink)
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
                    tint = Accent,
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(
                onClick = onNext,
                modifier = Modifier.semantics { contentDescription = nextLabel }
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Ink)
            }
            Text(
                when {
                    choosingStart -> stringResource(R.string.player_choose_start_hint)
                    state.currentSentence.isNotBlank() -> state.currentSentence
                    state.isPlaying -> stringResource(R.string.player_preparing)
                    else -> stringResource(R.string.player_paused)
                },
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            // 朗读处未知（老链路只有句文本、或还没开始念）时不显示：按下也没地方去。
            if (state.highlightBlockIndex >= 0) {
                IconButton(
                    onClick = onBackToSpeaking,
                    modifier = Modifier.semantics { contentDescription = backToSpeakingLabel }
                ) {
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = null,
                        tint = InkFaint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(
                onClick = onChooseStart,
                modifier = Modifier.semantics { contentDescription = startLabel }
            ) {
                Icon(
                    Icons.Filled.Flag,
                    contentDescription = null,
                    tint = if (choosingStart) Accent else InkFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (state.canCacheBook) {
                IconButton(
                    onClick = onCacheBook,
                    modifier = Modifier.semantics { contentDescription = cacheLabel }
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = if (state.isCachingBook) Accent else InkFaint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Box {
                TextButton(
                    onClick = { speedMenuOpen = true },
                    modifier = Modifier.semantics { contentDescription = speedLabel }
                ) {
                    Icon(
                        Icons.Filled.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = InkSoft
                    )
                    Text(
                        " ${rateLabel(state.speechRate)}",
                        color = Ink,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                DropdownMenu(
                    expanded = speedMenuOpen,
                    onDismissRequest = { speedMenuOpen = false }
                ) {
                    var rate by remember(state.speechRate) { mutableFloatStateOf(state.speechRate) }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            stringResource(R.string.player_speed_value, rateLabel(rate)),
                            style = MaterialTheme.typography.labelLarge,
                            color = Ink
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
                            color = InkFaint
                        )
                    }
                }
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier.semantics { contentDescription = stopLabel }
            ) {
                Icon(Icons.Filled.Close, contentDescription = null, tint = InkFaint)
            }
        }
    }
}

private fun rateLabel(rate: Float): String =
    if (rate % 1f == 0f) {
        "${rate.toInt()}×"
    } else {
        String.format(Locale.ROOT, "%.2f", rate).trimEnd('0').trimEnd('.') + "×"
    }
