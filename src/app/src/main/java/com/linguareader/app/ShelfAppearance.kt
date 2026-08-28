package com.linguareader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.StringRes
import java.io.File
import java.io.FileOutputStream

/**
 * 书架外观预设。只提供浅色底：外壳文字颜色由日/夜调色板驱动，深色底在日间外壳下
 * 无法保证可读性（顶栏标题、卡片文字都取 Ink 系语义色），所以不做深色预设。
 */
internal data class ShelfBackgroundPreset(
    val id: String,
    @StringRes val labelRes: Int,
    val topColor: Long,
    val bottomColor: Long
)

internal object ShelfBackgroundPresets {
    val all = listOf(
        ShelfBackgroundPreset("green", R.string.shelf_preset_green, 0xFFCCE8CF, 0xFFB4DCC0),
        ShelfBackgroundPreset("sand", R.string.shelf_preset_sand, 0xFFF2E4CE, 0xFFE6D2B4),
        ShelfBackgroundPreset("blush", R.string.shelf_preset_blush, 0xFFEBDCD8, 0xFFDFCAC6),
        ShelfBackgroundPreset("mist", R.string.shelf_preset_mist, 0xFFDCE7EC, 0xFFC6D6DE)
    )

    fun byId(id: String?): ShelfBackgroundPreset? = all.firstOrNull { it.id == id }
}

/**
 * 书架外观设置：预设背景色或自定义图片（二选一，图片优先），加可调蒙版保证文字可读。
 *
 * 持久化照 [com.linguareader.app.tts.CloudTtsSettings] 的手写 key 风格；背景图本体
 * 存 `filesDir/shelf_background/`（照 MiMoVoiceStore 的内部存储模式），prefs 只存文件名。
 */
internal data class ShelfAppearance(
    /** 预设 id，null = 跟随调色板默认纸色。 */
    val presetId: String? = null,
    /** 背景图文件名（位于 [ShelfBackgroundStore.backgroundDir]），null = 未设置。 */
    val imageFile: String? = null,
    /** 蒙版透明度：纸色系蒙版盖在背景上，数值越大背景越收敛、文字越清楚。 */
    val dimOpacity: Float = DEFAULT_DIM_OPACITY
) {
    val preset: ShelfBackgroundPreset? get() = ShelfBackgroundPresets.byId(presetId)

    /** 是否有任何自定义背景（预设或图片）。 */
    val isCustomized: Boolean get() = presetId != null || imageFile != null

    companion object {
        const val DEFAULT_DIM_OPACITY = 0.35f
        private const val PREFS = "shelf_settings"
        private const val KEY_PRESET = "preset_id"
        private const val KEY_IMAGE = "image_file"
        private const val KEY_DIM = "dim_opacity"

        fun load(context: Context): ShelfAppearance {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val image = prefs.getString(KEY_IMAGE, null).orEmpty().ifBlank { null }
            return ShelfAppearance(
                presetId = prefs.getString(KEY_PRESET, null).orEmpty().ifBlank { null },
                imageFile = image?.takeIf { ShelfBackgroundStore.backgroundFile(context, it).isFile },
                dimOpacity = prefs.getFloat(KEY_DIM, DEFAULT_DIM_OPACITY).coerceIn(0f, 1f)
            )
        }

        fun save(context: Context, appearance: ShelfAppearance) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRESET, appearance.presetId.orEmpty())
                .putString(KEY_IMAGE, appearance.imageFile.orEmpty())
                .putFloat(KEY_DIM, appearance.dimOpacity)
                .apply()
        }

        fun reset(context: Context): ShelfAppearance {
            ShelfBackgroundStore.deleteAll(context)
            return ShelfAppearance().also { save(context, it) }
        }
    }
}

/** 背景图的内部存储：导入时降采样为最长边 ≤2048px 的 JPEG，控制内存与磁盘占用。 */
internal object ShelfBackgroundStore {
    private const val DIR_NAME = "shelf_background"
    const val BACKGROUND_FILE = "background.jpg"
    private const val MAX_DIM = 2048
    private const val JPEG_QUALITY = 85

    fun backgroundDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun backgroundFile(context: Context, name: String): File = File(backgroundDir(context), name)

    /** 把所选图片导入为固定文件名 `background.jpg`；解析失败返回 false（调用方给行内提示）。 */
    fun importImage(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false

            val bitmap = resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(
                    input,
                    null,
                    BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
                )
            } ?: return@runCatching false

            val scaled = scaleDown(bitmap)
            val target = backgroundFile(context, BACKGROUND_FILE)
            backgroundDir(context).mkdirs()
            val ok = FileOutputStream(target).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            ok
        }.getOrDefault(false)
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_DIM) {
            sample *= 2
            longest /= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DIM) return bitmap
        val ratio = MAX_DIM.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    /** 删除背景图（重置用）；文件不存在也视为成功。 */
    fun deleteAll(context: Context): Boolean =
        runCatching { backgroundFile(context, BACKGROUND_FILE).delete() }.getOrDefault(false)
}
