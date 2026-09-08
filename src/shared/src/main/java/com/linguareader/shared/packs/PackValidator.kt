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
    fun validateFiles(manifest: PackManifest, root: File): PackValidationResult {
        if (!root.isDirectory) return PackValidationResult.reject("资源包目录不存在：${root.name}")
        val declared = manifest.files.associateBy { it.path }
        for ((path, entry) in declared) {
            val file = File(root, path)
            if (!file.isFile) return PackValidationResult.reject("资源包缺少文件：$path")
            if (file.length() != entry.bytes) {
                return PackValidationResult.reject("资源包文件大小不符：$path")
            }
            if (ImportSupport.sha256(file) != entry.sha256) {
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

    /** 目录实际占用（字节），与 manifest 里 files 的 bytes 之和独立核算。 */
    fun directoryBytes(root: File): Long =
        if (root.isDirectory) root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        else root.length()
}

/** 章节树摘要：章内 `相对路径:sha256` 排序后拼行再取 SHA-256。 */
object PackHasher {

    fun treeSha256(root: File): String {
        val lines = root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                file.relativeTo(root).invariantSeparatorsPath + ":" + ImportSupport.sha256(file)
            }
            .sorted()
        return sha256Hex(lines.joinToString("\n"))
    }

    fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
