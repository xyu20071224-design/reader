package com.linguareader.shared.alignment

import java.io.File
import kotlin.test.Test

/**
 * **真实 ECDICT Provider**：从 `src/app/src/main/assets/dictionary/ecdict.sqlite` 读词条。
 *
 * ## 为什么不能用 JDBC
 *
 * `:shared` 的依赖白名单里没有 sqlite 驱动（`sqlite-jdbc` 只在 `:app` 的
 * `testImplementation` 里）。本任务的硬约束是「不新增第三方依赖」，所以这里改用
 * **预导出的词条文件**：`artifacts/alignment-eval/ecdict-sample.tsv`（由
 * [EcdictExportTest] 一次性导出，格式 `word \t gloss1|gloss2|...`）。
 *
 * 这份导出是**测试夹具**，不是生产代码路径——生产环境由 `:app` 注入真 ECDICT。
 *
 * ## 为什么必须用真 ECDICT 做验收
 *
 * 合成语料用 `truthLexicon()`（能力上界：多义词被真值唯一化、无词形变化噪声）。
 * 真实书的可用性只能用真 ECDICT 测——它有 77 万条、多义词、需要词形还原。
 */
internal class EcdictProvider private constructor(
    private val entries: Map<String, List<String>>,
    /** 词形 → 原形（来自 ECDICT 的 `forms` 表）。 */
    private val lemmaOf: Map<String, String>
) : MeaningProvider {

    override fun chineseCandidates(englishWord: String): List<String> {
        val word = englishWord.lowercase()
        entries[word]?.let { return it }
        val lemma = lemmaOf[word] ?: return emptyList()
        return entries[lemma] ?: emptyList()
    }

    val entryCount: Int get() = entries.size
    val lemmaCount: Int get() = lemmaOf.size

    companion object {
        /** 默认导出文件位置（相对仓库根）。 */
        const val EXPORT_RELATIVE_PATH = "artifacts/alignment-eval/ecdict-sample.tsv"

        /** 载入导出文件；文件缺失返回 `null`（测试应 SKIP）。 */
        fun load(repoRoot: File): EcdictProvider? {
            val file = File(repoRoot, EXPORT_RELATIVE_PATH)
            if (!file.isFile) return null
            val entries = HashMap<String, List<String>>(8192)
            val lemmaOf = HashMap<String, String>(2048)
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val word = line.substring(0, tab).lowercase()
                val glosses = line.substring(tab + 1).split('|').filter { it.isNotBlank() }
                when {
                    word.startsWith(">") -> lemmaOf[word.substring(1)] = glosses.firstOrNull() ?: ""
                    glosses.isNotEmpty() -> entries[word] = glosses
                }
            }
            return if (entries.isEmpty()) null else EcdictProvider(entries, lemmaOf)
        }

        /**
         * 把 ECDICT 的 `translation` 拆成 2–4 字的纯汉字段。
         *
         * 输入形如 `n. 长者, 长老\nadj. 年长的`。按非汉字字符切分，丢弃长度不在
         * 2–4 之间的片段（词性标注 `n.`、括号注释会被一起丢掉）。
         *
         * 与 `VerifyLayer` 的 `TERM_MIN_CHARS..TERM_MAX_CHARS` 口径一致。
         */
        internal fun parseGlosses(translation: String): List<String> {
            val result = LinkedHashSet<String>(8)
            val current = StringBuilder()
            fun flush() {
                val text = current.toString()
                current.setLength(0)
                if (text.length < 2 || text.length > 4) return
                result.add(text)
            }
            for (c in translation) {
                if (c in '\u4E00'..'\u9FFF') current.append(c) else flush()
            }
            flush()
            return result.toList()
        }
    }
}
