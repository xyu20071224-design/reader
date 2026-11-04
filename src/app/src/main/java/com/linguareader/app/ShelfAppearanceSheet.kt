package com.linguareader.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.app.components.LinguaSheet
import com.linguareader.app.components.SecondaryButton
import com.linguareader.app.components.SectionHeader
import com.linguareader.app.skin.ImageSkinImporter
import com.linguareader.app.skin.SkinStore
import com.linguareader.app.skin.LocalDesignTokens
import com.linguareader.shared.data.ReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 图片导入的行内结果。internal 而非 private：[ShelfAppearanceContent] 的参数类型要用到它。 */
internal enum class ShelfImageStatus { DONE, FAILED }

// ── 组件专有几何常量 ──────────────────────────────────────────────
//
// 这几个值**不进间距梯**，理由是它们不是「间距」而是组件自身的形状参数：
// 间距梯的意义是「同一语义在不同屏上表现一致」（区块间距/卡内边距/行内间隙），
// 把「一个色块多大」塞进去只会让梯子变成杂物间——下一个人看到 `spacing.xxl`
// 将无法判断它指的是「大间距」还是「色块直径」。
//
// 而梯子里也确实没有对应档位（4/8/16/24/32/48）：44、64、10、12、6 硬套会改变像素，
// 而 P3 的验收前提是「迁移前后视觉等价」。集中定义在这里，可读性也不比裸 dp 差。
private val SwatchDiameter = 44.dp
private val SwatchColumnWidth = 64.dp
private val SwatchBorderSelected = 2.dp
private val SwatchBorderNormal = 1.dp
private val SwatchLabelGap = 4.dp
private val ChipBorder = 1.dp
private val ChipHorizontalPadding = 12.dp
private val ChipVerticalPadding = 6.dp
private val AnimChipColumnGap = 10.dp

/**
 * 书架外观弹层：预设背景色 / 自定义图片 / 蒙版浓度 / 恢复默认。
 *
 * 状态由 BookshelfScreen 持有（背景要实时跟着变），这里只做受控编辑；
 * 弹层会盖住全局 Snackbar，导入成败用行内状态提示（照 ListeningSettingsSheet 的先例）。
 *
 * P3 迁移：外壳换成 [LinguaSheet]，分区标题换成 [SectionHeader]，按钮换成 [SecondaryButton]，
 * 样式取自 [LocalDesignTokens]。**交互、文案、持久化语义零改动**。
 *
 * 注意：书架背景的 4 个预设色（[ShelfBackgroundPresets]）是**用户数据**（以 Long 存进设置），
 * 不是样式 token，因此原样保留；这里只是用设计系统的容器承载它们。
 */
