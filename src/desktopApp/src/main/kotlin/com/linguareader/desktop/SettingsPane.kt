package com.linguareader.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linguareader.shared.app.PreferencesStore
import com.linguareader.shared.data.ReviewMode
import com.linguareader.shared.data.ReviewPace
import java.io.File

/**
 * 设置屏（M2 桌面）：复习节奏三选一。写的是与 Android 相同的
 * `review_settings` prefs 键（ReviewMode.PREFERENCE_KEY / ReviewPace.STORAGE_KEY），
 * 两端配置语义互通。自定义节奏（F-138 JSON）在本屏只读展示，编辑后续再上。
 */
@Composable
fun SettingsPane(reviewPrefs: PreferencesStore, home: File) {
    var selected by remember {
        mutableStateOf(
            runCatching { ReviewMode.valueOf(reviewPrefs.getString(ReviewMode.PREFERENCE_KEY) ?: "") }
                .getOrDefault(ReviewMode.DEFAULT)
        )
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        Text("复习节奏", style = MaterialTheme.typography.titleMedium)
        for (mode in ReviewMode.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected == mode, role = Role.RadioButton, onClick = {
                        selected = mode
                        reviewPrefs.putString(ReviewMode.PREFERENCE_KEY, mode.name)
                        reviewPrefs.putString(ReviewPace.STORAGE_KEY, mode.toPace().toJson())
                    })
                    .padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == mode, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "首隔 ${mode.firstDelayMillis / 60_000} 分钟 · 倍率 ×${mode.intervalMultiplier} · " +
                            "每次 ${mode.sessionMaxWords} 词",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        Text("数据目录", style = MaterialTheme.typography.titleMedium)
        Text(
            home.absolutePath + "（vocabulary.json 与 prefs/*.json；" +
                "与 Android 的 filesDir 同构，可用 -Dlr.home 指定）",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
