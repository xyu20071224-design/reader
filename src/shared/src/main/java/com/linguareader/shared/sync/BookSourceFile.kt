package com.linguareader.shared.sync

import com.linguareader.shared.data.Book
import java.io.File

/**
 * 书籍正文（blob）同步的**源文件留存**契约（task-3 上传侧）。
 *
 * 导入器（Android facade）在导入成功后把原始文件拷成 [fileNameFor] 放进该书目录
 * （`booksDir/<id>/source.<ext>`），随删书被整体清理；上传时用 [locate] 找回。
 * 老书（本功能之前导入的）没有留存文件 → [locate] 返回 null，调用方静默跳过上传。
 *
 * 纯逻辑、无平台依赖，可直接单测。
 */
object BookSourceFile {

    const val PREFIX = "source"

    /** sourceFormat → 扩展名；未知格式退化为 `bin`（服务端按内容寻址，不依赖扩展名）。 */
    fun extensionFor(sourceFormat: String): String = when (sourceFormat.trim().lowercase()) {
        "epub" -> "epub"
        "txt" -> "txt"
        "fb2" -> "fb2"
        "pdf" -> "pdf"
        else -> "bin"
    }

    fun fileNameFor(sourceFormat: String): String = PREFIX + "." + extensionFor(sourceFormat)

    /**
     * 这本书留存的源文件；未留存、文件缺失或为空时返回 null。
     * 注意 [Book.extractedDir] 为空串（老数据/异常记录）时同样返回 null。
     */
    fun locate(book: Book): File? {
        val dir = book.extractedDir.takeIf { it.isNotBlank() } ?: return null
        val file = File(dir, fileNameFor(book.sourceFormat))
        return file.takeIf { it.isFile && it.length() > 0L }
    }
}