@Composable
internal fun ShelfAppearanceSheet(
    appearance: ShelfAppearance,
    onAppearanceChange: (ShelfAppearance) -> Unit,
    animSpeed: UiAnimSpeed,
    onAnimSpeedChange: (UiAnimSpeed) -> Unit,
    /** Q2-c07：主题改动要通知外壳，让整体配色立刻生效（否则只写盘、需重启才变）。 */
    onReaderThemeChange: (ReaderTheme) -> Unit,
    /**
     * 皮肤改动要立刻通知外壳（P19）。
     *
     * 与 [onReaderThemeChange] 同一模式：**改了要立刻生效**，不能只写盘等重启。
     * 外壳（MainActivity）收到后更新自己的 customSkin 状态，整棵树重组。
     */
    onSkinChange: (com.linguareader.shared.ui.skin.SkinSpec?) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageStatus by remember { mutableStateOf<ShelfImageStatus?>(null) }
    // 阅读主题（审查 5-10）：在书架弹层内直接可改；未设置过时按当前系统深浅推断初值。
    val systemDark = isSystemInDarkTheme()
    var selectedTheme by remember(systemDark) {
        mutableStateOf(storedReaderTheme(context) ?: if (systemDark) ReaderTheme.DARK else ReaderTheme.PAPER)
    }
    // 深色外壳下浅色预设不可读（审查 5-1），据此禁用预设并说明。
    val darkChrome = chromeIsDark(storedReaderTheme(context), isSystemInDarkTheme())

    // 皮肤选择（P19）：与上面的「书架背景」是两件事 ——
    // 背景只影响书架那张纸下面的图；皮肤影响所有已迁移的屏。
    // 这里的状态与持久化都走 SkinStore，选中后通过 onSkinChange 通知外壳立刻重组。
    var customSkin by remember { mutableStateOf(SkinStore.currentCustom(context)) }
    var selectedSkinId by remember { mutableStateOf(SkinStore.selectedIdOrLight(context)) }
    val skinLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                // 任何解码失败都会退回内置皮肤（ImageSkinImporter 内部保证不抛）
                val spec = ImageSkinImporter.import(context, uri)
                SkinStore.selectCustom(context, spec)
                customSkin = spec
                selectedSkinId = spec.id
                onSkinChange(spec)
            }
        }

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

    LinguaSheet(
        onDismiss = onDismiss,
        // 标题由容器统一管（一致性 + 可访问性：title 槽之后会挂 heading 语义）。
        title = stringResource(R.string.shelf_appearance_title)
    ) {
        ShelfAppearanceContent(
            appearance = appearance,
            onAppearanceChange = onAppearanceChange,
            animSpeed = animSpeed,
            onAnimSpeedChange = onAnimSpeedChange,
            selectedTheme = selectedTheme,
            onSelectTheme = { theme ->
                selectedTheme = theme
                storeReaderTheme(context, theme)
                onReaderThemeChange(theme)
            },
            darkChrome = darkChrome,
            imageStatus = imageStatus,
            onImageStatusShown = { imageStatus = null },
            onPickImage = { imageLauncher.launch(arrayOf("image/*")) },
            selectedSkinId = selectedSkinId,
            customSkin = customSkin,
            onSelectSkin = { id ->
                SkinStore.selectBuiltIn(context, id)
                selectedSkinId = id
                customSkin = null
                // 内置皮肤交给外壳按「阅读主题 + 系统夜间」重新选，故传 null
                onSkinChange(null)
            },
            onPickSkinImage = { skinLauncher.launch(arrayOf("image/*")) },
            onRemoveCustomSkin = {
                SkinStore.clearCustom(context)
                selectedSkinId = com.linguareader.shared.ui.skin.SkinBuiltIns.light.id
                customSkin = null
                onSkinChange(null)
            },
            onReset = {
                scope.launch {
                    onAppearanceChange(withContext(Dispatchers.IO) { ShelfAppearance.reset(context) })
                }
            }
        )
    }
}

/**
 * 弹层的**内容体**，与 sheet 外壳解耦（理由同 `UpdateSheetContent`：
 * `ModalBottomSheet` 在渲染台里画不进位图，抽出来才能做迁移前后对比）。
 *
 * ⚠️ 与 `UpdateSheetContent` 同样的代价：**不再自带宽度/背景约束**，
 * 单独使用时调用方必须补齐（渲染台见 `SheetMigrationRenderTest`）。
 *
 * 只收数据与回调，不碰 Activity/SAF（那两个留给外壳），所以它是纯展示、可渲染的。
 */
