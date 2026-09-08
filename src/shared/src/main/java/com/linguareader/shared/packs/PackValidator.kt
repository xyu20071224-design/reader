package com.linguareader.shared.packs

import com.linguareader.shared.importer.ImportSupport
import java.io.File

/** 校验结论。失败消息面向用户，可直接进对话框/Snackbar。 */
sealed interface PackValidationResult {
    val ok: Boolean

    object Ok : PackValidationResult {
        override val ok: Boolean get() = true
    }

    data class Rejected(val reason: String) : PackValidationResult {
        override val ok: Boolean get() = false
    }

    companion object {
        fun reject(reason: String): PackValidationResult = Rejected(reason)
    }
}

/**
 * manifest 与磁盘内容对账。
 *
 * 分两层：**不读盘**的 manifest 自洽性（schema 版本、最低应用版本、载荷引用是否
 * 都在 files 里）在装包前就能判掉；**读盘**的逐文件哈希对账在解压后跑一次。
 * 加载时**不重哈希** —— 58 MB 词典每次打开都算一遍不可接受，改由资源包页面的
 * 「验证完整性」手动触发。
 */
object PackValidator {

    /** 不读盘：格式版本闸门 + 最低应用版本闸门 + 载荷引用自洽。 */
    fun validateManifest(manifest: PackManifest, appVersionCode: Int): PackValidationResult {
        val supported = PackSchema.supported(manifest.type)
        if (manifest.schemaVersion > supported) {
            return PackValidationResult.reject(
                "资源包格式版本 ${manifest.schemaVersion} 高于本应用支持的 $supported，请先升级应用"
            )
        }
        if (manifest.schemaVersion <= 0) {
            return PackValidationResult.reject("资源包格式版本不合法：${manifest.schemaVersion}")
        }
        if (manifest.minAppVersion > appVersionCode) {
            return PackValidationResult.reject(
                "该资源包需要更高版本的应用（需要 ${manifest.minAppVersion}，当前 $appVersionCode）"
            )
        }
        return PackValidationResult.Ok
    }

    /**
     * 读盘：逐文件 SHA-256 全量对账，fail-closed。
     *
     * 三个判据都不可省：缺文件、大小不符、哈希不符、**多出未登记的文件**。
     * 最后一个常被忘 —— 未登记的载荷文件永远不会被校验，正是夹带私货的入口。
     */
    fun validateFiles(
        manifest: PackManifest,
        root: File,
        /** 预先算好的 `路径 → sha256`（音频包安装时复用同一份做章节树对账，避免哈希两遍）。 */
        digests: Map<String, String>? = null
    ): PackValidationResult {
        if (!root.isDirectory) return PackValidationResult.reject("资源包目录不存在：${root.name}")
        val declared = manifest.files.associateBy { it.path }
        for ((path, entry) in declared) {
            val file = File(root, path)
            if (!file.isFile) return PackValidationResult.reject("资源包缺少文件：$path")
            if (file.length() != entry.bytes) {
                return PackValidationResult.reject("资源包文件大小不符：$path")
            }
            val actual = digests?.get(path) ?: ImportSupport.sha256(file)
            if (actual != entry.sha256) {
                return PackValidationResult.reject("资源包文件校验失败：$path")
            }
        }
        val extra = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it != PackManifest.FILE_NAME && it !in declared }
            .toList()
        if (extra.isNotEmpty()) {
            return PackValidationResult.reject("资源包含未登记的文件：${extra.first()}")
        }
        return PackValidationResult.Ok
    }

    /** 只对登记过的路径算哈希（不扫目录），供 `validateFiles` 与章节树对账共用。 */
    fun digests(root: File, paths: Collection<String>): Map<String, String> =
        paths.associateWith { ImportSupport.sha256(File(root, it)) }

    /**
     * 音频包的结构 + 章节树对账（方案 T3.1）。
     *
     * 逐文件哈希只证明「每个文件没坏」，证明不了「章目录与 manifest 对得上」——
     * 少一个章节目录、章内多塞一个未登记文件，文件哈希都是全对的。这里按
     * `audio/<章号>/` 分组复核文件数与树摘要。
     */
    fun validateAudioChapters(manifest: PackManifest, digests: Map<String, String>): PackValidationResult {
        val audio = manifest.audioPayload ?: return PackValidationResult.Ok
        val declaredChapters = audio.chapters.associateBy { it.index }
        val grouped = digests.keys
            .filter { it.startsWith(AudioPackSource.PAYLOAD_DIR + "/") }
            .groupBy { path ->
                path.removePrefix(AudioPackSource.PAYLOAD_DIR + "/").substringBefore('/').toIntOrNull()
            }
        if (grouped.containsKey(null)) {
            return PackValidationResult.reject("音频包里有无法识别的章节目录")
        }
        for ((index, files) in grouped) {
            val chapter = declaredChapters[index]
                ?: return PackValidationResult.reject("音频包清单里没有第 $index 章")
            if (files.size != chapter.files) {
                return PackValidationResult.reject(
                    "第 $index 章文件数与清单不符（清单 ${chapter.files}，实际 ${files.size}）"
                )
            }
            val prefix = "${AudioPackSource.PAYLOAD_DIR}/$index"
            val actual = PackHasher.treeSha256(prefix, digests)
            if (chapter.treeSha256.isNotBlank() && actual != chapter.treeSha256) {
                return PackValidationResult.reject("第 $index 章内容与清单不符")
            }
        }
        val missing = declaredChapters.keys.filter { it !in grouped.keys }
        if (missing.isNotEmpty()) {
            return PackValidationResult.reject("音频包缺少第 ${missing.min()} 章")
        }
        return PackValidationResult.Ok
    }

    /** 目录实际占用（字节），与 manifest 里 files 的 bytes 之和独立核算。 */
    fun directoryBytes(root: File): Long =
        if (root.isDirectory) root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        else root.length()
}

/** 章节树摘要：章内 `相对路径:sha256` 排序后拼行再取 SHA-256。 */
object PackHasher {

    /** 直接扫目录算树摘要（包生成工具与单测用）。 */
    fun treeSha256(root: File): String = treeSha256(
        prefix = "",
        digests = root.walkTopDown()
            .filter { it.isFile }
            .associate { it.relativeTo(root).invariantSeparatorsPath to ImportSupport.sha256(it) }
    )

    /**
     * 从**已有的** `路径 → sha256` 表算某个子树摘要，路径相对 [prefix]。
     *
     * 应用侧安装音频包时用它：逐文件哈希只算一遍，章节摘要从同一份结果推出来。
     * [prefix] 为空表示整棵树。
     */
    fun treeSha256(prefix: String, digests: Map<String, String>): String {
        val base = if (prefix.isBlank()) "" else prefix.trimEnd('/') + "/"
        val lines = digests
            .filterKeys { it.startsWith(base) }
            .map { (path, sha) -> path.removePrefix(base) + ":" + sha }
            .sorted()
        return sha256Hex(lines.joinToString("\n"))
    }

    fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
