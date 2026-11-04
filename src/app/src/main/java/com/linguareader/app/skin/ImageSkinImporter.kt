package com.linguareader.app.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.linguareader.shared.ui.skin.ImageSkinBuilder
import com.linguareader.shared.ui.skin.SkinSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * **图片 → 皮肤**（P9：把三段式管线的第一段接上真实用户输入）。
 *
 * ## 为什么要有这个类
 *
 * `ImageSkinBuilder.fromImage(pixels, w, h, ...)` 要的是**解码后的 ARGB 像素数组**。
 * 从相册拿到的只是一个 `content://` Uri，中间这段「Uri → Bitmap → IntArray」
 * 此前只存在于测试里（测试直接构造像素）。**这个类是那段缺失的生产代码。**
 *
 * ## 三个必须做对的地方
 *
 * 1. **缩到 128×128 再采样**。用户可能选一张 4000×3000 的相机原图，
 *    直接 `getPixels` 会分配 ~48MB 数组。皮肤只需要色调分布，128 方图足够，
 *    且 `inSampleSize` 由系统解码器做，比事后缩放省内存。
 * 2. **估算解码尺寸再决定采样率**。`inJustDecodeBounds` 先读尺寸，
 *    避免把整张原图解码进内存。
 * 3. **任何失败都退回内置皮肤，不抛异常**。选错一张透明图 / 图被删了 /
 *    Uri 权限被回收，都不该让设置页崩掉或黑屏。
 *
 * 失败的原因分三类，都必须走降级：IO 异常、解码返回 null、解码后全透明。
 */
object ImageSkinImporter {

    /** 采样后的目标边长。128 足够表达色调分布，且远小于手机缩略图。 */
    private const val TARGET_SIZE = 128

    /**
     * 从 [uri] 导入一张图片皮肤。
     *
     * @param name 展示名（设置页回显）。默认「图片皮肤」。
     * @return 永远非 null —— 解码失败会退回内置日间皮肤（`id` 仍为图片指纹）。
     */
    suspend fun import(
        context: Context,
        uri: Uri,
        name: String = "图片皮肤"
    ): SkinSpec = withContext(Dispatchers.IO) {
        val bitmap = runCatching { decodeSampled(context, uri) }.getOrNull()
            ?: return@withContext ImageSkinBuilder.fromClusters(emptyList(), name = name)

        try {
            val scaled = Bitmap.createScaledBitmap(bitmap, TARGET_SIZE, TARGET_SIZE, true)
            val pixels = IntArray(TARGET_SIZE * TARGET_SIZE)
            scaled.getPixels(pixels, 0, TARGET_SIZE, 0, 0, TARGET_SIZE, TARGET_SIZE)
            if (scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
            ImageSkinBuilder.fromImage(pixels, TARGET_SIZE, TARGET_SIZE, name = name)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** 先读尺寸再按采样率解码，避免整张原图进内存。 */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IOException("无法打开图片：\$uri")

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("图片尺寸无效：\${bounds.outWidth}x\${bounds.outHeight}")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /** 2 的幂，让解码后的图不小于 [TARGET_SIZE]。 */
    internal fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        val shortest = minOf(width, height)
        while (shortest / (sample * 2) >= TARGET_SIZE) sample *= 2
        return sample
    }
}