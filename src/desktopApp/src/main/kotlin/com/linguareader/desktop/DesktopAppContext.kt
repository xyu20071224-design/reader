package com.linguareader.desktop

import com.linguareader.shared.app.AppContext
import com.linguareader.shared.app.PreferencesStore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * [AppContext] 的桌面实现（迁移方案 §4 平台面，M2 刀5）。
 *
 * `filesDir` 由宿主传入（正式产品 = 用户数据目录，如 `%APPDATA%/LinguaReader`，
 * M2 桌面 UI 阶段接线；冒烟/测试传临时目录）。prefs(name) 落成
 * `<filesDir>/prefs/<name>.json`，一个名字一个文件，写穿即持久——语义对齐
 * Android 侧 `SharedPreferencesStore`（同键名、同取值行为，两端数据面一致）。
 */
class DesktopAppContext(override val filesDir: File) : AppContext {
    private val prefsDir = File(filesDir, "prefs")
    private val stores = ConcurrentHashMap<String, PreferencesStore>()

    override fun prefs(name: String): PreferencesStore =
        stores.computeIfAbsent(name) { JsonPreferencesStore(File(prefsDir, "$name.json")) }
}

/**
 * JSON 文件键值存储。读容错（坏文件按空处理，不抛——与 SharedPreferences 容错一致）；
 * 写走 temp + rename 原子替换，失败降级直写（与 :shared 两个仓库同一套磁盘纪律）。
 */
class JsonPreferencesStore(private val file: File) : PreferencesStore {
    private val lock = Any()
    private var loaded = false
    private val values = LinkedHashMap<String, String>()

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.isFile) return
        runCatching {
            val obj = JSONObject(file.readText())
            for (key in obj.keys()) values[key] = obj.getString(key)
        }
    }

    override fun getString(key: String): String? = synchronized(lock) {
        ensureLoaded()
        values[key]
    }

    override fun putString(key: String, value: String) {
        synchronized(lock) {
            ensureLoaded()
            values[key] = value
            writeAtomically()
        }
    }

    private fun writeAtomically() {
        file.parentFile?.mkdirs()
        val obj = JSONObject()
        for ((key, value) in values) obj.put(key, value)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(obj.toString(2))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }
}
