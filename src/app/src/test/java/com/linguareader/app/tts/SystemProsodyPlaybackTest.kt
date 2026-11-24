package com.linguareader.app.tts

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import com.linguareader.shared.tts.PlannedSpeechRequest
import com.linguareader.shared.tts.ProsodyConfiguration
import com.linguareader.shared.tts.ProsodyPlanner
import com.linguareader.shared.tts.TtsUtterance
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemProsodyPlaybackTest {
    private class Listener : TtsSynthesizerListener {
        val starts = mutableListOf<String>()
        val dones = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun onReady() = Unit
        override fun onInitFailed(status: Int) = Unit
        override fun onStart(utteranceId: String) { starts += utteranceId }
        override fun onDone(utteranceId: String) { dones += utteranceId }
        override fun onError(utteranceId: String) { errors += utteranceId }
    }
    private fun request(): PlannedSpeechRequest {
        val source = TtsUtterance(0, 0, 1, "narrator", "This is the first longer phrase, followed by the second longer phrase.", 0, 0)
        val config = ProsodyConfiguration(ProsodyPlanner.Policy(mode = ProsodyPlanner.Mode.PHRASES, minimumPhraseChars = 5))
        return PlannedSpeechRequest(source, ProsodyPlanner.plan(source, 1f, config.policy), config)
    }
    private fun engine(listener: Listener): SystemTtsSynthesizer {
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        ShadowTextToSpeech.addVoice(Voice("prosody-en", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
        return SystemTtsSynthesizer(ApplicationProvider.getApplicationContext(), listener, enVoice = "prosody-en").also {
            shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).onInitListener.onInit(TextToSpeech.SUCCESS)
        }
    }

    @Test fun systemPhrasesSubmitExactTextAndOnlyOneParentStartAndDone() {
        val listener = Listener()
        val synth = engine(listener)
        try {
            val request = request()
            synth.speakPlanned(request, "parent")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val system = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
            assertEquals(request.units.map { it.utterance.text }, system.spokenTextList)
            assertEquals(listOf("parent"), listener.starts)
            assertEquals(listOf("parent"), listener.dones)
            assertTrue(listener.errors.isEmpty())
        } finally { synth.shutdown() }
    }

    @Test fun stopDuringPhrasePauseDoesNotSubmitTheRemainingText() {
        val listener = Listener()
        val synth = engine(listener)
        try {
            val request = request()
            synth.speakPlanned(request, "parent")
            shadowOf(Looper.getMainLooper()).idle()
            synth.stop()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertEquals(listOf(request.units.first().utterance.text), shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance()).spokenTextList)
            assertTrue(listener.dones.isEmpty())
            assertTrue(listener.errors.isEmpty())
        } finally { synth.shutdown() }
    }
}
