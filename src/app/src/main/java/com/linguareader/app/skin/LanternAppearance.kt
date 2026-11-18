package com.linguareader.app.skin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.linguareader.shared.data.ReaderPreferences
import com.linguareader.shared.data.ReaderTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinSpec
import com.linguareader.shared.ui.skin.SkinValidator

internal enum class LanternMode(
    val artworkKey: String,
    val illustratedReaderTheme: ReaderTheme,
    val plainReaderTheme: ReaderTheme
) {
    DAY("day", ReaderTheme.LANTERN_DAY, ReaderTheme.PAPER),
    NIGHT("night", ReaderTheme.LANTERN_NIGHT, ReaderTheme.DARK);

    val skin: SkinSpec
        get() = if (this == DAY) lanternDaySkin else lanternNightSkin
}

internal data class LanternAppearance(
    val mode: LanternMode? = null,
    val readerArtwork: Boolean = true
) {
    fun readerPreferences(base: ReaderPreferences): ReaderPreferences {
        val activeMode = mode ?: return base
        return base.copy(
            theme = if (readerArtwork) activeMode.illustratedReaderTheme else activeMode.plainReaderTheme
        )
    }
}

/** The scene overlays the existing skin, shelf wallpaper and reading preferences. */
internal class LanternAppearanceState(context: Context) {
    private val store = preferences(context)
    var appearance by mutableStateOf(read(context))
        private set

    fun selectMode(mode: LanternMode?) = update(appearance.copy(mode = mode))

    fun showReaderArtwork(show: Boolean) = update(appearance.copy(readerArtwork = show))

    private fun update(value: LanternAppearance) {
        if (value == appearance) return
        store.edit()
            .putString(KEY_MODE, value.mode?.name)
            .putBoolean(KEY_READER_ART, value.readerArtwork)
            .apply()
        appearance = value
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_READER_ART = "reader_artwork"

        private fun preferences(context: Context) = context.getSharedPreferences("lantern_appearance", Context.MODE_PRIVATE)

        fun read(context: Context): LanternAppearance {
            val store = preferences(context)
            val mode = store.getString(KEY_MODE, null)
            return LanternAppearance(
                mode = LanternMode.entries.firstOrNull { it.name == mode },
                readerArtwork = store.getBoolean(KEY_READER_ART, true)
            )
        }
    }
}

internal val LocalLanternAppearance = staticCompositionLocalOf<LanternAppearanceState?> { null }

private val lanternDaySkin = SkinBuiltIns.light.let { base ->
    base.copy(
        id = "lantern-day",
        name = "Lantern Library",
        colors = SkinValidator.conform(
            base.colors.copy(
                paperDeep = 0xFFEFE3D0L,
                cardSurface = 0xFFFFFAF0L,
                inkSoft = 0xFF655645L,
                inkFaint = 0xFF695A49L,
                accent = 0xFF794827L,
                accentDeep = 0xFF61371EL,
                accentSoft = 0xFFEEDBC1L,
                outline = 0xFFCDAF8DL,
                bookCoverFallback = 0xFFE6CDA8L
            )
        )
    )
}

private val lanternNightSkin = SkinBuiltIns.dark.let { base ->
    base.copy(
        id = "lantern-night",
        name = "Lantern Library",
        colors = SkinValidator.conform(
            base.colors.copy(
                paperDeep = 0xFF201F23L,
                cardSurface = 0xFF272326L,
                inkSoft = 0xFFC3B6A9L,
                inkFaint = 0xFFA7998BL,
                accent = 0xFFE5AF75L,
                accentDeep = 0xFFF0C99BL,
                accentSoft = 0xFF433027L,
                outline = 0xFF604738L,
                bookCoverFallback = 0xFF42302AL
            )
        )
    )
}
