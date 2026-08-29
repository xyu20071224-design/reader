package com.linguareader.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 自动更新设置：默认值与持久化往返。 */
@RunWith(RobolectricTestRunner::class)
class AppUpdateSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `auto check defaults to off`() {
        // 隐私边界：自动检查是出网行为，出厂必须默认关闭。
        assertFalse(AppUpdateSettings.load(context).autoCheckEnabled)
    }

    @Test
    fun `settings round trip`() {
        AppUpdateSettings.save(context, AppUpdateSettings(autoCheckEnabled = true))
        assertTrue(AppUpdateSettings.load(context).autoCheckEnabled)
        AppUpdateSettings.save(context, AppUpdateSettings(autoCheckEnabled = false))
        assertFalse(AppUpdateSettings.load(context).autoCheckEnabled)
    }
}
