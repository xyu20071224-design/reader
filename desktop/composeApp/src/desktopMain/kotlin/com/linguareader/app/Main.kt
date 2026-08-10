package com.linguareader.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.linguareader.app.platform.appDataDir
import com.linguareader.app.platform.appPreferences
import javafx.application.Platform

fun main() = application {
    // Pre-initialize the JavaFX toolkit so the WebView reader can start
    // immediately and any environment issue surfaces at launch.
    Platform.startup { }

    val viewModel = remember {
        AppViewModel(
            dataDir = appDataDir,
            reviewPrefs = appPreferences("review_settings"),
            launchPrefs = appPreferences("launch_promo")
        )
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "LinguaReader 语境阅读",
        state = rememberWindowState(width = 1080.dp, height = 780.dp)
    ) {
        MaterialTheme(
            colorScheme = LinguaColorScheme,
            shapes = AppShapes
        ) {
            LinguaReaderApp(viewModel)
        }
    }
}
