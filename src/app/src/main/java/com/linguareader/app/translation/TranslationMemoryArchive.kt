package com.linguareader.app.translation

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Android min 23 可用的档案事务；写入或提交前检查失败时恢复最后一个完整版本。 */
internal class TranslationMemoryArchive(
    private val atomicFile: (File) -> AtomicFile = { AtomicFile(it) }
) {
    fun exists(file: File): Boolean = file.isFile || hasBackup(file)

    fun hasBackup(file: File): Boolean = File(file.path + ".bak").isFile

    fun read(file: File): String = atomicFile(file).openRead().use { input ->
        input.readBytes().toString(Charsets.UTF_8)
    }

    fun delete(file: File) {
        atomicFile(file).delete()
        // 上一版自制写入器留下的临时文件不属于任何已提交档案。
        File(file.path + ".tmp").delete()
    }

    fun write(file: File, text: String, beforeCommit: () -> Unit) {
        val archive = atomicFile(file)
        val bytes = text.toByteArray(Charsets.UTF_8)
        var output: FileOutputStream? = null
        var finished = false
        try {
            output = archive.startWrite()
            output.write(bytes)
            output.fd.sync()
            beforeCommit()
            archive.finishWrite(output)
            finished = true
            // 平台 finishWrite 在 rename 失败时只记录日志，不返回失败状态。
            // 真正读回新字节之前不能发布索引或向调用方报告保存成功。
            if (!archive.readFully().contentEquals(bytes)) {
                throw IOException("Translation archive commit did not publish the new contents")
            }
        } catch (failure: Throwable) {
            // finishWrite 之后不能再 failWrite：旧平台可能将已提交的主文件删掉。
            // 读回失败时保留仍可读的旧/新完整版本，让调用方报告失败而非更新缓存。
            if (!finished && output != null) archive.failWrite(output)
            throw failure
        }
    }
}
