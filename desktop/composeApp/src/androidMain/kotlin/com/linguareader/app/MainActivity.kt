package com.linguareader.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import com.linguareader.app.platform.appDataDir
import com.linguareader.app.platform.appPreferences
import com.linguareader.app.platform.initAndroidPlatform

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initAndroidPlatform(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = LinguaColorScheme,
                shapes = AppShapes
            ) {
                val viewModel = remember {
                    AppViewModel(
                        dataDir = appDataDir,
                        reviewPrefs = appPreferences("review_settings"),
                        launchPrefs = appPreferences("launch_promo")
                    )
                }
                LinguaReaderApp(viewModel)
            }
        }
    }
}
