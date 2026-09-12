package com.linguareader.app.tts

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第四轮审查 6-1/6-2 的护栏：TTS 缓存必须**原子写**。
 *
 * 命中判据只有「文件存在且 >0 字节」，所以最终路径一旦出现就必须是完整文件；
 * 半截文件若落在最终路径上会**永久**命中且不自我修复（用户反复听到残缺音频）。
 * 这里钉死三条：写成功才出现、失败不留残骸、失败不产生半成品最终文件。
 */
@RunWith(RobolectricTestRunner::class)
class AtomicAudioWriteTest {

    private fun tempDir(prefix: String): File =
        File.createTempFile(prefix, "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `target appears only after write completes and tmp is cleaned`() {
        val dir = tempDir("atomic-")
        val target = File(dir, "s0-0.mp3")

        writeAudioAtomically(target) { temp ->
            // 写入回调内部：最终路径还不该存在（否则等于暴露半成品）
            assertFalse(target.exists(), "写入过程中最终路径不应提前出现")
            temp.writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        assertTrue(target.isFile)
        assertEquals(4, target.readBytes().size)
        assertEquals(0, dir.listFiles().orEmpty().count { it.name.endsWith(".tmp") })
    }

    @Test
    fun `failure leaves no residue and no partial target`() {
        val dir = tempDir("atomic-")
        val target = File(dir, "s0-1.mp3")

        val thrown = runCatching {
            writeAudioAtomically(target) { temp ->
                temp.writeBytes(byteArrayOf(1, 2, 3)) // 写了一半
                error("模拟合成中途失败（断网/被杀）")
            }
        }.exceptionOrNull()

        assertEquals("模拟合成中途失败（断网/被杀）", thrown?.message)
        assertFalse(target.exists(), "失败绝不能留下最终路径文件（否则永久命中半成品）")
        assertEquals(0, dir.listFiles().orEmpty().count { it.name.endsWith(".tmp") })
    }

    @Test
    fun `existing target is replaced atomically`() {
        val dir = tempDir("atomic-")
        val target = File(dir, "s0-2.mp3").apply { writeBytes(byteArrayOf(9, 9, 9, 9, 9)) }

        writeAudioAtomically(target) { temp -> temp.writeBytes(byteArrayOf(7, 7)) }

        assertEquals(2, target.readBytes().size)
        assertEquals(0, dir.listFiles().orEmpty().count { it.name.endsWith(".tmp") })
    }
}
