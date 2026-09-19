package com.linguareader.desktop

import com.linguareader.shared.sync.FileSecretStore
import com.linguareader.shared.sync.SecretStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 桌面凭据存储（D4）：优先调用系统凭据管理器 CLI，不可用或调用失败时降级为 0600 文件。
 *
 * - Linux：`secret-tool`（libsecret）
 * - macOS：`security`
 * - Windows：**当前主动降级为文件存储** —— `cmdkey` 无法读回口令，免依赖的可读回方案
 *   （DPAPI/JNA）尚未引入，故不假装安全；[secure] 会返回 false，设置页据此显示警告。
 *
 * [enableCli] 仅用于测试，生产固定 true。
 */
class DesktopSecretStore(home: File, private val enableCli: Boolean = true) : SecretStore {

    private val fallback = FileSecretStore(File(home, "secrets.json"))
    private val os = System.getProperty("os.name").lowercase()
    private val linuxCli = enableCli && os.contains("linux") && hasCommand("secret-tool")
    private val macCli = enableCli && (os.contains("mac") || os.contains("darwin")) && hasCommand("security")

    /** 是否真的落在系统凭据管理器里（false = 正在用文件兜底，UI 必须提示）。 */
    val secure: Boolean get() = linuxCli || macCli

    override fun get(key: String): String? = when {
        linuxCli -> exec(listOf("secret-tool", "lookup", "service", SERVICE, "key", key))
            ?.takeIf { it.isNotBlank() } ?: fallback.get(key)
        macCli -> exec(listOf("security", "find-generic-password", "-s", SERVICE, "-a", key, "-w"))
            ?.trim()?.takeIf { it.isNotBlank() } ?: fallback.get(key)
        else -> fallback.get(key)
    }

    override fun put(key: String, value: String) {
        val stored = when {
            linuxCli -> exec(listOf("secret-tool", "store", "--label=" + SERVICE, "service", SERVICE, "key", key), stdin = value) != null
            macCli -> exec(listOf("security", "add-generic-password", "-U", "-s", SERVICE, "-a", key, "-w", value)) != null
            else -> false
        }
        if (!stored) fallback.put(key, value)
    }

    override fun remove(key: String) {
        when {
            linuxCli -> exec(listOf("secret-tool", "clear", "service", SERVICE, "key", key))
            macCli -> exec(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", key))
        }
        fallback.remove(key)
    }

    private fun hasCommand(name: String): Boolean = try {
        val process = ProcessBuilder(name, "--help").redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor(5, TimeUnit.SECONDS)
        true
    } catch (_: Exception) {
        false
    }

    private fun exec(command: List<String>, stdin: String? = null): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (stdin != null) {
            process.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
        } else {
            process.outputStream.close()
        }
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SERVICE = "linguareader-sync"
    }
}
