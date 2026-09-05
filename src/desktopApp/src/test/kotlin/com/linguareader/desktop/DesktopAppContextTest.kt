package com.linguareader.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 桌面平台面（DesktopAppContext/JsonPreferencesStore）的单元测试（M2 刀5）。
 * 语义对齐 Android 侧 SharedPreferences 的行为面：put 后立即可读、跨实例持久、
 * 坏文件不抛。
 */
class DesktopAppContextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun prefsRoundTripAndPersistenceAcrossInstances() {
        val filesDir = tmp.newFolder("home")
        val context = DesktopAppContext(filesDir)

        context.prefs("review_settings").putString("review_mode", "DILIGENT")
        assertEquals("DILIGENT", context.prefs("review_settings").getString("review_mode"))

        // 同一 Context 内同名 prefs 是同一实例（对齐 Android getSharedPreferences 缓存）。
        assertTrue(context.prefs("review_settings") === context.prefs("review_settings"))

        // 新实例从磁盘读回：写穿即持久。
        val reopened = DesktopAppContext(filesDir).prefs("review_settings")
        assertEquals("DILIGENT", reopened.getString("review_mode"))
        assertNull(reopened.getString("missing"))
    }

    @Test
    fun corruptPrefsFileIsToleratedLikeSharedPreferences() {
        val filesDir = tmp.newFolder("home2")
        val prefsFile = File(filesDir, "prefs/broken.json")
        prefsFile.parentFile.mkdirs()
        prefsFile.writeText("{not json at all")

        val store = DesktopAppContext(filesDir).prefs("broken")
        assertNull(store.getString("anything"))

        // 坏文件之后仍可正常写入并持久。
        store.putString("recovered", "yes")
        assertEquals("yes", DesktopAppContext(filesDir).prefs("broken").getString("recovered"))
    }

    @Test
    fun filesDirIsPassedThroughAndPrefsLiveUnderIt() {
        val filesDir = tmp.newFolder("home3")
        val context = DesktopAppContext(filesDir)
        assertEquals(filesDir, context.filesDir)

        context.prefs("smoke").putString("k", "v")
        assertTrue(File(filesDir, "prefs/smoke.json").isFile)
    }
}
