package com.linguareader.app.tts

import android.content.Context
import java.io.File

/** Azure Speech (21Vianet / China regions) backend. */
class AzureTtsBackend(
    private val settings: CloudTtsSettings,
    context: Context
) : CloudTtsBackend {
    private val client = AzureSpeechClient(settings.region, settings.apiKey)
    private val voices: List<AzureVoice> = CloudVoiceStore.load(context)

    override val label: String = "Azure 云 TTS"

    override fun isConfigured(): Boolean =
        settings.region.isNotBlank() && settings.apiKey.isNotBlank()

    override suspend fun synthesize(
        text: String,
        voice: String,
        outputFile: File
    ): Result<Unit> = client.synthesize(text, voice, outputFile)

    override fun voiceFor(text: String): String = when {
        settings.useMultilingual && settings.multilingualVoice.isNotBlank() ->
            settings.multilingualVoice

        text.any(::isHan) ->
            settings.zhVoice.ifBlank { CloudVoicePicker.defaultChinese(voices) }

        else ->
            settings.enVoice.ifBlank { CloudVoicePicker.defaultEnglish(voices) }
    }

    private fun isHan(char: Char): Boolean {
        val code = char.code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x20000..0x2FA1F
    }
}
