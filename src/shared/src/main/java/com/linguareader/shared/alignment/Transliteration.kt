package com.linguareader.shared.alignment

/**
 * 英中**音译专名**匹配（纯 Kotlin/JVM，无 android.*，无第三方依赖，无状态）。
 *
 * ## 为什么需要它
 * 英中句级对齐的词义通道（ECDICT 释义 vs 文学译本用词）在真实语料里几乎不重叠，
 * 中文侧拉丁串/阿拉伯数字锚点在译本里也几乎不存在（译本把 `Frodo` 写成「佛羅多」、
 * `1987` 写成「一九八七年」）。但**音译专名**是稳定规律且全书译法固定，
 * 是少数可靠的跨语言锚点。
 *
 * ## 三个 API
 * - [candidates]：英文专名 -> 可能的中文音译（2-5 汉字，按可能性排序，最多 8 个）
 * - [scoreInSentence]：英文专名 + 一句中文 -> 置信度 [0,1]，繁简都能匹配
 * - [extractNames]：从英文文本抽候选专名
 *
 * ## 实现分层
 * 1. **音节切分**：元音核切分 + 常见辅音簇处理（`Fro-do`、`Gan-dalf`、`Bil-bo`）。
 * 2. **声母/韵母映射**：英文音节 -> 拼音音节（含鼻音韵尾合并、成音节辅音、
 *    边音并入韵母等），再由拼音音节 -> 音译常用汉字。**不硬编码任何整名**。
 * 3. **合法性兜底**：所有产出的拼音音节必须是普通话合法音节，否则无字可用。
 * 4. **排序**：2-4 字优先、首字用高频音译起首字（阿/佛/甘/山/比……）加权。
 * 5. **匹配**：候选串直接包含判定（快、零误报）+ 音节近似对齐兜底。
 * 6. **繁简**：内置一份「只含本模块字表可能产出字符」的繁->简表，
 *    匹配前整句归一，因此 `佛羅多` 与 `佛罗多` 同分。
 *
 * ## 性能
 * [candidates] 与内部骨架表均用 [ConcurrentHashMap] 缓存；
 * [scoreInSentence] 为 O(候选数 × 句长)，不编译正则。
 */
object Transliteration {

    // ──────────────────────────────────────────────────────────────
    //  缓存（热路径：会被调用几十万次）
    // ──────────────────────────────────────────────────────────────

    private val candidateCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>(256)
    private val skeletonCache = java.util.concurrent.ConcurrentHashMap<String, List<PinyinSeq>>(256)
    private val syllablesCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>(256)

    /** 候选上限。 */
    private const val MAX_CANDIDATES = 8
    /** 音译用字表里每个拼音最多取几个字参与组合。 */
    private const val ALT_PER_SYLLABLE = 3
    /** 一个拼音序列最多展开多少个汉字串。 */
    private const val MAX_FORMS_PER_SEQ = 120
    /** 骨架表上限（用于近似对齐）。 */
    private const val MAX_SKELETONS = 60
    /** 近似对齐允许的平均每音节代价。 */
    private const val FUZZY_MAX_AVG_COST = 0.30
    /** 近似对齐窗口内至少要有几个音节**精确**匹配（区分度的关键）。 */
    private const val FUZZY_MIN_EXACT_SYLLABLES = 2

    // ──────────────────────────────────────────────────────────────
    //  公开 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 给定英文专名，给出可能的中文音译形式。
     *
     * @param englishName 英文专名，可含连字符/撇号/重音符（`Khazad-dûm`、`Frodo's`）。
     * @return 按可能性降序的候选，每项 2-5 个汉字，最多 8 个；无法音译时返回空列表。
     */
    fun candidates(englishName: String): List<String> {
        val key = englishName.trim()
        if (key.isEmpty()) return emptyList()
        candidateCache[key]?.let { return it }
        val built = buildCandidates(key)
        candidateCache[key] = built
        return built
    }

    /**
     * 给定英文专名和一句中文文本，返回该专名在中文里出现的置信度。
     *
     * 繁简均可：内部先把句子做繁->简归一，再匹配。
     *
     * @return [0,1]。0 表示完全不匹配；1.0 表示候选表首位命中。
     */
    fun scoreInSentence(englishName: String, zhSentence: String): Double {
        if (englishName.isBlank() || zhSentence.isBlank()) return 0.0
        val simplified = toSimplified(zhSentence)
        if (simplified.isEmpty()) return 0.0

        // 1) 候选串直接包含（快、零误报）。按候选排序给分。
        val cands = candidates(englishName)
        for (rank in cands.indices) {
            if (simplified.contains(cands[rank])) {
                return when (rank) {
                    0 -> 1.0
                    1 -> 0.95
                    2 -> 0.90
                    else -> maxOf(0.65, 0.88 - 0.02 * rank)
                }
            }
        }

        // 2) 音节近似对齐兜底（容忍译名用字与规则产出的差异）。
        val skeletons = skeletons(englishName)
        if (skeletons.isEmpty()) return 0.0
        return if (fuzzyMatch(simplified, skeletons)) 0.70 else 0.0
    }

