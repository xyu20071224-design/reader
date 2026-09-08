package com.linguareader.app.tts

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.linguareader.app.packs.PackRepository
import com.linguareader.shared.packs.PackHasher
import com.linguareader.shared.packs.PackManifest
import com.linguareader.shared.tts.TtsCacheKey
import com.linguareader.shared.tts.TtsPipelineContract
import kotlinx.coroutines.runBlocking
import com.linguareader.shared.tts.TtsChapter
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 复现「自建服务器（OpenAI 兼容）/ 云 TTS 无声音」的回归测试。
 *
 * 根因：TtsPlaybackEngine.utteranceIdFor 生成的 utteranceId 段数
 *      （当年 4 段 "${bookId}:${chapter}:${sentence}:${attempt}"）与
 *      CloudTtsSynthesizer.parseUtteranceId 接受的段数不一致时，
 *      speak() 对每一句都立刻走 onError，全程不合成、不播放。
 *      2026-09-06 句内片段化后是 5 段（…:sentence:segment:attempt）。
 *      本测试锁住「解析必须跟上引擎格式」这条契约。
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
            // 真实后端会建目录（CloudTtsBackend 实现里都有）；测试替身必须同口径，
            // 否则「章预生成」这条路径一碰就 FileNotFoundException。
            outputFile.parentFile?.mkdirs()
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
    fun engineUtteranceIdMustNotBeRejected() {
        val backend = RecordingBackend()
        val listener = RecordingListener()
        val synth = CloudTtsSynthesizer(
            ApplicationProvider.getApplicationContext(),
            backend,
            listener
        )

        // TtsPlaybackEngine.speakNow 实际传入的 utteranceId 格式（5 段，2026-09-06
        // 加句内片段维度）：
        //   utteranceIdFor(chapter, sentence, segment, attempt)
        //     = "${book.id}:$chapter:$sentence:$segment:$attempt"
        synth.speak("你好，世界。", 1f, "book-1:0:0:0:1")
        shadowOf(Looper.getMainLooper()).idle()

        // 正确行为：5 段 utteranceId 应被解析，不触发 onError。
        assertTrue(
            listener.errors.isEmpty(),
            "云 TTS 必须解析引擎的 5 段 utteranceId，但 speak() 直接触发了 onError（无声音）"
        )
        synth.shutdown()
    }


    /**
     * M3 核心契约：**包命中时一句都不许再合成**（合成 = 花钱 + 慢）。
     *
     * 直接驱动 `generateOne`（章预生成的单位）而不是 `speak`：后者在无章预生成时
     * 会先等 25s 缓存窗口，测试会慢到不可接受，而这条契约只关心「有没有发起合成」。
     */
    @Test
    fun audioPackIsResolvedWithoutSynthesizing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engineTag = VoiceLibraryLoader.engineKey(CloudTtsSettings.load(context))
        val staging = File.createTempFile("pack-", "").let { it.delete(); it.mkdirs(); it }
        val relative = TtsCacheKey.relativePath(0, 0, 0, engineTag, "default")
        val payload = File(staging, "audio/$relative").apply {
            parentFile?.mkdirs()
            writeText("fake-mp3")
        }
        val sha = com.linguareader.shared.importer.ImportSupport.sha256(payload)
        val manifest = JSONObject()
            .put("packId", "synth-pack").put("type", "audio").put("version", "1.0.0")
            .put("nameZh", "音频包").put("schemaVersion", 1).put("minAppVersion", 0)
            .put(
                "files",
                JSONArray().put(
                    JSONObject().put("path", "audio/$relative").put("sha256", sha)
                        .put("bytes", payload.length())
                )
            )
            .put(
                "audio",
                JSONObject().put("bookId", "book-1").put("engineTag", engineTag)
                    .put("voice", "default").put("pipelineVersion", TtsPipelineContract.VERSION)
                    .put(
                        "chapters",
                        JSONArray().put(
                            JSONObject().put("index", 0).put("files", 1)
                                .put("treeSha256", PackHasher.treeSha256("audio/0", mapOf("audio/$relative" to sha)))
                        )
                    )
            )
        val zip = File(staging, "pack.lrpack")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry(PackManifest.FILE_NAME))
            out.write(manifest.toString().toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("audio/$relative"))
            out.write(payload.readBytes())
            out.closeEntry()
        }
        PackRepository(context, appVersionCode = 15).install(Uri.fromFile(zip))

        val backend = RecordingBackend()
        val synth = CloudTtsSynthesizer(context, backend, RecordingListener())
        val chapter = TtsChapter(0, "Chapter", listOf("Hello. Goodbye."))
        val book = com.linguareader.shared.data.Book(
            id = "book-1", title = "T", author = "A", extractedDir = "/tmp",
            coverRelativePath = null, chapters = emptyList(), addedAt = 0L
        )
        val packed = chapter.utterances.first { it.sentenceIndex == 0 && it.segmentIndex == 0 }
        val unpacked = chapter.utterances.first { it.sentenceIndex == 1 }

        // 解析链：包命中 → 返回包内文件（安装后已落位到 filesDir/packs）
        val resolved = synth.packFile("book-1", 0, 0, 0, "default")
        assertEquals(true, resolved != null)
        assertEquals("fake-mp3", resolved!!.readText())
        assertEquals(true, resolved.path.endsWith("audio/$relative"))
        // 不匹配的三种情形都不命中 → 自动降级现场合成
        assertEquals(null, synth.packFile("book-2", 0, 0, 0, "default"))
        assertEquals(null, synth.packFile("book-1", 0, 0, 0, "other"))
        assertEquals(null, synth.packFile("book-1", 9, 0, 0, "default"))

        // 包里的句子：不合成
        assertEquals(true, synth.generateOne(book, chapter, packed))
        assertEquals(0, backend.synthesizeCalls, "包命中不该合成（会重复计费）")

        // 包里没有的句子：照常合成
        assertEquals(true, synth.generateOne(book, chapter, unpacked))
        assertEquals(1, backend.synthesizeCalls)

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

    /**
     * D2.2：缓存键必须带**引擎身份**。
     *
     * 同一个音色 id 在两台不同的自建服务器上是不同的声音；键里不带引擎，换服务器后
     * 会直接播出上一台合成的音频，而命中判据只有「文件存在且非空」，照样不报错。
     */
    @Test
    fun cacheKeyDistinguishesEngines() {
        val a = TtsAudioCache.segmentFor("server:http://a.local:8000", "narrator.wav")
        val b = TtsAudioCache.segmentFor("server:http://b.local:8000", "narrator.wav")
        assertNotEquals(a, b, "同名音色在不同服务器上必须落到不同缓存目录")
        // 同一引擎同一音色必须稳定命中（否则每次启动都重新合成，云 TTS 要花钱）
        assertEquals(a, TtsAudioCache.segmentFor("server:http://a.local:8000", "narrator.wav"))
        // 音色那半截仍然肉眼可读，方便排查
        assertTrue(a.endsWith("~narrator.wav"), "音色段应保持无损，实际 $a")
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
