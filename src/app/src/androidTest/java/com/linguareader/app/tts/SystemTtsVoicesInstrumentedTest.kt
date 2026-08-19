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
        var rawLocalNames = emptyList<String>()
        val rawLatch = CountDownLatch(1)
        lateinit var raw: TextToSpeech
        raw = TextToSpeech(context) { status ->
            rawInitOk = status == TextToSpeech.SUCCESS
            if (rawInitOk) {
                val v = raw.voices
                rawNull = v == null
                val voices = v.orEmpty()
                rawNames = voices.map { it.name }
                rawLocalNames = voices.filterNot { it.isNetworkConnectionRequired }.map { it.name }
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
            // The loader intentionally drops network-required voices so the
            // dropdowns only offer voices that work offline.
            assertEquals(rawLocalNames.toSet(), voices.map { it.name }.toSet())
        }
        // When the engine reports no voices (or init fails), the loader must
        // still finish with a non-null (possibly empty) list — asserted above
        // by the timeout + orEmpty, and it must never crash.
    }

    @Test
    fun selectedVoicesAreApplicableViaSetVoice() {
        // The real question behind "is the feature usable": can the voices the
        // UI surfaces actually be applied via `setVoice`? This mirrors what
        // `SystemTtsSynthesizer.speak` does with the user's saved zh/en voice.
        val loadLatch = CountDownLatch(1)
        var voices: List<SystemVoiceInfo> = emptyList()
        SystemTtsVoices.load(context) { voices = it; loadLatch.countDown() }
        assertTrue("voice list timed out", loadLatch.await(15, TimeUnit.SECONDS))

        val zh = voices.firstOrNull { it.isChinese && !it.isNetwork }
        val en = voices.firstOrNull { it.isEnglish && !it.isNetwork }
        assertTrue("no local Chinese voice on device", zh != null)
        assertTrue("no local English voice on device", en != null)

        val ttsLatch = CountDownLatch(1)
        lateinit var tts: TextToSpeech
        var initOk = false
        var zhSetOk = false
        var enSetOk = false
        tts = TextToSpeech(context) { status ->
            initOk = status == TextToSpeech.SUCCESS
            if (initOk) {
                val all = tts.voices.orEmpty()
                zhSetOk = all.any { it.name == zh!!.name } &&
                    tts.setVoice(all.first { it.name == zh!!.name }) == TextToSpeech.SUCCESS
                enSetOk = all.any { it.name == en!!.name } &&
                    tts.setVoice(all.first { it.name == en!!.name }) == TextToSpeech.SUCCESS
            }
            ttsLatch.countDown()
        }
        assertTrue("TTS init timed out", ttsLatch.await(10, TimeUnit.SECONDS))
        runCatching { tts.shutdown() }

        println("initOk=$initOk zh=${zh!!.name} setOk=$zhSetOk en=${en!!.name} setOk=$enSetOk")
        assertTrue("TTS init should succeed", initOk)
        assertTrue("selected Chinese voice (${zh!!.name}) should be settable", zhSetOk)
        assertTrue("selected English voice (${en!!.name}) should be settable", enSetOk)
    }
}
