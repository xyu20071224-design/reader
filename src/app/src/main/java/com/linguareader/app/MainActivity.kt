package com.linguareader.app

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
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
            val snackbar = rememberAppSnackbar()
            CompositionLocalProvider(
                LocalLinguaPalette provides palette,
                LocalAppSnackbar provides snackbar
            ) {
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

/**
 * 状态栏/导航栏颜色与图标亮度跟随调色板，夜间不再是白条。
 *
 * 图标明暗已走 WindowCompat.getInsetsController 返回的 WindowInsetsControllerCompat
 * （迁移完毕）。窗口栏「颜色」没有对应的 compat API：targetSdk 35 强制 edge-to-edge
 * 后这两个 setter 在新系统上被忽略（栏透明、由 Compose Surface(Paper) 自己垫底），
 * 仅在 < 35 的旧系统上仍然生效，因此保留并 @Suppress 弃用告警。冷启动首帧的
 * 栏/窗口底色由 res/values(-night)/themes.xml 决定，与此处运行时值保持一致。
 */
@Composable
private fun ApplySystemBars(palette: LinguaPalette) {
    val view = LocalView.current
    if (view.isInEditMode) return
    LaunchedEffect(palette.isDark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        @Suppress("DEPRECATION") // 见上：仅旧系统生效，新系统由 edge-to-edge + Compose 垫底
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
    val snackbar = LocalAppSnackbar.current

    // 一次性提示（导入/删除等）统一走全局 Snackbar，弹过即清空。
    // 提示带语义（成功/失败），渲染端据此换容器配色（见 snackbarColorsFor）。
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.show(it, state.noticeTone)
            viewModel.clearNotice()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        if (currentBook == null) {
            BookshelfScreen(
                state = state,
                onImport = viewModel::importBook,
                onOpen = viewModel::openBook,
                onDelete = viewModel::deleteBook,
                onAttachTranslation = viewModel::attachTranslation,
                onDetachTranslation = viewModel::detachTranslation,
                onPrepareAiTranslation = viewModel::prepareAiTranslation,
                onStartAiTranslation = viewModel::startAiTranslation,
                onCancelAiTranslation = viewModel::cancelAiTranslation,
                onDismissAiTranslationPrepare = viewModel::dismissAiTranslationPrepare,
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

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(
            hostState = snackbar.hostState,
            modifier = Modifier.padding(bottom = 72.dp)
        ) { data ->
            // 中性提示沿用墨色容器（日深夜浅互逆）；成功/失败换语义色容器，
            // 米色纸底上不再是「米色条」，且一眼可辨结果好坏。
            val tone = (data.visuals as? AppSnackbarVisuals)?.tone ?: StatusTone.NEUTRAL
            val (container, content) = snackbarColorsFor(tone, LocalLinguaPalette.current)
            Snackbar(
                snackbarData = data,
                containerColor = container,
                contentColor = content,
                shape = SmallShape
            )
        }
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
