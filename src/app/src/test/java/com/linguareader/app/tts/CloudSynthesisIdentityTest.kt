package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSynthesisIdentityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun mimo(settings: CloudTtsSettings = CloudTtsSettings()) = MiMoTtsBackend(settings, context)

    @Test fun actualPresetModelAndStyleChangeTheIdentityButCredentialsDoNot() = runBlocking {
        val settings = CloudTtsSettings(mimoModel = "model-a", mimoStyleInstruction = "calm", mimoApiKey = "credential-a")
        val original = mimo(settings).synthesisConfiguration("Mia")
        assertNotEquals(original, mimo(settings.copy(mimoModel = "model-b")).synthesisConfiguration("Mia"))
        assertNotEquals(original, mimo(settings.copy(mimoStyleInstruction = "bright")).synthesisConfiguration("Mia"))
        assertEquals(original, mimo(settings.copy(mimoApiKey = "credential-b")).synthesisConfiguration("Mia"))
        assertTrue(original.matches(Regex("[0-9a-f]{64}")))
        assertFalse(original.contains("credential"))
        assertEquals("model-a", JSONObject(mimo(settings).requestBody("Hello", "Mia")).getString("model"))
        assertEquals("model-b", JSONObject(mimo(settings.copy(mimoModel = "model-b")).requestBody("Hello", "Mia")).getString("model"))
    }

    @Test fun sameDesignedVoiceIdWithChangedPromptInvalidatesItsAudio() = runBlocking {
        val voice = "mimo-design:unchanged"
        register(voice, "DESIGN", prompt = "a calm low voice")
        val original = mimo().synthesisConfiguration(voice)
        register(voice, "DESIGN", prompt = "a bright high voice")
        assertNotEquals(original, mimo().synthesisConfiguration(voice))
        val body = JSONObject(mimo(CloudTtsSettings(mimoModel = "unused-preset")).requestBody("Hello", voice))
        assertEquals(MiMoTtsBackend.MODEL_VOICE_DESIGN, body.getString("model"))
        assertEquals("a bright high voice", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        // The actual design request uses its own prompt, not the global style or preset model.
        assertEquals(mimo().synthesisConfiguration(voice), mimo(CloudTtsSettings(
            mimoModel = "other-preset-model", mimoStyleInstruction = "unused global style"
        )).synthesisConfiguration(voice))
    }

    @Test fun sameClonedVoiceIdAndFilenameWithChangedBytesInvalidatesItsAudio() = runBlocking {
        val voice = "mimo-clone:unchanged"
        register(voice, "CLONE", sample = "same.wav")
        val sample = File(context.filesDir, "mimo-voices/same.wav").apply { parentFile!!.mkdirs(); writeText("AAAA") }
        val timestamp = sample.lastModified()
        val original = mimo().synthesisConfiguration(voice)
        sample.writeText("BBBB")
        sample.setLastModified(timestamp)
        assertNotEquals(original, mimo().synthesisConfiguration(voice))
    }

    @Test fun endpointPathCaseAndActualModelArePreservedWithoutBindingTheToken() = runBlocking {
        val settings = CloudTtsSettings(serverUrl = "https://example.invalid/VoiceA", serverModel = "model-a", serverToken = "credential-a")
        val original = OpenAiCompatTtsBackend(settings).synthesisConfiguration("voice")
        assertNotEquals(original, OpenAiCompatTtsBackend(settings.copy(serverUrl = "https://example.invalid/voicea")).synthesisConfiguration("voice"))
        assertNotEquals(original, OpenAiCompatTtsBackend(settings.copy(serverModel = "model-b")).synthesisConfiguration("voice"))
        assertEquals(original, OpenAiCompatTtsBackend(settings.copy(serverUrl = " https://example.invalid/VoiceA/ ", serverToken = "credential-b")).synthesisConfiguration("voice"))
        assertNotEquals(original, OpenAiCompatTtsBackend(settings).synthesisConfiguration("other-voice"))
    }

    private fun register(id: String, kind: String, prompt: String = "", sample: String = "") {
        val entry = JSONObject().put("id", id).put("kind", kind).put("name", "test")
            .put("language", "en").put("gender", "neutral").put("prompt", prompt).put("sampleFile", sample)
        context.getSharedPreferences("mimo_voice_store", Context.MODE_PRIVATE).edit()
            .putString("voices", JSONArray().put(entry).toString()).commit()
    }
}
