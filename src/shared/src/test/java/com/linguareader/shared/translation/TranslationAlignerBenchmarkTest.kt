package com.linguareader.shared.translation

import com.linguareader.shared.tts.SentenceSplitter
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 整本书对齐的性能基准（本地用）。
 *
 * 需要 `artifacts/alignment-package/` 下解包出来的魔戒中英 EPUB（gitignore 的本地产物），
 * **缺失时自动跳过**，所以在没有这批素材的机器上不会失败。
 *
 * 存在的理由：这是唯一能在改动对齐器后立刻发现「又把文本扫描塞回 DP 内层」的护栏 ——
 * 早期实现在真机上整本书要 5 分钟以上（单线程 100% CPU）。
 */
class TranslationAlignerBenchmarkTest {

    private val blockSelector =
        "p, li, h1, h2, h3, h4, h5, h6, blockquote, td, figcaption, pre, div, section, article, header, footer"

    @Test
    fun alignsAFullNovelFastEnoughForAPhone() {
        val english = findSource("英文原版", "lotr-book")
        val chinese = findSource("中譯本", "lotr-zh")
        assumeTrue("缺少 artifacts 里的测试书，跳过基准", english != null && chinese != null)

        val enChapters = readBlocks(english!!)
        val zhChapters = readBlocks(chinese!!)

        val started = System.currentTimeMillis()
        val pairs = TranslationAligner.align(enChapters, zhChapters)
        val elapsed = System.currentTimeMillis() - started

        println(
            "[benchmark] 章节 英${enChapters.size}/中${zhChapters.size}" +
                " 句对=${pairs.size} 耗时=${elapsed}ms" +
                " 平均置信度=${"%.2f".format(pairs.map { it.confidence }.average())}"
        )

        // 真正要量的是「点词命中率」：模拟在每个英文段落的首句上点词，看查询是否命中。
        // 注意别用「不同 enParagraph 文本数」当覆盖率——2:1 合并会把两段源文本存成一条，
        // 那个指标会把合并误算成漏掉。
        val index = TranslationMemoryIndex(
            TranslationMemory(
                sourceBookId = "bench",
                sourceTitle = "bench",
                translationBookId = "bench-zh",
                translationTitle = "译本",
                alignedAt = 0L,
                pairs = pairs
            )
        )
        var hits = 0
        var sentenceHits = 0
        var misses = 0
        val missesByChapter = HashMap<Int, Int>()
        enChapters.forEachIndexed { chapterIndex, paragraphs ->
            paragraphs.forEach { paragraph ->
                val sentence = SentenceSplitter.split(paragraph).firstOrNull { it.isNotBlank() }
                    ?: paragraph
                val result = index.lookup(chapterIndex, sentence, paragraph)
                if (result == null) {
                    misses++
                    missesByChapter[chapterIndex] = (missesByChapter[chapterIndex] ?: 0) + 1
                } else {
                    hits++
                    if (result.matchLevel == TranslationMatchLevel.SENTENCE) sentenceHits++
                }
            }
        }
        val total = hits + misses
        println(
            "[lookup] 命中=$hits/$total（${"%.1f".format(hits * 100.0 / total)}%）" +
                " 其中句级=$sentenceHits 段级=${hits - sentenceHits} 未命中=$misses" +
                " 未命中最多的章节=${missesByChapter.entries.sortedByDescending { it.value }.take(5)
                    .joinToString { "ch${it.key}:${it.value}" }}"
        )
        println(
            "[coverage] 章节覆盖=${pairs.map { it.enChapter }.toSet().size}/${enChapters.size}" +
                " 句级句对=${pairs.count { it.enSentence.isNotBlank() }}" +
                " 段级句对=${pairs.count { it.enSentence.isBlank() }}"
        )

