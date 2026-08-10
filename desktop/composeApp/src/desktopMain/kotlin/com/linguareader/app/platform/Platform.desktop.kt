package com.linguareader.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.util.Base64

private val desktopDataDir: File by lazy {
    val os = System.getProperty("os.name").lowercase()
    val base = if (os.contains("win")) {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "LinguaReader")
    } else {
        File(System.getProperty("user.home"), ".lingua-reader")
    }
    base.apply { mkdirs() }
}

actual val appDataDir: File get() = desktopDataDir

actual val appCacheDir: File get() = File(System.getProperty("java.io.tmpdir"))

actual fun appPreferences(name: String): KeyValueStore = JsonPreferencesStore(name)

actual fun decodeBase64(value: String): ByteArray = Base64.getDecoder().decode(value)

actual fun ensureDictionaryFile(): File {
    val databaseDir = File(appDataDir, "dictionary").apply { mkdirs() }
    val target = File(databaseDir, "ecdict-v2.sqlite")
    if (!target.exists() || target.length() == 0L) {
        val temp = File(databaseDir, "ecdict-v2.sqlite.tmp")
        val stream = JsonPreferencesStore::class.java.classLoader
            .getResourceAsStream("dictionary/ecdict.sqlite")
            ?: error("缺少内置离线词典资源 dictionary/ecdict.sqlite")
        stream.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!temp.renameTo(target)) {
            target.outputStream().use { output ->
                temp.inputStream().use { it.copyTo(output) }
            }
            temp.delete()
        }
    }
    return target
}

actual val supportsListening: Boolean = false

actual val supportsNotifications: Boolean = false

actual suspend fun decodeCoverImage(file: File): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
        }.getOrNull()
    }

private class JsonPreferencesStore(name: String) : KeyValueStore {
    private val file = File(appDataDir, "prefs_$name.json")
    private val lock = Any()
    private val values = mutableMapOf<String, String>()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        synchronized(lock) {
            runCatching {
                val json = org.json.JSONObject(file.readText())
                json.keys().forEach { key -> values[key] = json.optString(key) }
            }
        }
    }

    private fun persist() {
        synchronized(lock) {
            val json = org.json.JSONObject().apply {
                values.forEach { (key, value) -> put(key, value) }
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    override fun getString(key: String, def: String?): String? =
        synchronized(lock) { values[key] ?: def }

    override fun getInt(key: String, def: Int): Int =
        synchronized(lock) { values[key]?.toIntOrNull() ?: def }

    override fun getFloat(key: String, def: Float): Float =
        synchronized(lock) { values[key]?.toFloatOrNull() ?: def }

    override fun putString(key: String, value: String) {
        synchronized(lock) { values[key] = value }
        persist()
    }

    override fun putInt(key: String, value: Int) {
        synchronized(lock) { values[key] = value.toString() }
        persist()
    }

    override fun putFloat(key: String, value: Float) {
        synchronized(lock) { values[key] = value.toString() }
        persist()
    }
}
