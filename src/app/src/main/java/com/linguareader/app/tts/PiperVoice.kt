package com.linguareader.app.tts

/**
 * One Piper voice known to the app: the bundled Ryan voice, a voice that can be
 * downloaded from the official Piper repository, or a voice already imported
 * into `filesDir` by the user.
 *
 * @param modelPath absolute `.onnx` path once installed locally (imported only).
 * @param tokensPath absolute `tokens.txt` path once installed locally.
 * @param builtin true for the asset-bundled voice.
 */
data class PiperVoice(
    val id: String,
    val displayName: String,
    val gender: String,
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
    val sizeLabel: String get() = if (sizeMb <= 0) "多说话人" else "约 $sizeMb MB"
}

/** Static catalogue of official Piper English voices (samples + downloads). */
object PiperVoiceCatalog {
    /** Id of the bundled English voice. */
    const val DEFAULT_ID = "en_US-ryan-medium"

    val builtin: PiperVoice = PiperVoice(
        id = DEFAULT_ID,
        displayName = "Ryan（内置·美式男声）",
        gender = "男",
        language = "英语",
        sizeMb = 60,
        sampleUrl = "",
        modelUrl = "",
        packageUrl = "",
        builtin = true
    )

    private fun en(
        voice: String,
        quality: String,
        displayName: String,
        gender: String,
        sizeMb: Int
    ) = PiperVoice(
        id = "en_US-$voice-$quality",
        displayName = displayName,
        gender = gender,
        language = "英语",
        sizeMb = sizeMb,
        sampleUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/" +
            "$voice/$quality/samples/speaker_0.mp3",
        modelUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/" +
            "$voice/$quality/en_US-$voice-$quality.onnx",
        packageUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
            "vits-piper-en_US-$voice-$quality.tar.bz2"
    )

    val downloadable: List<PiperVoice> = listOf(
        en("ryan", "high", "Ryan 高音质（美式男声）", "男", 90),
        en("ryan", "low", "Ryan 精简（美式男声）", "男", 30),
        en("lessac", "medium", "Lessac（美式女声）", "女", 60),
        en("amy", "medium", "Amy（美式女声）", "女", 60),
        en("kristin", "medium", "Kristin（美式女声）", "女", 60),
        en("hfc_female", "medium", "HFC 女声（美式）", "女", 60),
        en("hfc_male", "medium", "HFC 男声（美式）", "男", 60),
        en("libritts_r", "medium", "LibriTTS-R（904 个说话人）", "多", 0),
        en("l2arctic", "medium", "L2Arctic（5 个说话人）", "多", 0)
    )
}
