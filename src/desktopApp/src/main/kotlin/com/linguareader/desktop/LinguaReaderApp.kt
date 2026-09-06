package com.linguareader.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linguareader.shared.app.AppContext
import com.linguareader.shared.data.Book
import com.linguareader.shared.data.DictionaryDatabase
import com.linguareader.shared.data.LibraryRepository
import com.linguareader.shared.tts.TtsPlaybackEngine
import com.linguareader.shared.tts.TtsPlaybackState
import com.linguareader.shared.data.VocabularyRepository
import java.io.File

/**
 * 桌面版壳（M2 起）。数据目录：`-Dlr.home=<路径>` 优先，否则
 * `%APPDATA%/LinguaReader`（无该环境变量时回退 `~/.linguareader`）——
 * 与 Android 的 filesDir 同构，里面落 books/、vocabulary.json、prefs 目录下各 .json。
 */
fun resolveHomeDir(): File {
    System.getProperty("lr.home")?.let { return File(it) }
    val appdata = System.getenv("APPDATA")
    if (appdata != null) return File(File(appdata, "LinguaReader"), "home")
    return File(File(System.getProperty("user.home"), ".linguareader"), "home")
}

fun main() {
    val home = resolveHomeDir().apply { mkdirs() }
    val context: AppContext = DesktopAppContext(home)
    val vocabulary = VocabularyRepository(context)
    val library = LibraryRepository(context)
    val ttsState = mutableStateOf(TtsPlaybackState())
    val engine = createDesktopTtsEngine(context, library) { ttsState.value = it }
    // 词典找不到时不阻塞书架/复习：阅读屏给提示，查词不可用。
    val dictionary: DictionaryDatabase? = DesktopDictionaryDatabase.resolve(home)?.let {
        runCatching { DesktopDictionaryDatabase.open(it) }.getOrNull()
    }

    application {
        Window(onCloseRequest = ::exitApplication, title = "语境阅读 · 桌面版") {
            LinguaReaderTheme {
                AppScaffold(context, vocabulary, library, engine, ttsState, dictionary, home)
            }
        }
    }
}

private enum class Pane(val label: String) {
    Library("书架"),
    Review("复习"),
    Listening("听书"),
    Vocabulary("生词本"),
    Settings("设置")
}

@Composable
fun LinguaReaderTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

@Composable
fun AppScaffold(
    context: AppContext,
    vocabulary: VocabularyRepository,
    library: LibraryRepository,
    engine: TtsPlaybackEngine,
    ttsState: androidx.compose.runtime.MutableState<TtsPlaybackState>,
    dictionary: DictionaryDatabase?,
    home: File
) {
    var pane by remember { mutableStateOf(Pane.Library) }
    var reading by remember { mutableStateOf<Book?>(null) }
    val reviewPrefs = remember { context.prefs("review_settings") }

    Scaffold { padding ->
        if (reading != null) {
            Surface(Modifier.fillMaxSize().padding(padding)) {
                if (DesktopCefRuntime.available) {
                    DesktopReaderPane(
                        book = reading!!,
                        home = home,
                        dictionary = dictionary,
                        vocabulary = vocabulary,
                        reviewPrefs = reviewPrefs,
                        library = library,
                        onBack = { reading = null; pane = Pane.Library }
                    )
                } else {
                    ReadingPane(
                        book = reading!!,
                        dictionary = dictionary,
                        vocabulary = vocabulary,
                        reviewPrefs = reviewPrefs,
                        onBack = { reading = null; pane = Pane.Library }
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize().padding(padding)) {
                NavigationRail {
                    for (p in Pane.entries) {
                        NavigationRailItem(
                            selected = pane == p,
                            onClick = { pane = p },
                            icon = {},
                            label = { Text(p.label) }
                        )
                    }
                }
                Surface(Modifier.fillMaxSize().weight(1f)) {
                    when (pane) {
                        Pane.Library -> LibraryPane(library) { reading = it }
                        Pane.Review -> ReviewPane(vocabulary, reviewPrefs)
                        Pane.Listening -> ListeningPane(context, library, engine, ttsState)
                        Pane.Vocabulary -> VocabularyPane(vocabulary)
                        Pane.Settings -> SettingsPane(reviewPrefs, home)
                    }
                }
            }
        }
    }
}
