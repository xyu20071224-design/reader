package com.linguareader.app.uirender

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 能力探针：在**纯 JVM** 里把真实 Compose UI 渲染成 PNG。
 *
 * 背景：本机没有硬件虚拟化，x86_64 模拟器起不来；真机也不总在线。视觉重构若只能靠
 * 真机验收，进度会长期卡住。若本测试绿，就有一条可自动化的视觉回归通道
 * （Robolectric NATIVE 图形 + 真 Activity 挂载 Compose + Bitmap 导出）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposeRenderProbeTest {

    private fun renderToPng(name: String, w: Int, h: Int, content: @Composable () -> Unit): Bitmap {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent(content = content)
        val decor = activity.window.decorView
        decor.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, w, h)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        bmp.eraseColor(android.graphics.Color.WHITE)
        decor.draw(canvas)

        val out = File("build/ui-render-probe/$name.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PROBE_PNG=$name bytes=${out.length()}")
        return bmp
    }

    @Test
    fun composeBoxWithBackgroundRenders() {
        val bmp = renderToPng("compose-box", 200, 100) {
            Box(Modifier.size(200.dp, 100.dp).background(Color(0xFF8D5535)))
        }
        assertEquals("background should be the accent color", 0xFF8D5535.toInt(), bmp.getPixel(100, 50))
    }

    @Test
    fun composeTextPaintsPixels() {
        val bmp = renderToPng("compose-text", 400, 200) {
            Box(Modifier.size(400.dp, 200.dp).background(Color.White)) {
                Text(
                    text = "Hello",
                    color = Color(0xFF27231F),
                    fontSize = 40.sp,
                    modifier = Modifier.padding(20.dp)
                )
            }
        }
        var nonWhite = 0
        for (x in 0 until 400 step 2) for (y in 0 until 200 step 2) {
            if (bmp.getPixel(x, y) != android.graphics.Color.WHITE) nonWhite++
        }
        println("PROBE_NONWHITE=$nonWhite")
        assertTrue("text must paint pixels, got $nonWhite", nonWhite > 50)
    }
}
