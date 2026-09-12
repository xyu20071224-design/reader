package com.linguareader.shared.tts

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
