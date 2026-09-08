package com.linguareader.app

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 交互动画速度的落盘与生效链路：prefs 往返、坏值兜底、系统缩放读取。
 * 全局单例在用例间要复位，避免污染同 JVM 的其它测试。
 */
@RunWith(RobolectricTestRunner::class)
class UiAnimSpeedStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val delta = 0.0001f

    @After
    fun restoreGlobalScale() {
        appUiAnimDurationScale.scaleFactor = 1f
    }

    @Test
    fun `default speed is normal`() {
        assertEquals(UiAnimSpeed.NORMAL, storedUiAnimSpeed(context))
    }

    @Test
    fun `round trip persists the chosen speed`() {
        saveUiAnimSpeed(context, UiAnimSpeed.FAST)
        assertEquals(UiAnimSpeed.FAST, storedUiAnimSpeed(context))
    }

    @Test
    fun `unknown stored value falls back to normal`() {
        context.getSharedPreferences("reader_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString("ui_anim_speed", "TURBO")
            .commit()
        assertEquals(UiAnimSpeed.NORMAL, storedUiAnimSpeed(context))
    }

    @Test
    fun `apply multiplies the system animation scale`() {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0.5f
        )
        assertEquals(0.5f, systemAnimDurationScale(context), delta)

        applyUiAnimSpeed(context, UiAnimSpeed.SLOW)
        assertEquals(1f, appUiAnimDurationScale.scaleFactor, delta)

        applyUiAnimSpeed(context, UiAnimSpeed.OFF)
        assertEquals(0f, appUiAnimDurationScale.scaleFactor, delta)
    }

    @Test
    fun `system-disabled animation stays disabled after applying a tier`() {
        Settings.Global.putFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            0f
        )
        applyUiAnimSpeed(context, UiAnimSpeed.SLOW)
        assertEquals(0f, appUiAnimDurationScale.scaleFactor, delta)
    }
}
