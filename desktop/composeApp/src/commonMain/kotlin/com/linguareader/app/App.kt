package com.linguareader.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import com.linguareader.app.platform.rememberSpeaker

/** Version constants shared by both targets (replaces Android BuildConfig). */
object AppVersion {
    const val VERSION_CODE = 6
    const val VERSION_NAME = "1.3.0"
}

internal val LinguaColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentDeep,
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = AccentDeep,
    tertiary = Success,
    background = Paper,
    onBackground = Ink,
    surface = CardSurface,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkSoft,
    outline = AccentSoft,
    outlineVariant = PaperDeep,
    error = Danger,
    onError = Color.White
)

@Composable
fun LinguaReaderApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsState()
    val currentBook = state.currentBook
    val speak = rememberSpeaker()

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        if (currentBook == null) {
            BookshelfScreen(
                state = state,
                onImport = viewModel::importBook,
                onOpen = viewModel::openBook,
                onDelete = viewModel::deleteBook,
                onRemoveWord = viewModel::removeSavedWord,
                onReviewModeChange = viewModel::setReviewMode,
                onCustomReviewChange = viewModel::setCustomReview,
                onRemindersChange = viewModel::setReminders,
                onReviewWord = viewModel::reviewWord,
                onExportVocabulary = viewModel::exportVocabulary,
                onSpeak = speak,
                onDismissMessage = viewModel::clearMessage
            )
        } else {
            ReaderScreen(
                book = currentBook,
                viewModel = viewModel,
                savedWords = state.savedWords,
                reviewPace = state.reviewPace,
                reviewPreset = state.reviewPreset,
                customReview = state.customReview,
                reminders = state.reminders,
                onReviewModeChange = viewModel::setReviewMode,
                onCustomReviewChange = viewModel::setCustomReview,
                onRemindersChange = viewModel::setReminders,
                onSpeak = speak,
                onClose = viewModel::closeBook
            )
        }
    }

    state.launchPrompt?.let { prompt ->
        LaunchPromptDialog(prompt = prompt, onDismiss = viewModel::dismissLaunchPrompt)
    }
}
