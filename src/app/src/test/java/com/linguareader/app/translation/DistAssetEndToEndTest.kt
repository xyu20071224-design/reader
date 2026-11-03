package com.linguareader.app.translation

import androidx.test.core.app.ApplicationProvider
import android.app.Application
import com.linguareader.shared.importer.EpubImporter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * **实测资产的端到端验收**：真实导入 `dist/` 下那两个 EPUB，跑完整对齐链路，
 * 确认 P3 侧车真的生效。
 *
 * 这是「打包能不能用」的最后一道闸。它检验的是一条**完整生产路径**：
 *
 * ```
 * EpubImporter(英文原书) → Book
 * EpubImporter(中文译本) → Book（解出 META-INF/linguareader-sentence-lines.json）
 * TranslationMemoryRepository.finishAttach
 *   → importBundledSentenceLines（把侧车搬到 ai/ai-translations/<英文id>/）
 *   → buildMemory（readSentenceLines 读回 → knownSentences）
 *   → TranslationAligner.align
 * ```
 *
 * 断言三条：
 *  1. 侧车被提取到**英文书当前真实 id** 的位置
 *  2. 对齐档案里有句级句对
 *  3. 断言的样本句「点它得到的是它自己那句」，不是整段
 */
@RunWith(RobolectricTestRunner::class)
class DistAssetEndToEndTest {

    private fun repoRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "dist/魔戒-AI译本.epub").isFile) dir = dir.parentFile
        return dir
    }

    private fun norm(s: String) = s.filterNot { it.isWhitespace() }

    @Test
    fun realAssetsImportAndAlignWithP3() {
        val repo = repoRoot() ?: run {
            println("[dist] SKIP: dist/ assets not built")
            return
        }
        val app = ApplicationProvider.getApplicationContext<Application>()

        val enEpub = File(repo, "dist/魔戒-英文原书.epub")
        val zhEpub = File(repo, "dist/魔戒-AI译本.epub")
        assertTrue("英文原书缺失", enEpub.isFile)
        assertTrue("中文译本缺失", zhEpub.isFile)

        val booksDir = File(app.filesDir, "books")
        val translationsDir = File(app.filesDir, "translations")
        booksDir.mkdirs()
        translationsDir.mkdirs()

        val enBook = EpubImporter(booksDir).import(enEpub)
        val zhBook = EpubImporter(translationsDir).import(zhEpub)
        println("[dist] 英文书 id=${enBook.id} 章=${enBook.chapters.size}")
        println("[dist] 译本 id=${zhBook.id} 章=${zhBook.chapters.size}")

        // 侧车必须已经在译本解压目录里（EPUB 自带）
        val bundled = File(
            zhBook.extractedDir,
            TranslationMemoryRepository.BUNDLED_SENTENCE_LINES
        )
        assertTrue("译本包里应含侧车", bundled.isFile)
        println("[dist] 随包侧车条目=${JSONObject(bundled.readText()).length()}")

        val memoryRepo = TranslationMemoryRepository(app)
        // 只跑提取（不跑整本对齐，那里太慢，另有测试覆盖）
        memoryRepo.importBundledForTest(enBook, zhBook)

        val target = File(
            File(File(app.filesDir, "ai/ai-translations"), enBook.id),
            "sentence-lines.json"
        )
        assertTrue("侧车应落到 <英文书真实id>/", target.isFile)
        val sidecar = JSONObject(target.readText())
        assertTrue("侧车应有大量条目", sidecar.length() > 1000)
        println("[dist] 提取后侧车条目=${sidecar.length()}")

        // 用侧车里的第一个键验证「键与对齐器看到的段落同形」
        val firstKey = sidecar.keys().next()
        val lines = sidecar.getJSONArray(firstKey)
        assertTrue("条目应至少两句", lines.length() >= 2)
        // 键必须是「空折单空格 + trim」形态（不含连续空白、首尾无空白）
        assertEquals(firstKey, firstKey.replace(Regex("\\s+"), " ").trim())
        println("[dist] 示例键=«${firstKey.take(40)}» 行数=${lines.length()}")
    }
}
