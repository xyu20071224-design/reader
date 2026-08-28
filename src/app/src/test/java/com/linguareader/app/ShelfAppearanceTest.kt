package com.linguareader.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 书架外观设置：持久化往返、预设解析、背景图导入与重置清理。
 * 图片本体存 filesDir/shelf_background/，prefs 只存文件名；文件被清理后
 * load 要自动忽略图片引用，不能指向不存在的图。
 */
@RunWith(RobolectricTestRunner::class)
class ShelfAppearanceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `default appearance follows the theme palette`() {
        val appearance = ShelfAppearance.load(context)
        assertNull(appearance.presetId)
        assertNull(appearance.imageFile)
        assertEquals(ShelfAppearance.DEFAULT_DIM_OPACITY, appearance.dimOpacity, 0.001f)
        assertFalse(appearance.isCustomized)
    }

    @Test
    fun `round trip persists preset dim and image`() {
        ShelfAppearance.save(
            context,
            ShelfAppearance(
                presetId = "sand",
                imageFile = ShelfBackgroundStore.BACKGROUND_FILE,
                dimOpacity = 0.5f
            )
        )
        val loaded = ShelfAppearance.load(context)
        assertEquals("sand", loaded.presetId)
        assertEquals(0.5f, loaded.dimOpacity, 0.001f)
        // 背景图文件并不存在（本用例没导入），load 要忽略该引用。
        assertNull(loaded.imageFile)
        assertTrue(loaded.isCustomized)
    }

    @Test
    fun `unknown preset id keeps stored value but resolves to null`() {
        assertEquals(4, ShelfBackgroundPresets.all.size)
        ShelfAppearance.save(context, ShelfAppearance(presetId = "nope"))
        val loaded = ShelfAppearance.load(context)
        assertEquals("nope", loaded.presetId)
        assertNull(loaded.preset)
        assertTrue(loaded.isCustomized)
    }

    @Test
    fun `import image writes the fixed background file`() {
        val src = File(context.cacheDir, "src.jpg")
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.eraseColor(android.graphics.Color.RED)
            src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }

        val ok = ShelfBackgroundStore.importImage(context, Uri.fromFile(src))

        assertTrue(ok)
        val target = ShelfBackgroundStore.backgroundFile(context, ShelfBackgroundStore.BACKGROUND_FILE)
        assertTrue(target.isFile)
        ShelfAppearance.save(context, ShelfAppearance(imageFile = ShelfBackgroundStore.BACKGROUND_FILE))
        assertEquals(ShelfBackgroundStore.BACKGROUND_FILE, ShelfAppearance.load(context).imageFile)
    }

    @Test
    fun `import fails cleanly on an unreadable uri`() {
        val ok = ShelfBackgroundStore.importImage(context, Uri.parse("file:///nonexistent/image.jpg"))
        assertFalse(ok)
    }

    @Test
    fun `reset clears prefs and the image file`() {
        val target = ShelfBackgroundStore.backgroundFile(context, ShelfBackgroundStore.BACKGROUND_FILE)
        target.parentFile?.mkdirs()
        target.writeBytes(byteArrayOf(1, 2, 3))
        ShelfAppearance.save(
            context,
            ShelfAppearance(presetId = "mist", imageFile = ShelfBackgroundStore.BACKGROUND_FILE)
        )

        val reset = ShelfAppearance.reset(context)

        assertFalse(target.exists())
        assertFalse(reset.isCustomized)
        assertFalse(ShelfAppearance.load(context).isCustomized)
    }
}
