package com.linguareader.app.tts

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * On-device probe for the system voice list. Verifies that [SystemTtsVoices.load]
 * completes without crashing even when `TextToSpeech.getVoices()` returns null,
 * and surfaces the voices the engine actually exposes.
 */
@RunWith(AndroidJUnit4::class)
class SystemTtsVoicesInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loadReturnsEngineVoicesWithoutCrashing() {
        // 1) Raw probe: what does `getVoices()` return on this device?
        var rawInitOk = false
        var rawNull = false
        var rawNames = emptyList<String>()
        val rawLatch = CountDownLatch(1)
        lateinit var raw: TextToSpeech
        raw = TextToSpeech(context) { status ->
            rawInitOk = status == TextToSpeech.SUCCESS
            if (rawInitOk) {
                val v = raw.voices
                rawNull = v == null
                rawNames = v.orEmpty().map { it.name }
            }
            rawLatch.countDown()
        }
        assertTrue("raw TTS init timed out", rawLatch.await(10, TimeUnit.SECONDS))
        runCatching { raw.shutdown() }

        // 2) The fixed loader.
        val latch = CountDownLatch(1)
        var result: List<SystemVoiceInfo>? = null
        SystemTtsVoices.load(context) { voices ->
            result = voices
            latch.countDown()
        }
        assertTrue("SystemTtsVoices.load timed out", latch.await(15, TimeUnit.SECONDS))

        val voices = result.orEmpty()
        println("RAW: initOk=$rawInitOk null=$rawNull count=${rawNames.size}")
        println("LOADER: count=${voices.size}")
        voices.forEach { println("  voice: ${it.name} / ${it.locale} / network=${it.isNetwork}") }

        if (rawInitOk && rawNames.isNotEmpty()) {
            assertTrue("loader should surface engine voices", voices.isNotEmpty())
            assertEquals(rawNames.toSet(), voices.map { it.name }.toSet())
        }
        // When the engine reports no voices (or init fails), the loader must
        // still finish with a non-null (possibly empty) list — asserted above
        // by the timeout + orEmpty, and it must never crash.
    }
}
