package com.linguareader.shared.packs

import java.io.File

/**
 * 音色包（M4）的资源解析。
 *
 * 两种模式，语义不同，别混：
 * - **metadata**：`key` 是**既有音色 id**（自建服务器的裸 id，如 `narrator.wav`），
 *   包只是给它补语言/性别/风格，替代 `VoiceNaming` 的 id 形状猜测。它**不产生新音色**，
 *   也不参与合成。
 * - **clone**：`key` 是新音色的短名，进入音色库时加命名空间 `pack:<packId>/<key>`，
 *   与 server / MiMo 的 id 天然不撞。样本随包分发，合成时由引擎读取。
 *
 * 命名空间用 `/` 分隔是安全的：`TtsCacheKey.voiceSegment` 对含 `/` 的 id 会换成哈希，
 * 缓存目录不会被穿越（单射性由 [TtsCacheKey] 的测试守住）。
 */
object VoicePackSource {

    const val ID_PREFIX = "pack:"

    fun idFor(packId: String, key: String): String = "$ID_PREFIX$packId/$key"

    /** 一个可用的克隆音色：包身份 + 已解析到磁盘的样本。 */
    data class CloneVoice(
        val packId: String,
        val packName: String,
        val packRoot: File,
        val voice: PackVoice
    ) {
        val id: String get() = idFor(packId, voice.key)

        val sampleFile: File? get() = voice.sample
            ?.let { File(packRoot, it) }
            ?.takeIf { it.isFile && it.length() > 0 }
    }

    /** 全部克隆音色（样本缺失的也返回，由调用方决定是否展示）。 */
    fun cloneVoices(registry: PackRegistry, packsRoot: File): List<CloneVoice> =
        registry.ofType(PackType.VOICE).flatMap { pack ->
            val payload = pack.manifest.voicePayload ?: return@flatMap emptyList()
            payload.voices
                .filter { it.mode == PackVoice.MODE_CLONE }
                .map { CloneVoice(pack.packId, pack.nameZh.ifBlank { pack.nameEn }, pack.root(packsRoot), it) }
        }

    /**
     * metadata 音色：**目标音色 id（小写）→ 元数据**。
     *
     * 多个包描述同一个 id 时，后安装的（registry 列表顺序靠后）胜出 —— 与「用户刚装的那个
     * 是想生效的那个」直觉一致。
     */
    fun metadata(registry: PackRegistry): Map<String, PackVoice> =
        registry.ofType(PackType.VOICE)
            .flatMap { it.manifest.voicePayload?.voices.orEmpty() }
            .filter { it.mode == PackVoice.MODE_METADATA }
            .associateBy { it.key.lowercase() }

    /** `pack:<packId>/<key>` → 样本文件；不是音色包 id 或样本缺失时返回 null。 */
    fun sampleFile(registry: PackRegistry, packsRoot: File, voiceId: String): File? =
        cloneVoices(registry, packsRoot).firstOrNull { it.id == voiceId }?.sampleFile

    /** 该 id 是否属于音色包的克隆音色（引擎据此选克隆模型）。 */
    fun isPackVoice(voiceId: String): Boolean = voiceId.startsWith(ID_PREFIX)
}