@Composable
internal fun ShelfAppearanceContent(
    appearance: ShelfAppearance,
    onAppearanceChange: (ShelfAppearance) -> Unit,
    animSpeed: UiAnimSpeed,
    onAnimSpeedChange: (UiAnimSpeed) -> Unit,
    selectedTheme: ReaderTheme,
    onSelectTheme: (ReaderTheme) -> Unit,
    darkChrome: Boolean,
    imageStatus: ShelfImageStatus?,
    /** 行内状态展示满 3 秒后由外壳清空（状态是外壳持有的，内容体只负责画与计时）。 */
    onImageStatusShown: () -> Unit,
    onPickImage: () -> Unit,
    onReset: () -> Unit,
    /** 皮肤选择（P9）。默认值让既有调用方与渲染夹具零改动。 */
    skinBuiltIns: List<com.linguareader.shared.ui.skin.SkinSpec> =
        com.linguareader.shared.ui.skin.SkinBuiltIns.all,
    selectedSkinId: String = com.linguareader.shared.ui.skin.SkinBuiltIns.light.id,
    customSkin: com.linguareader.shared.ui.skin.SkinSpec? = null,
    onSelectSkin: (String) -> Unit = {},
    onPickSkinImage: () -> Unit = {},
    onRemoveCustomSkin: () -> Unit = {}
) {
    val t = LocalDesignTokens.current
    // 自带宽度的 Column + 统一行距：原实现是 `Column(spacedBy(16.dp))`，
    // 换成 LinguaSheet 的 content 槽之后没有别人替它给间距了，必须自己带。
    // 标题不在这里画：由 LinguaSheet 的 title 槽统一渲染（见外壳注释）。
    Column(
        modifier = Modifier.fillMaxWidth(),
        // 原实现是 spacedBy(16.dp)，正好等于 t.spacing.m —— 用它保持像素级一致。
        verticalArrangement = Arrangement.spacedBy(t.spacing.m)
    ) {
        SectionHeader(text = stringResource(R.string.shelf_appearance_background))
        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.m)) {
            PresetSwatch(
                label = stringResource(R.string.shelf_appearance_follow_theme),
                selected = appearance.presetId == null && appearance.imageFile == null,
                brush = Brush.linearGradient(listOf(LightLinguaPalette.paper, DarkLinguaPalette.paper)),
                onClick = { onAppearanceChange(ShelfAppearance(dimOpacity = appearance.dimOpacity)) }
            )
            ShelfBackgroundPresets.all.forEach { preset ->
                PresetSwatch(
                    label = stringResource(preset.labelRes),
                    selected = appearance.presetId == preset.id && appearance.imageFile == null,
                    brush = Brush.verticalGradient(
                        // 预设色是用户数据（见类注释），不是样式 token，故此处直接 Color(Long)。
                        listOf(androidx.compose.ui.graphics.Color(preset.topColor), androidx.compose.ui.graphics.Color(preset.bottomColor))
                    ),
                    enabled = !darkChrome,
                    onClick = {
                        onAppearanceChange(appearance.copy(presetId = preset.id, imageFile = null))
                    }
                )
            }
        }

        // 深色外壳下这些浅色预设叠加后文字对比度只有 1.02–2.56:1（审查 5-1），
        // 直接禁用并说明原因，比装上去再让用户自己发现不可读更诚实。
        if (darkChrome) {
            Text(
                text = stringResource(R.string.shelf_preset_dark_disabled),
                style = t.typography.caption,
                color = t.colors.inkSoft
            )
        }

        // 审查 5-10：阅读主题入口原本只在阅读页（约 4 次点击），书架处没有入口。
        // 这里直接给出同一个选择器（与阅读页共用 prefs 键），点击深度从 4 降到 ≤2。
        SectionHeader(text = stringResource(R.string.reader_theme_label))
        ReaderThemePicker(selected = selectedTheme, onSelect = onSelectTheme)

        SecondaryButton(
            text = stringResource(
                if (appearance.imageFile != null) {
                    R.string.shelf_background_change_image
                } else {
                    R.string.shelf_background_pick_image
                }
            ),
            onClick = onPickImage,
            modifier = Modifier.fillMaxWidth()
        )
        imageStatus?.let { status ->
            Text(
                text = stringResource(
                    if (status == ShelfImageStatus.DONE) {
                        R.string.shelf_background_image_done
                    } else {
                        R.string.shelf_background_image_failed
                    }
                ),
                style = t.typography.caption,
                color = if (status == ShelfImageStatus.DONE) t.colors.success else t.colors.danger
            )
            LaunchedEffect(status) {
                delay(3000)
                onImageStatusShown()
            }
        }

        SectionHeader(text = stringResource(R.string.shelf_background_dim))
        Slider(
            value = appearance.dimOpacity,
            onValueChange = { onAppearanceChange(appearance.copy(dimOpacity = it)) },
            valueRange = 0f..0.8f,
            colors = SliderDefaults.colors(
                thumbColor = t.colors.accent,
                activeTrackColor = t.colors.accent,
                inactiveTrackColor = t.colors.paperDeep
            )
        )

        UiAnimSpeedSelector(animSpeed = animSpeed, onAnimSpeedChange = onAnimSpeedChange)

        // 皮肤选择（P9）：与上面的「书架背景」是两件事 ——
        // 背景只影响书架那张纸下面的图，皮肤影响所有已迁移的屏（弹层、生词本、复习屏…）。
        // 放在这里是因为它是用户找「外观」时的自然落点，不必再开一屏。
        com.linguareader.app.skin.SkinPickerSection(
            builtIns = skinBuiltIns,
            selectedId = selectedSkinId,
            customSkin = customSkin,
            onSelectBuiltIn = onSelectSkin,
            onPickImage = onPickSkinImage,
            onRemoveCustom = onRemoveCustomSkin
        )

        SecondaryButton(
            text = stringResource(R.string.shelf_background_reset),
            onClick = onReset,
            modifier = Modifier.fillMaxWidth()
        )
        }
    }

    @Composable
    private fun PresetSwatch(
        label: String,
    selected: Boolean,
    brush: Brush,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val t = LocalDesignTokens.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(SwatchColumnWidth)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        Box(
            modifier = Modifier
                .size(SwatchDiameter)
                .clip(CircleShape)
                .background(brush)
                .border(
                    width = if (selected) SwatchBorderSelected else SwatchBorderNormal,
                    color = if (selected) t.colors.accent else t.colors.inkFaint,
                    shape = CircleShape
                )
        )
        Spacer(Modifier.height(SwatchLabelGap))
        Text(
            text = label,
            style = t.typography.label,
            color = if (selected) t.colors.accent else t.colors.inkSoft,
            maxLines = 1
        )
    }
}

