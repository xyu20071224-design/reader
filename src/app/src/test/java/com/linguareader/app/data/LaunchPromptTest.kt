package com.linguareader.app.data

import com.linguareader.app.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchPromptTest {
    @Test
    fun updateNoteShowsOnlyWhenInstalledVersionIsNewer() {
        assertTrue(LaunchPromptPolicy.shouldShowUpdateNote(installedVersion = 5, lastSeenVersion = 4))
        assertFalse(LaunchPromptPolicy.shouldShowUpdateNote(installedVersion = 5, lastSeenVersion = 5))
        assertFalse(LaunchPromptPolicy.shouldShowUpdateNote(installedVersion = 4, lastSeenVersion = 5))
    }

    @Test
    fun greetingFollowsFourPeriodBoundaries() {
        // 文案已资源化（titleRes/messageRes），这里校验资源 id 与时段的映射。
        assertEquals(R.string.launch_greeting_dawn_title, LaunchPromptPolicy.greetingForHour(5).titleRes)
        assertEquals(R.string.launch_greeting_dawn_title, LaunchPromptPolicy.greetingForHour(11).titleRes)
        assertEquals(R.string.launch_greeting_noon_title, LaunchPromptPolicy.greetingForHour(12).titleRes)
        assertEquals(R.string.launch_greeting_noon_title, LaunchPromptPolicy.greetingForHour(17).titleRes)
        assertEquals(R.string.launch_greeting_dusk_title, LaunchPromptPolicy.greetingForHour(18).titleRes)
        assertEquals(R.string.launch_greeting_dusk_title, LaunchPromptPolicy.greetingForHour(21).titleRes)
        assertEquals(R.string.launch_greeting_night_title, LaunchPromptPolicy.greetingForHour(22).titleRes)
        assertEquals(R.string.launch_greeting_night_title, LaunchPromptPolicy.greetingForHour(23).titleRes)
        assertEquals(R.string.launch_greeting_night_title, LaunchPromptPolicy.greetingForHour(0).titleRes)
        assertEquals(R.string.launch_greeting_night_title, LaunchPromptPolicy.greetingForHour(4).titleRes)
    }

    @Test
    fun greetingCopyAndPeriodMatchConfirmedDesign() {
        val dawn = LaunchPromptPolicy.greetingForHour(6)
        assertEquals(GreetingPeriod.DAWN, dawn.period)
        assertEquals(R.string.launch_greeting_hours_dawn, dawn.period.hoursLabelRes)
        assertEquals(R.string.launch_greeting_dawn_message, dawn.messageRes)

        val noon = LaunchPromptPolicy.greetingForHour(13)
        assertEquals(GreetingPeriod.NOON, noon.period)
        assertEquals(R.string.launch_greeting_noon_message, noon.messageRes)

        val dusk = LaunchPromptPolicy.greetingForHour(19)
        assertEquals(GreetingPeriod.DUSK, dusk.period)
        assertEquals(R.string.launch_greeting_dusk_message, dusk.messageRes)

        val night = LaunchPromptPolicy.greetingForHour(23)
        assertEquals(GreetingPeriod.NIGHT, night.period)
        assertEquals(R.string.launch_greeting_hours_night, night.period.hoursLabelRes)
        assertEquals(R.string.launch_greeting_night_message, night.messageRes)
    }

    @Test
    fun knownUpdateNoteDescribesOneDotTwo() {
        val note = updateNoteFor(5, "1.2.0")

        assertEquals("1.2.0", note.versionName)
        assertEquals(2, note.items.size)
        assertTrue(note.items.contains(R.string.launch_note_v5_2))
        assertTrue(note.items.contains(R.string.launch_note_v5_1))
    }

    @Test
    fun unknownVersionFallsBackToGenericNote() {
        val note = updateNoteFor(99, "9.9.9")

        assertEquals("9.9.9", note.versionName)
        assertEquals(listOf(R.string.launch_note_generic), note.items)
    }
}
