package com.linguareader.app.tts

import android.content.res.AssetManager

/**
 * JNI bridge to the bundled Piper/ncnn offline synthesis engine
 * (see src/main/jni/piperncnn.cpp). The models are pre-built into
 * assets/piper/{en,zh}_*.ncnn.bin/param plus {en,zh}-word_id.bin.
 */
class PiperNcnn {
    external fun loadModel(mgr: AssetManager, langid: Int, cpugpu: Int): Boolean

    /**
     * Synthesizes [text] into 22050 Hz mono 16-bit little-endian PCM.
     * Returns null when no model is loaded. This call is synchronous and can
     * take tens of milliseconds to a few seconds — run it off the main thread.
     */
    external fun synthesize(text: String, speakerid: Int, lengthScale: Double): ByteArray?

    companion object {
        const val LANG_EN = 0
        const val LANG_ZH = 1

        init {
            System.loadLibrary("piperncnn")
        }
    }
}
