package com.linguareader.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.linguareader.app.tts.SingleWordSpeaker

/** A separate window keeps requested speech failures visible above lookup and review sheets. */
@Composable
internal fun WordSpeechFailureDialog(
    failure: SingleWordSpeaker.Failure,
    onDismiss: () -> Unit
) {
    val message = when (failure) {
        SingleWordSpeaker.Failure.ENGINE_UNAVAILABLE -> R.string.tts_word_unavailable
        SingleWordSpeaker.Failure.LANGUAGE_UNAVAILABLE -> R.string.tts_word_language_missing
        SingleWordSpeaker.Failure.REQUEST_REJECTED,
        SingleWordSpeaker.Failure.PLAYBACK_FAILED -> R.string.tts_word_playback_failed
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tts_word_failure_title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) }
        }
    )
}
