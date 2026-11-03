package com.linguareader.app.uirender

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 纯 JVM 的 Compose 渲染台：把任意 @Composable 渲染成 PNG。
 *
 * 用于「没有真机 / 没有模拟器」时的视觉回归：同一段 UI 反复出新旧两版 PNG，
 * 人眼或像素差对比。**它不是真机的替代品**——字体、WebView、手势、TTS 都测不到。
 */
object ComposeRenderHarness {

    /** 产物目录（相对 :app 模块根，即 src/app）。 */
    private const val OUTPUT_DIR = "build/ui-render-probe"

    /**
     * 渲染一张图。
     *
     * ⚠️ 参数是**像素**。Robolectric 的 `@Config(qualifiers = "...-xxhdpi")` 会让
     * density = 3.0，于是 `render(name, 540, 960)` 实际给 Compose 的可用宽度只有
     * 540 ÷ 3 = **180dp**——文字会被裁成半截、背景会在内容底部断层，看起来像实现 bug。
     * **夹具请优先用 [renderDp]**，它按 dp 收尺寸再换算成像素。
     *
     * @param name 文件名（不含扩展名），**快照基线用的名字要保持稳定**
     * @param widthPx 画布宽（像素）
     * @param heightPx 画布高（像素）
     */
    fun render(
        name: String,
        widthPx: Int = 1080,
        heightPx: Int = 2400,
        content: @Composable () -> Unit
    ): Bitmap {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent(content = content)
        val decor = activity.window.decorView
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, widthPx, heightPx)
        ShadowLooper.idleMainLooper()

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        bmp.eraseColor(android.graphics.Color.WHITE)
        decor.draw(canvas)

        val out = File(OUTPUT_DIR + "/" + name + ".png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENDER " + name + " -> " + out.absolutePath + " (" + out.length() + " bytes)")
        return bmp
    }

    /**
     * 按 **dp** 渲染（推荐）。内部用当前 `displayMetrics.density` 换算成像素。
     *
     * 为什么要有它：本渲染台的 qualifier 是 `xxhdpi`（density 3.0），夹具里写
     * `render(name, 411, 891)` 本意是「411dp 宽的手机」，实际只给了 137dp ——
     * 文字被裁、背景断层，而且症状长得非常像屏幕实现有问题。
     * 这个坑已经踩过一次（见 `SheetMigrationRenderTest` 的类注释），所以把它变成默认能力。
     *
     * @param widthDp 画布宽（dp），默认 411dp = 常见手机竖屏宽度
     * @param heightDp 画布高（dp），默认 891dp
     */
    fun renderDp(
        name: String,
        widthDp: Int = 411,
        heightDp: Int = 891,
        content: @Composable () -> Unit
    ): Bitmap {
        val density = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .resources.displayMetrics.density
        return render(name, (widthDp * density).toInt(), (heightDp * density).toInt(), content)
    }

    /**
     * 统计图片里出现最多的 N 种颜色（量化到 4 bit/通道），用来断言「这块区域确实是主题色」。
     * 便于在无基线图的情况下做粗粒度视觉断言。
     */
    fun dominantColors(bmp: Bitmap, top: Int = 5): List<Pair<Int, Int>> {
        val counts = HashMap<Int, Int>()
        for (x in 0 until bmp.width step 3) {
            for (y in 0 until bmp.height step 3) {
                val c = bmp.getPixel(x, y)
                val q = (c and 0xF0F0F0) // 量化
                counts[q] = (counts[q] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(top).map { it.key to it.value }
    }

    fun hexColor(c: Int): String = "#" + Integer.toHexString(c).uppercase().padStart(8, '0')
}
