package com.linguareader.shared.app

import java.io.File

/**
 * 平台上下文的最小共享面（迁移方案 §4「应用上下文」行，M2 落地）。
 *
 * Android 的 `Context` 穿透了 39 个文件，:shared 只认这里声明的平台面。
 * 按 M2 各刀的实际需要逐成员扩展（base64/platform 等等有消费点时再加），
 * 不预置没人消费的成员。
 */
interface AppContext {
    /** 应用私有数据目录（Android = `Context.filesDir`；桌面 = 用户数据目录下的应用目录）。 */
    val filesDir: File

    /** 按 name 打开（不存在则创建）一个字符串键值存储，语义对齐 Android 的 SharedPreferences。 */
    fun prefs(name: String): PreferencesStore
}

/**
 * 字符串键值持久化的最小接口。Android 由 [com.linguareader.app.data.SharedPreferencesStore]
 * 包一层 `SharedPreferences` 提供；桌面端 M2+ 落 JSON 文件实现。
 * 只暴露共享代码真正用到的两个操作；int/long/bool 等留给各端自己的存储面。
 */
interface PreferencesStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)
}
