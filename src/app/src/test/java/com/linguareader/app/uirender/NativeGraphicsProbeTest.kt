package com.linguareader.app.uirender

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 环境探针：验证 Robolectric NATIVE 图形模式下 Canvas **真的**光栅化并写出 PNG。
 *
 * 为什么需要它：本机没有硬件虚拟化（HypervisorPresent=False，x86_64 镜像起不来），
 * 也不保证有真机，「改动必须真机实测」这一条在视觉重构里会一直阻塞。若这里绿了，
 * 就有一条纯 JVM 的视觉回归通道可用。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NativeGraphicsProbeTest {

    @Test
    fun bitmapPixelsRoundTripThroughArgb() {
        val bmp = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.LTGRAY)
        bmp.setPixel(1, 0, Color.RED)
        assertEquals("LTGRAY roundtrip", 0xFFCCCCCC.toInt(), bmp.getPixel(0, 0))
        assertEquals("RED roundtrip", 0xFFFF0000.toInt(), bmp.getPixel(1, 0))
    }

    @Test
    fun canvasDrawsAndCompressesToPng() {
        val w = 240
        val h = 120
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(247, 243, 234))
        val p = Paint().apply { color = Color.rgb(141, 85, 53); isAntiAlias = true }
        canvas.drawRect(20f, 20f, 220f, 100f, p)

        // 关键判据：绘制内容确实落在 bitmap 上（不是空白 canvas）
        val center = bmp.getPixel(120, 60)
        assertEquals("drawn rect color at center", 0xFF8D5535.toInt(), center)
        val outside = bmp.getPixel(5, 5)
        assertEquals("background color outside rect", 0xFFF7F3EA.toInt(), outside)

        val out = File("build/ui-render-probe/native-probe.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PROBE_PNG=" + out.absolutePath + " bytes=" + out.length())
        assertTrue("PNG should be written", out.length() > 100)
        assertTrue("PNG magic", out.readBytes().take(4) == listOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    }
}