/**
 * 全局交互动画速度选择器：横向一排选项（舒缓 / 标准 / 跟手 / 关闭）。
 * 独立成组件，方便以后放到其它设置入口复用。
 */
@Composable
internal fun UiAnimSpeedSelector(
    animSpeed: UiAnimSpeed,
    onAnimSpeedChange: (UiAnimSpeed) -> Unit
) {
    val t = LocalDesignTokens.current
    // 行内 gap 用 token（8 = spacing.s）；排间距 10dp 梯子里没有，走专有常量。
    Column(verticalArrangement = Arrangement.spacedBy(AnimChipColumnGap)) {
        SectionHeader(text = stringResource(R.string.shelf_anim_speed_title))
        Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.s)) {
            UiAnimSpeed.entries.forEach { speed ->
                AnimSpeedChip(
                    label = stringResource(speed.labelRes()),
                    selected = speed == animSpeed,
                    onClick = { onAnimSpeedChange(speed) }
                )
            }
        }
    }
}

@Composable
private fun AnimSpeedChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .clip(t.pillShape)
            .background(if (selected) t.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                width = ChipBorder,
                color = if (selected) t.colors.accent else t.colors.inkFaint,
                shape = t.pillShape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = ChipHorizontalPadding, vertical = ChipVerticalPadding)
    ) {
        Text(
            text = label,
            style = t.typography.label,
            color = if (selected) t.colors.accent else t.colors.inkSoft
        )
    }
}

private fun UiAnimSpeed.labelRes(): Int = when (this) {
    UiAnimSpeed.SLOW -> R.string.shelf_anim_speed_slow
    UiAnimSpeed.NORMAL -> R.string.shelf_anim_speed_normal
    UiAnimSpeed.FAST -> R.string.shelf_anim_speed_fast
    UiAnimSpeed.OFF -> R.string.shelf_anim_speed_off
}