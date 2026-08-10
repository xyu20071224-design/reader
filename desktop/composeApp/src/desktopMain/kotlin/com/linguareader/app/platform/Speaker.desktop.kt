package com.linguareader.app.platform

import androidx.compose.runtime.Composable

@Composable
actual fun rememberSpeaker(): (String) -> Unit {
    // Windows MVP intentionally ships without TTS.
    return { _ -> }
}
