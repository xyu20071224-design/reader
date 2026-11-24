package com.linguareader.app.uirender

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposeRenderHarnessClockTest {
    @Test
    fun `infinite animation advances a bounded number of frames and every composition is disposed`() {
        var entered = 0
        var disposed = 0
        var frames = 0
        val wasPaused = ShadowChoreographer.isPaused()
        val delay = ShadowChoreographer.getFrameDelay()
        repeat(2) { snapshot ->
            val framesBefore = frames
            val bitmap = ComposeRenderHarness.renderDp("harness-infinite-$snapshot", 100, 100) {
                DisposableEffect(Unit) {
                    entered++
                    onDispose { disposed++ }
                }
                LaunchedEffect(Unit) {
                    while (isActive) withFrameNanos { frames++ }
                }
                CircularProgressIndicator()
            }
            assertEquals(snapshot + 1, entered)
            assertEquals("the old animation must not survive the snapshot", entered, disposed)
            assertTrue("frames must run without draining an infinite animation: ${frames - framesBefore}", frames - framesBefore in 1..4)
            assertTrue("the actual progress indicator must be drawn", ComposeRenderHarness.dominantColors(bitmap, 2).size > 1)
            assertEquals(wasPaused, ShadowChoreographer.isPaused())
            assertEquals(delay, ShadowChoreographer.getFrameDelay())
        }
    }

    @Test
    fun `a caller owned paused clock and frame interval are restored`() {
        val wasPaused = ShadowChoreographer.isPaused()
        val delay = ShadowChoreographer.getFrameDelay()
        try {
            ShadowChoreographer.setPaused(true)
            ShadowChoreographer.setFrameDelay(Duration.ofMillis(25))
            ComposeRenderHarness.renderDp("harness-paused", 100, 100) { CircularProgressIndicator() }
            assertTrue(ShadowChoreographer.isPaused())
            assertEquals(Duration.ofMillis(25), ShadowChoreographer.getFrameDelay())
        } finally {
            ShadowChoreographer.setFrameDelay(delay)
            ShadowChoreographer.setPaused(wasPaused)
        }
    }
}
