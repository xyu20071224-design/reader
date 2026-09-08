package com.linguareader.shared.packs

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 包在磁盘上的落位规则（安装器与读取方共用，别各自拼路径）。 */
object PackPaths {
    /** `<packsRoot>/<type>/<packId>/<version>`，相对 packsRoot 的路径。 */
    fun directoryFor(type: PackType, packId: String, version: String): String =
        "${type.wire}/$packId/$version"
}

/**
 * 一个已安装的包：磁盘位置 + 安装时间 + **完整 manifest**。
 *
 * 把 manifest 整份存进 registry（而不是只存几个字段）是刻意的：registry 是唯一
 * 真相源，两套平行状态体系（`AppViewModel` 与 TTS 链路）都只读它，不再各自去
 * 磁盘翻 manifest.json 一遍。
 */
data class InstalledPack(
    val dir: String,
    val installedAt: Long,
    val manifest: PackManifest
) {
    val packId: String get() = manifest.packId
    val type: PackType get() = manifest.type
    val version: String get() = manifest.version
    val nameZh: String get() = manifest.nameZh
    val nameEn: String get() = manifest.nameEn
    val schemaVersion: Int get() = manifest.schemaVersion

    /** 载荷总字节数（来自 manifest 清单，不用扫盘 —— 音频包动辄十万文件）。 */
    val bytes: Long get() = manifest.files.sumOf { it.bytes }

    fun root(packsRoot: File): File = File(packsRoot, dir)

    /** 词典包的词库文件；文件缺失时返回 null（调用方回退内置词典）。 */
    fun dictionaryFile(packsRoot: File): File? {
        val entry = manifest.dictionaryEntryFile ?: return null
        return File(root(packsRoot), entry).takeIf { it.isFile && it.length() > 0 }
    }
}

/**
 * 安装登记表 —— `filesDir/packs/registry.json` 的内存形态。
 *
 * 为什么不是「每次扫描目录」：目录里能看出有哪些包，看不出**哪个词典包是当前生效的**，
 * 也看不出安装时间与原始 manifest。扫描只能做对账，不能做状态。
 */
data class PackRegistry(
    /** 当前生效的词典包 id；null = 用内置词典。 */
    val activeDictionary: String? = null,
    val packs: List<InstalledPack> = emptyList()
) {
    fun ofType(type: PackType): List<InstalledPack> = packs.filter { it.type == type }

    fun byId(packId: String): InstalledPack? = packs.firstOrNull { it.packId == packId }

    fun activeDictionaryPack(): InstalledPack? = activeDictionary
        ?.let { id -> packs.firstOrNull { it.packId == id && it.type == PackType.DICTIONARY } }

    /** 同 packId 覆盖（升级场景）；不同 id 追加。 */
    fun upsert(pack: InstalledPack): PackRegistry =
        copy(packs = packs.filterNot { it.packId == pack.packId } + pack)

    fun remove(packId: String): PackRegistry = copy(
        packs = packs.filterNot { it.packId == packId },
        activeDictionary = activeDictionary.takeIf { it != packId }
    )

    fun withActiveDictionary(packId: String?): PackRegistry {
        if (packId != null && byId(packId)?.type != PackType.DICTIONARY) return this
        return copy(activeDictionary = packId)
    }

    fun toJson(): String = JSONObject()
        .put("version", FORMAT_VERSION)
        .apply { activeDictionary?.let { put("activeDictionary", it) } }
        .put("packs", JSONArray().apply {
            packs.forEach { pack ->
                put(
                    JSONObject()
                        .put("dir", pack.dir)
                        .put("installedAt", pack.installedAt)
                        .put("manifest", JSONObject(pack.manifest.toJson()))
                )
            }
        })
        .toString(2)

    companion object {
        const val FORMAT_VERSION = 1

        val EMPTY = PackRegistry()

        fun parse(text: String): PackRegistry {
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw PackFormatException("资源包登记表损坏（不是合法 JSON）")
            }
            val packs = root.optJSONArray("packs")?.let { array ->
                (0 until array.length()).map { index ->
                    val item = array.optJSONObject(index)
                        ?: throw PackFormatException("登记表第 ${index + 1} 项不是对象")
                    val dir = PackManifest.normalizePath(item.optString("dir").trim())
                    val manifest = PackManifest.parse(
                        (item.optJSONObject("manifest") ?: throw PackFormatException("登记表缺少 manifest"))
                            .toString()
                    )
                    InstalledPack(
                        dir = dir,
                        installedAt = item.optLong("installedAt", 0L),
                        manifest = manifest
                    )
                }
            }.orEmpty()
            return PackRegistry(
                activeDictionary = root.optString("activeDictionary").trim().takeIf { it.isNotEmpty() },
                packs = packs
            )
        }
    }
}

/**
 * 词典文件解析链（方案 §3）：**活动包 → null（=内置）**。
 *
 * `filesDir/dictionary/` 那份既有拷贝不在这里出现：它只是内置 assets 的落盘副本，
 * 活动包存在时根本不该被创建（否则白占 58 MB）。包文件缺失时返回 null 让调用方
 * 回退内置 —— 离线优先意味着「包坏了也不能把查词弄哑」，宁可静默降级。
 */
object DictionarySource {
    fun resolve(registry: PackRegistry, packsRoot: File): File? =
        registry.activeDictionaryPack()?.dictionaryFile(packsRoot)
}

/**
 * 音频包解析链（M3）：包（只读，永不淘汰）优先于 `tts_cache`。
 *
 * 同一本书可能装了多个音色/引擎的包，[relativePath] 里含引擎与音色段，所以按序
 * 试到第一个命中的文件即可；都没命中返回 null，调用方继续走缓存/现场合成。
 */
object AudioPackSource {
    const val PAYLOAD_DIR = "audio"

    fun resolve(
        registry: PackRegistry,
        packsRoot: File,
        bookId: String,
        relativePath: String
    ): File? {
        val candidates = registry.ofType(PackType.AUDIO).filter { pack ->
            pack.manifest.audioPayload?.bookId == bookId
        }
        for (pack in candidates) {
            val file = File(pack.root(packsRoot), "$PAYLOAD_DIR/$relativePath")
            if (file.isFile && file.length() > 0) return file
        }
        return null
    }
}
