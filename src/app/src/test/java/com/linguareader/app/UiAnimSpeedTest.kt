package com.linguareader.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交互动画速度档位：只测纯函数（档位 × 系统缩放），不依赖 Android。
 */
class UiAnimSpeedTest {

    private val delta = 0.0001f

    @Test
    fun `normal follows the system scale`() {
        assertEquals(1f, effectiveAnimDurationScale(UiAnimSpeed.NORMAL, 1f), delta)
        assertEquals(0.5f, effectiveAnimDurationScale(UiAnimSpeed.NORMAL, 0.5f), delta)
        assertEquals(2f, effectiveAnimDurationScale(UiAnimSpeed.NORMAL, 2f), delta)
    }

    @Test
    fun `tiers multiply the system scale`() {
        assertEquals(2f, effectiveAnimDurationScale(UiAnimSpeed.SLOW, 1f), delta)
        assertEquals(1f, effectiveAnimDurationScale(UiAnimSpeed.SLOW, 0.5f), delta)
        assertEquals(0.5f, effectiveAnimDurationScale(UiAnimSpeed.FAST, 1f), delta)
        assertEquals(0.25f, effectiveAnimDurationScale(UiAnimSpeed.FAST, 0.5f), delta)
    }

    @Test
    fun `off disables animation whatever the system scale is`() {
        assertEquals(0f, effectiveAnimDurationScale(UiAnimSpeed.OFF, 1f), delta)
        assertEquals(0f, effectiveAnimDurationScale(UiAnimSpeed.OFF, 2f), delta)
    }

    @Test
    fun `system-disabled animation is never re-enabled by a tier`() {
        // 系统「移除动画」是无障碍承诺，任何档位都不能把它翻回正数。
        UiAnimSpeed.entries.forEach { speed ->
            assertEquals(
                "${speed.name} re-enabled animation while the system disabled it",
                0f,
                effectiveAnimDurationScale(speed, 0f),
                delta
            )
        }
    }

    @Test
    fun `scale never goes negative`() {
        assertEquals(0f, effectiveAnimDurationScale(UiAnimSpeed.SLOW, -1f), delta)
    }

    @Test
    fun `tiers are strictly ordered fast to slow`() {
        // 关闭 < 跟手 < 标准 < 舒缓。
        val order = listOf(UiAnimSpeed.OFF, UiAnimSpeed.FAST, UiAnimSpeed.NORMAL, UiAnimSpeed.SLOW)
        order.zipWithNext().forEach { (fast, slow) ->
            assertTrue(
                "${fast.name}(${fast.scaleFactor}) not faster than ${slow.name}(${slow.scaleFactor})",
                fast.scaleFactor < slow.scaleFactor
            )
        }
    }
}
