package com.linguareader.shared.tts

/**
 * 朗读管线契约版本 —— **改切分就得 bump**。
 *
 * 为什么需要它：音频缓存（以及 M3 的预生成音频包）按 `s<句号>-<段号>` 命名，而
 * 「第 5 句」是哪段文本，取决于下面三处**没有类型系统保护**的等价契约：
 *
 * 1. [SentenceSplitter]（断句）—— 改终止符/缩写表/长句硬切都会改变句号；
 * 2. `ReaderScripts.TTS_BLOCK_SELECTOR`（JS）↔ `TtsTextExtractor`（Kotlin）的
 *    块选择器等价 —— 不等价则块号漂移，句号跟着漂；
 * 3. [QuoteSpans] / [TtsChapter] 的发言-旁白片段拆分 —— 改片段规则会改变段号。
 *
 * 任一处改动，**存量缓存的句子与文本就对不上了**，而且没有任何报错：用户听到的
 * 是另一句话，或者音频和正文错位。这个版本号就是唯一的闸门 ——
 * 它进入缓存键（见 [TtsCacheKey]），也进入音频包 manifest（`pipelineVersion`），
 * 不匹配的包会被拒载。
 *
 * 2026-09-06 的片段键变更（`5.mp3` → `s5-0.mp3`）就是一次没有版本号的漂移，
 * 代价是存量缓存全部作废却只能靠「改文件名」硬扛。别再来第二次。
 */
object TtsPipelineContract {

    /**
     * 当前管线版本。改动 [SentenceSplitter] / 块选择器 / 片段拆分后 **+1**，
     * 并同步更新 `方案-资源包系统.md` 的实施记录与 `.agents/memory/tts-architecture.md`。
     */
    const val VERSION = 1
}

/**
 * 音频缓存键的唯一实现（应用与包生成工具共用）。
 *
 * 布局：`<bookId>/<chapterIndex>/e<引擎哈希8位>~v<管线版本>~<音色段>/s<句>-<段>.mp3`。
 *
 * - 引擎那半截一律哈希：`server:http://…` 带路径分隔符，不能当目录名；
 * - 音色那半截「无损优先」（见 [voiceSegment]），方便肉眼排查；
 * - **管线版本在键里**：切分规则一变，旧目录自然不命中，不会再放出对不上文本的音频。
 *
 * 包生成工具（`scripts/build_audio_pack.py`）必须复刻同一串，否则打出来的包
 * 永远不命中 —— 这是「工具漂移」唯一能被人工检查的锚点。
 */
object TtsCacheKey {

    /** 淘汰单元/缓存目录名：`e<engineHash8>~v<pipelineVersion>~<voiceSegment>`。 */
    fun segmentDir(
        engineTag: String,
        voice: String,
        pipelineVersion: Int = TtsPipelineContract.VERSION
    ): String = "e" + sha256Hex(engineTag).take(8) + "~v" + pipelineVersion + "~" + voiceSegment(voice)

    /** 句内片段文件名：`s<sentenceIndex>-<segmentIndex>.mp3`。 */
    fun fileName(sentenceIndex: Int, segmentIndex: Int): String = "s$sentenceIndex-$segmentIndex.mp3"

    /** 相对书目录的路径（不含 bookId），音频包载荷也用同一串。 */
    fun relativePath(
        chapterIndex: Int,
        sentenceIndex: Int,
        segmentIndex: Int,
        engineTag: String,
        voice: String,
        pipelineVersion: Int = TtsPipelineContract.VERSION
    ): String = "$chapterIndex/${segmentDir(engineTag, voice, pipelineVersion)}/" +
        fileName(sentenceIndex, segmentIndex)

    /**
     * 音色 id → 目录名段。**必须是单射**，否则缓存会串音。
     *
     * 旧实现是 `voice.replace(Regex("[^A-Za-z0-9._-]"), "_")` —— 有损映射：两个不同音色
     * 可以消毒成同一个目录名（自建服务器把参考音频命名成「男声.wav」「女声.wav」，
     * 两者都变成同一串下划线），而命中判据只有「文件存在且非空」，于是**放出另一个
     * 音色的音频**，且永远发现不了。
     *
     * 现在只在「这个 id 当目录名会出事」时才改写，其余原样保留：
     * - 原样保留 ⇒ 单射显然成立（MiMo 的 `mimo-clone:<slug>`、系统音色名、
     *   常见服务器音色名都落在这一档）；
     * - 含路径分隔符 / 空串 / 单点 / 双点 的才换成哈希 —— 这几种当目录名会穿越或
     *   指错地方，哈希同时保证单射。
     */
    fun voiceSegment(voice: String): String {
        val unusable = voice.isEmpty() ||
            voice == "." ||
            voice == ".." ||
            voice.any { it == '/' || it == '\\' || it == '\u0000' }
        if (!unusable) return voice
        return "h-" + sha256Hex(voice).take(16)
    }

    fun sha256Hex(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
