package com.linguareader.app

import com.linguareader.shared.tts.DegradedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第四轮审查 6-6 第二项：降级/暂停必须在 UI 有**可见**提示。
 *
 * 这里锁住"哪种状态显示哪句"的映射（`ListeningBar.degradedHintRes`）。渲染本身
 * 需要设备或 androidTest 的 compose ui-test 依赖，本仓库的 JVM 单测 classpath 没有它
 * （见 `src/app/build.gradle.kts:114-116`），所以渲染层靠真机/仪器测试，映射层由本用例守。
 */
class DegradedHintTest {

    @Test
    fun fallbackToSystemShowsItsOwnNotice() {
        assertEquals(
            R.string.player_degraded_fallback,
            degradedHintRes(DegradedReason.FALLBACK_TO_SYSTEM)
        )
    }

    @Test
    fun pauseAfterErrorsShowsItsOwnNotice() {
        assertEquals(
            R.string.player_degraded_paused,
            degradedHintRes(DegradedReason.PAUSED_AFTER_ERRORS)
        )
    }

    /** 正常状态不得显示任何降级提示（否则监听条会常挂一条假警报）。 */
    @Test
    fun healthyStateShowsNothing() {
        assertNull(degradedHintRes(null))
    }
}
