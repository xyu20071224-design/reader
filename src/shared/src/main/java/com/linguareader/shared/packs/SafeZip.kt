package com.linguareader.shared.packs

import java.io.File
import java.util.zip.ZipFile

/**
 * 安全的 ZIP 解压 —— 书籍导入（EPUB）与资源包安装共用这一份护栏。
 *
 * 为什么单独成对象：这段逻辑原来只在 `EpubImporter` 里，资源包安装再抄一份就会
 * 出现「一处修了另一处没修」的经典漂移，而它守的是路径穿越与解压炸弹 —— 漂移的
 * 代价是安全漏洞。两处调用方用不同阈值，但规则只有一份。
 *
 * 规则：
 * - 每个条目落盘前用 `canonicalPath` 校验必须落在目标目录内（拦 `../`、绝对路径）；
 * - 条目数上限；
 * - 解压总量上限，**按实际写出的字节数**累计（zip 头里的 size 是攻击者可控的）。
 */
object SafeZip {

    /** `C:` 这类盘符前缀 —— Android 上不会出现，出现即视为畸形条目。 */
    private val DRIVE_LETTER = Regex("^[A-Za-z]:")

    /**
     * 解压 [zip] 到 [destination]（不存在则创建），返回实际写出的字节数。
     *
     * [label] 只用于错误消息前缀（如 "EPUB"），不影响行为。失败时**不清理**
     * 已写出的内容 —— 调用方负责删掉半成品目录（两处调用方都这么做）。
     */
    fun extract(
        zip: File,
        destination: File,
        maxEntries: Int,
        maxBytes: Long,
        label: String = "压缩包"
    ): Long {
        val rootPath = destination.canonicalPath + File.separator
        var totalBytes = 0L
        var entryCount = 0
        ZipFile(zip).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                require(entryCount <= maxEntries) { "$label 文件条目过多" }

                // 绝对路径 / 反斜杠 / 盘符：畸形条目当场拒绝（canonicalPath 检查其实
                // 已能拦住穿越，但把「本来就不该出现的形态」显式拦掉，报错更准确）。
                val name = entry.name
                require(
                    name.isNotEmpty() &&
                        !name.startsWith('/') &&
                        !name.startsWith('\\') &&
                        !name.contains('\\') &&
                        !DRIVE_LETTER.containsMatchIn(name)
                ) { "检测到不安全的文件路径" }

                val output = File(destination, entry.name)
                require(output.canonicalPath.startsWith(rootPath)) { "检测到不安全的文件路径" }

                if (entry.isDirectory) {
                    output.mkdirs()
                    continue
                }

                // 不信任 zip 头里的 size（攻击者可控）：只按实际写出的字节数计额。
                output.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    output.outputStream().use { out ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            totalBytes += read
                            require(totalBytes <= maxBytes) { "解压内容超过 ${humanBytes(maxBytes)}" }
                        }
                    }
                }
            }
        }
        return totalBytes
    }

    private fun humanBytes(bytes: Long): String {
        val gb = 1024L * 1024L * 1024L
        val mb = 1024L * 1024L
        val kb = 1024L
        return when {
            bytes % gb == 0L -> "${bytes / gb}GB"
            bytes % mb == 0L -> "${bytes / mb}MB"
            bytes % kb == 0L -> "${bytes / kb}KB"
            else -> "${bytes}B"
        }
    }
}
