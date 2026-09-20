package com.linguareader.shared.tts

import com.linguareader.shared.packs.PackHasher
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 第四轮审查 6-12 / §9 P2 第 14 项：Python 音频包生成器 ↔ Kotlin 缓存键的**对拍**。
 *
 * 背景：缓存键与包内路径是同一串契约的两个实现（Kotlin [TtsCacheKey] 与
 * `scripts/build_audio_pack.py`），而 `PIPELINE_VERSION` 在 Python 侧是**硬编码副本**，
 * 没有任何自动比对。任一侧 bump 忘改另一侧的表现是"响亮失败"（找不到目录/拒装），
 * 不会静默放错音，但仍然是一次版本漂移。
 *
 * 这里做两件事：
 * 1. 版本常量对拍（纯 JVM 读源码，CI 必跑）；
 * 2. 键派生对拍——**真的调用 Python 侧函数**跑固定向量，与 Kotlin 结果逐字符比较。
 *    python3 不存在时跳过（`assumeTrue`），不让测试在不具备条件的环境里假红。
 */
class AudioPackGeneratorContractTest {

    /**
     * 仓库根：从测试工作目录向上找 `scripts/build_audio_pack.py`。
     *
     * Gradle 子项目的测试 cwd 是模块目录（`:shared` 下是 `src/shared`，`:app` 下是
     * `src/app`），不是仓库根，也不能假定只差一级。
     */
    private val generator: File = run {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            val candidate = File(dir, "scripts/build_audio_pack.py")
            if (candidate.isFile) found = candidate
            dir = dir.parentFile
        }
        found ?: File(System.getProperty("user.dir"), "scripts/build_audio_pack.py")
    }

    private fun python(): String? =
        listOf("python3", "python").firstOrNull { exe ->
            runCatching {
                ProcessBuilder(exe, "--version").redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
        }

    /**
     * 端到端对拍（第四轮审查 6-12 的另一半）：用**合成的 tts_cache 目录树**真的跑一遍
     * `build_audio_pack.py`，再用 Kotlin 侧 [PackHasher.treeSha256] 独立算同一棵树，
     * 断言与包内 manifest 的 `treeSha256` 相等。
     *
     * 为什么需要它：上面那条只证明两侧 `segment_dir` 字符串一致；而 `treeSha256` 的
     * **输入序列语义**（前缀裁剪、排序、换行拼接）此前只靠注释声明等价（报告 §10.1 第 4 条
     * 明确说那是"语义等价验证，不是执行 Kotlin 字节码"）。这一例把输入序列也钉死。
     */
    @Test
    fun `synthetic cache tree packs with the same tree hash kotlin computes`() {
        val python = python()
        assumeTrue("环境里没有 python3/python，跳过端到端打包对拍", python != null)
        assertTrue(generator.isFile, "找不到生成器脚本：$generator")

        val engineTag = "server:http://a.local:8000@deadbeef"
        val voice = "narrator.wav"
        val version = TtsPipelineContract.VERSION
        val segment = TtsCacheKey.segmentDir(engineTag, voice, version)

        val root = File.createTempFile("audio-pack-e2e-", "").let { it.delete(); it.mkdirs(); it }
        // 目录名任意；不能用 <book> —— '<' '>' 在 Windows 上是非法文件名字符
        val cache = File(root, "cache/book-1").apply { mkdirs() }
        val payloadFiles = linkedMapOf<String, ByteArray>()
        listOf(0, 1, 2).forEach { chapter ->
            val voiceDir = File(cache, "$chapter/$segment").apply { mkdirs() }
            listOf(0 to 0, 0 to 1, 1 to 0).forEachIndexed { i, (sentence, seg) ->
                val bytes = "chapter-$chapter-sentence-$sentence-segment-$seg".toByteArray()
                File(voiceDir, TtsCacheKey.fileName(sentence, seg)).writeBytes(bytes)
                payloadFiles["$chapter/${TtsCacheKey.fileName(sentence, seg)}"] = bytes
                // 每章文件数一致，便于下面按章断言
                if (i == 2) Unit
            }
        }

        val out = File(root, "out.lrpack")
        val process = ProcessBuilder(
            python, generator.absolutePath,
            "--cache", cache.absolutePath,
            "--book-id", "book-1",
            "--pack-id", "e2e-pack",
            "--engine-tag", engineTag,
            "--voice", voice,
            "--pipeline-version", version.toString(),
            "--min-app-version", "0",
            "--out", out.absolutePath
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        assertEquals(0, process.waitFor(), "生成器执行失败：$log")
        assertTrue(out.isFile, "未产出 .lrpack：$log")

        // 用 Kotlin 侧独立读包内 manifest，再对同一份载荷重算章树摘要
        val manifestJson = java.util.zip.ZipFile(out).use { zip ->
            val entry = zip.getEntry("manifest.json")!!
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        val manifest = org.json.JSONObject(manifestJson)
        val chapters = manifest.getJSONObject("audio").getJSONArray("chapters")
        assertEquals(3, chapters.length(), "应打出 3 章")

        // Kotlin 独立算「章内相对路径:sha256 排序拼行」的树摘要，与 manifest 逐章比对
        listOf(0, 1, 2).forEach { chapterIndex ->
            val digests = payloadFiles
                .filterKeys { it.startsWith("$chapterIndex/") }
                .mapValues { (_, bytes) -> sha256Hex(bytes) }
                .mapKeys { (path, _) -> "audio/$path" }
            val kotlinTree = PackHasher.treeSha256("audio/$chapterIndex", digests)

            val meta = (0 until chapters.length())
                .map { chapters.getJSONObject(it) }
                .first { it.getInt("index") == chapterIndex }
            assertEquals(
                kotlinTree,
                meta.getString("treeSha256"),
                "第 $chapterIndex 章的 treeSha256 必须与 Kotlin 侧一致（输入序列语义）"
            )
            assertEquals(3, meta.getInt("files"), "第 $chapterIndex 章应有 3 个文件")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `generator pipeline version matches the kotlin contract`() {
        assertTrue(generator.isFile, "找不到生成器脚本：$generator")
        val declared = Regex("""^PIPELINE_VERSION\s*=\s*(\d+)""", RegexOption.MULTILINE)
            .find(generator.readText())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        assertEquals(
            TtsPipelineContract.VERSION,
            declared,
            "scripts/build_audio_pack.py 的 PIPELINE_VERSION 必须与 TtsPipelineContract.VERSION 一致"
        )
    }

    @Test
    fun `generator key derivation matches kotlin byte for byte`() {
        val python = python()
        assumeTrue("环境里没有 python3/python，跳过生成器对拍", python != null)
        assertTrue(generator.isFile, "找不到生成器脚本：$generator")

        val engineTag = "server:http://a.local:8000"
        val voice = "narrator.wav"
        val version = TtsPipelineContract.VERSION
        val chapterIndex = 3

        val script = """
import importlib.util
spec = importlib.util.spec_from_file_location("gen", r"${generator.absolutePath}")
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)
print(gen.segment_dir(r"$engineTag", r"$voice", $version))
print(gen.voice_segment(r"$voice"))
""".trimIndent()

        val process = ProcessBuilder(python, "-c", script)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
        assertEquals(0, process.waitFor(), "生成器调用失败：$output")

        val lines = output.lines()
        val kotlinSegment = TtsCacheKey.segmentDir(engineTag, voice, version)
        val kotlinVoiceSegment = TtsCacheKey.voiceSegment(voice)

        assertEquals(
            kotlinSegment,
            lines.getOrNull(0),
            "段目录必须逐字符一致（原始输出=$output）"
        )
        assertEquals(
            kotlinVoiceSegment,
            lines.getOrNull(1),
            "音色段必须一致"
        )
    }
}
