package com.linguareader.shared.packs

import com.linguareader.shared.importer.ImportSupport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 资源包（`.lrpack`）的格式契约 —— 见根目录 `方案-资源包系统.md` §3。
 *
 * 为什么放在 `:shared`：包解析/校验/路径解析是纯 JVM 逻辑，桌面端将来要复用同一份
 * 口径；放 `:app` 会让「应用能装、桌面不能装」的漂移从第一天就埋下。这里只允许
 * `org.json` 与 `java.*`，**禁止 `android.*`**（`:shared` 的硬约束）。
 *
 * 本文件是「manifest 长什么样」的唯一真相；安装管线的落位/registry 在
 * [PackRegistry]，磁盘对账在 [PackValidator]。
 */

/** manifest 里三种载荷类型。wire 值即 JSON 里的字符串，**不要改**（已发布包会失联）。 */
enum class PackType(val wire: String) {
    DICTIONARY("dictionary"),
    AUDIO("audio"),
    VOICE("voice");

    companion object {
        fun fromWire(value: String): PackType? =
            entries.firstOrNull { it.wire == value.trim().lowercase() }
    }
}

/**
 * 解压护栏阈值（方案 D2）。按类型分开：书籍导入那套「条目 ≤10,000 / 解压 ≤500 MB」
 * 是为单本书定的，整书音频包动辄十万级文件，硬套会把核心场景直接判死。
 */
data class PackLimits(
    val maxSourceBytes: Long,
    val maxEntries: Int,
    val maxUnzippedBytes: Long
) {
    companion object {
        private const val MB = 1024L * 1024L
        private const val GB = 1024L * MB

        fun of(type: PackType): PackLimits = when (type) {
            // 词典包：一个 58 MB 的 sqlite 加许可文件，10,000 条绰绰有余。
            PackType.DICTIONARY -> PackLimits(maxSourceBytes = 500 * MB, maxEntries = 10_000, maxUnzippedBytes = 1 * GB)
            // 音色包：样本音频，量级同词典包。
            PackType.VOICE -> PackLimits(maxSourceBytes = 500 * MB, maxEntries = 10_000, maxUnzippedBytes = 1 * GB)
            // 音频包：20 章 × 300 句 × 2 段就能超一万条，上限按整书量级放。
            PackType.AUDIO -> PackLimits(maxSourceBytes = 2 * GB, maxEntries = 200_000, maxUnzippedBytes = 4 * GB)
        }
    }
}

/** 载荷清单里的一项：相对路径 + SHA-256 + 字节数。 */
data class PackFileEntry(val path: String, val sha256: String, val bytes: Long)

/** 音频包的一章：章号 + 文件数 + 章内文件树摘要（避免 manifest 随句数膨胀）。 */
data class AudioChapter(val index: Int, val files: Int, val treeSha256: String)

/**
 * 音色包里的一个音色。
 *
 * [mode]：`metadata` = 只补元数据（自建服务器裸 id 靠它拿到语言/性别/风格）；
 * `clone` = 带克隆样本（当前只有 MiMo 走这条，样本内联进请求）。
 */
data class PackVoice(
    val key: String,
    val displayName: String,
    val language: String,
    val gender: String,
    val style: List<String>,
    val mode: String,
    val sample: String?
) {
    companion object {
        const val MODE_METADATA = "metadata"
        const val MODE_CLONE = "clone"
    }
}

/** 载荷字段，按 [PackType] 三选一。 */
sealed interface PackPayload {
    data class Dictionary(
        val entryFile: String,
        val wordCount: Int?,
        val source: String,
        val license: String
    ) : PackPayload

    data class Audio(
        val bookId: String,
        val engineTag: String,
        val voice: String,
        val pipelineVersion: Int,
        val chapters: List<AudioChapter>
    ) : PackPayload

    data class Voice(val voices: List<PackVoice>) : PackPayload
}

