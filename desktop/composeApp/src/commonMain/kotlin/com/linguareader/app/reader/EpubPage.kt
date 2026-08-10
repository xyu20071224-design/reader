package com.linguareader.app.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.linguareader.app.data.ReaderPreferences
import com.linguareader.app.data.WordLookup
import java.io.File

/** Platform-specific chapter renderer (Android WebView / desktop JavaFX WebView). */
@Composable
expect fun EpubPage(
    chapterFile: File,
    initialPage: Int,
    preferences: ReaderPreferences,
    savedWords: List<String> = emptyList(),
    controller: ReaderController,
    modifier: Modifier = Modifier,
    onReady: (Int, Int) -> Unit,
    onPageChanged: (Int, Int) -> Unit,
    onChapterRequested: (Int) -> Unit,
    onWord: (WordLookup) -> Unit,
    onToolbarRequested: () -> Unit,
    onSentenceTapped: (String, Int) -> Unit = { _, _ -> },
    onTtsPage: (Int) -> Unit = {}
)
