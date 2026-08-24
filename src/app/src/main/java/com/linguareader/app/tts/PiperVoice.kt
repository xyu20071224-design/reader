package com.linguareader.app.tts

import androidx.annotation.StringRes
import com.linguareader.app.R

/**
 * One Piper voice known to the app: the bundled Ryan voice, a voice that can be
 * downloaded from the official Piper repository, or a voice already imported
 * into `filesDir` by the user.
 *
 * 显示文案规则：目录音色（内置/可下载）带 [displayNameRes]，由 UI 解析成
 * 本地化文案；用户导入的音色没有对应资源，displayName 存文件名派生的 id，
 * 由 UI 原样显示。gender/language 也只存稳定代码（male/female/multi、en），
 * 展示时再映射资源，避免模型层硬编码中文。
 *
 * @param modelPath absolute `.onnx` path once installed locally (imported only).
 * @param tokensPath absolute `tokens.txt` path once installed locally.
 * @param builtin true for the asset-bundled voice.
 */
data class PiperVoice(
    val id: String,
    /** Fallback label (imported voices); catalog voices leave it empty. */
    val displayName: String,
    /** Localized display label for catalog voices; null = use [displayName]. */
    @StringRes val displayNameRes: Int? = null,
    /** Stable gender code (male/female/multi/?); resolved to text by the UI. */
    val gender: String,
    /** Stable language code (en); resolved to text by the UI. */
    val language: String,
    /** Approximate model size in MB; 0 = multi-speaker / unknown. */
    val sizeMb: Int,
    val sampleUrl: String,
    val modelUrl: String,
    val packageUrl: String,
    val modelPath: String? = null,
    val tokensPath: String? = null,
    val builtin: Boolean = false
) {
    val installed: Boolean get() = builtin || (modelPath != null && tokensPath != null)
}

/** Static catalogue of official Piper English voices (samples + downloads). */
object PiperVoiceCatalog {
    /** Id of the bundled English voice. */
    const val DEFAULT_ID = "en_US-ryan-medium"

    val builtin: PiperVoice = PiperVoice(
        id = DEFAULT_ID,
        displayName = "",
        displayNameRes = R.string.tts_piper_voice_ryan_builtin,
        gender = "male",
        language = "en",
        sizeMb = 60,
        sampleUrl = "",
        modelUrl = "",
        packageUrl = "",
        builtin = true
    )

    private fun en(
        voice: String,
        quality: String,
        @StringRes displayNameRes: Int,
        gender: String,
        sizeMb: Int
    ) = PiperVoice(
        id = "en_US-$voice-$quality",
        displayName = "",
        displayNameRes = displayNameRes,
        gender = gender,
        language = "en",
        sizeMb = sizeMb,
        sampleUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/" +
            "$voice/$quality/samples/speaker_0.mp3",
        modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/" +
            "$voice/$quality/en_US-$voice-$quality.onnx",
        packageUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "vits-piper-en_US-$voice-$quality.tar.bz2"
    )

    val downloadable: List<PiperVoice> = listOf(
        en("ryan", "high", R.string.tts_piper_voice_ryan_high, "male", 90),
        en("ryan", "low", R.string.tts_piper_voice_ryan_low, "male", 30),
        en("lessac", "medium", R.string.tts_piper_voice_lessac, "female", 60),
        en("amy", "medium", R.string.tts_piper_voice_amy, "female", 60),
        en("kristin", "medium", R.string.tts_piper_voice_kristin, "female", 60),
        en("hfc_female", "medium", R.string.tts_piper_voice_hfc_female, "female", 60),
        en("hfc_male", "medium", R.string.tts_piper_voice_hfc_male, "male", 60),
        en("libritts_r", "medium", R.string.tts_piper_voice_libritts_r, "multi", 0),
        en("l2arctic", "medium", R.string.tts_piper_voice_l2arctic, "multi", 0)
    )
}
