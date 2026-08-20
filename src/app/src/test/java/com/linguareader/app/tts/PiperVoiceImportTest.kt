package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 本地 Piper 音色导入与登记的回归测试：文件名净化（防路径穿越）、ONNX 结构探测、
 * 失效记录过滤、未知 id 回退内置音色。
 */
@RunWith(RobolectricTestRunner::class)
class PiperVoiceImportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `id sanitising strips paths and unsafe characters`() {
        assertEquals("en_US-lessac-medium", PiperVoiceImporter.sanitizeId("en_US-lessac-medium.onnx"))
        // 路径穿越与分隔符：只保留文件名部分，其余折叠成下划线
        assertEquals("passwd", PiperVoiceImporter.sanitizeId("../../etc/passwd.onnx"))
        assertEquals("model", PiperVoiceImporter.sanitizeId("C:\\models\\model.onnx"))
        assertFalse(PiperVoiceImporter.sanitizeId("...hidden.onnx").startsWith("."))
        // 中文是合法字母，保留（可读性），空格等分隔符折叠成下划线
        assertEquals("中文_名字", PiperVoiceImporter.sanitizeId("中文 名字.onnx"))
        // 关键安全属性：结果里不含任何路径分隔符或 ..
        listOf("../a.onnx", "..\\a.onnx", "a/b/c.onnx", "..%2Fx.onnx").forEach { name ->
            val id = PiperVoiceImporter.sanitizeId(name)
            assertFalse(id.contains("/"))
            assertFalse(id.contains("\\"))
            assertFalse(id.contains(".."))
        }
        assertTrue(PiperVoiceImporter.sanitizeId(".onnx").startsWith("voice_"))
        assertTrue(PiperVoiceImporter.sanitizeId("x".repeat(200) + ".onnx").length <= 64)
    }

    @Test
    fun `onnx probe accepts protobuf header and rejects junk`() {
        val dir = File(context.filesDir, "probe").apply { mkdirs() }
        val protobuf = File(dir, "a.onnx").apply { writeBytes(byteArrayOf(0x08, 0x07, 0x12, 0x04)) }
        val named = File(dir, "b.onnx").apply { writeText("pytorch onnx exporter model") }
        val junk = File(dir, "c.onnx").apply { writeText("just a text file") }
        val empty = File(dir, "d.onnx").apply { writeText("") }

        assertTrue(PiperVoiceImporter.looksLikeOnnx(protobuf))
        assertTrue(PiperVoiceImporter.looksLikeOnnx(named))
        assertFalse(PiperVoiceImporter.looksLikeOnnx(junk))
        assertFalse(PiperVoiceImporter.looksLikeOnnx(empty))
        assertFalse(PiperVoiceImporter.looksLikeOnnx(File(dir, "missing.onnx")))
    }

    @Test
    fun `imported voices with missing files are dropped and pruned`() {
        val dir = File(PiperVoiceStore.voicesDir(context), "kept").apply { mkdirs() }
        val model = File(dir, "kept.onnx").apply { writeBytes(ByteArray(16)) }
        val tokens = File(dir, "tokens.txt").apply { writeText("a 1") }
        val kept = voice("kept", model.absolutePath, tokens.absolutePath)
        val gone = voice("gone", File(dir, "nope.onnx").absolutePath, tokens.absolutePath)
        PiperVoiceStore.registerImported(context, kept)
        PiperVoiceStore.registerImported(context, gone)

        // 指向不存在文件的记录会被过滤（否则选中它会让英文朗读整体失败）
        assertEquals(listOf("kept"), PiperVoiceStore.imported(context).map { it.id })
        // 并且被顺手修正回持久化数据里
        assertEquals(listOf("kept"), PiperVoiceStore.imported(context).map { it.id })
    }

    @Test
    fun `installed always offers the bundled voice and resolve falls back`() {
        val installed = PiperVoiceStore.installed(context)
        assertEquals(PiperVoiceCatalog.DEFAULT_ID, installed.first().id)
        assertTrue(installed.first().builtin)
        // 未知 id（例如音色被删掉后仍留在设置里）回退内置音色
        assertEquals(
            PiperVoiceCatalog.DEFAULT_ID,
            PiperVoiceStore.resolve(context, "does-not-exist").id
        )
        assertEquals(
            PiperVoiceCatalog.DEFAULT_ID,
            PiperVoiceStore.resolve(context, "").id
        )
    }

    @Test
    fun `catalogue urls point at the official piper sources`() {
        val lessac = PiperVoiceCatalog.downloadable.first { it.id == "en_US-lessac-medium" }
        assertTrue(lessac.sampleUrl.startsWith("https://huggingface.co/rhasspy/piper-voices/"))
        assertTrue(lessac.packageUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/"))
        assertTrue(lessac.packageUrl.endsWith(".tar.bz2"))
        assertTrue(PiperVoiceCatalog.downloadable.all { it.id.startsWith("en_US-") })
        assertTrue(PiperVoiceCatalog.downloadable.none { it.builtin })
    }

    private fun voice(id: String, model: String, tokens: String) = PiperVoice(
        id = id,
        displayName = id,
        gender = "?",
        language = "英语",
        sizeMb = 1,
        sampleUrl = "",
        modelUrl = "",
        packageUrl = "",
        modelPath = model,
        tokensPath = tokens
    )
}
