package com.linguareader.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechReadinessTest {

    @Test
    fun `request before ready is deferred and replayed once after init`() {
        val gate = SpeechReadiness()

        // 首次点击落在 TTS 初始化完成前：不能直接播，但要记下来。
        assertFalse(gate.request("hole"))

        // onInit 成功后补播一次，且只补一次。
        assertEquals("hole", gate.onReady())
        assertNull(gate.onReady())
    }

    @Test
    fun `request after ready plays immediately`() {
        val gate = SpeechReadiness()
        gate.onReady()

        assertTrue(gate.request("hole"))
    }

    @Test
    fun `only the last pre-ready request is kept`() {
        val gate = SpeechReadiness()

        assertFalse(gate.request("first"))
        assertFalse(gate.request("second"))

        // 连点两次只补最后一次：避免初始化完成后连播两遍。
        assertEquals("second", gate.onReady())
    }

    @Test
    fun `reset clears readiness and pending`() {
        val gate = SpeechReadiness()
        assertFalse(gate.request("hole"))

        gate.reset()

        // 复位后回到未就绪：新请求先记账不立即播，且旧 pending（"hole"）已被清掉。
        assertFalse(gate.request("new"))
        assertEquals("new", gate.onReady())
    }
}
