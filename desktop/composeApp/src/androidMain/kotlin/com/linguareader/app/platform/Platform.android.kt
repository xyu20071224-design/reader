package com.linguareader.app.platform

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal lateinit var androidAppContext: Context

/** Must be called before any composition (from MainActivity.onCreate). */
fun initAndroidPlatform(context: Context) {
    androidAppContext = context.applicationContext
}

actual val appDataDir: File get() = androidAppContext.filesDir

actual val appCacheDir: File get() = androidAppContext.cacheDir

actual fun appPreferences(name: String): KeyValueStore =
    SharedPreferencesStore(androidAppContext.getSharedPreferences(name, Context.MODE_PRIVATE))

actual fun decodeBase64(value: String): ByteArray =
    android.util.Base64.decode(value, android.util.Base64.DEFAULT)

actual fun ensureDictionaryFile(): File {
    val databaseDir = File(androidAppContext.filesDir, "dictionary").apply { mkdirs() }
    val target = File(databaseDir, "ecdict-v2.sqlite")
    if (!target.exists() || target.length() == 0L) {
        val temp = File(databaseDir, "ecdict-v2.sqlite.tmp")
        androidAppContext.assets.open("dictionary/ecdict.sqlite").use { input ->
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

actual val supportsListening: Boolean = true

actual val supportsNotifications: Boolean = true

actual suspend fun decodeCoverImage(file: File): ImageBitmap? =
    withContext(Dispatchers.IO) {
        BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }

private class SharedPreferencesStore(
    private val prefs: SharedPreferences
) : KeyValueStore {
    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)

    override fun getInt(key: String, def: Int): Int = prefs.getInt(key, def)

    override fun getFloat(key: String, def: Float): Float = prefs.getFloat(key, def)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }
}
