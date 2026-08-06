package com.linguareader.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchPromptTest {
    @Test
    fun updateNoteShowsOnlyWhenInstalledVersionIsNewer() {
        assertTrue(LaunchPromptPolicy.shouldShowUpdateNote(installedVersion = 4, lastSeenVersion = 3))
        assertFalse(LaunchPromptPolicy.shouldShowUpdateNote(installedVersion = 4, lastSeenVersion = 4))
        assertFalse(LaunchPromptPolicy.shouldShowUpdateNote(installedVersion = 3, lastSeenVersion = 4))
    }

    @Test
    fun greetingFollowsFourPeriodBoundaries() {
        assertEquals("清晨", LaunchPromptPolicy.greetingForHour(5).title)
        assertEquals("清晨", LaunchPromptPolicy.greetingForHour(11).title)
        assertEquals("正午", LaunchPromptPolicy.greetingForHour(12).title)
        assertEquals("正午", LaunchPromptPolicy.greetingForHour(17).title)
        assertEquals("黄昏", LaunchPromptPolicy.greetingForHour(18).title)
        assertEquals("黄昏", LaunchPromptPolicy.greetingForHour(21).title)
        assertEquals("深夜", LaunchPromptPolicy.greetingForHour(22).title)
        assertEquals("深夜", LaunchPromptPolicy.greetingForHour(23).title)
        assertEquals("深夜", LaunchPromptPolicy.greetingForHour(0).title)
        assertEquals("深夜", LaunchPromptPolicy.greetingForHour(4).title)
    }

    @Test
    fun greetingCopyAndPeriodMatchConfirmedDesign() {
        val dawn = LaunchPromptPolicy.greetingForHour(6)
        assertEquals(GreetingPeriod.DAWN, dawn.period)
        assertEquals("5–11 点", dawn.period.hoursLabel)
        assertEquals("被崭新的一天唤醒，迎接美好的朝阳", dawn.message)

        val noon = LaunchPromptPolicy.greetingForHour(13)
        assertEquals(GreetingPeriod.NOON, noon.period)
        assertEquals("在重叠的光影中游戏，世间万物欣欣向荣", noon.message)

        val dusk = LaunchPromptPolicy.greetingForHour(19)
        assertEquals(GreetingPeriod.DUSK, dusk.period)
        assertEquals("同疲倦的归鸟还家，消失于天空尽头的晚霞", dusk.message)

        val night = LaunchPromptPolicy.greetingForHour(23)
        assertEquals(GreetingPeriod.NIGHT, night.period)
        assertEquals("22–4 点", night.period.hoursLabel)
        assertEquals("忙碌的人们陷入好梦，窗外是闪烁的星河", night.message)
    }

    @Test
    fun knownUpdateNoteDescribesOneDotOne() {
        val note = updateNoteFor(4, "1.1.0")

        assertEquals("1.1.0", note.versionName)
        assertEquals(5, note.items.size)
        assertTrue(note.items.any { it.contains("PDF") })
        assertTrue(note.items.any { it.contains("复习提醒") })
    }

    @Test
    fun unknownVersionFallsBackToGenericNote() {
        val note = updateNoteFor(99, "9.9.9")

        assertEquals("9.9.9", note.versionName)
        assertEquals(1, note.items.size)
    }
}