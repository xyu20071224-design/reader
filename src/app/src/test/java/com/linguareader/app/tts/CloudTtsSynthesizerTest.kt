package com.linguareader.app.tts

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertTrue

/**
 * 复现「自建服务器（OpenAI 兼容）/ 云 TTS 无声音」的回归测试。
 *
 * 根因：TtsPlaybackEngine.utteranceIdFor 生成 4 段 utteranceId
 *      "${bookId}:${chapter}:${sentence}:${attempt}"（见 TtsPlaybackEngine.kt:553），
 *      而 CloudTtsSynthesizer.parseUtteranceId 只接受恰好 3 段
 *      （parts.size != 3 即返回 null，见 CloudTtsSynthesizer.kt:243）。
 *      于是 speak() 对每一句都立刻走 onError，全程不合成、不播放。
 *
 * 该缺陷影响所有复用 CloudTtsSynthesizer 的云后端（OpenAI 兼容 / MiMo）。
 * 本测试在缺陷存在时会失败（onError 被立即触发），修复后才会通过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudTtsSynthesizerTest {

    private class RecordingBackend : CloudTtsBackend {
        var synthesizeCalls = 0
        override fun isConfigured(): Boolean = true
        override suspend fun synthesize(text: String, voice: String, outputFile: File): Result<Unit> {
            synthesizeCalls++
            outputFile.writeBytes(byteArrayOf(1, 2, 3))
            return Result.success(Unit)
        }
        override fun voiceFor(text: String): String = "default"
    }

    private class RecordingListener : TtsSynthesizerListener {
        val errors = mutableListOf<String>()
        override fun onReady() {}
        override fun onInitFailed(status: Int) {}
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) {}
        override fun onError(utteranceId: String) { errors += utteranceId }
    }

    @Test
    fun engineFourPartUtteranceIdMustNotBeRejected() {
        val backend = RecordingBackend()
        val listener = RecordingListener()
        val synth = CloudTtsSynthesizer(
            ApplicationProvider.getApplicationContext(),
            backend,
            listener
        )

        // TtsPlaybackEngine.speakNow 实际传入的 utteranceId 格式（4 段）：
        //   utteranceIdFor(chapter, sentence, attempt) = "${book.id}:$chapter:$sentence:$attempt"
        synth.speak("你好，世界。", 1f, "book-1:0:0:1")
        shadowOf(Looper.getMainLooper()).idle()

        // 正确行为：4 段 utteranceId 应被解析，不触发 onError。
        // 当前代码：parseUtteranceId 要求 3 段 → 返回 null → 立即 onError → 本断言失败。
        assertTrue(
            listener.errors.isEmpty(),
            "云 TTS 必须解析引擎的 4 段 utteranceId，但 speak() 直接触发了 onError（无声音）"
        )
        synth.shutdown()
    }
}
