package com.linguareader.app.platform

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
actual fun rememberSpeaker(): (String) -> Unit {
    val context = LocalContext.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        lateinit var created: TextToSpeech
        created = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) created.language = Locale.US
        }
        engine = created
        onDispose {
            created.stop()
            created.shutdown()
            engine = null
        }
    }
    return { text ->
        if (text.isNotBlank()) {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lingua-reader-word")
        }
    }
}
