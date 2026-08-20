package com.linguareader.app.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导入用户挑选的 Piper `.onnx` 模型，供离线朗读使用。
 *
 * 校验分三层，越靠后越贵但越可靠：
 * 1. 文件名与体积（挡住明显选错的文件）；
 * 2. ONNX 结构探测（protobuf 头 / 文件里出现 "onnx" 标识）；
 * 3. **真的加载一次**（[PiperAssets.canLoad]）——tokens 不匹配、不是 VITS/Piper
 *    结构等问题只有加载时才暴露，通不过就不写进音色列表。
 *
 * `tokens.txt` 默认从内置 Ryan 音色复制：en_US 的 Piper 音色共用同一套 eSpeak
 * 音素表（已核对 ryan == lessac）。若模型自带 tokens（同目录同名 `.txt`/`tokens.txt`
 * 由调用方另行提供），第 3 层校验会挡住不匹配的情况。
 */
object PiperVoiceImporter {

    /** 参考模型体积区间：小于 1 MB 不可能是 VITS，大于 400 MB 明显选错。 */
    private const val MIN_BYTES = 1L * 1024 * 1024
    private const val MAX_BYTES = 400L * 1024 * 1024
    private const val MAGIC_SCAN_BYTES = 8 * 1024

    /**
     * 导入并登记一个音色。**在 IO 线程执行**（模型有 30–90 MB，主线程复制会 ANR）。
     *
     * [validator] 仅供测试注入；默认就是真的加载一次模型。
     */
    suspend fun import(
        context: Context,
        onnxUri: Uri,
        validator: (String, String) -> Boolean = { model, tokens ->
            PiperAssets.canLoad(context, model, tokens)
        }
    ): Result<PiperVoice> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val pickedName = displayName(appContext, onnxUri)
                ?: throw IllegalStateException("无法读取所选文件")
            if (!pickedName.endsWith(".onnx", ignoreCase = true)) {
                throw IllegalStateException("请选择 .onnx 模型文件")
            }
            val id = sanitizeId(pickedName)
            val voiceDir = File(PiperVoiceStore.voicesDir(appContext), id)
            val existing = voiceDir.isDirectory
            voiceDir.mkdirs()
            val modelFile = File(voiceDir, id + ".onnx")
            val tokensFile = File(voiceDir, "tokens.txt")
            val temp = File(voiceDir, modelFile.name + ".tmp")

            try {
                // 原子写：先落 .tmp，校验通过后再 rename，避免半个模型留在列表里。
                appContext.contentResolver.openInputStream(onnxUri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取模型文件")

                val size = temp.length()
                if (size < MIN_BYTES) throw IllegalStateException("文件太小，不像是 Piper 模型")
                if (size > MAX_BYTES) throw IllegalStateException("文件超过 400 MB，请确认选对了模型")
                if (!looksLikeOnnx(temp)) throw IllegalStateException("这不是 ONNX 模型文件")

                PiperAssets.copyAssetDir(appContext, PiperAssets.BUILTIN_TOKENS_ASSET, tokensFile)
                if (!temp.renameTo(modelFile)) {
                    temp.copyTo(modelFile, overwrite = true)
                    temp.delete()
                }

                if (!validator(modelFile.absolutePath, tokensFile.absolutePath)) {
                    throw IllegalStateException(
                        "模型无法加载：可能不是 Piper VITS 模型，或需要与之匹配的 tokens.txt"
                    )
                }

                val voice = PiperVoice(
                    id = id,
                    displayName = id,
                    gender = "?",
                    language = "英语",
                    sizeMb = (modelFile.length() / (1024 * 1024)).toInt().coerceAtLeast(1),
                    sampleUrl = "",
                    modelUrl = "",
                    packageUrl = "",
                    modelPath = modelFile.absolutePath,
                    tokensPath = tokensFile.absolutePath
                )
                PiperVoiceStore.registerImported(appContext, voice)
                voice
            } catch (failure: Throwable) {
                // 失败不留垃圾：新建的目录整体删掉，覆盖导入时只删临时文件。
                temp.delete()
                if (!existing) voiceDir.deleteRecursively()
                throw failure
            }
        }
    }

    /**
     * 文件名 → 安全的音色 id / 目录名。
     *
     * DocumentsProvider 返回的名字是不可信输入（可能含 `../` 或分隔符），所以只保留
     * 字母数字与 `_ . -`，并去掉开头的点，避免写到目录之外或产生隐藏目录。
     */
    internal fun sanitizeId(pickedName: String): String {
        val base = pickedName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".onnx")
            .removeSuffix(".ONNX")
        val cleaned = base
            .map { char ->
                if (char.isLetterOrDigit() || char == '_' || char == '-' || char == '.') char else '_'
            }
            .joinToString("")
            .trimStart('.', '_', '-')
            .replace(Regex("\\.{2,}"), ".")
            .take(64)
        return cleaned.ifBlank { "voice_" + System.currentTimeMillis() }
    }

    /**
     * ONNX 结构探测：protobuf 首字段（`ir_version`，tag 0x08）或前 8 KB 里出现
     * "onnx" 标识（producer/opset domain 几乎总会带）。只用于挡明显错误的文件。
     */
    internal fun looksLikeOnnx(file: File): Boolean = runCatching {
        val head = ByteArray(MAGIC_SCAN_BYTES)
        val read = file.inputStream().use { it.read(head) }
        if (read <= 0) return false
        if (head[0] == 0x08.toByte()) return true
        val text = String(head, 0, read, Charsets.ISO_8859_1)
        text.contains("onnx", ignoreCase = true)
    }.getOrDefault(false)

    private fun displayName(context: Context, uri: Uri): String? {
        var name: String? = null
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx)
                    }
                }
        }
        return name ?: uri.lastPathSegment
    }
}
