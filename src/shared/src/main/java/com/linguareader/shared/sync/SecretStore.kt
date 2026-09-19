package com.linguareader.shared.sync

import org.json.JSONObject
import java.io.File

/**
 * 令牌/凭据存储接缝。**任何实现都不得把明文写进日志或普通偏好**。
 * - Android：Android Keystore 加密后落 SharedPreferences（复用既有 CloudKeyStore 能力）
 * - 桌面：优先系统凭据管理器（Windows cmdkey / macOS security / Linux secret-tool），
 *   不可用时降级为 0600 文件并在 UI 明确警告（D4）
 * - 测试：[InMemorySecretStore]
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class InMemorySecretStore : SecretStore {
    private val values = LinkedHashMap<String, String>()

    override fun get(key: String): String? = values[key]

    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

/**
 * 兜底实现：JSON 文件 + 尽力设 0600。**安全性弱于系统凭据管理器**，
 * 只应在系统 CLI 不可用时降级使用，并在设置页明确告知用户。
 */
class FileSecretStore(private val file: File) : SecretStore {
    private val lock = Any()

    override fun get(key: String): String? = synchronized(lock) { read()[key] }

    override fun put(key: String, value: String) {
        synchronized(lock) {
            val map = read()
            map[key] = value
            write(map)
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val map = read()
            map.remove(key)
            write(map)
        }
    }

    private fun read(): MutableMap<String, String> {
        if (!file.isFile) return LinkedHashMap()
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return LinkedHashMap()
        val map = LinkedHashMap<String, String>()
        for (key in json.keys()) map[key] = json.optString(key)
        return map
    }

    private fun write(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        for ((key, value) in map) json.put(key, value)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
    }
}
