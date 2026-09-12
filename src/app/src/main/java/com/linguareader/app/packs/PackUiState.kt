package com.linguareader.app.packs

import com.linguareader.shared.packs.InstalledPack
import com.linguareader.shared.packs.PackPayload
import com.linguareader.shared.packs.PackType

/** 资源包页面的一行。载荷差异用可空子对象表达，界面按类型分支渲染。 */
data class PackUiItem(
    val packId: String,
    val type: PackType,
    val nameZh: String,
    val nameEn: String,
    val version: String,
    val bytes: Long,
    /** 当前生效的词典包（全局只有一个）。 */
    val active: Boolean,
    val installedAt: Long,
    val dictionary: DictionaryInfo? = null,
    val audio: AudioInfo? = null,
    val voice: VoiceInfo? = null
) {
    data class DictionaryInfo(
        val wordCount: Int?,
        val source: String,
        val license: String
    )

    /** [bookTitle] 为 null = 书已不在书库（包还在，可听但无从起播）。 */
    data class AudioInfo(
        val bookId: String,
        val bookTitle: String?,
        val voice: String,
        val engineTag: String,
        val chapters: Int,
        val pipelineVersion: Int
    )

    data class VoiceInfo(val count: Int, val cloneCount: Int)
}

/** 资源包页面的整体状态。 */
data class PackUiState(
    val items: List<PackUiItem> = emptyList(),
    /**
     * 全部包的**磁盘实测**字节数（`PackRepository.totalBytes()` 扫盘），与存储页同源。
     *
     * 审查 6-14 / 7-13：此前这里取「各条 manifest 声明字节之和」，而存储页取扫盘实测，
     * 两处口径不同 ⇒ 同一批包在两个页面显示不同数字。注意它**不等于** `items.sumOf { it.bytes }`：
     * 实测含每包的 `manifest.json` 等清单外文件，故总数会略大于各行之和，界面已注明口径。
     */
    val totalBytes: Long = 0L,
    /** 安装中（SAF 回来到落位完成之间）；界面禁用安装按钮并显示进度。 */
    val installing: Boolean = false,
    /** 正在重哈希校验的包 id（手动「验证完整性」）。 */
    val verifying: String? = null,
    /**
     * 本次加载的降级提示；null = 一切正常。
     *
     * 两类静默失败必须显式告诉用户，否则「查词静默回内置、界面却写着已启用」：
     * [LoadWarning.REGISTRY_DAMAGED]（登记表损坏 → 按空表加载）与
     * [LoadWarning.ACTIVE_DICTIONARY_MISSING]（活动词典包文件缺失 → 当前用内置词典）。
     */
    val loadWarning: LoadWarning? = null
) {
    val dictionaryPack: PackUiItem? get() = items.firstOrNull { it.type == PackType.DICTIONARY && it.active }
}

/** 资源包加载降级原因；文案映射在界面层（`packs_load_warning_*`）。 */
enum class LoadWarning {
    REGISTRY_DAMAGED,
    ACTIVE_DICTIONARY_MISSING
}

fun InstalledPack.toUiItem(active: Boolean, bookTitle: String?): PackUiItem = PackUiItem(
    packId = packId,
    type = type,
    nameZh = nameZh,
    nameEn = nameEn,
    version = version,
    bytes = bytes,
    active = active,
    installedAt = installedAt,
    dictionary = (manifest.payload as? PackPayload.Dictionary)?.let {
        PackUiItem.DictionaryInfo(
            wordCount = it.wordCount,
            source = it.source,
            license = it.license
        )
    },
    audio = manifest.audioPayload?.let {
        PackUiItem.AudioInfo(
            bookId = it.bookId,
            bookTitle = bookTitle,
            voice = it.voice,
            engineTag = it.engineTag,
            chapters = it.chapters.size,
            pipelineVersion = it.pipelineVersion
        )
    },
    voice = manifest.voicePayload?.let { payload ->
        PackUiItem.VoiceInfo(
            count = payload.voices.size,
            cloneCount = payload.voices.count { it.mode == com.linguareader.shared.packs.PackVoice.MODE_CLONE }
        )
    }
)
