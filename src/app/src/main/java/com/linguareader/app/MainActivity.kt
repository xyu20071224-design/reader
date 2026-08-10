package com.linguareader.app

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

private val LinguaColorScheme = lightColorScheme(
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

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = LinguaColorScheme,
                shapes = AppShapes
            ) {
                LinguaReaderApp(viewModel)
            }
        }
    }
}

@Composable
private fun LinguaReaderApp(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentBook = state.currentBook
    val speak = rememberEnglishSpeaker()

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        if (currentBook == null) {
            BookshelfScreen(
                state = state,
                onImport = viewModel::importBook,
                onOpen = viewModel::openBook,
                onDelete = viewModel::deleteBook,
                onAiSettingsChange = viewModel::setAiSettings,
                onLoadGlossary = viewModel::glossary,
                onAddGlossary = viewModel::addGlossaryEntry,
                onUpdateGlossary = viewModel::updateGlossaryEntry,
                onRemoveGlossary = viewModel::removeGlossaryEntry,
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
                aiSettings = state.aiSettings,
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

@Composable
private fun rememberEnglishSpeaker(): (String) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        lateinit var created: TextToSpeech
        created = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) created.language = Locale.US
        }
        engine = created
        onDispose {
            created.stop()
            created.shutdown()
            engine = null
        }
    }
    return { text ->
        if (text.isNotBlank()) {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lingua-reader-word")
        }
    }
}
