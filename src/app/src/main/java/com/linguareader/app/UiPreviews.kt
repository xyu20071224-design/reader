package com.linguareader.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linguareader.app.tts.TtsPlaybackState

/**
 * 预览脚手架：把 MainActivity 里那套「调色板 + MaterialTheme + 纸色底」搬进 @Preview。
 *
 * 少了这一层，组件在预览里拿到的是 Material 默认配色和 LocalLinguaPalette 的兜底值，
 * 看到的颜色跟真机不是一回事——尤其夜间主题，预览会全程是浅色。
 */
@Composable
internal fun PreviewScaffold(
    dark: Boolean = false,
    content: @Composable () -> Unit
) {
    val palette = if (dark) DarkLinguaPalette else LightLinguaPalette
    CompositionLocalProvider(LocalLinguaPalette provides palette) {
        MaterialTheme(colorScheme = colorSchemeFor(palette), shapes = AppShapes) {
            Surface(color = palette.paper) {
                Column(Modifier.padding(12.dp)) { content() }
            }
        }
    }
}

private val PreviewListeningState = TtsPlaybackState(
    bookId = "preview-book",
    chapterIndex = 2,
    sentenceIndex = 17,
    sentenceCount = 84,
    currentSentence = "The lantern library keeps every lamp lit until dawn.",
    isPlaying = true,
    speechRate = 1.15f,
    canCacheBook = true
)

@Preview(name = "听书条 · 日间", showBackground = true, widthDp = 380)
@Composable
private fun ListeningBarLightPreview() {
    PreviewScaffold {
        ListeningBar(
            state = PreviewListeningState,
            onToggle = {},
            onPrevious = {},
            onNext = {},
            onStop = {},
            onRateChange = {}
        )
    }
}

@Preview(name = "听书条 · 夜间", showBackground = true, widthDp = 380)
@Composable
private fun ListeningBarDarkPreview() {
    PreviewScaffold(dark = true) {
        ListeningBar(
            state = PreviewListeningState,
            onToggle = {},
            onPrevious = {},
            onNext = {},
            onStop = {},
            onRateChange = {}
        )
    }
}

/** 全书缓存进行态：进度条与「缓存中」文案的排版最容易在窄屏上挤坏。 */
@Preview(name = "听书条 · 全书缓存中", showBackground = true, widthDp = 320)
@Composable
private fun ListeningBarCachingPreview() {
    PreviewScaffold {
        ListeningBar(
            state = PreviewListeningState.copy(
                isPlaying = false,
                isCachingBook = true,
                cachedSentences = 213,
                cachedTotal = 940
            ),
            onToggle = {},
            onPrevious = {},
            onNext = {},
            onStop = {},
            onRateChange = {}
        )
    }
}

// dwellMillis = 0 → 关掉自动消失的 LaunchedEffect，预览里横幅不会自己跑掉。
@Preview(name = "复习提醒 · 日间", showBackground = true, widthDp = 380)
@Composable
private fun ReviewPromptBannerLightPreview() {
    PreviewScaffold {
        ReviewPromptBanner(count = 12, dwellMillis = 0L, onStart = {}, onDismiss = {})
    }
}

@Preview(name = "复习提醒 · 夜间", showBackground = true, widthDp = 380)
@Composable
private fun ReviewPromptBannerDarkPreview() {
    PreviewScaffold(dark = true) {
        ReviewPromptBanner(count = 1, dwellMillis = 0L, onStart = {}, onDismiss = {})
    }
}
