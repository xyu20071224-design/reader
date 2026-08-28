package com.linguareader.app.ai

import android.content.Context
import com.linguareader.app.R
import com.linguareader.app.data.Book
import com.linguareader.app.data.Chapter
import com.linguareader.app.data.escapeHtml
import com.linguareader.app.tts.TtsTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AI 整本书翻译（生成译本对照）的编排层。
 *
 * 流程：用 [TtsTextExtractor] 抽全书叶级段落（与对齐引擎完全同源）→
 * [AiBookTranslator.groupIntoBatches] 按段落分批 → 逐批调 DeepSeek（术语表注入 +
 * 上一批译文衔接）→ 批后自检 → **每批成功即原子写检查点**（仿
 * [SpeakerTagRepository]：tmp+rename+Mutex，一章多批各一个小文件）→
 * 全部完成后把译文写成 XHTML 章节文件，构造一个不上书架的合成 [Book]，
 * 由调用方交给 `TranslationMemoryRepository.attachGenerated` 对齐落盘。
 *
 * 中断恢复是硬需求：整本书几十次串行请求在手机上是小时级，检查点让
 * 取消、进程被杀、断网失败都只损失当前一批。
 */
class AiTranslationRepository(
    context: Context,
    private val settingsStore: AiSettingsStore,
    private val glossaryRepository: BookGlossaryRepository,
    /** 翻译批量出口工厂（测试注入假客户端用）。 */
    private val chatClientFactory: (AiSettings) -> AiTranslationChatClient =
        { AiTranslators.forSettings(it) }
) {
    private val appContext = context.applicationContext
    private val checkpointDir = File(appContext.filesDir, "ai/ai-translations")
    private val translationsDir = File(appContext.filesDir, "translations")
    private val mutex = Mutex()

    /** 确认框展示用的规模估算（全部是本地抽取，无出网）。 */
    data class Estimate(val chapters: Int, val batches: Int, val glossaryTerms: Int)

    suspend fun estimate(book: Book): Estimate = withContext(Dispatchers.IO) {
        val extractor = TtsTextExtractor()
        val batches = book.chapters.indices.sumOf { chapterIndex ->
            AiBookTranslator.groupIntoBatches(chapterIndex, extractor.chapter(book, chapterIndex).blocks).size
        }
        Estimate(
            chapters = book.chapters.size,
            batches = batches,
            glossaryTerms = glossaryRepository.load(book.id).entries.count { it.enabled }
        )
    }

    /**
     * 逐章逐批翻译整本书。译文章节文件写好后返回合成译本 [Book]（id 带
     * `ai-` 前缀，不进 `files/books/`、不上书架）；对齐由调用方接续完成。
     *
     * [onProgress] 在每批完成后回调已完成批次的百分比（0-100）。
     */
    suspend fun translateBook(
        book: Book,
        onProgress: suspend (percent: Int) -> Unit
    ): Book = withContext(Dispatchers.IO) {
        val settings = settingsStore.load()
        val client = chatClientFactory(settings)
        val glossary = glossaryRepository.load(book.id).entries.filter { it.enabled }
        val keepOriginalTerms = glossary.filter { it.translation.isBlank() }.map { it.term }
        val extractor = TtsTextExtractor()
        val checkpoints = File(checkpointDir, book.id)

        // 先做全书的批次规划（纯本地），进度才有确定的总数。
        val chapterBatches = book.chapters.indices.map { chapterIndex ->
            val blocks = extractor.chapter(book, chapterIndex).blocks
            AiBookTranslator.groupIntoBatches(chapterIndex, blocks)
        }
        val totalBatches = chapterBatches.sumOf { it.size }.coerceAtLeast(1)
        var finishedBatches = 0
        var tail: String? = null

        val translatedChapters = chapterBatches.mapIndexed { chapterIndex, batches ->
            val chapter = book.chapters[chapterIndex]
            val paragraphs = batches.flatMap { batch ->
                val translations = restoreOrTranslate(
                    client, checkpoints, book, chapter, glossary, keepOriginalTerms, tail, batch
                )
                tail = translations.last()
                finishedBatches++
                onProgress(finishedBatches * 100 / totalBatches)
                translations
            }
            paragraphs
        }
        writeTranslationBook(book, translatedChapters)
    }

    /** 删除一本书的全部翻译检查点（删书时随书清理）。 */
    fun delete(bookId: String) {
        if (bookId.isBlank()) return
        File(checkpointDir, bookId).deleteRecursively()
    }

    /** 命中有效检查点直接复用；否则调 DeepSeek 翻译（自检失败带原因重试一次）。 */
    private suspend fun restoreOrTranslate(
        client: AiTranslationChatClient,
        checkpoints: File,
        book: Book,
        chapter: Chapter,
        glossary: List<GlossaryEntry>,
        keepOriginalTerms: List<String>,
        tail: String?,
        batch: TranslationBatch
    ): List<String> {
        val hash = AiBookTranslator.sourceHash(batch.paragraphs)
        readCheckpoint(checkpoints, batch, hash)?.let { return it }
        return translateWithRetry(
            client, book, chapter, glossary, keepOriginalTerms, tail, batch, checkpoints, hash,
            retryError = null
        )
    }

    private suspend fun translateWithRetry(
        client: AiTranslationChatClient,
        book: Book,
        chapter: Chapter,
        glossary: List<GlossaryEntry>,
        keepOriginalTerms: List<String>,
        tail: String?,
        batch: TranslationBatch,
        checkpoints: File,
        hash: String,
        retryError: String?
    ): List<String> {
        val user = AiBookTranslator.buildUserPrompt(
            bookTitle = book.title,
            chapterTitle = chapter.title,
            glossary = glossary,
            previousTail = tail,
            batch = batch,
            retryError = retryError
        )
        val json = try {
            client.translateSegments(AiBookTranslator.TRANSLATION_SYSTEM_PROMPT, user)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (retryError == null) {
                return translateWithRetry(
                    client, book, chapter, glossary, keepOriginalTerms, tail, batch,
                    checkpoints, hash, retryError = "请求失败：${error.message}"
                )
            }
            throw error
        }
        return try {
            val translations = AiBookTranslator.extractValidated(json, batch, keepOriginalTerms)
            storeCheckpoint(checkpoints, batch, hash, translations)
            translations
        } catch (error: AiRequestException) {
            if (retryError == null) {
                translateWithRetry(
                    client, book, chapter, glossary, keepOriginalTerms, tail, batch,
                    checkpoints, hash, retryError = error.message
                )
            } else {
                throw error
            }
        }
    }

    // --- 检查点 --------------------------------------------------------------

    private fun checkpointFile(dir: File, batch: TranslationBatch): File =
        File(dir, "${batch.chapterIndex}-${batch.batchIndex}.json")

    private fun readCheckpoint(dir: File, batch: TranslationBatch, hash: String): List<String>? {
        val file = checkpointFile(dir, batch)
        if (!file.isFile) return null
        val stored = runCatching {
            val json = JSONObject(file.readText())
            if (json.optString("sourceHash") != hash) return null
            val array = json.optJSONArray("paragraphs") ?: return null
            (0 until array.length()).map { array.optString(it) }
        }.getOrNull() ?: return null
        // 段数对不上说明检查点属于旧版批次规划，作废重翻。
        return stored.takeIf { it.size == batch.paragraphs.size && it.all(String::isNotBlank) }
    }

    private suspend fun storeCheckpoint(
        dir: File,
        batch: TranslationBatch,
        hash: String,
        translations: List<String>
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = checkpointFile(dir, batch)
            file.parentFile?.mkdirs()
            val json = JSONObject()
                .put("sourceHash", hash)
                .put("paragraphs", JSONArray(translations))
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    // --- 译文落盘 ------------------------------------------------------------

    /**
     * 把逐章译文写成 XHTML（每段一个 `<p>`，与英文侧叶级块 1:1，标题不进正文
     * 以免多出原文没有的段落），并构造不上书架的合成译本 [Book]。
     */
    private fun writeTranslationBook(source: Book, translatedChapters: List<List<String>>): Book {
        val dir = File(translationsDir, translationId(source.id))
        dir.deleteRecursively()
        dir.mkdirs()
        val chapters = translatedChapters.mapIndexed { index, paragraphs ->
            val title = source.chapters[index].title.ifBlank { "第 ${index + 1} 章" }
            val file = File(dir, "chapter_%03d.xhtml".format(index))
            file.writeText(xhtmlFor(title, paragraphs), Charsets.UTF_8)
            Chapter(
                title = title,
                relativePath = file.relativeTo(dir).invariantSeparatorsPath
            )
        }
        return Book(
            id = translationId(source.id),
            title = appContext.getString(R.string.translation_ai_title),
            author = "DeepSeek",
            extractedDir = dir.absolutePath,
            coverRelativePath = null,
            chapters = chapters,
            addedAt = System.currentTimeMillis(),
            sourceFormat = "epub"
        )
    }

    private fun xhtmlFor(title: String, paragraphs: List<String>): String {
        val body = paragraphs.filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${escapeHtml(it)}</p>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml"><head>
<meta charset="UTF-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"/>
<title>${escapeHtml(title)}</title>
</head><body>
${body.ifBlank { "<p></p>" }}
</body></html>"""
    }

    companion object {
        /** 合成译本的 book id / 目录名前缀。`remove()` 靠它整目录删除。 */
        fun translationId(sourceBookId: String): String = "ai-$sourceBookId"
    }
}
