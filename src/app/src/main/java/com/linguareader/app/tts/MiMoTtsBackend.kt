package com.linguareader.app.tts

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 小米 MiMo-V2.5-TTS 云引擎后端（文档：mimo.mi.com/docs …/speech-synthesis-v2.5）。
 *
 * 接口形态与其它云后端不同：不是 `/v1/audio/speech`，而是 OpenAI 兼容的
 * `POST {MIMO_BASE_URL}/chat/completions`，鉴权头 `api-key`，目标文本放在
 * `assistant` 消息（放在 user 消息会失败），样式指令放在 `user` 消息，音频
 * 以 base64（非流式 = wav）随 `choices[0].message.audio.data` 返回。
 *
 * 音色 id 三态分派（多角色服务的关键）：
 * - 预置音色（裸 id，如 `Mia`/`mimo_default`/`苏打`）→ `mimo-v2.5-tts`
 * - 设计音色（`mimo-design:<key>`）→ `mimo-v2.5-tts-voicedesign`，user 消息
 *   必须是音色设计描述（必填）
 * - 克隆音色（`mimo-clone:<key>`）→ `mimo-v2.5-tts-voiceclone`，`audio.voice`
 *   传 `data:audio/mpeg;base64,…` 样本（≤10 MB，mp3/wav）
 *
 * 音色 id 本身对多角色管线是透明的：分配器/音色库/试听只认「不透明 id」，
 * 这里按前缀决定模型与请求内容，所以一个书里可以有预置旁白 + 若干设计/
 * 克隆角色音色混用。
 */
