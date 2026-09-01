package com.linguareader.app.tts

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

    /**
     * 缓存目录名必须是音色 id 的**单射**映射。
     *
     * 旧实现把非 [A-Za-z0-9._-] 的字符统统换成下划线当目录名，那是有损的：
     * 自建服务器把参考音频命名成「男声.wav」「女声.wav」时，两个音色消毒成同一个
     * 目录，而缓存命中判据只有「文件存在且非空」—— 于是放出另一个音色的音频，
     * 且不会报任何错。这条测试在旧实现下必红。
     */
    @Test
    fun cacheDirectoryNameIsInjectiveAcrossVoiceIds() {
        val a = TtsAudioCache.voiceSegment("男声.wav")
        val b = TtsAudioCache.voiceSegment("女声.wav")
        assertNotEquals(a, b, "两个不同音色不能落到同一个缓存目录（会串音）")
    }

    /**
     * 只有「当目录名会出事」的 id 才允许改写，其余原样保留 —— 这条守的是
     * **存量缓存不作废**：云 TTS 合成是要花钱的，换个键就等于让用户重新买一遍。
     */
    @Test
    fun ordinaryVoiceIdsKeepTheirLegacyDirectoryName() {
        // 系统音色 / 服务器音色 / MiMo（id 形如 mimo-clone:<ascii-slug>，含冒号）
        for (id in listOf("alloy", "zh-CN-XiaoxiaoNeural", "voice_03.wav", "mimo-clone:bingtang")) {
            assertEquals(id, TtsAudioCache.voiceSegment(id), "$id 的缓存目录名不该变")
        }
    }

    /** 当目录名会穿越或指错地方的 id 必须换成哈希，且哈希里不能再有分隔符。 */
    @Test
    fun pathUnsafeVoiceIdsFallBackToAHash() {
        for (id in listOf("", ".", "..", "a/b", "..\\evil")) {
            val segment = TtsAudioCache.voiceSegment(id)
            assertTrue(segment.startsWith("h-"), "$id 应改用哈希目录名，实际 $segment")
            assertFalse(segment.contains('/'), "哈希目录名不能含路径分隔符")
            assertFalse(segment.contains('\\'), "哈希目录名不能含路径分隔符")
        }
        // 仍然是单射：两个不同的非法 id 不能撞到一起
        assertNotEquals(
            TtsAudioCache.voiceSegment("a/b"),
            TtsAudioCache.voiceSegment("a/c")
        )
    }
}
