package com.linguareader.app.tts

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * 本地 Piper（sherpa-onnx）资源与模型加载的共用逻辑。
 *
 * 内置音色从 assets 读；用户导入的音色是 `filesDir` 下的真实文件——sherpa-onnx
 * 传入 AssetManager 时会把路径当 asset 解析，所以两者必须用不同的构造方式，
 * 这里统一收口，导入校验与播放走的是同一条加载路径。
 */
internal object PiperAssets {
    /** espeak-ng 数据必须是真实文件（Piper 音素化器不认 asset 条目）。 */
    const val ESPEAK_DATA_ASSET_PATH = "sherpa/vits-piper-en_US-ryan-medium/espeak-ng-data"
    const val BUILTIN_MODEL_ASSET = "sherpa/vits-piper-en_US-ryan-medium/en_US-ryan-medium.onnx"
    const val BUILTIN_TOKENS_ASSET = "sherpa/vits-piper-en_US-ryan-medium/tokens.txt"

    /** 把 espeak-ng-data 复制到 filesDir（只做一次），返回其目录。 */
    fun ensureEspeakData(context: Context): File {
        val target = File(context.applicationContext.filesDir, ESPEAK_DATA_ASSET_PATH)
        if (!target.isDirectory) {
            copyAssetDir(context.applicationContext, ESPEAK_DATA_ASSET_PATH, target)
        }
        return target
    }

    fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val assets = context.applicationContext.assets
        val entries = assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (entry in entries) {
                copyAssetDir(context, "$assetPath/$entry", File(dest, entry))
            }
        }
    }

    /** 英文（Piper）配置：内置走 asset 路径，导入音色走绝对文件路径。 */
    fun englishConfig(context: Context, voice: PiperVoice): OfflineTtsConfig = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
            vits = OfflineTtsVitsModelConfig(
                model = voice.modelPath ?: BUILTIN_MODEL_ASSET,
                tokens = voice.tokensPath ?: BUILTIN_TOKENS_ASSET,
                dataDir = ensureEspeakData(context).absolutePath
            ),
            numThreads = 2
        )
    )

    /**
     * 创建英文合成器。导入音色不能传 AssetManager（否则绝对路径会被当 asset 解析
     * 而加载失败）；返回 null 表示这个音色加载不了，调用方应回退内置音色。
     */
    fun createEnglishTts(context: Context, voice: PiperVoice): OfflineTts? {
        val appContext = context.applicationContext
        val config = englishConfig(appContext, voice)
        return runCatching {
            if (voice.modelPath != null) {
                // 文件路径模式
                OfflineTts(config = config)
            } else {
                OfflineTts(assetManager = appContext.assets, config = config)
            }
        }.getOrNull()
    }

    /**
     * 导入校验：真的把模型加载一次再释放。这是唯一能确定「这个 .onnx 能用」的办法
     * ——扩展名与魔数只能挡住明显错误的文件，tokens 不匹配、不是 VITS/Piper 结构
     * 等问题只有加载时才暴露。
     */
    fun canLoad(context: Context, modelPath: String, tokensPath: String): Boolean {
        val probe = PiperVoice(
            id = "probe",
            displayName = "probe",
            gender = "",
            language = "",
            sizeMb = 0,
            sampleUrl = "",
            modelUrl = "",
            packageUrl = "",
            modelPath = modelPath,
            tokensPath = tokensPath
        )
        val tts = createEnglishTts(context, probe) ?: return false
        runCatching { tts.release() }
        return true
    }
}
