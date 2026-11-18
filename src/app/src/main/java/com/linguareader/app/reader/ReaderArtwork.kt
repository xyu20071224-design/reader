package com.linguareader.app.reader

import android.content.res.AssetManager
import android.webkit.WebView
import com.linguareader.app.skin.LANTERN_ARTWORK_DIRECTORY
import com.linguareader.app.skin.readLanternManifest
import com.linguareader.shared.data.ReaderPreferences
import com.linguareader.shared.data.ReaderTheme
import java.io.IOException
import org.json.JSONObject

internal object ReaderArtwork {
    fun installation(assets: AssetManager): String? = try {
        val renderer = assets.open("reader-art/renderer.js").bufferedReader().use { it.readText() }
        val manifest = assets.readLanternManifest()
        val assetBase = JSONObject.quote("file:///android_asset/$LANTERN_ARTWORK_DIRECTORY/")
        "$renderer\nwindow.lrInstallArtwork($manifest, $assetBase);"
    } catch (_: IOException) {
        null // A missing bundle leaves the selected theme's paper color in place.
    }

    fun preference(theme: ReaderTheme): String {
        val mode = theme.artworkMode?.let(JSONObject::quote) ?: "null"
        return "\nwindow.lrSetArtwork && window.lrSetArtwork($mode);"
    }
}

internal fun WebView.applyReaderPreferences(preferences: ReaderPreferences) {
    evaluateJavascript(ReaderScripts.preferenceScript(preferences) + ReaderArtwork.preference(preferences.theme), null)
}
