package com.linguareader.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ShelfImageStatus { DONE, FAILED }

/**
 * 书架外观弹层：预设背景色 / 自定义图片 / 蒙版浓度 / 恢复默认。
 *
 * 状态由 BookshelfScreen 持有（背景要实时跟着变），这里只做受控编辑；
 * 弹层会盖住全局 Snackbar，导入成败用行内状态提示（照 ListeningSettingsSheet 的先例）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfAppearanceSheet(
    appearance: ShelfAppearance,
    onAppearanceChange: (ShelfAppearance) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageStatus by remember { mutableStateOf<ShelfImageStatus?>(null) }

    val imageLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val ok = withContext(Dispatchers.IO) { ShelfBackgroundStore.importImage(context, uri) }
                if (ok) {
                    onAppearanceChange(appearance.copy(imageFile = ShelfBackgroundStore.BACKGROUND_FILE))
                }
                imageStatus = if (ok) ShelfImageStatus.DONE else ShelfImageStatus.FAILED
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Paper) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                stringResource(R.string.shelf_appearance_title),
                style = MaterialTheme.typography.titleMedium,
                color = Ink
            )

            Text(
                stringResource(R.string.shelf_appearance_background),
                style = MaterialTheme.typography.labelLarge,
                color = InkSoft
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PresetSwatch(
                    label = stringResource(R.string.shelf_appearance_follow_theme),
                    selected = appearance.presetId == null && appearance.imageFile == null,
                    brush = Brush.linearGradient(
                        listOf(LightLinguaPalette.paper, DarkLinguaPalette.paper)
                    ),
                    onClick = { onAppearanceChange(ShelfAppearance(dimOpacity = appearance.dimOpacity)) }
                )
                ShelfBackgroundPresets.all.forEach { preset ->
                    PresetSwatch(
                        label = stringResource(preset.labelRes),
                        selected = appearance.presetId == preset.id && appearance.imageFile == null,
                        brush = Brush.verticalGradient(
                            listOf(Color(preset.topColor), Color(preset.bottomColor))
                        ),
                        onClick = {
                            onAppearanceChange(
                                appearance.copy(presetId = preset.id, imageFile = null)
                            )
                        }
                    )
                }
            }

            OutlinedButton(
                onClick = { imageLauncher.launch(arrayOf("image/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (appearance.imageFile != null) {
                            R.string.shelf_background_change_image
                        } else {
                            R.string.shelf_background_pick_image
                        }
                    )
                )
            }
            imageStatus?.let { status ->
                Text(
                    stringResource(
                        if (status == ShelfImageStatus.DONE) {
                            R.string.shelf_background_image_done
                        } else {
                            R.string.shelf_background_image_failed
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == ShelfImageStatus.DONE) Success else Danger
                )
                LaunchedEffect(status) {
                    delay(3000)
                    imageStatus = null
                }
            }

            Text(
                stringResource(R.string.shelf_background_dim),
                style = MaterialTheme.typography.labelLarge,
                color = InkSoft
            )
            Slider(
                value = appearance.dimOpacity,
                onValueChange = { onAppearanceChange(appearance.copy(dimOpacity = it)) },
                valueRange = 0f..0.8f
            )

            OutlinedButton(
                onClick = {
                    scope.launch {
                        onAppearanceChange(withContext(Dispatchers.IO) { ShelfAppearance.reset(context) })
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.shelf_background_reset))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PresetSwatch(
    label: String,
    selected: Boolean,
    brush: Brush,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(brush)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Accent else InkFaint,
                    shape = CircleShape
                )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Accent else InkSoft,
            maxLines = 1
        )
    }
}
