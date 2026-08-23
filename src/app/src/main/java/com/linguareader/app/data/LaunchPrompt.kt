package com.linguareader.app.data

import androidx.annotation.StringRes
import com.linguareader.app.R

/** Time periods for the launch greeting (F-144). */
enum class GreetingPeriod(@StringRes val hoursLabelRes: Int) {
    DAWN(R.string.launch_greeting_hours_dawn),
    NOON(R.string.launch_greeting_hours_noon),
    DUSK(R.string.launch_greeting_hours_dusk),
    NIGHT(R.string.launch_greeting_hours_night)
}

/** Time-of-day greeting shown when entering the app. */
data class Greeting(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    val period: GreetingPeriod
)

/** One-time update note shown after an app version bump. */
data class UpdateNote(
    val versionCode: Int,
    val versionName: String,
    /** 每条更新说明的 string 资源 id。 */
    val items: List<Int>
)

/**
 * Pure launch-prompt decisions (F-144): a time-based greeting normally, and a
 * one-time update note that replaces the greeting on the first launch after an
 * app update.
 */
object LaunchPromptPolicy {
    /** Whether an update note should replace the greeting this launch. */
    fun shouldShowUpdateNote(installedVersion: Int, lastSeenVersion: Int): Boolean =
        installedVersion > lastSeenVersion

    /**
     * Greeting by hour (0–23). Four periods: 清晨 5–11, 正午 12–17,
     * 黄昏 18–21, 深夜 22–4.
     */
    fun greetingForHour(hour: Int): Greeting = when {
        hour in 5..11 -> Greeting(
            R.string.launch_greeting_dawn_title,
            R.string.launch_greeting_dawn_message,
            GreetingPeriod.DAWN
        )
        hour in 12..17 -> Greeting(
            R.string.launch_greeting_noon_title,
            R.string.launch_greeting_noon_message,
            GreetingPeriod.NOON
        )
        hour in 18..21 -> Greeting(
            R.string.launch_greeting_dusk_title,
            R.string.launch_greeting_dusk_message,
            GreetingPeriod.DUSK
        )
        else -> Greeting(
            R.string.launch_greeting_night_title,
            R.string.launch_greeting_night_message,
            GreetingPeriod.NIGHT
        )
    }
}

/** Update notes for known versions; unknown future versions fall back to a generic note. */
fun updateNoteFor(versionCode: Int, versionName: String): UpdateNote = when (versionCode) {
    4 -> UpdateNote(
        versionCode = 4,
        versionName = "1.1.0",
        items = listOf(
            R.string.launch_note_v4_1,
            R.string.launch_note_v4_2,
            R.string.launch_note_v4_3,
            R.string.launch_note_v4_4,
            R.string.launch_note_v4_5
        )
    )
    5 -> UpdateNote(
        versionCode = 5,
        versionName = "1.2.0",
        items = listOf(
            R.string.launch_note_v5_1,
            R.string.launch_note_v5_2
        )
    )
    6 -> UpdateNote(
        versionCode = 6,
        versionName = "1.3.0",
        items = listOf(
            R.string.launch_note_v6_1,
            R.string.launch_note_v6_2,
            R.string.launch_note_v6_3,
            R.string.launch_note_v6_4
        )
    )
    7 -> UpdateNote(
        versionCode = 7,
        versionName = "1.3.1",
        items = listOf(
            R.string.launch_note_v7_1,
            R.string.launch_note_v7_2,
            R.string.launch_note_v7_3,
            R.string.launch_note_v7_4
        )
    )
    8 -> UpdateNote(
        versionCode = 8,
        versionName = "1.3.2",
        items = listOf(
            R.string.launch_note_v8_1,
            R.string.launch_note_v8_2
        )
    )
    9 -> UpdateNote(
        versionCode = 9,
        versionName = "1.4.0",
        items = listOf(
            R.string.launch_note_v9_1,
            R.string.launch_note_v9_2,
            R.string.launch_note_v9_3,
            R.string.launch_note_v9_4
        )
    )
    else -> UpdateNote(
        versionCode = versionCode,
        versionName = versionName,
        items = listOf(R.string.launch_note_generic)
    )
}
