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
    /** 全部包的载荷字节数（来自 manifest 清单，不扫盘）。 */
    val totalBytes: Long = 0L,
    /** 安装中（SAF 回来到落位完成之间）；界面禁用安装按钮并显示进度。 */
    val installing: Boolean = false,
    /** 正在重哈希校验的包 id（手动「验证完整性」）。 */
    val verifying: String? = null
) {
    val dictionaryPack: PackUiItem? get() = items.firstOrNull { it.type == PackType.DICTIONARY && it.active }
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
