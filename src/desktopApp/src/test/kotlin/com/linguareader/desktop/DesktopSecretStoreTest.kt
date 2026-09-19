package com.linguareader.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** 凭据存储：CLI 不可用时必须落到 0600 文件兜底，且明确暴露 secure=false。 */
class DesktopSecretStoreTest {

    private val roots = mutableListOf<File>()

    private fun home(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "lr-desktop-secret-" + System.nanoTime())
        dir.mkdirs()
        roots.add(dir)
        return dir
    }

    @AfterTest
    fun cleanup() {
        roots.forEach { it.deleteRecursively() }
    }

    @Test
    fun fallsBackToFileStoreWhenCliDisabled() {
        val store = DesktopSecretStore(home(), enableCli = false)
        assertFalse(store.secure, "禁用 CLI 时必须报告为不安全存储")
        assertEquals(null, store.get("sync.token"))
        store.put("sync.token", "tok-1")
        assertEquals("tok-1", store.get("sync.token"))
        store.remove("sync.token")
        assertEquals(null, store.get("sync.token"))
    }

    @Test
    fun fallbackPersistsAcrossInstances() {
        val dir = home()
        DesktopSecretStore(dir, enableCli = false).put("sync.token", "tok-2")
        assertEquals("tok-2", DesktopSecretStore(dir, enableCli = false).get("sync.token"))
    }
}
