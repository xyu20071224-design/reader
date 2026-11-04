package com.linguareader.app.skin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.ui.skin.ImageSkinBuilder
import com.linguareader.shared.ui.skin.SkinBuiltIns
import com.linguareader.shared.ui.skin.SkinOrigin
import com.linguareader.shared.ui.skin.SkinSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **换肤管线的连通性测试**（P9）。
 *
 * ## 守的是什么
 *
 * 在本次改动之前，`ImageSkinBuilder` / `ImagePaletteExtractor` 这两个类**在生产代码里零调用点** ——
 * 80 多个单元测试全绿，但用户永远用不到。这类「建好了但没接线」的缺陷，
 * 单测覆盖不到，只有「从用户入口走到最后一步」的测试才抓得住。
 *
 * 所以这里测的不是算法（那在 `:shared` 已有 80 个测试），而是**接线**：
 * 1. 图片 → SkinSpec（`ImageSkinBuilder` 真的被调到了）；
 * 2. SkinSpec → 持久化 → 读回（`SkinStore` 往返）；
 * 3. 读回的皮肤能翻译成 DesignTokens（`SkinRegistry` 接受它）。
 *
 * ## 为什么测采样率
 *
 * `ImageSkinImporter.sampleSizeFor` 是防 OOM 的关键：用户可能选 4000×3000 的相机原图，
 * 不采样直接 `getPixels` 要分配 ~48MB。这个函数是纯的，可以直接测边界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SkinPipelineWiringTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SkinStore.clearCustom(context)
    }

    /** 构造一张有明确色调的图：暖木色，确保能生成合法的浅色皮肤。 */
    private fun warmWoodSkin(): SkinSpec {
        val size = 64
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // 大面积暖棕纸色 + 少量深色墨点，模拟一张书桌照。
                val deep = (x in 20..40 && y in 20..40)
                pixels[y * size + x] = if (deep) 0xFF2B2118.toInt() else 0xFFE8D9C3.toInt()
            }
        }
        return ImageSkinBuilder.fromImage(pixels, size, size, name = "书桌照")
    }

    @Test
    fun imageBecomesSkinSpecEndToEnd() {
        val spec = warmWoodSkin()
        assertEquals(SkinOrigin.IMAGE, spec.origin)
        // 纸色必须是浅色：暖木照片不该被判定成深色皮肤（历史缺陷）。
        val paperLum = luminance(spec.colors.paper)
        assertTrue("暖木照片应生成浅色纸，实际亮度 \$paperLum", paperLum > 0.6)
    }

    @Test
    fun customSkinSurvivesPersistenceRoundTrip() {
        val spec = warmWoodSkin()
        SkinStore.selectCustom(context, spec)

        val loaded = SkinStore.currentCustom(context)
        assertNotNull("自定义皮肤应当能读回", loaded)
        assertEquals(spec.id, loaded!!.id)
        assertEquals(spec.colors.paper, loaded.colors.paper)
        assertEquals(spec.colors.accent, loaded.colors.accent)
    }

    @Test
    fun builtInSelectionDoesNotPersistJson() {
        SkinStore.selectBuiltIn(context, SkinBuiltIns.dark.id)
        // 内置皮肤不存 JSON —— 否则内置皮肤升级后会被旧 JSON 永久锁住。
        assertNull(SkinStore.currentCustom(context))
        assertEquals(SkinBuiltIns.dark.id, SkinStore.selectedIdOrLight(context))
    }

    @Test
    fun clearingCustomFallsBackToLight() {
        SkinStore.selectCustom(context, warmWoodSkin())
        assertNotNull(SkinStore.currentCustom(context))
        SkinStore.clearCustom(context)
        assertNull(SkinStore.currentCustom(context))
        assertEquals(SkinBuiltIns.light.id, SkinStore.selectedIdOrLight(context))
    }

    @Test
    fun corruptJsonFallsBackInsteadOfCrashing() {
        // 直接写入一段坏 JSON，模拟「升级后格式变了 / 用户手改 / 磁盘写坏」。
        context.getSharedPreferences("app_skin", Context.MODE_PRIVATE).edit()
            .putString("selected_skin_id", "image-broken")
            .putString("selected_skin_json", "{not valid json")
            .apply()

        // 不抛异常、不返回 null 给调用方去崩，而是退回内置。
        assertEquals(SkinBuiltIns.light.id, SkinStore.load(context).id)
        assertNull(SkinStore.currentCustom(context))
    }

    @Test
    fun loadedSkinTranslatesToDesignTokens() {
        // 接线最后一环：读回来的皮肤必须能被 SkinRegistry 接受并产出 token。
        SkinStore.selectCustom(context, warmWoodSkin())
        val loaded = SkinStore.load(context)
        val tokens = SkinRegistry.toDesignTokens(loaded)
        assertEquals(loaded.id, tokens.skinId)
        assertEquals(loaded.name, tokens.skinName)
    }

    // ── 采样率：防 OOM 的关键 ──────────────────────────────

    @Test
    fun sampleSizeKeepsImageAtOrAboveTarget() {
        // 小图不采样。
        assertEquals(1, ImageSkinImporter.sampleSizeFor(128, 128))
        assertEquals(1, ImageSkinImporter.sampleSizeFor(255, 255))
        // 4000×3000 取「缩完最短边仍 >= 128」的最大 2 的幂：
        // 3000/16 = 187 >= 128，3000/32 = 93 < 128，故 sample = 16。
        assertEquals(16, ImageSkinImporter.sampleSizeFor(4000, 3000))

        // 判据写成两条不变量而不是只钉一个数，这样将来改 TARGET_SIZE 时测试依然成立：
        //   采样后最短边仍 >= 目标边长（不丢信息）
        //   且再采样一倍就会低于目标（即这是「最大」的 2 的幂，没有白白丢分辨率）
        val sample = ImageSkinImporter.sampleSizeFor(4000, 3000)
        assertTrue("采样后最短边必须仍 >= 128，实际 ${3000 / sample}", 3000 / sample >= 128)
        assertTrue("采样率不该超过必要大小，实际 sample=$sample", 3000 / (sample * 2) < 128)
    }

    private fun luminance(argb: Long): Double {
        val r = ((argb shr 16) and 0xFF).toDouble() / 255
        val g = ((argb shr 8) and 0xFF).toDouble() / 255
        val b = (argb and 0xFF).toDouble() / 255
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}