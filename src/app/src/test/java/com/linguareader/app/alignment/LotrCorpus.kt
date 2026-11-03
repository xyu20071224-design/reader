package com.linguareader.app.alignment

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.alignment.MeaningProvider
import com.linguareader.shared.translation.MeaningIndex
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.Locale

/**
 * **真实语料读取与真 ECDICT 接入**（T5 独立验收台的地基，不属于产品代码）。
 *
 * 三条口径，全部照抄生产路径，不另立一套：
 *
 * 1. 英文书按 app 导入格式读（`metadata.json` 的章表 + `relativePath`），
 *    与 `TranslationGoldenReplayTest.readEnBook()` 同源；
 * 2. 中文译本按解包 EPUB 的 `container.xml → content.opf spine` 读，同上；
 * 3. 段落抽取走 **`TtsTextExtractor`（叶级块）**——这是「与阅读器/TTS 同源」的
 *    唯一实现，本文件**不复制**它的选择器逻辑（复制就等于另立口径）。
 *
 * 分句不在这里做：新引擎内部委托 `SentenceSplitter.split`（不传上限），
 * 这里只交叶级段落，与契约 `AlignmentChapter.paragraphs` 的定义一致。
 */
internal object LotrCorpus {

    /** 仓库根：三个真实语料同时存在才算找到（缺一不可，避免读到半份素材）。 */
    fun findRoot(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "artifacts/lotr-book/metadata.json").isFile &&
                File(dir, "artifacts/lotr-zh/META-INF/container.xml").isFile &&
                File(dir, "artifacts/alignment-eval/golden-samples.json").isFile
            ) return dir
            dir = dir.parentFile
        }
        return null
    }

    /** 英文原版（app 导入格式）。 */
    fun readEnBook(dir: File): Book {
        val meta = JSONObject(File(dir, "metadata.json").readText())
        val chaptersJson = meta.getJSONArray("chapters")
        val chapters = (0 until chaptersJson.length()).map { i ->
            val chapter = chaptersJson.getJSONObject(i)
            Chapter(chapter.getString("title"), chapter.getString("relativePath"))
        }
        return Book(
            id = meta.optString("id", "lotr-en"),
            title = meta.optString("title", "lotr-en"),
            author = meta.optString("author", ""),
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = chapters,
            addedAt = 0L
        )
    }

    /** 中文译本（解包 EPUB，按 spine 的顺序读，章序即阅读顺序）。 */
    fun readZhBook(dir: File): Book {
        val container = File(dir, "META-INF/container.xml").readText()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: error("container.xml 缺少 rootfile full-path")
        val opf = Jsoup.parse(File(dir, opfPath).readText())
        val manifest = opf.select("manifest item").associate { it.attr("id") to it.attr("href") }
        val spine = opf.select("spine itemref").mapNotNull { manifest[it.attr("idref")] }
        // manifest item 的 href 可能带目录；container 里的 baseDir 只在 opf 位于子目录时需要。
        val baseDir = opfPath.substringBeforeLast('/', "")
        val chapters = spine.map { href ->
            val relativePath = if (baseDir.isEmpty()) href else "$baseDir/$href"
            Chapter(href.substringAfterLast('/'), relativePath)
        }
        return Book(
            id = "lotr-zh",
            title = opf.select("metadata title").firstOrNull()?.text() ?: "译本",
            author = "",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = chapters,
            addedAt = 0L
        )
    }

    /**
     * 归一化：只折叠空白 + 小写。
     *
     * **不要**用 `TranslationMemorySearch.normalize`——它把标点抹成空格，
     * 「样本英文句是否在段落里」的包含判断会因此误命中（金标准定位的老坑）。
     */
    fun lightNormalize(text: String): String =
        Regex("[\\s\\u00A0\\u2007\\u202F]+").replace(text, " ").trim().lowercase(Locale.ROOT)

    /**
     * 真 ECDICT → 新契约的 `MeaningProvider`。
     *
     * 底层就是生产用的 [MeaningIndex]（`EcdictMeaningIndex`：filesDir 的 ecdict 副本 +
     * `MeaningPhraseParser` 实义词性过滤），本类只做**接口形状适配**
     * （`Set` → `List`），不复刻任何词典逻辑。因此新引擎拿到的候选短语与旧对齐器
     * 拿到的是同一批——A/B 对照里「词典」这一项是同一个变量。
     */
    fun meaningProvider(index: MeaningIndex): MeaningProvider =
        MeaningProvider { word -> index.phrasesOf(word).toList() }
}