class MiMoTtsBackend(
    private val settings: CloudTtsSettings,
    context: Context
) : CloudTtsBackend {

    private val appContext = context.applicationContext

    override fun isConfigured(): Boolean = settings.mimoApiKey.isNotBlank()

    /** 模型固定：预置音色用 settings 里的模型（默认 mimo-v2.5-tts），
     *  设计/克隆各自独立模型（文档规定）。 */
    internal fun modelFor(voiceId: String): String = when {
        voiceId.startsWith(MiMoVoiceStore.DESIGN_PREFIX) -> MODEL_VOICE_DESIGN
        voiceId.startsWith(MiMoVoiceStore.CLONE_PREFIX) -> MODEL_VOICE_CLONE
        else -> settings.mimoModel.ifBlank { CloudTtsSettings.DEFAULT_MIMO_MODEL }
    }

    /** 按句语言路由：中文 → 中文预置音色，其余 → 英文预置音色。 */
    override fun voiceFor(text: String): String =
        if (TtsLanguage.of(text) == TtsLanguage.CHINESE) {
            settings.mimoZhVoice.ifBlank { CloudTtsSettings.DEFAULT_MIMO_ZH_VOICE }
        } else {
            settings.mimoEnVoice.ifBlank { CloudTtsSettings.DEFAULT_MIMO_EN_VOICE }
        }

    override suspend fun synthesize(
        text: String,
        voice: String,
        outputFile: File
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sampleUri = if (voice.startsWith(MiMoVoiceStore.CLONE_PREFIX)) {
                loadCloneSampleUri(voice)
            } else {
                null
            }
            // 设计音色的 user 消息 = 该音色自己的描述（mimo-design:<key> 在
            // MiMoVoiceStore 里登记的 prompt），而非全局风格指令；其余音色才
            // 用全局风格指令（文档：voicedesign 的 user 消息必填 = 音色描述）。
            val styleInstruction = if (voice.startsWith(MiMoVoiceStore.DESIGN_PREFIX)) {
                MiMoVoiceStore.designPrompt(appContext, voice)
            } else {
                settings.mimoStyleInstruction.trim()
            }
            val endpoint = CloudTtsSettings.MIMO_BASE_URL + "/chat/completions"
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 120_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                // MiMo 鉴权：api-key 头（非 Authorization: Bearer）。
                connection.setRequestProperty("api-key", settings.mimoApiKey)
                val body = buildRequestBody(
                    text = text,
                    voice = voice,
                    styleInstruction = styleInstruction,
                    sampleDataUri = sampleUri
                )
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("MiMo 合成失败（HTTP ${connection.responseCode}）：${error.take(200)}")
                }
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val audioBytes = decodeAudioData(json)
                check(audioBytes.isNotEmpty()) { "MiMo 合成结果为空" }
                outputFile.parentFile?.mkdirs()
                outputFile.writeBytes(audioBytes)
            } finally {
                connection.disconnect()
            }
        }
    }

    /** 克隆样本 → `data:<mime>;base64,<data>`（文档要求的前缀格式）。 */
    private fun loadCloneSampleUri(voice: String): String {
        val sample = MiMoVoiceStore.sampleFile(appContext, voice)
            ?: error("克隆音色样本缺失：$voice")
        val mime = when (sample.extension.lowercase()) {
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        val base64 = Base64.encodeToString(sample.readBytes(), Base64.NO_WRAP)
        return "data:$mime;base64,$base64"
    }

    companion object {
        const val MODEL_VOICE_DESIGN = "mimo-v2.5-tts-voicedesign"
        const val MODEL_VOICE_CLONE = "mimo-v2.5-tts-voiceclone"

        /**
         * 构造 chat/completions 请求体（纯函数，可单测）：
         * messages = [user(样式/设计描述，可空时省略), assistant(目标文本)]，
         * audio = {format:"wav", voice?}（克隆音色由 [sampleDataUri] 提供）。
         */
        internal fun buildRequestBody(
            text: String,
            voice: String,
            styleInstruction: String = "",
            sampleDataUri: String? = null
        ): String {
            val messages = JSONArray()
            if (voice.startsWith(MiMoVoiceStore.DESIGN_PREFIX)) {
                val prompt = styleInstruction // 设计音色的描述在 store 里按 key 存，
                // 由调用方（synthesize 上层）负责解析；这里仅保证非空。
                check(prompt.isNotBlank()) { "设计音色缺少描述（user 消息必填）" }
                messages.put(JSONObject().put("role", "user").put("content", prompt))
            } else if (styleInstruction.isNotBlank()) {
                messages.put(JSONObject().put("role", "user").put("content", styleInstruction))
            }
            messages.put(JSONObject().put("role", "assistant").put("content", text))
            val audio = JSONObject().put("format", "wav")
            when {
                voice.startsWith(MiMoVoiceStore.CLONE_PREFIX) -> {
                    check(!sampleDataUri.isNullOrBlank()) { "克隆音色缺少样本" }
                    audio.put("voice", sampleDataUri)
                }
                voice.startsWith(MiMoVoiceStore.DESIGN_PREFIX) -> {
                    // voicedesign 无需 audio.voice，user 消息即音色描述。
                }
                else -> audio.put("voice", voice.ifBlank { "mimo_default" })
            }
            return JSONObject()
                .put("model", modelForVoice(voice))
                .put("messages", messages)
                .put("audio", audio)
                .toString()
        }

        /** 从响应 JSON 取 base64 音频并解码（纯函数，可单测）。 */
        internal fun decodeAudioData(responseJson: String): ByteArray {
            val root = JSONObject(responseJson)
            val choices = root.optJSONArray("choices")
                ?: throw IllegalArgumentException("MiMo 响应缺少 choices")
            if (choices.length() == 0) throw IllegalArgumentException("MiMo 响应 choices 为空")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalArgumentException("MiMo 响应缺少 message")
            val audio = message.optJSONObject("audio")
                ?: throw IllegalArgumentException("MiMo 响应缺少 message.audio")
            val data = audio.optString("data")
            if (data.isBlank()) throw IllegalArgumentException("MiMo 响应音频数据为空")
            val bytes = Base64.decode(data, Base64.DEFAULT)
            check(bytes.isNotEmpty()) { "MiMo 音频 base64 解码为空" }
            return bytes
        }

        internal fun modelForVoice(voice: String): String = when {
            voice.startsWith(MiMoVoiceStore.DESIGN_PREFIX) -> MODEL_VOICE_DESIGN
            voice.startsWith(MiMoVoiceStore.CLONE_PREFIX) -> MODEL_VOICE_CLONE
            else -> CloudTtsSettings.DEFAULT_MIMO_MODEL
        }
    }
}