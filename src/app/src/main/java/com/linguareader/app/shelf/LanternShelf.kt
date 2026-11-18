package com.linguareader.app.shelf

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.linguareader.app.UiTags
import com.linguareader.app.skin.LANTERN_ARTWORK_DIRECTORY
import com.linguareader.app.skin.LanternAssets
import com.linguareader.app.skin.LanternMode
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.app.skin.readLanternManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LanternEmptyShelf(mode: LanternMode, onImport: () -> Unit) {
    val tokens = LocalDesignTokens.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val artworkHeight = (maxHeight * .56f).coerceIn(132.dp, 450.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            LanternShelfArtwork(mode, Modifier.fillMaxWidth().height(artworkHeight), fadesIntoPaper = true)
            ShelfEmptyState(onImport = onImport, modifier = Modifier.fillMaxWidth().background(tokens.colors.paper))
        }
    }
}

@Composable
internal fun LanternShelfArtwork(
    mode: LanternMode,
    modifier: Modifier = Modifier,
    fadesIntoPaper: Boolean = false
) {
    val assets = LocalContext.current.assets
    val tokens = LocalDesignTokens.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val manifest by produceState<LanternAssets?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            runCatching { LanternAssets(assets.readLanternManifest()) }.getOrNull()
        }
    }
    // onSizeChanged reports physical pixels. Only the WebView needs to multiply CSS px by DPR.
    val candidate = manifest?.select(mode, size.width, size.height)
    val bitmap by produceState<ImageBitmap?>(null, assets, candidate) {
        val file = candidate?.file ?: return@produceState
        val decoded = withContext(Dispatchers.IO) {
            runCatching {
                assets.open("$LANTERN_ARTWORK_DIRECTORY/$file").use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        // A new selection cancels this producer. A failed decode leaves the current image on screen.
        if (decoded != null) value = decoded.asImageBitmap()
    }
    val frame = if (fadesIntoPaper) Modifier else {
        Modifier.clip(tokens.cardShape).border(tokens.hairline, tokens.colors.outline, tokens.cardShape)
    }
    Box(
        modifier
            .then(frame)
            .onSizeChanged { size = it }
            .background(Brush.linearGradient(listOf(tokens.colors.paperDeep, tokens.colors.accentSoft)))
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().testTag(UiTags.SHELF_LANTERN_IMAGE),
                alignment = BiasAlignment(horizontalBias = .32f, verticalBias = .28f),
                contentScale = ContentScale.Crop
            )
        }
        if (fadesIntoPaper) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        .72f to Color.Transparent,
                        .93f to tokens.colors.paper.copy(alpha = .70f),
                        1f to tokens.colors.paper
                    )
                )
            )
        }
    }
}

@Composable
internal fun Modifier.lanternBookSurface(): Modifier {
    val tokens = LocalDesignTokens.current
    return clip(tokens.cardShape)
        .background(
            Brush.verticalGradient(
                0f to tokens.colors.accentSoft,
                .22f to tokens.colors.cardSurface,
                .87f to tokens.colors.cardSurface,
                1f to tokens.colors.paperDeep
            )
        )
        .border(tokens.hairline, tokens.colors.outline, tokens.cardShape)
}
