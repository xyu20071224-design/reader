package com.linguareader.app

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.app.data.ReaderTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<AppViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            // 外壳配色跟随正文阅读主题（「夜间」→ 整个界面变暗），
            // 用户还没设过阅读主题时跟随系统深色设置。
            var readerTheme by remember { mutableStateOf(storedReaderTheme(context)) }
            val palette = paletteFor(readerTheme, isSystemInDarkTheme())
            ApplySystemBars(palette)
            CompositionLocalProvider(LocalLinguaPalette provides palette) {
                MaterialTheme(
                    colorScheme = colorSchemeFor(palette),
                    shapes = AppShapes
                ) {
                    LinguaReaderApp(
                        viewModel = viewModel,
                        onReaderThemeChanged = { readerTheme = it }
                    )
                }
            }
        }
    }
}

/** 状态栏/导航栏颜色与图标亮度跟随调色板，夜间不再是白条。 */
@Composable
private fun ApplySystemBars(palette: LinguaPalette) {
    val view = LocalView.current
    if (view.isInEditMode) return
    LaunchedEffect(palette.isDark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        @Suppress("DEPRECATION")
        window.statusBarColor = palette.paper.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.paper.toArgb()
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }
}

@Composable
private fun LinguaReaderApp(
    viewModel: AppViewModel,
    onReaderThemeChanged: (ReaderTheme) -> Unit
) {
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
                onClose = viewModel::closeBook,
                onAppearanceChanged = onReaderThemeChanged
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
