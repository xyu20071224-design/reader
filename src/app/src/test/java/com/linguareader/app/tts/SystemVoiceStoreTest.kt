package com.linguareader.app.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * M5 data-layer tests (PLAN-MULTI-VOICE §13): annotation persistence per engine,
 * locale → assigner-language normalisation, and the snapshot × annotations
 * library build that feeds the D2' gate.
 */
@RunWith(RobolectricTestRunner::class)
class SystemVoiceStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun voice(name: String, tag: String) =
        SystemVoiceInfo(name, Locale.forLanguageTag(tag))

    @Test
    fun `annotations and snapshots round-trip`() {
        val annotations = listOf(
            SystemVoiceAnnotation("en-us-x-iod", "female"),
            SystemVoiceAnnotation("cmn-cn-x-ccc-local", "male", enabled = false)
        )
        SystemVoiceStore.saveAnnotations(context, "com.google.android.tts", annotations)
        assertEquals(annotations, SystemVoiceStore.loadAnnotations(context, "com.google.android.tts"))

        val snapshot = SystemVoiceStore.Snapshot(
            "com.google.android.tts",
            listOf(voice("en-us-x-iod", "en-US"), voice("cmn-cn-x-ccc-local", "zh-CN"))
        )
        SystemVoiceStore.saveSnapshot(context, snapshot)
        assertEquals(snapshot.voices, SystemVoiceStore.loadSnapshot(context, "com.google.android.tts").voices)
    }

    @Test
    fun `annotations are isolated per engine package`() {
        SystemVoiceStore.saveAnnotations(
            context,
            "com.google.android.tts",
            listOf(SystemVoiceAnnotation("a", "female"))
        )
        SystemVoiceStore.saveAnnotations(
            context,
            "com.iflytek.speechsuite",
            listOf(SystemVoiceAnnotation("b", "male"))
        )
        assertEquals("female", SystemVoiceStore.loadAnnotations(context, "com.google.android.tts").single().gender)
        assertEquals("male", SystemVoiceStore.loadAnnotations(context, "com.iflytek.speechsuite").single().gender)
        assertTrue(SystemVoiceStore.loadAnnotations(context, "com.samsung.SMT").isEmpty())
    }

    @Test
    fun `corrupt store entries collapse to empty`() {
        context.getSharedPreferences("system_voice_annotations", Context.MODE_PRIVATE)
            .edit()
            .putString("annotations@broken", "{not json")
            .putString("snapshot@broken", "[not an object")
            .putString("annotations@partial", """[{"voiceName":"x"}]""")
            .apply()

        assertTrue(SystemVoiceStore.loadAnnotations(context, "broken").isEmpty())
        assertTrue(SystemVoiceStore.loadSnapshot(context, "broken").voices.isEmpty())
        // Entries without a voice name are dropped instead of crashing.
        assertEquals(listOf("x"), SystemVoiceStore.loadAnnotations(context, "partial").map { it.voiceName })
    }

    @Test
    fun `assignerLanguage normalises vendor and iso639-3 codes`() {
        assertEquals("zh", voice("v", "zh-CN").assignerLanguage)
        assertEquals("zh", voice("v", "cmn-CN").assignerLanguage)
        assertEquals("zh", voice("v", "chn").assignerLanguage)
        assertEquals("en", voice("v", "en-US").assignerLanguage)
        assertEquals("en", voice("v", "usa").assignerLanguage)
        // Unrecognised locales stay blank = multilingual-tolerant for the filter.
        assertEquals("", voice("v", "fr-FR").assignerLanguage)
    }

    @Test
    fun `library only contains enabled voices with known gender`() {
        val snapshot = listOf(
            voice("google-female", "en-US"),
            voice("google-male", "en-US"),
            voice("unknown-gender", "en-US"),
            voice("disabled", "en-US"),
            voice("unannotated", "en-US")
        )
        val annotations = listOf(
            SystemVoiceAnnotation("google-female", "female"),
            SystemVoiceAnnotation("google-male", "male"),
            SystemVoiceAnnotation("unknown-gender", ""),
            SystemVoiceAnnotation("disabled", "female", enabled = false)
        )

        val voices = SystemVoiceStore.buildVoices(snapshot, annotations)

        assertEquals(listOf("google-female", "google-male"), voices.map { it.id })
        assertEquals("en", voices[0].language)
        assertEquals("system", voices[0].source)
    }

    @Test
    fun `usableVoices follows the current engine pointer`() {
        SystemVoiceStore.setCurrentEngine(context, "com.google.android.tts")
        SystemVoiceStore.saveSnapshot(
            context,
            SystemVoiceStore.Snapshot("com.google.android.tts", listOf(voice("v1", "en-US")))
        )
        SystemVoiceStore.saveAnnotations(
            context,
            "com.google.android.tts",
            listOf(SystemVoiceAnnotation("v1", "female"))
        )
        // Annotations for another engine must not leak into the current one.
        SystemVoiceStore.saveAnnotations(
            context,
            "com.iflytek.speechsuite",
            listOf(SystemVoiceAnnotation("v1", "male"))
        )

        val usable = SystemVoiceStore.usableVoices(context)
        assertEquals(1, usable.size)
        assertEquals("female", usable.single().gender)

        // A blank pointer (never probed) means no library at all.
        SystemVoiceStore.setCurrentEngine(context, "")
        assertTrue(SystemVoiceStore.usableVoices(context).isEmpty())
    }
}
