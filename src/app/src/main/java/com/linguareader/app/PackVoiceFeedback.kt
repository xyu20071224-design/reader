package com.linguareader.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.linguareader.app.tts.PackVoiceFallbackNotice
import com.linguareader.app.tts.PackVoiceUnavailableException
import com.linguareader.app.tts.PackVoiceUnavailableReason

@Composable
internal fun packVoiceFallbackText(notice: PackVoiceFallbackNotice?): String? {
    if (notice == null || !notice.usedFallback) return null
    return stringResource(if (PackVoiceUnavailableReason.MISSING_SAMPLE in notice.reasons)
        R.string.voice_pack_missing_fallback else R.string.voice_pack_unsupported_fallback)
}

internal fun packVoiceFailureText(context: Context, error: Throwable?): String? =
    (error as? PackVoiceUnavailableException)?.let {
        context.getString(when (it.reason) {
            PackVoiceUnavailableReason.MISSING_SAMPLE -> R.string.voice_pack_missing_audition
            PackVoiceUnavailableReason.UNSUPPORTED_ENGINE -> R.string.voice_pack_unsupported_audition
        })
    }
