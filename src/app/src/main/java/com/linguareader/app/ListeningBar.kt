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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Close
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
    choosingStart: Boolean = false,
    onChooseStart: () -> Unit = {}
) {
    var speedMenuOpen by remember { mutableStateOf(false) }
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
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevious,
                modifier = Modifier.semantics { contentDescription = "上一句" }
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = Ink)
            }
            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(44.dp)
                    .semantics {
                        contentDescription = if (state.isPlaying) "暂停" else "播放"
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
                modifier = Modifier.semantics { contentDescription = "下一句" }
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Ink)
            }
            Text(
                when {
                    choosingStart -> "点击正文中的单词/句子，从此句开始朗读"
                    state.currentSentence.isNotBlank() -> state.currentSentence
                    state.isPlaying -> "正在准备朗读…"
                    else -> "已暂停"
                },
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            IconButton(
                onClick = onChooseStart,
                modifier = Modifier.semantics { contentDescription = "设置起点" }
            ) {
                Icon(
                    Icons.Filled.Flag,
                    contentDescription = null,
                    tint = if (choosingStart) Accent else InkFaint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Box {
                TextButton(
                    onClick = { speedMenuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "语速" }
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
                            "语速 ${rateLabel(rate)}",
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
                            "0.5× – 2.0×，点击页面可从此句开始听",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkFaint
                        )
                    }
                }
            }
            IconButton(
                onClick = onStop,
                modifier = Modifier.semantics { contentDescription = "停止听书" }
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