        assertTrue("句对数异常偏少：${pairs.size}", pairs.size > 10_000)
        // 手机大约比这台机器慢一个数量级，留 3 秒的余量 ≈ 手机 30 秒级。
        assertTrue("整本书对齐退化到 ${elapsed}ms（>3s），DP 内层大概率又在扫描文本", elapsed < 3_000)
    }

    /** 测试书来源：优先 `artifacts/alignment-package` 里的 EPUB，缺失时退回解包目录。 */
    private sealed interface BookSource {
        class EpubFile(val file: File) : BookSource
        class UnpackedDir(val dir: File) : BookSource
    }

    private fun findSource(epubPrefix: String, unpackedDirName: String): BookSource? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val packageDir = File(dir, "artifacts/alignment-package")
            if (packageDir.isDirectory) {
                val epub = packageDir.walkTopDown().firstOrNull {
                    it.isFile && it.extension == "epub" && it.name.startsWith(epubPrefix)
                }
                if (epub != null) return BookSource.EpubFile(epub)
            }
            // 2026-09-08：alignment-package 在多次搬迁中丢失，基准测试长期静默跳过；
            // 退回解包目录（英文 = app 导入格式，中文 = 解包 EPUB）以恢复性能护栏。
            val unpacked = File(dir, "artifacts/$unpackedDirName")
            if (unpacked.isDirectory) return BookSource.UnpackedDir(unpacked)
            dir = dir.parentFile
        }
        return null
    }

    private fun readBlocks(source: BookSource): List<List<String>> = when (source) {
        is BookSource.EpubFile -> readEpubBlocks(source.file)
        is BookSource.UnpackedDir -> readUnpackedBlocks(source.dir)
    }

    /** 与 `align-cli` 相同的读法：每章 → 叶级段落文本列表。 */
    private fun readEpubBlocks(file: File): List<List<String>> {
        ZipFile(file).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
                ?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) }
                ?: error("不是合法 EPUB：${file.name}")
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
                ?: error("container.xml 缺少 rootfile full-path")
            val opfXml = zip.getEntry(opfPath)!!.let {
                zip.getInputStream(it).readBytes().toString(Charsets.UTF_8)
            }
            val opf = Jsoup.parse(opfXml)
            val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
            val spine = opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
            val baseDir = opfPath.substringBeforeLast('/', "")

            val chapters = mutableListOf<List<String>>()
            for (href in spine) {
                val path = if (baseDir.isEmpty()) href else "$baseDir/$href"
                val entry = zip.getEntry(path) ?: continue
                val html = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                val blocks = leafBlocks(Jsoup.parse(html))
                    .map { it.text().replace(Regex("\\s+"), " ").trim() }
                    .filter { it.isNotBlank() }
                if (blocks.isNotEmpty()) chapters += blocks
            }
            return chapters
        }
    }

    /**
     * 解包目录的读法（`alignment-package` 缺失时的回退）：
     * - 有 `metadata.json` = app 导入格式（英文书）：按章表里的 `relativePath` 读；
     * - 有 `META-INF/container.xml` = 解包 EPUB（中文译本）：按 spine 读。
     *
     * 与 EPUB 读法同样丢掉空章节，保证两种来源喂给对齐器的形状一致。
     */
    private fun readUnpackedBlocks(root: File): List<List<String>> {
        val metadata = File(root, "metadata.json")
        val paths: List<String> = if (metadata.isFile) {
            val chapters = JSONObject(metadata.readText()).getJSONArray("chapters")
            (0 until chapters.length()).map { chapters.getJSONObject(it).getString("relativePath") }
        } else {
            val container = File(root, "META-INF/container.xml").readText()
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
                ?: error("container.xml 缺少 rootfile full-path")
            val opf = Jsoup.parse(File(root, opfPath).readText())
            val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
            val baseDir = opfPath.substringBeforeLast('/', "")
            opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
                .map { href -> if (baseDir.isEmpty()) href else "$baseDir/$href" }
        }
        val chapters = mutableListOf<List<String>>()
        for (path in paths) {
            val file = File(root, path)
            if (!file.isFile) continue
            val blocks = leafBlocks(Jsoup.parse(file.readText()))
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }
            if (blocks.isNotEmpty()) chapters += blocks
        }
        return chapters
    }

    private fun leafBlocks(document: Document): List<Element> {
        val candidates = document.select(blockSelector)
        return candidates.filter { candidate ->
            candidate.select(blockSelector).all { it === candidate }
        }
    }
}