    /**
     * 从英文文本里抽取候选专名（大写开头、非常见词、可含连字符/撇号）。
     *
     * 句首大写词会被排除（否则 `The`/`Then` 这类全被当成专名），
     * 但保留连续的大写词串（`Middle-earth`、`Sackville-Baggins`）。
     *
     * @return 去重后的专名列表，保持首次出现顺序。
     */
    fun extractNames(englishText: String): List<String> {
        if (englishText.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val n = englishText.length
        var i = 0
        while (i < n) {
            if (!englishText[i].isLetter()) {
                i++
                continue
            }
            val start = i
            while (i < n && (englishText[i].isLetter() || englishText[i] == '\'' ||
                    englishText[i] == '’' || englishText[i] == '-' ||
                    Character.getType(englishText[i]) == Character.NON_SPACING_MARK.toInt())
            ) {
                i++
            }
            val token = englishText.substring(start, i)
            if (looksLikeName(token)) {
                out.add(token)
            }
            if (out.size >= MAX_EXTRACTED_NAMES) break
        }
        return out.toList()
    }

    private const val MAX_EXTRACTED_NAMES = 128

    // ──────────────────────────────────────────────────────────────
    //  专名抽取辅助
    // ──────────────────────────────────────────────────────────────

    private fun looksLikeName(token: String): Boolean {
        if (token.length < 3 || token.length > 24) return false
        val first = token[0]
        if (!first.isUpperCase()) return false
        // 全大写（章节标题/缩写）不算：CHAPTER、BOOK
        if (token.length > 3 && token.all { !it.isLetter() || it.isUpperCase() }) return false
        val letters = token.filter { it.isLetter() }
        if (letters.length < 3) return false
        // 需要至少一个元音，否则不是可音译的词
        if (letters.none { it.lowercaseChar() in VOWELS }) return false
        // 句首的大写词（The / Then / Suddenly …）与常见词由停用表过滤；
        // 不做「句首一律排除」——真实专名同样会出现在句首（`Frodo met Gandalf.`）。
        if (token.lowercase() in STOP_WORDS) return false
        return true
    }

    // ──────────────────────────────────────────────────────────────
    //  归一化
    // ──────────────────────────────────────────────────────────────

    private val VOWELS = "aeiouy"
    private val VOWEL_SET: Set<Char> = VOWELS.toSet()

    /**
     * 英文名归一：小写、去重音、去撇号/连字符、去魔法 e、合并重复辅音。
     *
     * 例：`Khazad-dûm` -> `khazadum`；`Frodo's` -> `frodos`；`Shire` -> `shir`。
     */
    private fun normalizeName(name: String): String {
        val sb = StringBuilder(name.length)
        for (ch in name.lowercase()) {
            val mapped = ACCENT_FOLD[ch]
            if (mapped != null) {
                sb.append(mapped)
                continue
            }
            if (ch in 'a'..'z') sb.append(ch)
        }
        if (sb.isEmpty()) return ""
        var s = sb.toString()
        // 词尾不发音的 e：C V C e -> C V C（Shire -> shir），但不碰 Frodo / Bilbo
        if (s.length >= 3 && s.endsWith("e") && s[s.length - 2] !in VOWEL_SET &&
            s[s.length - 3] in VOWEL_SET
        ) {
            s = s.dropLast(1)
        }
        // 合并重复辅音：pp -> p（Pippin -> pipin），但保留 oo/ee 等元音组合
        val out = StringBuilder(s.length)
        var prev = ' '
        for (ch in s) {
            val isConsonant = ch in 'a'..'z' && ch !in VOWEL_SET
            if (isConsonant && ch == prev) continue
            out.append(ch)
            prev = ch
        }
        return out.toString()
    }

    // ──────────────────────────────────────────────────────────────
    //  音节切分
    // ──────────────────────────────────────────────────────────────

    /** 英文名 -> 英文音节列表（`fro do`、`gan dalf`、`bil bo`）。 */
    private fun syllablesOf(name: String): List<String> {
        val norm = normalizeName(name)
        if (norm.isEmpty()) return emptyList()
        syllablesCache[name]?.let { return it }
        val built = syllabify(norm)
        syllablesCache[name] = built
        return built
    }

    /**
     * 元音核 + 辅音簇切分。
     *
     * 规则：
     * - 先找所有元音组作为音节核；
     * - 两个核之间的辅音簇，若末两位是「辅音+l/r」（fr/gr/br…）则整体归属后一音节；
     * - 否则末位辅音归属后一音节（作声母），其余归属前一音节（作韵尾）；
     * - `-ia/-io/-iu/-ua` 在辅音后按「高元音单独成音节」裂开（Mo-ri-a、An-du-in）。
     */
    private fun syllabify(word: String): List<String> {
        val groups = vowelGroups(word)
        if (groups.isEmpty()) return listOf(word)
        // 元音核；hiccups: i/u 后接元音时裂开
        val nuclei = ArrayList<IntArray>()
        for (g in groups) {
            val text = word.substring(g[0], g[1])
            if (text.length >= 2 && (text[0] == 'i' || text[0] == 'u') && g[0] > 0 &&
                word[g[0] - 1] !in VOWEL_SET
            ) {
                nuclei.add(intArrayOf(g[0], g[0] + 1))
                nuclei.add(intArrayOf(g[0] + 1, g[1]))
            } else {
                nuclei.add(g)
            }
        }
        val bounds = ArrayList<Int>(nuclei.size + 1)
        bounds.add(0)
        for (k in 1 until nuclei.size) {
            val prevEnd = nuclei[k - 1][1]
            val nextStart = nuclei[k][0]
            val cluster = word.substring(prevEnd, nextStart)
            var cut = nextStart
            if (cluster.isNotEmpty()) {
                if (cluster.length >= 2 && MERGED_LIQUID.containsKey(cluster.takeLast(2))) {
                    cut = nextStart - 2
                } else if (SINGLE_ONSET.containsKey(cluster.takeLast(1))) {
                    cut = nextStart - 1
                }
            }
            if (cut <= bounds[bounds.size - 1]) cut = nextStart
            bounds.add(cut)
        }
        bounds.add(word.length)
        val out = ArrayList<String>(bounds.size)
        for (k in 0 until bounds.size - 1) {
            if (bounds[k] < bounds[k + 1]) out.add(word.substring(bounds[k], bounds[k + 1]))
        }
        return out
    }

    private fun vowelGroups(s: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i < s.length) {
            if (s[i] in VOWEL_SET) {
                var j = i
                while (j < s.length && s[j] in VOWEL_SET) j++
                out.add(intArrayOf(i, j))
                i = j
            } else {
                i++
            }
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────
    //  英文音节 -> 拼音序列
    // ──────────────────────────────────────────────────────────────

    /** 一个拼音音节序列（如 `fu luo duo`）及其代价。 */
    private class PinyinSeq(val syllables: List<String>, val cost: Double)

    /**
     * 英文音节 -> 若干候选拼音序列。
     *
     * 处理：声母映射、韵母映射、簇辅音（prosthetic + liquid）、
     * 韵尾（鼻音合并 / 塞音成音节 / 边音并入 / r 不发音）。
     */
    private fun parseSyllable(syl: String, isFinal: Boolean): List<PinyinSeq> {
        if (syl.isEmpty()) return emptyList()
        val out = ArrayList<PinyinSeq>()

        // ---- (a) 辅音簇 + 流音/滑音：fro / bre / gla / str
        for (clen in intArrayOf(3, 2)) {
            if (syl.length <= clen) continue
            val key = syl.substring(0, clen)
            val cons = MERGED_LIQUID[key] ?: continue
            if (syl[clen] !in VOWEL_SET) continue
            val liquid = key[key.length - 1]
            val rest = syl.substring(clen)
            val vg = vowelGroups(rest)
            if (vg.isEmpty()) continue
            val vtext = rest.substring(vg[0][0], vg[0][1])
            val coda = rest.substring(vg[0][1])
            val finals = FINAL[vtext] ?: listOf(FinalChoice(vtext, 3.0))
            val codaPlans = codaPlans(finals[0].pinyin, coda, 0)
            for (plan in codaPlans.take(5)) {
                val liquidSyl = liquidSyllable(liquid, plan.final)
                // (a1) 插入元音 + 流音音节：fu-luo
                val prosthetics = PROSTHETIC[cons] ?: listOf(cons + "e")
                for ((pi, p) in prosthetics.withIndex()) {
                    out.add(PinyinSeq(listOf(p, liquidSyl) + plan.extra, plan.cost + finals[0].cost + pi))
                }
                // (a2) 合流：dra -> 德拉（直接 combine）
                for (m in legalize(cons, plan.final)) {
                    val merged = ArrayList<String>(plan.extra.size + 1)
                    merged.add(m.first)
                    merged.addAll(plan.extra)
                    out.add(PinyinSeq(merged, plan.cost + finals[0].cost + 3.0 + m.second))
                }
            }
            break
        }
        if (out.isNotEmpty()) return dedupeSeq(out)

        // ---- (b) 单声母
        var onset = ""
        var rest = syl
        for (len in minOf(3, syl.length) downTo 0) {
            val cand = syl.substring(0, len)
            if (SINGLE_ONSET.containsKey(cand) &&
                (len == syl.length || syl[len] in VOWEL_SET)
            ) {
                onset = cand
                rest = syl.substring(len)
                break
            }
        }
        if (rest.isEmpty() || vowelGroups(rest).isEmpty()) {
            val onsetOpts = SINGLE_ONSET[onset] ?: listOf("")
            return dedupeSeq(onsetOpts.map { ini ->
                PinyinSeq(listOf(if (ini.isEmpty()) "e" else ini + "e"), 4.0)
            })
        }
        val vg = vowelGroups(rest)
        val vtext = rest.substring(vg[0][0], vg[0][1])
        var coda = rest.substring(vg[0][1])
        var finals = FINAL[vtext]
        if (finals == null) {
            // 最长前缀匹配（已含双元音但表里没有）
            var found: List<FinalChoice>? = null
            for (len in minOf(3, vtext.length) downTo 1) {
                if (FINAL.containsKey(vtext.substring(0, len))) {
                    found = FINAL[vtext.substring(0, len)]!!
                    coda = vtext.substring(len) + coda
                    break
                }
            }
            finals = found ?: listOf(FinalChoice(vtext, 4.0))
        }
        val onsetOpts = SINGLE_ONSET[onset] ?: listOf("")
        for (f in finals) {
            for (plan in codaPlans(f.pinyin, coda, 0).take(5)) {
                for ((oi, ini) in onsetOpts.withIndex()) {
                    for (m in legalize(ini, plan.final)) {
                        val merged = ArrayList<String>(plan.extra.size + 1)
                        merged.add(m.first)
                        merged.addAll(plan.extra)
                        out.add(
                            PinyinSeq(
                                merged.filter { it.isNotEmpty() },
                                f.cost + plan.cost + oi + m.second,
                            )
                        )
                    }
                    if (oi >= 1) break
                }
            }
        }
        return dedupeSeq(out)
    }

    private class FinalChoice(val pinyin: String, val cost: Double)

    private class CodaPlan(val final: String, val extra: List<String>, val cost: Double)

    /** 韵尾规划：可能改写元音（鼻音化）并/或追加音节（塞音/流音）。 */
    private fun codaPlans(f: String, coda: String, depth: Int): List<CodaPlan> {
        if (depth > 3 || coda.isEmpty()) return listOf(CodaPlan(f, emptyList(), 0.0))
        val out = ArrayList<CodaPlan>()
        val c0 = coda[0]
        when {
            c0 == 'l' -> {
                val merges = L_MERGE[f] ?: listOf(f)
                for (mf in merges) {
                    for (p in codaPlans(mf, coda.substring(1), depth + 1).take(2)) {
                        out.add(CodaPlan(p.final, p.extra, p.cost + if (mf == f) 1.0 else 0.0))
                    }
                }
                for (p in codaPlans(f, coda.substring(1), depth + 1).take(2)) {
                    out.add(CodaPlan(p.final, listOf("er") + p.extra, p.cost + 1.0))
                }
            }
            c0 == 'r' -> {
                // 非儿化：r 不发音
                out.addAll(codaPlans(f, coda.substring(1), depth + 1).take(3))
            }
            coda.startsWith("ng") || coda.startsWith("nk") -> {
                val ng = NASAL_NG[f] ?: NASAL_N[f] ?: f
                out.addAll(codaPlans(ng, coda.substring(2), depth + 1).take(2))
            }
            coda.startsWith("rn") -> {
                val ng = NASAL_NG[f]
                if (ng != null) {
                    for (p in codaPlans(ng, coda.substring(2), depth + 1).take(2)) {
                        out.add(CodaPlan(p.final, p.extra, p.cost + 1.0))
                    }
                }
                out.addAll(codaPlans(f, coda.substring(2), depth + 1).take(1))
            }
            c0 == 'n' || c0 == 'm' -> {
                val nz = NASAL_N[f] ?: f
                for (p in codaPlans(nz, coda.substring(1), depth + 1).take(2)) {
                    out.add(CodaPlan(p.final, p.extra, p.cost))
                }
                val alt = NASAL_ALT[f]
                if (alt != null && alt != nz) {
                    for (p in codaPlans(alt, coda.substring(1), depth + 1).take(1)) {
                        out.add(CodaPlan(p.final, p.extra, p.cost + 1.0))
                    }
                }
                if (c0 == 'm') {
                    for (p in codaPlans(f, coda.substring(1), depth + 1).take(1)) {
                        out.add(CodaPlan(p.final, listOf("mu") + p.extra, p.cost + 1.0))
                    }
                    // 词尾 -m 直接脱落：Gollum /ˈɡɒləm/ -> 咕魯。
                    // 音译里 -um 既可作「姆」也可只留元音，两种都要给。
                    if (coda.length == 1 && depth == 0) {
                        for (p in codaPlans(f, "", depth + 1).take(1)) {
                            out.add(CodaPlan(p.final, p.extra, p.cost + 3.0))
                        }
                    }
                }
            }
            else -> {
                var handled = false
                for (len in intArrayOf(2, 1)) {
                    if (coda.length < len) continue
                    val syl = STOP_SYLLABLE[coda.substring(0, len)] ?: continue
                    handled = true
                    for (p in codaPlans(f, coda.substring(len), depth + 1).take(2)) {
                        out.add(CodaPlan(p.final, syl.split(" ") + p.extra, p.cost))
                    }
                    for (p in codaPlans(f, coda.substring(len), depth + 1).take(1)) {
                        out.add(CodaPlan(p.final, p.extra, p.cost + 1.0))
                    }
                    break
                }
                if (!handled) out.add(CodaPlan(f, emptyList(), 2.0))
            }
        }
        return out
    }

    private fun liquidSyllable(liquid: Char, final: String): String {
        val map: Map<String, String> = when (liquid) {
            'r' -> LIQUID_R
            'l' -> LIQUID_L
            else -> LIQUID_W
        }
        val direct = map[final]
        if (direct != null) return direct
        val ini = if (liquid == 'w') "w" else "l"
        return legalize(ini, final).firstOrNull()?.first ?: "le"
    }

    private fun dedupeSeq(list: List<PinyinSeq>): List<PinyinSeq> {
        val seen = HashSet<String>()
        val out = ArrayList<PinyinSeq>(list.size)
        for (p in list) {
            val k = p.syllables.joinToString(" ")
            if (seen.add(k)) out.add(p)
        }
        return out
    }

    /**
     * 把「声母 + 韵母」合法化成一个真实存在的普通话音节。
     *
     * 语音转换经常产出不合法的组合（`b` + `uo`、`g` + `i`），
     * 这里按「同声母下最接近的合法韵母」兜底，并给出额外代价。
     */
    private fun legalize(initial: String, fin: String): List<Pair<String, Double>> {
        val out = ArrayList<Pair<String, Double>>(3)
        if (initial.isEmpty()) {
            ZERO_INITIAL[fin]?.let { if (it in VALID_PINYIN) out.add(it to 0.0) }
            if (out.isEmpty()) {
                for (alt in listOf(fin, fin + "i", if (fin.isNotEmpty()) fin.dropLast(1) else "a")) {
                    val sp = ZERO_INITIAL[alt] ?: alt
                    if (sp in VALID_PINYIN) {
                        out.add(sp to 2.0)
                        break
                    }
                }
            }
            if (out.isEmpty()) out.add("e" to 4.0)
            return out
        }
        if (initial + fin in VALID_PINYIN) out.add((initial + fin) to 0.0)
        // 颚化：g/k/h + i -> j/q/x
        if (fin.startsWith("i")) {
            val pal = PALATAL[initial]
            if (pal != null && pal + fin in VALID_PINYIN) out.add((pal + fin) to 1.0)
        }
        // 唇音后不能接 uo/ong/un 等
        if (initial in LABIAL_INITIALS) {
            for ((alt, c) in LABIAL_REPAIR[fin] ?: emptyList()) {
                if (initial + alt in VALID_PINYIN) out.add((initial + alt) to c)
            }
        }
        // 通用兜底：鼻音互换、截短、换韵
        for ((alt, c) in GENERIC_REPAIR) {
            val cand = when {
                alt == "%NG2N%" -> fin.replace("ng", "n")
                alt == "%N2NG%" -> fin.replace("n", "ng")
                alt == "%F2%" -> fin.take(2)
                alt == "%F1%" -> fin.take(1)
                alt == "%FI%" -> fin + "i"
                alt == "%FN%" -> fin + "n"
                else -> alt
            }
            if (cand.isEmpty()) continue
            if (initial + cand in VALID_PINYIN) out.add((initial + cand) to c)
        }
        if (out.isEmpty()) {
            for (alt in FALLBACK_FINALS) {
                if (initial + alt in VALID_PINYIN) {
                    out.add((initial + alt) to 5.0)
                    break
                }
            }
        }
        if (out.isEmpty()) out.add("si" to 6.0)
        return out.sortedBy { it.second }.distinctBy { it.first }.take(2)
    }

    // ──────────────────────────────────────────────────────────────
    //  拼音序列 -> 汉字候选
    // ──────────────────────────────────────────────────────────────

    /**
     * 生成候选：beam 搜拼音序列 -> 每个序列展开成汉字串 -> 排序。
     *
     * 排序考虑：语音代价、音节数（2-4 字最优）、首字是否高频音译起首字、
     * 同一序列内的备选字顺序。
     */
    private fun buildCandidates(name: String): List<String> {
        val seqs = beamSequences(name)
        if (seqs.isEmpty()) return buildSingleSyllableFallback(name)
        val scored = ArrayList<ScoredForm>(seqs.size * 4)
        val seen = HashSet<String>(seqs.size * 4)
        for (ps in seqs) {
            if (ps.syllables.size < 1 || ps.syllables.size > 5) continue
            val base = sequenceRank(ps)
            val forms = formsFor(ps.syllables)
            for (k in forms.indices) {
                val form = forms[k]
                if (form.length < 2 || !seen.add(form)) continue
                if (form.any { it in BAD_CHARS }) {
                    seen.remove(form)
                    continue
                }
                var pen = base + 0.1 * k
                if (form[0] in NAME_INITIAL_CHARS) pen -= 1.3
                scored.add(ScoredForm(form, pen))
            }
        }
        if (scored.isEmpty()) return buildSingleSyllableFallback(name)
        scored.sortWith(compareBy({ it.cost }, { it.form }))
        val out = ArrayList<String>(MAX_CANDIDATES)
        for (s in scored) {
            if (out.isEmpty() || out[out.size - 1] != s.form) {
                if (!out.contains(s.form)) out.add(s.form)
            }
            if (out.size >= MAX_CANDIDATES) break
        }
        return out
    }

    private class ScoredForm(val form: String, val cost: Double)

    /** 单音节名（`Shire`、`Bree`）兜底：用同音节叠字造 2 字候选。 */
    private fun buildSingleSyllableFallback(name: String): List<String> {
        val seqs = beamSequences(name)
        val out = LinkedHashSet<String>()
        for (ps in seqs) {
            if (ps.syllables.size != 1) continue
            val chars = HANZI[ps.syllables[0]] ?: continue
            for (a in chars.take(2)) {
                for (b in chars.take(2)) {
                    val form = "$a$b"
                    if (form.length == 2 && form.none { it in BAD_CHARS }) out.add(form)
                    if (out.size >= MAX_CANDIDATES) return out.toList()
                }
            }
        }
        return out.toList()
    }

    private fun sequenceRank(ps: PinyinSeq): Double {
        val n = ps.syllables.size
        return ps.cost + Math.abs(n - 3) * 0.35 +
            (if (n == 1) 1.0 else 0.0) + (if (n >= 6) 0.8 else 0.0)
    }

    /** 一个拼音序列 -> 汉字串（笛卡尔积，每音节取前几个字）。 */
    private fun formsFor(syllables: List<String>): List<String> {
        var total = 1
        val opts = ArrayList<String>(syllables.size)
        for (s in syllables) {
            val chars = HANZI[s]
            if (chars.isNullOrEmpty()) return emptyList()
            val picked = chars.take(ALT_PER_SYLLABLE)
            opts.add(picked)
            total *= picked.length
            if (total > MAX_FORMS_PER_SEQ) return emptyList()
        }
        val out = ArrayList<String>(total)
        val buf = CharArray(opts.size)
        fun rec(i: Int) {
            if (i == opts.size) {
                out.add(String(buf))
                return
            }
            val cur = opts[i]
            for (k in cur.indices) {
                buf[i] = cur[k]
                rec(i + 1)
            }
        }
        rec(0)
        return out
    }

    /** beam 搜索：每个英文音节展开成多个拼音候选，保留总代价最低的若干序列。 */
    private fun beamSequences(name: String): List<PinyinSeq> {
        val syls = syllablesOf(name)
        if (syls.isEmpty()) return emptyList()
        val perSyllable = ArrayList<List<PinyinSeq>>(syls.size)
        for (i in syls.indices) {
            val v = parseSyllable(syls[i], i == syls.size - 1)
            if (v.isEmpty()) return emptyList()
            perSyllable.add(v.sortedBy { it.cost }.take(30))
        }
        var states = listOf(PinyinSeq(emptyList(), 0.0))
        for (variants in perSyllable) {
            val next = ArrayList<PinyinSeq>(states.size * variants.size)
            for (st in states) {
                for (v in variants) {
                    next.add(PinyinSeq(st.syllables + v.syllables, st.cost + v.cost))
                }
            }
            next.sortWith(compareBy({ it.cost }, { it.syllables.size }))
            states = if (next.size > BEAM_WIDTH) next.subList(0, BEAM_WIDTH) else next
        }
        return states
    }

    private const val BEAM_WIDTH = 1500

    // ──────────────────────────────────────────────────────────────
    //  骨架（近似对齐用）
    // ──────────────────────────────────────────────────────────────

    private fun skeletons(name: String): List<PinyinSeq> {
        skeletonCache[name]?.let { return it }
        val built = beamSequences(name)
            .filter { it.syllables.size in 1..6 }
            .take(MAX_SKELETONS)
        skeletonCache[name] = built
        return built
    }

    // ──────────────────────────────────────────────────────────────
    //  繁简归一
    // ──────────────────────────────────────────────────────────────

    /**
     * 繁 -> 简归一。
     *
     * 表只覆盖「本模块候选可能产出」的字（闭集），外加少量高频字。
     * 这样既够用（候选串一定能被匹配到），又不需要背负完整繁简表。
     * 未收录的字原样保留。
     */
    internal fun toSimplified(text: String): String {
        var needs = false
        for (c in text) {
            if (TRAD_TO_SIMP.containsKey(c)) {
                needs = true
                break
            }
        }
        if (!needs) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(TRAD_TO_SIMP[c] ?: c)
        }
        return sb.toString()
    }

    /**
     * 反查表：汉字 -> 可能的读音（简体 + 繁体写法都能查到同一组读音）。
     *
     * 用 `by lazy` 而不是直接初始化：它依赖在文件更后面声明的 [HANZI] /
     * [TRAD_TO_SIMP]，而 Kotlin 的 object 属性按**声明顺序**初始化，
     * 直接写会在数据表就绪前执行并抛 NPE。
     */
    private val charReadings: Map<Char, List<String>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val acc = HashMap<Char, MutableList<String>>(1024)
        for ((py, chars) in HANZI) {
            for (c in chars) {
                acc.getOrPut(c) { ArrayList(2) }.add(py)
            }
        }
        for ((t, s) in TRAD_TO_SIMP) {
            val readings = acc[s] ?: continue
            acc[t] = ArrayList(readings)
        }
        acc
    }

