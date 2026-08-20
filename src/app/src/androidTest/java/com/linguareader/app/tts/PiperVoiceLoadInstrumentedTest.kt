package com.linguareader.app.tts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机验证「本地 Piper 音色导入」的加载路径（评审 H1）。
 *
 * H1 的问题只在运行期暴露：sherpa-onnx 传入 AssetManager 时会把模型路径当 asset
 * 解析，而导入的音色是 filesDir 下的**绝对路径**。这里把内置 ryan 模型复制到
 * filesDir，再按「导入音色」的方式（文件路径构造）加载并真的合成一段音频——
 * 不需要下载任何外部模型即可覆盖同一条代码路径。
 */
@RunWith(AndroidJUnit4::class)
class PiperVoiceLoadInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledVoiceStillLoadsFromAssets() {
        val tts = PiperAssets.createEnglishTts(context, PiperVoiceCatalog.builtin)
        assertNotNull("内置音色应能从 assets 加载", tts)
        val audio = tts!!.generate("The bundled voice still works.", sid = 0, speed = 1f)
        assertTrue("内置音色应合成出音频", audio.samples.isNotEmpty())
        assertTrue(audio.sampleRate > 0)
        tts.release()
    }

    @Test
    fun importedVoiceLoadsFromAbsoluteFilePaths() {
        val dir = File(context.filesDir, "piper-voices/itest-file-mode").apply { mkdirs() }
        val model = File(dir, "itest.onnx")
        val tokens = File(dir, "tokens.txt")
        copyAsset(PiperAssets.BUILTIN_MODEL_ASSET, model)
        copyAsset(PiperAssets.BUILTIN_TOKENS_ASSET, tokens)
        assertTrue(model.length() > 1_000_000)

        // 导入流程用的校验：真加载一次
        assertTrue(
            "文件路径模式必须能加载（H1）",
            PiperAssets.canLoad(context, model.absolutePath, tokens.absolutePath)
        )

        val voice = imported("itest-file-mode", model, tokens)
        val tts = PiperAssets.createEnglishTts(context, voice)
        assertNotNull("导入音色应能从绝对路径加载", tts)
        val audio = tts!!.generate("Imported voice speaking from files.", sid = 0, speed = 1f)
        assertTrue("导入音色应合成出音频", audio.samples.isNotEmpty())
        tts.release()
        dir.deleteRecursively()
    }

    @Test
    fun garbageModelIsRejectedByLoadValidation() {
        val dir = File(context.filesDir, "piper-voices/itest-garbage").apply { mkdirs() }
        // 0x08 开头能骗过魔数探测，但不是真的 VITS 模型 → 加载校验必须拦住
        val model = File(dir, "junk.onnx").apply { writeBytes(ByteArray(2 * 1024 * 1024) { 0x08 }) }
        val tokens = File(dir, "tokens.txt")
        copyAsset(PiperAssets.BUILTIN_TOKENS_ASSET, tokens)

        assertTrue(PiperVoiceImporter.looksLikeOnnx(model))
        assertFalse(
            "坏模型不应通过加载校验（M4）",
            PiperAssets.canLoad(context, model.absolutePath, tokens.absolutePath)
        )
        dir.deleteRecursively()
    }

    @Test
    fun engineFallsBackToBundledVoiceWhenSelectionIsBroken() {
        // 设置里存着一个已失效的音色 id：resolve 应回退内置，列表也不应包含它
        val dir = File(PiperVoiceStore.voicesDir(context), "itest-vanished").apply { mkdirs() }
        val missingModel = File(dir, "gone.onnx")
        val tokens = File(dir, "tokens.txt").apply { writeText("a 1") }
        PiperVoiceStore.registerImported(context, imported("itest-vanished", missingModel, tokens))

        assertFalse(
            PiperVoiceStore.installed(context).any { it.id == "itest-vanished" }
        )
        assertTrue(PiperVoiceStore.resolve(context, "itest-vanished").builtin)
        dir.deleteRecursively()
    }

    private fun imported(id: String, model: File, tokens: File) = PiperVoice(
        id = id,
        displayName = id,
        gender = "?",
        language = "英语",
        sizeMb = 1,
        sampleUrl = "",
        modelUrl = "",
        packageUrl = "",
        modelPath = model.absolutePath,
        tokensPath = tokens.absolutePath
    )

    private fun copyAsset(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
