package com.linguareader.app.skin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.ShelfAppearance
import com.linguareader.shared.data.ReaderFont
import com.linguareader.shared.data.ReaderPreferences
import com.linguareader.shared.data.ReaderTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinOrigin
import com.linguareader.shared.ui.skin.SkinValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LanternAppearanceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun sceneOnlyOverridesTheReadingTheme() {
        val original = ReaderPreferences(125, 1.8f, ReaderTheme.GREEN, ReaderFont.MONO)
        assertEquals(original, LanternAppearance().readerPreferences(original))
        assertEquals(
            original.copy(theme = ReaderTheme.LANTERN_DAY),
            LanternAppearance(LanternMode.DAY).readerPreferences(original)
        )
        assertEquals(
            original.copy(theme = ReaderTheme.DARK),
            LanternAppearance(LanternMode.NIGHT, readerArtwork = false).readerPreferences(original)
        )
        LanternMode.entries.forEach { mode ->
            assertEquals(mode.illustratedReaderTheme.background, mode.plainReaderTheme.background)
            assertTrue(SkinValidator.isCompliant(mode.skin.colors))
        }
    }

    @Test
    fun scenePersistenceLeavesTheExistingAppearanceAvailableOnExit() {
        val skin = SkinBuiltIns.light.copy(id = "fixture-image", origin = SkinOrigin.IMAGE)
        val wallpaper = ShelfAppearance(presetId = "mist", dimOpacity = .47f)
        val reader = context.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)
        reader.edit().putString("theme", ReaderTheme.SEPIA.name).commit()
        SkinStore.selectCustom(context, skin)
        ShelfAppearance.save(context, wallpaper)

        val scene = LanternAppearanceState(context)
        assertNull(scene.appearance.mode)
        scene.selectMode(LanternMode.DAY)
        scene.showReaderArtwork(false)
        val restarted = LanternAppearanceState(context)
        assertEquals(LanternAppearance(LanternMode.DAY, readerArtwork = false), restarted.appearance)
        restarted.selectMode(LanternMode.NIGHT)
        assertEquals(LanternAppearance(LanternMode.NIGHT, readerArtwork = false), LanternAppearanceState.read(context))
        restarted.selectMode(null)

        assertNull(LanternAppearanceState.read(context).mode)
        assertEquals(skin, SkinStore.selected(context))
        assertEquals(wallpaper, ShelfAppearance.load(context))
        assertEquals(ReaderTheme.SEPIA.name, reader.getString("theme", null))
    }

    @Test
    fun shelfSelectsFromTheBundledManifestByBothPhysicalDimensions() {
        val assets = LanternAssets(context.assets.readLanternManifest())
        assertNull(assets.select(LanternMode.DAY, 0, 400))
        assertEquals("day-wide-768.webp", assets.select(LanternMode.DAY, 768, 512)?.file)
        assertEquals("night-wide-1152.webp", assets.select(LanternMode.NIGHT, 768, 550)?.file)
        assertEquals("day-portrait-660.webp", assets.select(LanternMode.DAY, 390, 844)?.file)
        assertEquals("night-portrait-768.webp", assets.select(LanternMode.NIGHT, 780, 1688)?.file)
    }
}
