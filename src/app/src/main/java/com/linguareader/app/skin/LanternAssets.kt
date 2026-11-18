package com.linguareader.app.skin

import android.content.res.AssetManager
import org.json.JSONObject

internal const val LANTERN_ARTWORK_DIRECTORY = "reader-art/lantern-v1"

internal fun AssetManager.readLanternManifest(): String =
    open("$LANTERN_ARTWORK_DIRECTORY/manifest.json").bufferedReader().use { it.readText() }

internal data class LanternImage(val file: String, val width: Int, val height: Int)

internal class LanternAssets(manifest: String) {
    private val editions = JSONObject(manifest)

    fun select(mode: LanternMode, widthPixels: Int, heightPixels: Int): LanternImage? {
        if (widthPixels <= 0 || heightPixels <= 0) return null
        val family = if (widthPixels >= heightPixels * 1.08) "wide" else "portrait"
        val tiers = editions.optJSONObject(mode.artworkKey)?.optJSONArray(family) ?: return null
        var largest: LanternImage? = null
        for (index in 0 until tiers.length()) {
            val tier = tiers.optJSONObject(index) ?: continue
            val image = LanternImage(tier.optString("file"), tier.optInt("width"), tier.optInt("height"))
            if (image.file.isEmpty() || image.width <= 0 || image.height <= 0) continue
            if (image.width >= widthPixels && image.height >= heightPixels) return image
            largest = image
        }
        return largest
    }
}
