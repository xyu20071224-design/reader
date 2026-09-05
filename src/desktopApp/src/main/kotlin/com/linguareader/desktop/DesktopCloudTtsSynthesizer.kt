package com.linguareader.desktop

import com.linguareader.shared.tts.TtsSynthesizer
import com.linguareader.shared.tts.TtsSynthesizerListener
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** 桌面听书的云 TTS 设置（键名与 Android `CloudTtsSettings` 语义对齐；无 Keystore，明文进 prefs）。 */
data class DesktopCloudTtsSettings(
    val serverUrl: String = "",
    val serverToken: String = "",
    val serverModel: String = "tts-1",
    val voice: String = "default"
) {
    val configured: Boolean get() = serverUrl.isNotBlank()

    companion object {
        fun fromPrefs(store: com.linguareader.shared.app.PreferencesStore): DesktopCloudTtsSettings =
            DesktopCloudTtsSettings(
                serverUrl = store.getString("server_url").orEmpty(),
                serverToken = store.getString("server_token").orEmpty(),
                serverModel = store.getString("server_model")?.ifBlank { "tts-1" } ?: "tts-1",
                voice = store.getString("voice")?.ifBlank { "default" } ?: "default"
            )

        fun write(store: com.linguareader.shared.app.PreferencesStore, s: DesktopCloudTtsSettings) {
            store.putString("server_url", s.serverUrl)
            store.putString("server_token", s.serverToken)
            store.putString("server_model", s.serverModel)
            store.putString("voice", s.voice)
        }
    }
}

/**
 * 桌面听书的云 TTS 合成器（M3）：实现 :shared 的 [TtsSynthesizer]，
 * 走自建服务端的 OpenAI 兼容契约（POST /v1/audio/speech，返回原始 MP3，
 * 见 `.agents/memory/tts-server-stack.md`）。MP3 经 mp3spi SPI 解码为 PCM 后
 * 用 javax.sound.sampled 直放——JVM 无内置 AAC/MP3 解码，靠 SPI 补齐。
 *
 * 语速（rate）暂不改变播放速度：桌面 MVP 每句自然语速播完；
 * 变速重采样留 TODO。整书缓存不实现（不实现 BookTtsPreparer，
 * 引擎自动隐藏全书缓存）。
 */
class DesktopCloudTtsSynthesizer(
    private val settings: DesktopCloudTtsSettings,
    private val listener: TtsSynthesizerListener
) : TtsSynthesizer {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "desktop-cloud-tts").apply { isDaemon = true }
    }
    private val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private val playing = AtomicBoolean(false)
    @Volatile private var line: SourceDataLine? = null

    init {
        // 引擎在发起首个 speak 前等 onReady；桌面无初始化耗时，立即就绪。
        executor.execute { listener.onReady() }
    }

    override val isReady: Boolean get() = true

    override fun speak(text: String, rate: Float, utteranceId: String, voice: String?) {
        stopped.set(false)
        executor.execute {
            try {
                val mp3 = synthesize(text, voice)
                if (stopped.get()) return@execute
                play(mp3, utteranceId)
                if (!stopped.get()) listener.onDone(utteranceId)
            } catch (failure: Throwable) {
                if (!stopped.get()) listener.onError(utteranceId)
            }
        }
    }

    override fun stop() {
        stopped.set(true)
        line?.let { runCatching { it.stop(); it.close() } }
        line = null
    }

    override fun shutdown() {
        stop()
        executor.shutdownNow()
    }

    private fun synthesize(text: String, voice: String?): ByteArray {
        require(settings.configured) { "未配置 TTS 服务器地址" }
        val connection = URL(settings.serverUrl.trim().trimEnd('/') + "/v1/audio/speech")
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 8_000
        connection.readTimeout = 180_000
        if (settings.serverToken.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${settings.serverToken}")
        }
        connection.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("model", settings.serverModel.ifBlank { "tts-1" })
            .put("input", text)
            .put("voice", voice ?: settings.voice.ifBlank { "default" })
            .put("response_format", "mp3")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        if (code != 200) {
            val detail = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
            throw IllegalStateException("TTS 服务器 HTTP $code${detail?.let { "：${it.take(200)}" }.orEmpty()}")
        }
        return connection.inputStream.use { it.readBytes() }
    }

    private fun play(mp3: ByteArray, utteranceId: String) {
        listener.onStart(utteranceId)
        val mp3Stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(mp3))
        val base = mp3Stream.format
        // 解码到 16bit PCM 小端（mp3spi 负责解码），采样率/声道沿用源。
        val target = AudioFormat(base.sampleRate, 16, base.channels, true, false)
        val pcm = AudioSystem.getAudioInputStream(target, mp3Stream)
        val dataLine = AudioSystem.getSourceDataLine(target)
        dataLine.open(target, 1 shl 16)
        dataLine.start()
        line = dataLine
        playing.set(true)
        val buffer = ByteArray(16_384)
        while (playing.get()) {
            val read = pcm.read(buffer)
            if (read <= 0) break
            dataLine.write(buffer, 0, read)
        }
        runCatching { dataLine.drain(); dataLine.close() }
        playing.set(false)
        line = null
    }
}
