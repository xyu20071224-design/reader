package com.linguareader.app.skin

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.linguareader.app.AppShapes
import com.linguareader.app.LinguaPalette
import com.linguareader.app.LocalLinguaPalette
import com.linguareader.app.chromeIsDark
import com.linguareader.app.colorSchemeFor
import com.linguareader.app.data.ReaderTheme
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinSpec

/** 生产主题入口：显式选择优先，只有未选择时才跟随阅读主题/系统。 */
@Composable
internal fun AppSkinTheme(
    selectedSkin: SkinSpec?,
    readerTheme: ReaderTheme?,
    systemDark: Boolean,
    content: @Composable (LinguaPalette) -> Unit
) {
    val skin = selectedSkin ?: if (chromeIsDark(readerTheme, systemDark)) {
        SkinBuiltIns.dark
    } else {
        SkinBuiltIns.light
    }
    val tokens = SkinRegistry.toDesignTokens(skin)
    val palette = paletteOf(tokens)
    CompositionLocalProvider(
        LocalDesignTokens provides tokens,
        LocalLinguaPalette provides palette
    ) {
        MaterialTheme(colorScheme = colorSchemeFor(palette), shapes = AppShapes) {
            content(palette)
        }
    }
}