    // ──────────────────────────────────────────────────────────────
    //  近似对齐
    // ──────────────────────────────────────────────────────────────

    /**
     * 音节近似对齐：在句子中找一个长度等于骨架音节数的窗口，
     * 要求**平均每音节代价 ≤ [FUZZY_MAX_AVG_COST]** 且
     * **至少 [FUZZY_MIN_EXACT_SYLLABLES] 个音节精确匹配**。
     *
     * 后一条是关键：没有它，短骨架会在常见字上到处命中（实测误报高一个量级）。
     */
    private fun fuzzyMatch(simplified: String, skeletons: List<PinyinSeq>): Boolean {
        val n = simplified.length
        val readings = Array(n) { i -> charReadings[simplified[i]] ?: emptyList() }
        for (seq in skeletons) {
            val k = seq.syllables.size
            if (k < 1 || k > n) continue
            for (start in 0..(n - k)) {
                var total = 0.0
                var exact = 0
                var ok = true
                for (i in 0 until k) {
                    val rs = readings[start + i]
                    if (rs.isEmpty()) {
                        ok = false
                        break
                    }
                    var best = Double.MAX_VALUE
                    for (r in rs) {
                        val c = syllableCost(seq.syllables[i], r)
                        if (c != null && c < best) best = c
                    }
                    if (best == Double.MAX_VALUE) {
                        ok = false
                        break
                    }
                    if (best == 0.0) exact++
                    total += best
                }
                if (!ok) continue
                if (total / k <= FUZZY_MAX_AVG_COST && exact >= FUZZY_MIN_EXACT_SYLLABLES) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 两个音节之间的近似代价。0 = 完全相同；null = 完全无关。
     *
     * 声母相同看韵母差异；声母在同一「近音组」里给中等代价（l/n/r、s/sh/x……）。
     */
    private fun syllableCost(a: String, b: String): Double? {
        if (a == b) return 0.0
        val aParts = splitSyllable(a)
        val bParts = splitSyllable(b)
        var aOnset = aParts.first
        var aFinal = aParts.second
        var bOnset = bParts.first
        var bFinal = bParts.second
        // y/w 与零声母互通
        if (aOnset == "y" && bOnset.isEmpty()) {
            aOnset = ""
            if (aFinal == bFinal) return 0.15
        }
        if (bOnset == "y" && aOnset.isEmpty()) {
            bOnset = ""
            if (aFinal == bFinal) return 0.15
        }
        if (aOnset == "w" && bOnset.isEmpty() && aFinal == bFinal) return 0.2
        if (bOnset == "w" && aOnset.isEmpty() && aFinal == bFinal) return 0.2
        if (aOnset == bOnset) {
            if (aFinal == bFinal) return 0.0
            if (aFinal.isNotEmpty() && bFinal.isNotEmpty() && aFinal[0] == bFinal[0] &&
                Math.abs(aFinal.length - bFinal.length) <= 1
            ) {
                return 0.35
            }
            if (aFinal.isNotEmpty() && bFinal.isNotEmpty() && aFinal[0] == bFinal[0]) return 0.6
            if (aFinal.isNotEmpty() && bFinal.isNotEmpty() && aFinal.last() == bFinal.last() &&
                Math.abs(aFinal.length - bFinal.length) == 1
            ) {
                return 0.55
            }
            return 0.9
        }
        for (group in SYLLABLE_GROUPS) {
            if (aOnset in group && bOnset in group) {
                return if (aFinal == bFinal) 0.3 else 0.65
            }
        }
        return null
    }

    private fun splitSyllable(p: String): Pair<String, String> {
        for (o in listOf("zh", "ch", "sh")) {
            if (p.startsWith(o)) return o to p.substring(2)
        }
        if (p.isNotEmpty() && p[0] in "bpmfdtnlgkhjqxrzcsyw") return p[0].toString() to p.substring(1)
        return "" to p
    }


    // ══════════════════════════════════════════════════════════════
    //  数据表（由离线原型生成，逐条验证见 TransliterationTablesTest）
    // ══════════════════════════════════════════════════════════════

// ============ 数据表 A：拼音音节 -> 音译用字（简体，按音译惯例排序） ============
    private val HANZI: Map<String, String> = buildMap {
        put("a", "阿亚艾")
        put("ai", "爱艾埃哀")
        put("an", "安恩昂")
        put("ang", "昂安")
        put("ao", "奥欧傲")
        put("ba", "巴芭帕")
        put("bai", "拜白贝")
        put("ban", "班邦潘")
        put("bang", "邦彭庞")
        put("bao", "宝鲍包")
        put("bei", "贝北悲")
        put("ben", "本班彭")
        put("beng", "彭邦")
        put("bi", "比毕碧皮")
        put("bian", "边比安")
        put("biao", "彪标")
        put("bie", "别贝")
        put("bin", "宾斌")
        put("bing", "毕丙")
        put("bo", "博波伯")
        put("bu", "布卜普")
        put("ca", "卡察")
        put("cai", "凯赛采")
        put("can", "坎甘")
        put("cang", "坎康")
        put("cao", "考科")
        put("ce", "瑟策")
        put("ceng", "森曾")
        put("cha", "查察沙")
        put("chai", "柴夏")
        put("chan", "钱詹")
        put("chang", "昌强")
        put("chao", "超乔")
        put("che", "彻切")
        put("chen", "陈琴千")
        put("cheng", "成郑琴")
        put("chi", "奇契芝")
        put("chong", "崇琼")
        put("chu", "楚朱邱")
        put("chuan", "川传")
        put("chuang", "创庄")
        put("chui", "崔垂")
        put("chun", "春纯")
        put("ci", "齐慈西")
        put("cong", "康孔")
        put("cu", "库古")
        put("cui", "崔翠")
        put("cun", "昆村")
        put("cuo", "柯科")
        put("da", "达大塔")
        put("dai", "戴岱代")
        put("dan", "丹单旦")
        put("dang", "当唐")
        put("dao", "道陶多")
        put("de", "德得特")
        put("deng", "邓登")
        put("di", "迪帝底")
        put("dian", "迪安甸")
        put("diao", "迪奥刁")
        put("die", "迪耶蝶")
        put("ding", "丁定")
        put("dong", "东董")
        put("dou", "杜窦多")
        put("du", "杜督都")
        put("duan", "杜安端")
        put("dun", "敦顿")
        put("duo", "多朵杜铎")
        put("e", "埃厄额")
        put("en", "恩安")
        put("eng", "恩昂")
        put("er", "尔儿耳埃尔")
        put("fa", "法伐发")
        put("fan", "凡范芳")
        put("fang", "方芳")
        put("fei", "费菲斐")
        put("fen", "芬分")
        put("feng", "芬冯丰")
        put("fo", "佛弗福")
        put("fou", "福夫")
        put("fu", "夫弗福芙")
        put("ga", "加伽嘎")
        put("gai", "盖该")
        put("gan", "甘干冈")
        put("gang", "冈刚港")
        put("gao", "高戈")
        put("ge", "葛格哥")
        put("gei", "盖格")
        put("gen", "根庚")
        put("geng", "庚耿")
        put("gong", "龚贡孔")
        put("gou", "苟古勾")
        put("gu", "古咕库谷")
        put("gua", "瓜华")
        put("guan", "关冠")
        put("guang", "光广")
        put("gui", "桂贵归")
        put("gun", "衮")
        put("guo", "果郭过")
        put("ha", "哈夏")
        put("hai", "海亥")
        put("han", "汉罕汗")
        put("hang", "杭汉")
        put("hao", "豪郝霍")
        put("he", "赫荷何")
        put("hei", "黑海")
        put("hen", "亨汉")
        put("heng", "亨衡")
        put("hong", "宏洪红")
        put("hou", "侯霍")
        put("hu", "胡霍忽")
        put("hua", "华花瓦")
        put("huai", "怀淮")
        put("huan", "环欢")
        put("huang", "黄皇")
        put("hui", "惠辉费")
        put("hun", "洪罕")
        put("huo", "霍火侯")
        put("ji", "吉基季奇")
        put("jia", "佳加嘉")
        put("jian", "简健坚")
        put("jiang", "江姜强")
        put("jiao", "乔焦")
        put("jie", "杰捷介")
        put("jin", "金津")
        put("jing", "京景晶")
        put("jiu", "九久秋")
        put("ju", "朱居菊")
        put("juan", "娟君")
        put("jue", "爵觉")
        put("jun", "军君俊")
        put("ka", "卡咖加")
        put("kai", "凯开盖")
        put("kan", "坎康")
        put("kang", "康冈")
        put("kao", "考科")
        put("ke", "克柯科")
        put("ken", "肯坎")
        put("keng", "肯")
        put("kong", "孔康")
        put("kou", "库古")
        put("ku", "库古")
        put("kua", "夸")
        put("kuan", "宽")
        put("kuang", "匡康")
        put("kui", "魁")
        put("kun", "昆坤")
        put("kuo", "库科")
        put("la", "拉喇")
        put("lai", "莱赖来")
        put("lan", "兰蓝伦")
        put("lang", "朗郎兰")
        put("lao", "劳洛")
        put("le", "勒莱乐")
        put("lei", "雷蕾勒")
        put("leng", "冷伦")
        put("li", "里利李立雳黎理")
        put("lian", "连莲")
        put("liang", "梁良")
        put("liao", "廖里奥")
        put("lie", "列里耶")
        put("lin", "林琳麟")
        put("ling", "林灵凌")
        put("liu", "刘流柳")
        put("long", "隆龙朗")
        put("lou", "卢罗楼")
        put("lu", "鲁路露卢")
        put("luan", "卢安")
        put("lun", "伦龙")
        put("luo", "罗洛萝珞")
        put("ma", "马玛麻")
        put("mai", "麦迈")
        put("man", "曼满蔓")
        put("mang", "芒曼")
        put("mao", "毛茂莫")
        put("me", "梅")
        put("mei", "梅美玫")
        put("men", "门曼")
        put("meng", "孟梦蒙")
        put("mi", "米密弥")
        put("mian", "米安勉")
        put("miao", "米奥苗")
        put("mie", "米耶灭")
        put("min", "敏民闵")
        put("ming", "明鸣")
        put("miu", "缪")
        put("mo", "摩莫默魔")
        put("mou", "莫谋")
        put("mu", "姆穆木慕")
        put("na", "娜纳那")
        put("nai", "奈乃")
        put("nan", "南男")
        put("nang", "南")
        put("nao", "诺瑙")
        put("ne", "内涅")
        put("nei", "内奈")
        put("nen", "嫩南")
        put("neng", "南能")
        put("ni", "尼妮倪")
        put("nian", "尼安年")
        put("niang", "尼安")
        put("niao", "尼奥鸟")
        put("nie", "涅尼耶")
        put("nin", "宁")
        put("ning", "宁凝")
        put("niu", "纽牛")
        put("nong", "农浓")
        put("nu", "努奴")
        put("nuo", "诺挪娜")
        put("o", "欧奥")
        put("ou", "欧奥乌")
        put("pa", "帕巴")
        put("pai", "派拜")
        put("pan", "潘庞班")
        put("pang", "庞邦")
        put("pao", "保鲍")
        put("pei", "佩培贝")
        put("pen", "彭本")
        put("peng", "彭鹏朋邦")
        put("pi", "皮匹比")
        put("pian", "皮安片")
        put("piao", "皮奥彪")
        put("pie", "皮耶")
        put("pin", "聘品平")
        put("ping", "平萍聘")
        put("po", "波坡珀")
        put("pou", "波")
        put("pu", "普浦仆")
        put("qi", "奇齐琪契")
        put("qia", "夏卡")
        put("qian", "钱琴倩")
        put("qiang", "强羌")
        put("qiao", "乔巧")
        put("qie", "切")
        put("qin", "琴秦钦")
        put("qing", "庆青清")
        put("qiong", "琼")
        put("qiu", "邱秋裘")
        put("qu", "曲库瞿")
        put("quan", "泉全")
        put("que", "奎")
        put("qun", "昆群")
        put("ran", "兰冉")
        put("rang", "朗")
        put("rao", "劳饶")
        put("re", "雷热")
        put("ren", "伦仁任")
        put("reng", "伦")
        put("ri", "里日")
        put("rong", "荣龙蓉")
        put("rou", "罗柔")
        put("ru", "鲁如儒")
        put("ruan", "鲁安")
        put("rui", "瑞芮睿")
        put("run", "伦润")
        put("ruo", "罗若")
        put("sa", "萨沙撒")
        put("sai", "赛塞西")
        put("san", "桑山珊")
        put("sang", "桑尚")
        put("sao", "索")
        put("se", "瑟塞")
        put("sen", "森")
        put("seng", "森")
        put("sha", "沙莎厦")
        put("shai", "夏")
        put("shan", "山珊香")
        put("shang", "香尚商")
        put("shao", "萧少")
        put("she", "雪舍")
        put("shen", "森申神")
        put("sheng", "圣生盛")
        put("shi", "希施什士")
        put("shou", "修寿")
        put("shu", "休舒书")
        put("shua", "夏")
        put("shuang", "香双")
        put("shui", "水")
        put("shun", "顺荀")
        put("shuo", "修朔")
        put("si", "斯丝司")
        put("song", "宋松桑")
        put("sou", "索")
        put("su", "苏素速")
        put("suan", "苏安")
        put("sui", "苏伊")
        put("sun", "孙桑")
        put("suo", "索梭")
        put("ta", "塔他达")
        put("tai", "泰台戴")
        put("tan", "坦谭丹")
        put("tang", "汤唐堂")
        put("tao", "陶桃道")
        put("te", "特泰")
        put("teng", "腾登")
        put("ti", "堤蒂提")
        put("tian", "田天蒂安")
        put("tiao", "蒂奥条")
        put("tie", "铁蒂耶")
        put("ting", "汀廷丁")
        put("tong", "通童东")
        put("tou", "托")
        put("tu", "图杜吐")
        put("tuan", "图安")
        put("tui", "图伊")
        put("tun", "屯敦顿")
        put("tuo", "托陀拓")
        put("wa", "瓦娃华")
        put("wai", "瓦伊")
        put("wan", "万温汪")
        put("wang", "王旺汪")
        put("wei", "韦维威魏")
        put("wen", "文温汶")
        put("weng", "翁温")
        put("wo", "沃倭")
        put("wu", "乌伍武")
        put("xi", "西希席")
        put("xia", "夏霞")
        put("xian", "西安先仙")
        put("xiang", "香乡相")
        put("xiao", "萧小肖")
        put("xie", "谢协希")
        put("xin", "辛新欣")
        put("xing", "星兴辛")
        put("xiong", "雄")
        put("xiu", "修秀")
        put("xu", "许旭舒")
        put("xuan", "玄宣")
        put("xue", "雪薛")
        put("xun", "荀勋")
        put("ya", "亚雅牙")
        put("yan", "扬延晏")
        put("yang", "扬杨央")
        put("yao", "姚耀")
        put("ye", "耶叶也")
        put("yi", "伊易义依")
        put("yin", "因印音")
        put("ying", "英应莹")
        put("yo", "优")
        put("yong", "永勇雍")
        put("you", "尤友优")
        put("yu", "尤于宇玉")
        put("yuan", "元袁远")
        put("yue", "月约岳")
        put("yun", "云允")
        put("za", "萨扎")
        put("zai", "赛宰")
        put("zan", "赞桑")
        put("zang", "桑")
        put("zao", "佐")
        put("ze", "泽则瑟")
        put("zei", "泽")
        put("zen", "森")
        put("zeng", "曾增")
        put("zha", "查扎莎")
        put("zhai", "柴")
        put("zhan", "詹占")
        put("zhang", "张章")
        put("zhao", "赵昭")
        put("zhe", "哲泽")
        put("zhen", "珍真贞")
        put("zheng", "郑正")
        put("zhi", "芝志智")
        put("zhong", "仲钟")
        put("zhou", "周洲")
        put("zhu", "朱祖祝")
        put("zhuan", "朱安")
        put("zhuang", "庄壮")
        put("zhuo", "卓")
        put("zi", "兹子齐")
        put("zong", "宗桑")
        put("zou", "佐邹")
        put("zu", "祖苏")
        put("zui", "祖伊")
        put("zuo", "佐左")
    }

// ============ 数据表 B：繁体字形 -> 简体归一 ============
    private val TRAD_TO_SIMP: Map<Char, Char> = buildMap {
        put('亞', '亚')
        put('佔', '占')
        put('來', '来')
        put('個', '个')
        put('們', '们')
        put('倫', '伦')
        put('傑', '杰')
        put('傳', '传')
        put('儀', '仪')
        put('億', '亿')
        put('優', '优')
        put('兒', '儿')
        put('凱', '凯')
        put('則', '则')
        put('剛', '刚')
        put('創', '创')
        put('劉', '刘')
        put('劍', '剑')
        put('勞', '劳')
        put('勳', '勋')
        put('協', '协')
        put('員', '员')
        put('喬', '乔')
        put('單', '单')
        put('嚴', '严')
        put('園', '园')
        put('圓', '圆')
        put('圖', '图')
        put('團', '团')
        put('堅', '坚')
        put('壇', '坛')
        put('壯', '壮')
        put('夏', '夏')
        put('夢', '梦')
        put('奧', '奥')
        put('姆', '姆')
        put('婭', '娅')
        put('嬌', '娇')
        put('孫', '孙')
        put('寧', '宁')
        put('寬', '宽')
        put('寶', '宝')
        put('對', '对')
        put('岡', '冈')
        put('嶺', '岭')
        put('庫', '库')
        put('廣', '广')
        put('張', '张')
        put('強', '强')
        put('彌', '弥')
        put('彿', '佛')
        put('徹', '彻')
        put('愛', '爱')
        put('應', '应')
        put('懷', '怀')
        put('戲', '戏')
        put('捨', '舍')
        put('揚', '扬')
        put('擁', '拥')
        put('據', '据')
        put('於', '于')
        put('時', '时')
        put('書', '书')
        put('東', '东')
        put('楊', '杨')
        put('業', '业')
        put('樂', '乐')
        put('樓', '楼')
        put('歡', '欢')
        put('歸', '归')
        put('湯', '汤')
        put('溫', '温')
        put('滅', '灭')
        put('滿', '满')
        put('漁', '渔')
        put('漢', '汉')
        put('澤', '泽')
        put('濃', '浓')
        put('烏', '乌')
        put('無', '无')
        put('營', '营')
        put('爾', '尔')
        put('獸', '兽')
        put('理', '理')
        put('瑩', '莹')
        put('瑪', '玛')
        put('環', '环')
        put('瓊', '琼')
        put('當', '当')
        put('發', '发')
        put('盧', '卢')
        put('禰', '祢')
        put('禱', '祷')
        put('竇', '窦')
        put('範', '范')
        put('簡', '简')
        put('約', '约')
        put('紅', '红')
        put('紋', '纹')
        put('納', '纳')
        put('紐', '纽')
        put('純', '纯')
        put('絆', '绊')
        put('絲', '丝')
        put('絳', '绛')
        put('綠', '绿')
        put('綢', '绸')
        put('綬', '绶')
        put('維', '维')
        put('綺', '绮')
        put('綿', '绵')
        put('緋', '绯')
        put('緒', '绪')
        put('緯', '纬')
        put('練', '练')
        put('緹', '缇')
        put('繆', '缪')
        put('繩', '绳')
        put('續', '续')
        put('羅', '罗')
        put('義', '义')
        put('習', '习')
        put('聖', '圣')
        put('臟', '脏')
        put('與', '与')
        put('興', '兴')
        put('茲', '兹')
        put('莊', '庄')
        put('萊', '莱')
        put('萬', '万')
        put('葉', '叶')
        put('蓋', '盖')
        put('蓮', '莲')
        put('薩', '萨')
        put('藍', '蓝')
        put('藝', '艺')
        put('蘇', '苏')
        put('蘭', '兰')
        put('蘿', '萝')
        put('裡', '里')
        put('覺', '觉')
        put('許', '许')
        put('該', '该')
        put('說', '说')
        put('諾', '诺')
        put('謀', '谋')
        put('謝', '谢')
        put('譯', '译')
        put('讚', '赞')
        put('豐', '丰')
        put('貝', '贝')
        put('貞', '贞')
        put('貢', '贡')
        put('貴', '贵')
        put('費', '费')
        put('賀', '贺')
        put('賴', '赖')
        put('賽', '赛')
        put('贏', '赢')
        put('趙', '赵')
        put('躍', '跃')
        put('軍', '军')
        put('輝', '辉')
        put('農', '农')
        put('這', '这')
        put('連', '连')
        put('遊', '游')
        put('運', '运')
        put('過', '过')
        put('達', '达')
        put('遠', '远')
        put('邁', '迈')
        put('鄉', '乡')
        put('鄒', '邹')
        put('鄧', '邓')
        put('鄭', '郑')
        put('醫', '医')
        put('銀', '银')
        put('錢', '钱')
        put('錦', '锦')
        put('鍾', '钟')
        put('鐘', '钟')
        put('鐵', '铁')
        put('鐸', '铎')
        put('門', '门')
        put('開', '开')
        put('閔', '闵')
        put('閱', '阅')
        put('關', '关')
        put('陰', '阴')
        put('陳', '陈')
        put('陽', '阳')
        put('隱', '隐')
        put('雙', '双')
        put('雜', '杂')
        put('雲', '云')
        put('靂', '雳')
        put('靈', '灵')
        put('韋', '韦')
        put('頁', '页')
        put('順', '顺')
        put('預', '预')
        put('頓', '顿')
        put('題', '题')
        put('額', '额')
        put('願', '愿')
        put('顫', '颤')
        put('顯', '显')
        put('餘', '余')
        put('馬', '马')
        put('馮', '冯')
        put('騰', '腾')
        put('鬥', '斗')
        put('魚', '鱼')
        put('魯', '鲁')
        put('鮑', '鲍')
        put('鳥', '鸟')
        put('鳴', '鸣')
        put('鵬', '鹏')
        put('鷹', '鹰')
        put('麗', '丽')
        put('麥', '麦')
        put('麼', '么')
        put('黃', '黄')
        put('齊', '齐')
        put('龍', '龙')
        put('龐', '庞')
        put('龔', '龚')
    }

// ============ 数据表 C：普通话合法音节（共 410 个） ============
    private val VALID_PINYIN: Set<String> = hashSetOf(
        "a", "ai", "an", "ang", "ao", "ba", "bai", "ban", "bang", "bao", "bei", "ben",
        "beng", "bi", "bian", "biao", "bie", "bin", "bing", "bo", "bu", "ca", "cai", "can",
        "cang", "cao", "ce", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen",
        "cheng", "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo",
        "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo", "da", "dai", "dan", "dang",
        "dao", "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu",
        "dong", "dou", "du", "duan", "dui", "dun", "duo", "e", "ei", "en", "eng", "er",
        "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu", "ga", "gai", "gan",
        "gang", "gao", "ge", "gei", "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan",
        "guang", "gui", "gun", "guo", "ha", "hai", "han", "hang", "hao", "he", "hei", "hen",
        "heng", "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo", "ji",
        "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu", "ju", "juan", "jue",
        "jun", "ka", "kai", "kan", "kang", "kao", "ke", "kei", "ken", "keng", "kong", "kou",
        "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo", "la", "lai", "lan", "lang",
        "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao", "lie", "lin", "ling",
        "liu", "lo", "long", "lou", "lu", "luan", "lun", "luo", "lü", "lüe", "ma", "mai",
        "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie", "min",
        "ming", "miu", "mo", "mou", "mu", "na", "nai", "nan", "nang", "nao", "ne", "nei",
        "nen", "neng", "ni", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou",
        "nu", "nuan", "nuo", "nü", "nüe", "o", "ou", "pa", "pai", "pan", "pang", "pao",
        "pei", "pen", "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu",
        "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan",
        "que", "qun", "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru",
        "rua", "ruan", "rui", "run", "ruo", "sa", "sai", "san", "sang", "sao", "se", "sen",
        "seng", "sha", "shai", "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou",
        "shu", "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su",
        "suan", "sui", "sun", "suo", "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti",
        "tian", "tiao", "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo", "wa",
        "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu", "xi", "xia", "xian", "xiang",
        "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu", "xuan", "xue", "xun", "ya", "yan",
        "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong", "you", "yu", "yuan", "yue",
        "yun", "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha", "zhai",
        "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong", "zhou", "zhu", "zhua",
        "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou", "zu", "zuan", "zui",
        "zun", "zuo",
    )
    // ──────────────────────────────────────────────────────────────
    //  常量表
    // ──────────────────────────────────────────────────────────────

    private val ACCENT_FOLD: Map<Char, Char> = buildMap {
        val from = "ûüúùëéèêóöòôáàäíìîīāåøñçß"
        val to = "uuuuueeeeoooooaaaaaiaiiaoonss"
        for (i in from.indices) put(from[i], to[i])
    }

    /** 英文声母拼写 -> 拼音声母候选。 */
    private val SINGLE_ONSET: Map<String, List<String>> = buildMap {
        put("b", listOf("b")); put("p", listOf("p"))
        put("d", listOf("d")); put("t", listOf("t"))
        put("g", listOf("g")); put("k", listOf("k")); put("c", listOf("k"))
        put("q", listOf("k")); put("m", listOf("m"))
        put("n", listOf("n")); put("l", listOf("l")); put("r", listOf("l"))
        put("f", listOf("f")); put("v", listOf("w"))
        put("z", listOf("z")); put("s", listOf("s"))
        put("sh", listOf("sh")); put("ch", listOf("q")); put("th", listOf("s"))
        put("ph", listOf("f")); put("wh", listOf("w")); put("h", listOf("h"))
        put("j", listOf("j")); put("w", listOf("w")); put("y", listOf("y"))
        put("x", listOf("k")); put("", listOf(""))
        put("gn", listOf("n")); put("kn", listOf("n")); put("wr", listOf("l"))
        put("qu", listOf("k")); put("gh", listOf("g")); put("dh", listOf("d"))
        put("kh", listOf("h")); put("bh", listOf("b")); put("ts", listOf("c"))
    }

    /** 辅音簇 -> 主辅音（流音单独成音节或合流）。 */
    private val MERGED_LIQUID: Map<String, String> = buildMap {
        put("br", "b"); put("pr", "p"); put("dr", "d"); put("tr", "t")
        put("gr", "g"); put("kr", "k"); put("cr", "k"); put("fr", "f")
        put("vr", "w"); put("bl", "b"); put("pl", "p"); put("gl", "g")
        put("kl", "k"); put("cl", "k"); put("fl", "f"); put("sl", "s")
        put("vl", "w"); put("shr", "sh"); put("thr", "s"); put("chr", "q")
        put("phr", "f"); put("str", "s"); put("spr", "s"); put("scr", "s")
        put("skr", "s")
    }

    /** 辅音簇的插入元音（中文习惯在两辅音间插一个元音）。 */
    private val PROSTHETIC: Map<String, List<String>> = buildMap {
        put("b", listOf("bu", "bo")); put("p", listOf("pu", "po"))
        put("d", listOf("de", "di")); put("t", listOf("te", "ti"))
        put("g", listOf("ge", "gu")); put("k", listOf("ke", "ku"))
        put("f", listOf("fu", "fo")); put("w", listOf("wo", "wu"))
        put("s", listOf("si", "su")); put("sh", listOf("shi"))
        put("q", listOf("qi")); put("v", listOf("wo"))
    }

    /** 英文韵母拼写 -> 拼音韵母候选（含代价）。 */
    private val FINAL: Map<String, List<FinalChoice>> = buildMap {
        fun p(k: String, vararg v: Pair<String, Double>) {
            put(k, v.map { FinalChoice(it.first, it.second) })
        }
        p("a", "a" to 0.0, "ai" to 2.0)
        p("e", "e" to 0.0, "ai" to 2.0, "i" to 3.0)
        p("i", "i" to 0.0, "ai" to 2.0)
        p("o", "uo" to 0.0, "o" to 0.0, "ou" to 1.0, "u" to 2.0)
        p("u", "u" to 0.0, "ou" to 2.0)
        p("y", "i" to 0.0)
        p("ai", "ai" to 0.0, "ei" to 1.0)
        p("ay", "ei" to 0.0, "ai" to 1.0)
        p("ei", "ei" to 0.0, "ai" to 1.0)
        p("ey", "ei" to 0.0, "i" to 1.0)
        p("ea", "i" to 0.0, "ei" to 1.0)
        p("ee", "i" to 0.0)
        p("ie", "i" to 0.0, "ai" to 1.0)
        p("oa", "o" to 0.0, "ou" to 1.0)
        p("oo", "u" to 0.0, "ou" to 1.0)
        p("ou", "u" to 0.0, "ou" to 1.0)
        p("ow", "o" to 0.0, "ou" to 1.0)
        p("oi", "o" to 0.0)
        p("oy", "o" to 0.0)
        p("ui", "wei" to 0.0, "ui" to 1.0)
        p("au", "ao" to 0.0)
        p("aw", "ao" to 0.0)
        p("ue", "u" to 0.0)
        p("eu", "you" to 0.0, "u" to 1.0)
        p("ia", "ia" to 0.0, "a" to 2.0)
        p("io", "o" to 0.0)
        p("iu", "you" to 0.0, "iu" to 1.0)
        p("ua", "wa" to 0.0, "a" to 2.0)
        p("uo", "uo" to 0.0, "o" to 1.0)
    }

    private val NASAL_N: Map<String, String> = buildMap {
        put("a", "an"); put("o", "ong"); put("e", "en"); put("i", "in")
        put("u", "un"); put("ai", "an"); put("ei", "en"); put("ao", "ang")
        put("ou", "ong"); put("uo", "ong"); put("ia", "ian"); put("ie", "ian")
        put("io", "ong"); put("iu", "iong"); put("ua", "uan"); put("ya", "yan")
        put("yi", "yin"); put("yu", "yun"); put("wa", "wan"); put("wo", "weng")
        put("wei", "wen"); put("you", "yong")
    }

    private val NASAL_ALT: Map<String, String> = buildMap {
        put("a", "ang"); put("e", "an"); put("i", "en"); put("o", "an")
        put("u", "ong"); put("ai", "ang"); put("ei", "an"); put("ao", "ang")
        put("ou", "ang"); put("uo", "an")
    }

    private val NASAL_NG: Map<String, String> = buildMap {
        put("a", "ang"); put("o", "ong"); put("e", "eng"); put("i", "ing")
        put("u", "ong"); put("ai", "ang"); put("ei", "eng"); put("ao", "ang")
        put("ou", "ong"); put("uo", "ong"); put("ia", "iang"); put("ie", "iang")
        put("io", "iong"); put("iu", "iong"); put("ua", "uang")
    }

    /** 韵尾辅音 -> 成音节拼音。 */
    private val STOP_SYLLABLE: Map<String, String> = buildMap {
        put("f", "fu"); put("s", "si"); put("k", "ke"); put("t", "te")
        put("d", "de"); put("p", "pu"); put("c", "ke"); put("g", "ge")
        put("b", "bu"); put("h", "he"); put("z", "si"); put("x", "ke si")
        put("sh", "shi"); put("ch", "qi"); put("th", "si")
    }

    /** 边音并入韵母（-al -> ao、-el -> ei……）。 */
    private val L_MERGE: Map<String, List<String>> = buildMap {
        put("a", listOf("ao", "a")); put("e", listOf("ei", "e"))
        put("i", listOf("i", "ei")); put("o", listOf("ou", "u"))
        put("u", listOf("u")); put("ai", listOf("ai")); put("ei", listOf("ei"))
        put("ao", listOf("ao")); put("ou", listOf("ou"))
        put("uo", listOf("uo", "o")); put("ia", listOf("ia"))
        put("ie", listOf("ie")); put("ua", listOf("ua"))
    }

    private val LIQUID_R: Map<String, String> = buildMap {
        put("a", "la"); put("e", "le"); put("i", "li"); put("o", "luo")
        put("u", "lu"); put("ai", "lai"); put("ei", "lei"); put("ao", "lao")
        put("ou", "lu"); put("uo", "luo"); put("an", "lan"); put("en", "len")
        put("in", "lin"); put("on", "long"); put("un", "lun"); put("ang", "lang")
        put("eng", "leng"); put("ing", "ling"); put("ong", "long")
    }

    private val LIQUID_L: Map<String, String> = buildMap {
        put("a", "la"); put("e", "le"); put("i", "li"); put("o", "luo")
        put("u", "lu"); put("ai", "lai"); put("ei", "lei"); put("ao", "lao")
        put("ou", "lou"); put("uo", "luo"); put("an", "lan"); put("en", "len")
        put("in", "lin"); put("ang", "lang"); put("eng", "leng"); put("ing", "ling")
        put("ong", "long")
    }

    private val LIQUID_W: Map<String, String> = buildMap {
        put("a", "wa"); put("e", "wei"); put("i", "wei"); put("o", "wo")
        put("u", "wu"); put("ai", "wai"); put("ei", "wei"); put("an", "wan")
        put("en", "wen"); put("in", "wen"); put("ang", "wang"); put("eng", "weng")
    }

    /** 零声母拼写。 */
    private val ZERO_INITIAL: Map<String, String> = buildMap {
        put("a", "a"); put("ai", "ai"); put("an", "an"); put("ang", "ang")
        put("ao", "ao"); put("e", "e"); put("ei", "ei"); put("en", "en")
        put("eng", "eng"); put("er", "er"); put("o", "o"); put("ou", "ou")
        put("i", "yi"); put("ia", "ya"); put("ian", "yan"); put("iang", "yang")
        put("iao", "yao"); put("ie", "ye"); put("in", "yin"); put("ing", "ying")
        put("io", "yo"); put("iong", "yong"); put("iu", "you")
        put("u", "wu"); put("ua", "wa"); put("uai", "wai"); put("uan", "wan")
        put("uang", "wang"); put("ue", "yue"); put("ui", "wei"); put("un", "wen")
        put("uo", "wo")
    }

    /** g/k/h/z/c/s + i 的颚化替换。 */
    private val PALATAL: Map<String, String> = mapOf(
        "g" to "j", "k" to "q", "h" to "x", "z" to "j", "c" to "q", "s" to "x",
    )

    private val LABIAL_INITIALS = setOf("b", "p", "m", "f")

    private val LABIAL_REPAIR: Map<String, List<Pair<String, Double>>> = buildMap {
        put("uo", listOf("o" to 1.0))
        put("ong", listOf("eng" to 1.0, "ang" to 2.0))
        put("ui", listOf("ei" to 2.0))
        put("un", listOf("en" to 2.0))
        put("uai", listOf("ai" to 2.0))
        put("uan", listOf("an" to 2.0))
    }

    /** 通用韵母兜底（`%FIN%` 代表原韵母的鼻音互换）。 */
    private val GENERIC_REPAIR: List<Pair<String, Double>> = listOf(
        "%NG2N%" to 1.0, "%N2NG%" to 1.0, "%F2%" to 2.0, "%F1%" to 3.0,
        "%FI%" to 3.0, "%FN%" to 2.0, "uo" to 2.0, "o" to 2.0, "ou" to 2.0,
        "u" to 3.0, "e" to 3.0, "a" to 3.0, "i" to 3.0, "ai" to 3.0, "ao" to 3.0,
        "an" to 3.0, "ang" to 3.0, "en" to 3.0, "eng" to 3.0, "ong" to 3.0,
        "ei" to 3.0,
    )

    private val FALLBACK_FINALS = listOf(
        "i", "u", "e", "a", "o", "ai", "ao", "an", "ang", "en", "eng", "ong", "ou", "ei",
    )

    /** 声母近音组（近似对齐用）。 */
    private val SYLLABLE_GROUPS: List<Set<String>> = listOf(
        setOf("s", "sh", "x"), setOf("ch", "q", "c"), setOf("zh", "j", "z"),
        setOf("l", "n", "r"), setOf("f", "h"), setOf("b", "p"), setOf("d", "t"),
        setOf("g", "k"),
    )

    /** 音译里几乎不会出现的字（虚词/极常用功能字），命中即排除。 */
    private val BAD_CHARS: Set<Char> =
        "的了是在和有我你他她它们个不也就都这那要会对说很被把给让从因而所之其此".toSet()

    /** 高频音译起首字：名字以这些字开头更符合中文音译惯例。 */
    private val NAME_INITIAL_CHARS: Set<Char> =
        ("阿埃厄安恩欧奥爱艾伊亚尤因英延扬雅叶优瓦万王韦维威温文沃武翁西希" +
            "夏谢辛星修许舒玄雪荀勋杨牙佛甘山比萨索波凯洛摩马迪戴瑞汤庞葛勒" +
            "梅皮金贝唐康兰劳雷卢鲁曼美弥米莫纳娜南尼诺努帕潘培彭普奇齐钱乔" +
            "切琴庆邱曲泉赛桑瑟沙尚舍申施史苏塔泰坦陶特提田汀通图").toSet()

    /** 英文常用词/称谓/出版信息，避免被当成专名。 */
    private val STOP_WORDS: Set<String> = buildSet {
        addAll(
            listOf(
                "the", "a", "an", "and", "or", "but", "if", "of", "to", "in", "on",
                "at", "by", "for", "with", "from", "as", "is", "are", "was", "were",
                "be", "been", "being", "it", "its", "he", "she", "they", "them",
                "his", "her", "their", "our", "your", "my", "me", "you", "we", "us",
                "i", "this", "that", "these", "those", "there", "here", "then",
                "than", "so", "not", "no", "nor", "do", "does", "did", "done",
                "have", "has", "had", "will", "would", "shall", "should", "can",
                "could", "may", "might", "must", "one", "two", "three", "four",
                "five", "six", "seven", "eight", "nine", "ten", "mr", "mrs",
                "miss", "sir", "lord", "lady", "king", "queen", "chapter", "book",
                "part", "page", "note", "text", "foreword", "prologue", "who",
                "what", "when", "where", "why", "how", "all", "any", "both",
                "each", "few", "more", "most", "other", "some", "such", "only",
                "own", "same", "too", "very", "just", "now", "also", "yet",
                "still", "even", "well", "up", "down", "out", "off", "over",
                "under", "again", "further", "once", "yes", "though", "suddenly",
                "come", "let", "took", "many", "soon", "before", "indeed", "good",
                "nothing", "master", "great", "old", "black", "dark", "white",
                "wise", "enemy", "riders", "company", "river", "road", "forest",
                "mountains", "end", "after", "let", "yet", "day", "days", "night",
                "sun", "moon", "sea", "east", "west", "north", "south", "hall",
                "hill", "council", "shadow", "doom", "fellowship", "elves",
                "dwarves", "hobbits", "orcs", "men", "elf", "ring", "rings",
                "bag", "bridge", "ford", "grey", "misty", "pine", "brand",
            )
        )
    }

    // ──────────────────────────────────────────────────────────────
    //  @JvmStatic 调试入口（单测与评估用）
    // ──────────────────────────────────────────────────────────────

    /** 调试用：返回英文名切出的音节。 */
    internal fun debugSyllables(englishName: String): List<String> = syllablesOf(englishName)

    /** 调试用：返回英文名归一化后的形式。 */
    internal fun debugNormalize(englishName: String): String = normalizeName(englishName)

    /** 调试用：候选缓存当前大小（验证缓存有效）。 */
    internal fun debugCacheSize(): Int = candidateCache.size

    /** 调试用：清空缓存。 */
    internal fun debugClearCache() {
        candidateCache.clear()
        skeletonCache.clear()
        syllablesCache.clear()
    }
}
