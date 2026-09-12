package com.linguareader.app.tts

import java.io.File

/**
 * TTS 缓存的**原子写**：先写同目录临时文件，再 `renameTo` 到最终路径。
 *
 * 为什么必须原子（第四轮审查 6-1/6-2）：缓存命中判据只看「文件存在且 >0 字节」。
 * 直接写最终路径时，断网 / IO 异常 / 进程被杀 / 协程取消会留下半截 mp3，而它**永久**
 * 命中，用户反复听到残缺音频且系统不会自我修复；同时等待方只等 `file.exists()`，
 * 可能把正在写的半成品交给 `MediaPlayer`。改成「临时文件 + 原子改名」后，最终路径
 * 一旦出现就是完整文件，命中判据不需要变。
 *
 * `renameTo` 在 Android `filesDir`（ext4/f2fs）上是原子的（第四轮审查 §10.2 第 11 条
 * 标注该前提未在设备上实测）；失败时回退直写，与项目既有落盘纪律一致
 * （`AiTranslationRepository` / `BookGlossaryRepository` 同款）。
 */
internal fun writeAudioAtomically(target: File, write: (File) -> Unit) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, target.name + ".tmp")
    try {
        write(temp)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    } finally {
        // 失败路径不留残骸；成功改名后 temp 已不存在，delete 是 no-op。
        if (temp.exists()) temp.delete()
    }
}
