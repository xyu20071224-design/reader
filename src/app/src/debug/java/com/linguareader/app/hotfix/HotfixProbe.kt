package com.linguareader.app.hotfix

/** Debug-only proof target. Never shipped in release APKs. */
@androidx.annotation.Keep
object HotfixProbe {
    @JvmStatic fun value(): String = "base-native-kotlin"
}