/** 本应用认识的最新载荷结构版本。加字段/改语义时 bump 对应类型。 */
object PackSchema {
    const val DICTIONARY = 1
    const val AUDIO = 1
    const val VOICE = 1

    fun supported(type: PackType): Int = when (type) {
        PackType.DICTIONARY -> DICTIONARY
        PackType.AUDIO -> AUDIO
        PackType.VOICE -> VOICE
    }
}

/** manifest 不合法（缺字段/字段格式错/载荷与类型不符）。消息面向用户，可直接进对话框。 */
class PackFormatException(message: String) : IllegalArgumentException(message)

/**
 * 一个资源包的 manifest。
 *
 * 注意 [version] 与 [packId] 都会被拼进磁盘路径，所以两者都按「目录名安全」的字符集
 * 校验 —— 恶意 manifest 用 `../` 当版本号就是一次路径穿越。
 */
data class PackManifest(
    val packId: String,
    val type: PackType,
    val version: String,
    val nameZh: String,
    val nameEn: String,
    val schemaVersion: Int,
    val minAppVersion: Int,
    val files: List<PackFileEntry>,
    val payload: PackPayload
) {
    val name: String get() = nameEn.ifBlank { nameZh }

    /** 词典包的词库文件（相对包根）；非词典包为 null。 */
    val dictionaryEntryFile: String? get() = (payload as? PackPayload.Dictionary)?.entryFile

    val audioPayload: PackPayload.Audio? get() = payload as? PackPayload.Audio
    val voicePayload: PackPayload.Voice? get() = payload as? PackPayload.Voice

    fun fileEntry(path: String): PackFileEntry? = files.firstOrNull { it.path == path }

    fun toJson(): String = JSONObject()
        .put("packId", packId)
        .put("type", type.wire)
        .put("version", version)
        .put("nameZh", nameZh)
        .put("nameEn", nameEn)
        .put("schemaVersion", schemaVersion)
        .put("minAppVersion", minAppVersion)
        .put("files", JSONArray().apply {
            files.forEach { entry ->
                put(
                    JSONObject()
                        .put("path", entry.path)
                        .put("sha256", entry.sha256)
                        .put("bytes", entry.bytes)
                )
            }
        })
        .put(
            when (val value = payload) {
                is PackPayload.Dictionary -> "dictionary"
                is PackPayload.Audio -> "audio"
                is PackPayload.Voice -> "voice"
            },
            payloadJson(payload)
        )
        .toString(2)

    private fun payloadJson(payload: PackPayload): JSONObject = when (payload) {
        is PackPayload.Dictionary -> JSONObject()
            .put("entryFile", payload.entryFile)
            .put("wordCount", payload.wordCount ?: 0)
            .put("source", payload.source)
            .put("license", payload.license)

        is PackPayload.Audio -> JSONObject()
            .put("bookId", payload.bookId)
            .put("engineTag", payload.engineTag)
            .put("voice", payload.voice)
            .put("pipelineVersion", payload.pipelineVersion)
            .put("chapters", JSONArray().apply {
                payload.chapters.forEach { chapter ->
                    put(
                        JSONObject()
                            .put("index", chapter.index)
                            .put("files", chapter.files)
                            .put("treeSha256", chapter.treeSha256)
                    )
                }
            })

        is PackPayload.Voice -> JSONObject().put("voices", JSONArray().apply {
            payload.voices.forEach { voice ->
                put(
                    JSONObject()
                        .put("key", voice.key)
                        .put("displayName", voice.displayName)
                        .put("language", voice.language)
                        .put("gender", voice.gender)
                        .put("style", JSONArray().apply { voice.style.forEach { put(it) } })
                        .put("mode", voice.mode)
                        .apply { voice.sample?.let { put("sample", it) } }
                )
            }
        })
    }

    companion object {
        const val FILE_NAME = "manifest.json"

        private val PACK_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
        private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        fun parse(text: String): PackManifest {
            val root = runCatching { JSONObject(text) }.getOrElse {
                throw PackFormatException("资源包 manifest.json 不是合法的 JSON")
            }
            val packId = root.requireString("packId")
            if (!PACK_ID.matches(packId)) {
                throw PackFormatException("资源包标识不合法：$packId")
            }
            val type = PackType.fromWire(root.requireString("type"))
                ?: throw PackFormatException("不认识的资源包类型：${root.optString("type")}")
            val version = root.requireString("version")
            if (!VERSION.matches(version)) {
                throw PackFormatException("资源包版本号不合法：$version")
            }
            val files = parseFiles(root)
            val payload = parsePayload(root, type, files)
            return PackManifest(
                packId = packId,
                type = type,
                version = version,
                nameZh = root.optString("nameZh").trim().ifBlank { root.optString("name").trim() },
                nameEn = root.optString("nameEn").trim(),
                schemaVersion = root.requireInt("schemaVersion"),
                minAppVersion = root.optInt("minAppVersion", 0),
                files = files,
                payload = payload
            ).also {
                if (it.nameZh.isBlank() && it.nameEn.isBlank()) {
                    throw PackFormatException("资源包缺少名称（nameZh/nameEn）")
                }
            }
        }

        private fun parseFiles(root: JSONObject): List<PackFileEntry> {
            val array = root.optJSONArray("files")
                ?: throw PackFormatException("资源包 manifest 缺少 files 清单")
            if (array.length() == 0) throw PackFormatException("资源包的 files 清单为空")
            val entries = (0 until array.length()).map { index ->
                val item = array.optJSONObject(index)
                    ?: throw PackFormatException("files 清单第 ${index + 1} 项不是对象")
                val path = normalizePath(item.requireString("path"))
                val sha = item.requireString("sha256").lowercase()
                if (!SHA256.matches(sha)) {
                    throw PackFormatException("文件校验值不合法：$path")
                }
                val bytes = item.optLong("bytes", -1L)
                if (bytes < 0) throw PackFormatException("文件缺少字节数：$path")
                PackFileEntry(path, sha, bytes)
            }
            val duplicate = entries.groupBy { it.path }.entries.firstOrNull { it.value.size > 1 }
            if (duplicate != null) throw PackFormatException("files 清单重复登记：${duplicate.key}")
            return entries
        }

        private fun parsePayload(
            root: JSONObject,
            type: PackType,
            files: List<PackFileEntry>
        ): PackPayload = when (type) {
            PackType.DICTIONARY -> {
                val json = root.optJSONObject("dictionary")
                    ?: throw PackFormatException("词典包缺少 dictionary 载荷")
                val entryFile = normalizePath(json.requireString("entryFile"))
                requireDeclared(entryFile, files, "entryFile")
                PackPayload.Dictionary(
                    entryFile = entryFile,
                    wordCount = json.optInt("wordCount", 0).takeIf { it > 0 },
                    source = json.optString("source").trim(),
                    license = json.optString("license").trim()
                )
            }

            PackType.AUDIO -> {
                val json = root.optJSONObject("audio")
                    ?: throw PackFormatException("音频包缺少 audio 载荷")
                val chapters = json.optJSONArray("chapters")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        val item = array.optJSONObject(index) ?: return@mapNotNull null
                        AudioChapter(
                            index = item.requireInt("index"),
                            files = item.optInt("files", 0),
                            treeSha256 = item.optString("treeSha256").trim()
                        )
                    }
                }.orEmpty()
                if (chapters.isEmpty()) throw PackFormatException("音频包没有登记任何章节")
                if (chapters.map { it.index }.toSet().size != chapters.size) {
                    throw PackFormatException("音频包章节号重复")
                }
                PackPayload.Audio(
                    bookId = json.requireString("bookId"),
                    engineTag = json.requireString("engineTag"),
                    voice = json.requireString("voice"),
                    pipelineVersion = json.requireInt("pipelineVersion"),
                    chapters = chapters.sortedBy { it.index }
                )
            }

            PackType.VOICE -> {
                val json = root.optJSONObject("voice")
                    ?: throw PackFormatException("音色包缺少 voice 载荷")
                val array = json.optJSONArray("voices")
                    ?: throw PackFormatException("音色包没有登记任何音色")
                val voices = (0 until array.length()).map { index ->
                    val item = array.optJSONObject(index)
                        ?: throw PackFormatException("voices 第 ${index + 1} 项不是对象")
                    val key = item.requireString("key")
                    if (!PACK_ID.matches(key)) throw PackFormatException("音色标识不合法：$key")
                    val mode = item.optString("mode", PackVoice.MODE_METADATA).trim()
                        .ifBlank { PackVoice.MODE_METADATA }
                    if (mode != PackVoice.MODE_METADATA && mode != PackVoice.MODE_CLONE) {
                        throw PackFormatException("音色模式不合法：$mode")
                    }
                    val sample = item.optString("sample").trim().takeIf { it.isNotEmpty() }
                        ?.let { normalizePath(it) }
                    if (mode == PackVoice.MODE_CLONE) {
                        if (sample == null) throw PackFormatException("克隆音色缺少样本文件：$key")
                        requireDeclared(sample, files, "sample")
                    }
                    PackVoice(
                        key = key,
                        displayName = item.optString("displayName").trim().ifBlank { key },
                        language = item.optString("language").trim(),
                        gender = item.optString("gender").trim(),
                        style = item.optJSONArray("style")?.let { styles ->
                            (0 until styles.length()).mapNotNull { position ->
                                styles.optString(position).trim().takeIf(String::isNotEmpty)
                            }
                        }.orEmpty(),
                        mode = mode,
                        sample = sample
                    )
                }
                if (voices.isEmpty()) throw PackFormatException("音色包没有登记任何音色")
                if (voices.map { it.key }.toSet().size != voices.size) {
                    throw PackFormatException("音色标识重复")
                }
                PackPayload.Voice(voices)
            }
        }

        private fun requireDeclared(path: String, files: List<PackFileEntry>, field: String) {
            if (files.none { it.path == path }) {
                throw PackFormatException("$field 指向的文件不在 files 清单里：$path")
            }
        }

        /**
         * 路径规范化 + 穿越拦截。
         *
         * 拦的是「解压后能写到包目录之外」的一切形态：绝对路径、`..` 段、反斜杠、
         * Windows 盘符、控制字符。manifest 里的路径**只在解压后**用于查找，所以这里是
         * 第二道闸（第一道在 [SafeZip] 解压时）。
         */
        fun normalizePath(raw: String): String {
            val value = raw.trim()
            if (value.isEmpty()) throw PackFormatException("资源包里有空路径")
            if (value.contains('\\')) throw PackFormatException("资源包路径不能含反斜杠：$value")
            if (value.startsWith('/')) throw PackFormatException("资源包路径不能是绝对路径：$value")
            if (value.any { it.code < 0x20 }) throw PackFormatException("资源包路径含控制字符：$value")
            val segments = value.split('/')
            if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
                throw PackFormatException("资源包路径不安全：$value")
            }
            return segments.joinToString("/")
        }
    }
}

private fun JSONObject.requireString(key: String): String {
    val value = optString(key).trim()
    if (value.isEmpty()) throw PackFormatException("资源包 manifest 缺少字段：$key")
    return value
}

private fun JSONObject.requireInt(key: String): Int {
    if (!has(key) || isNull(key)) throw PackFormatException("资源包 manifest 缺少字段：$key")
    val value = optInt(key, Int.MIN_VALUE)
    if (value == Int.MIN_VALUE) throw PackFormatException("资源包 manifest 字段不是整数：$key")
    return value
}
