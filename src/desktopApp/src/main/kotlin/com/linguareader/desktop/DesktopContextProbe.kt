package com.linguareader.desktop

import com.linguareader.shared.data.Book
import com.linguareader.shared.data.Chapter
import com.linguareader.shared.data.ContextualDictionaryEntry
import com.linguareader.shared.data.PartOfSpeech
import com.linguareader.shared.data.ReviewMode
import com.linguareader.shared.data.VocabularyRepository
import com.linguareader.shared.data.WordLookup
import com.linguareader.shared.data.DictionarySense
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * 桌面侧数据平台面的端到端冒烟探针（迁移方案 M2 验收项，刀5）。
 *
 * 与 M1 的 [DictionaryProbe]（sqlite-jdbc 读词典）互补：这一步验证的是
 * 「`:shared` 数据层在桌面真实落盘」——`DesktopAppContext`（JSON prefs +
 * filesDir）喂给 `VocabularyRepository`，走 save → load → review → csv →
 * deleteBookData 全链路。产品价值无关，目的是让「数据层平台替换」的桌面风险
 * 在写第一行桌面 UI 之前见底。
 *
 * 跑法：`.\toolchain\build.ps1 :desktopApp:run "-PmainClass=com.linguareader.desktop.DesktopContextProbeKt"`
 *
 * TODO(M4): :desktopApp 正式化后，本文件与探针式路径引用一并删除或改造成设置页诊断。
 */
fun main() {
    val home = Files.createTempDirectory("lr-smoke-").toFile()
    println("临时家目录：${home.absolutePath}")
    val context = DesktopAppContext(home)

    // ① prefs 往返 + 跨实例持久（与 DesktopAppContextTest 同一断言，端到端再证一遍）。
    context.prefs("review_settings").putString("review_mode", "DILIGENT")
    check(DesktopAppContext(home).prefs("review_settings").getString("review_mode") == "DILIGENT") {
        "prefs 跨实例持久失败"
    }
    println("① prefs 往返 + 跨实例持久 OK")

    // ② 生词本仓库在桌面真实落盘。
    val book = Book(
        id = "smoke-book",
        title = "Smoke Book",
        author = "",
        extractedDir = "",
        coverRelativePath = null,
        chapters = listOf(Chapter(title = "One", relativePath = "ch-0.html")),
        addedAt = 42L
    )
    val lookup = WordLookup(
        word = "running",
        sentence = "She is running fast.",
        paragraph = "She is running fast.",
        sentenceOffset = 0,
        x = 0f,
        y = 0f
    )
    val entry = ContextualDictionaryEntry(
        surfaceWord = "running",
        headword = "run",
        phonetic = "rʌn",
        senses = listOf(DictionarySense("v. 跑", PartOfSpeech.VERB, contextPreferred = true)),
        definitions = listOf("move fast on foot"),
        matchedPhrase = null,
        inferredPartOfSpeech = PartOfSpeech.VERB
    )
    runBlocking {
        val repository = VocabularyRepository(context)
        val saved = repository.save(book, "One", lookup, entry, mode = ReviewMode.GENTLE)
        check(saved.size == 1) { "save 后生词数应为 1，实际 ${saved.size}" }

        val loaded = repository.load()
        check(loaded.single().headword == "run") { "落盘内容不符：${loaded.single()}" }

        val reviewed = repository.review(loaded.single().id, remembered = true, pace = ReviewMode.GENTLE.toPace())
        check(reviewed.single().reviewLevel == 1) { "复习推进失败：${reviewed.single()}" }

        val csv = VocabularyRepository.csv(reviewed)
        // appendLine 结尾带换行，lines() 会多一个尾部空串，按非空行数断言。
        check(csv.lines().count { it.isNotBlank() } == 2 && "run" in csv) { "csv 导出异常：$csv" }

        repository.deleteBookData(book)
        check(repository.load().isEmpty()) { "删书清生词失败" }
        println("② 生词本 save/load/review/csv/deleteBookData OK（csv 首行词: run，复习后 level=1）")
    }

    // ③ 文件布局：prefs 与生词本都应落在 filesDir 下。
    val listing = home.walkTopDown().filter { it.isFile }.map { it.relativeTo(home).path }.toList()
    println("③ filesDir 文件布局：$listing")

    home.deleteRecursively()
    println("OK：:shared 数据层（AppContext 平台面 + VocabularyRepository）在桌面端到端跑通。")
}
