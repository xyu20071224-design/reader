package com.linguareader.app.translation

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
        val english = findBook("英文原版")
        val chinese = findBook("中譯本")
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

        assertTrue("句对数异常偏少：${pairs.size}", pairs.size > 10_000)
        // 手机大约比这台机器慢一个数量级，留 3 秒的余量 ≈ 手机 30 秒级。
        assertTrue("整本书对齐退化到 ${elapsed}ms（>3s），DP 内层大概率又在扫描文本", elapsed < 3_000)
    }

    private fun findBook(prefix: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "artifacts/alignment-package")
            if (candidate.isDirectory) {
                return candidate.walkTopDown()
                    .firstOrNull { it.isFile && it.extension == "epub" && it.name.startsWith(prefix) }
            }
            dir = dir.parentFile
        }
        return null
    }

    /** 与 `align-cli` 相同的读法：每章 → 叶级段落文本列表。 */
    private fun readBlocks(file: File): List<List<String>> {
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

    private fun leafBlocks(document: Document): List<Element> {
        val candidates = document.select(blockSelector)
        return candidates.filter { candidate ->
            candidate.select(blockSelector).all { it === candidate }
        }
    }
}
