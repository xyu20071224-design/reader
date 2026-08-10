package com.linguareader.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.io.OutputStream

/** Minimal key-value storage shared by Android SharedPreferences and desktop JSON files. */
interface KeyValueStore {
    fun getString(key: String, def: String?): String?
    fun getInt(key: String, def: Int): Int
    fun getFloat(key: String, def: Float): Float
    fun putString(key: String, value: String)
    fun putInt(key: String, value: Int)
    fun putFloat(key: String, value: Float)
}

/** A file target chosen by the platform save dialog (content Uri or local path). */
fun interface OutputTarget {
    fun openStream(): OutputStream
}

/** Root data directory (Android filesDir / Windows %APPDATA%\\LinguaReader). */
expect val appDataDir: File

/** Cache/temp directory for imports. */
expect val appCacheDir: File

/** Named preference store. */
expect fun appPreferences(name: String): KeyValueStore

/** Platform Base64 decoder (Android API level-safe). */
expect fun decodeBase64(value: String): ByteArray

/** Ensures the bundled ECDICT database is available on disk and returns its file. */
expect fun ensureDictionaryFile(): File

/** Whether the desktop build includes listening/TTS UI (false for the Windows MVP). */
expect val supportsListening: Boolean

/** Whether the platform supports system notification reminders (false on desktop MVP). */
expect val supportsNotifications: Boolean

/** Decodes a cover image file into a Compose bitmap. */
expect suspend fun decodeCoverImage(file: File): ImageBitmap?

/** Opens a native "open file" dialog; the result is a local copy of the picked file. */
@Composable
expect fun rememberFileOpenLauncher(onResult: (File?) -> Unit): () -> Unit

/** Opens a native "save file" dialog; the result writes to the chosen target. */
@Composable
expect fun rememberFileSaveLauncher(
    defaultName: String,
    onResult: (OutputTarget?) -> Unit
): () -> Unit

/** System back handling; a no-op on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Word pronunciation hook; desktop MVP returns a no-op (TTS excluded). */
@Composable
expect fun rememberSpeaker(): (String) -> Unit
