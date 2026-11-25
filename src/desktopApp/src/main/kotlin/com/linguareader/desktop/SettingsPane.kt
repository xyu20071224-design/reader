package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linguareader.shared.app.PreferencesStore
import java.io.File

/**
 * 桌面设置屏：复习节奏、云同步与数据目录。复习配置沿用 Android 的持久化键。
 */
@Composable
fun SettingsPane(reviewPrefs: PreferencesStore, home: File, sync: DesktopSyncController) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        ReviewSettingsSection(reviewPrefs)

        SyncSettingsSection(sync)

        Text("数据目录", style = MaterialTheme.typography.titleMedium)
        Text(
            home.absolutePath + "（vocabulary.json 与 prefs/*.json；" +
                "与 Android 的 filesDir 同构，可用 -Dlr.home 指定）",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
