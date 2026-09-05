package com.linguareader.app.data

import android.content.SharedPreferences
import com.linguareader.shared.app.PreferencesStore

/**
 * 把 Android 的 [SharedPreferences] 适配成 :shared 的 [PreferencesStore]
 * （迁移方案 §4「应用上下文」抽象的 Android 侧 actual，M2 落地）。
 * 桌面端后续落自己的 JSON 文件实现，两端持久化键名与取值语义保持一致。
 */
class SharedPreferencesStore(private val prefs: SharedPreferences) : PreferencesStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

fun SharedPreferences.asPreferencesStore(): PreferencesStore = SharedPreferencesStore(this)
