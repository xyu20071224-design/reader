package com.linguareader.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.linguareader.shared.app.AppContext
import com.linguareader.shared.data.VocabularyRepository
import java.io.File

/**
 * 桌面版壳（M2 起）。数据目录：`-Dlr.home=<路径>` 优先，否则
 * `%APPDATA%/LinguaReader`（无该环境变量时回退 `~/.linguareader`）——
 * 与 Android 的 filesDir 同构，里面落 vocabulary.json 与 prefs 目录下各 .json。
 */
fun resolveHomeDir(): File {
    System.getProperty("lr.home")?.let { return File(it) }
    val appdata = System.getenv("APPDATA")
    if (appdata != null) return File(File(appdata, "LinguaReader"), "home")
    return File(File(System.getProperty("user.home"), ".linguareader"), "home")
}

/** 桌面侧的 ReviewMode 显示名映射（共享侧是 SharedString 符号，桌面端本地落地）。 */
fun reviewModeDisplayName(symbol: com.linguareader.shared.res.SharedString): String = when (symbol) {
    com.linguareader.shared.res.SharedString.REVIEW_MODE_IMMERSIVE -> "沉浸阅读"
    com.linguareader.shared.res.SharedString.REVIEW_MODE_GENTLE -> "温和节奏"
    com.linguareader.shared.res.SharedString.REVIEW_MODE_DILIGENT -> "勤学模式"
    com.linguareader.shared.res.SharedString.REVIEW_PACE_CUSTOM -> "自定义节奏"
    else -> symbol.name
}

fun main() {
    val home = resolveHomeDir().apply { mkdirs() }
    val context: AppContext = DesktopAppContext(home)
    val vocabulary = VocabularyRepository(context)

    application {
        Window(onCloseRequest = ::exitApplication, title = "语境阅读 · 桌面版") {
            LinguaReaderTheme {
                AppScaffold(context, vocabulary, home)
            }
        }
    }
}

private enum class Pane(val label: String) {
    Review("复习"),
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
fun AppScaffold(context: AppContext, vocabulary: VocabularyRepository, home: File) {
    var pane by remember { mutableStateOf(Pane.Review) }
    val reviewPrefs = remember { context.prefs("review_settings") }

    Scaffold { padding ->
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
                    Pane.Review -> ReviewPane(vocabulary, reviewPrefs)
                    Pane.Vocabulary -> VocabularyPane(vocabulary)
                    Pane.Settings -> SettingsPane(reviewPrefs, home)
                }
            }
        }
    }
}
