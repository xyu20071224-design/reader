package com.linguareader.desktop

import com.linguareader.shared.packs.*
import com.linguareader.shared.tts.TtsPipelineContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/** Desktop owns only the filesystem adapter. The published pack format stays in :shared. */
class DesktopPackRepository(val home: File, private val appVersion: Int = APP_VERSION) {
    val packsRoot = File(home, "packs")
    private val registryFile = File(packsRoot, "registry.json")
    private val temporaryRoot = File(packsRoot, ".tmp")
    private val mutex = Mutex()
    internal var beforePublication: () -> Unit = {}

    data class Snapshot(val registry: PackRegistry, val error: String? = null)
    data class ExpectedIdentity(val packId: String?, val version: String?, val type: PackType?)

    /** No network or directory scans: reopening the page works entirely offline. */
    fun snapshot(): Snapshot = try {
        if (!registryFile.exists()) Snapshot(PackRegistry.EMPTY)
        else Snapshot(PackRegistry.parse(registryFile.readText()))
    } catch (error: Exception) {
        Snapshot(PackRegistry.EMPTY, "资源包登记表无法读取，查词已回退默认词典：${error.message}")
    }

    private fun registryForMutation(): PackRegistry {
        val snapshot = snapshot()
        check(snapshot.error == null) { snapshot.error.orEmpty() }
        return snapshot.registry
    }

    suspend fun install(source: File, expected: ExpectedIdentity? = null): InstalledPack {
        val installed = installSingle(source, expected)
        if (installed.type == PackType.BUNDLE) {
            val failures = mutableListOf<String>()
            for (member in installed.manifest.bundlePayload!!.members) {
                coroutineContext.ensureActive()
                try {
                    installSingle(File(installed.root(packsRoot), member.path),
                        ExpectedIdentity(member.packId, member.version, member.type))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { failures += "${member.packId}：${error.message}" }
            }
            check(failures.isEmpty()) { "集合包已保留，但部分成员安装失败：${failures.joinToString("；")}" }
        }
        return installed
    }

    private suspend fun installSingle(source: File, expected: ExpectedIdentity?): InstalledPack =
        withContext(Dispatchers.IO) {
            val activeContext = coroutineContext
            val checkActive = { activeContext.ensureActive() }
            mutex.withLock {
                val previous = registryForMutation()
                check(source.isFile && source.length() > 0) { "所选文件为空或无法读取" }
                require(source.length() <= PackLimits.of(PackType.AUDIO).maxSourceBytes) { "资源包超过大小上限" }
                check(temporaryRoot.isDirectory || temporaryRoot.mkdirs()) { "无法创建资源包暂存目录" }
                val copy = File.createTempFile("source-", ".lrpack", temporaryRoot)
                val staging = Files.createTempDirectory(temporaryRoot.toPath(), "staging-").toFile()
                try {
                    source.inputStream().use { input -> copy.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            require(written <= PackLimits.of(PackType.AUDIO).maxSourceBytes) { "资源包超过大小上限" }
                            output.write(buffer, 0, count)
                        }
                    } }
                    val manifest = readManifest(copy, checkActive)
                    expected?.let {
                        require((it.packId == null || it.packId == manifest.packId) &&
                            (it.version == null || it.version == manifest.version) &&
                            (it.type == null || it.type == manifest.type)) { "资源包身份与所选目录条目不符" }
                    }
                    val limits = PackLimits.of(manifest.type)
                    require(copy.length() <= limits.maxSourceBytes) { "资源包超过该类型大小上限" }
                    PackValidator.validateManifest(manifest, appVersion).requireOk()
                    previous.byId(manifest.packId)?.let {
                        require(it.type == manifest.type) { "相同资源标识不能更换包类型，请先卸载当前包" }
                        require(!isDowngrade(it.version, manifest.version)) { "资源包版本更旧，请先卸载当前版本" }
                    }
                    if (manifest.type == PackType.AUDIO) {
                        require(manifest.audioPayload!!.pipelineVersion == TtsPipelineContract.VERSION) {
                            "音频包朗读管线版本不兼容"
                        }
                    }
                    SafeZip.extract(copy, staging, limits.maxEntries, limits.maxUnzippedBytes, "资源包", checkActive)
                    val digests = PackValidator.digests(staging, manifest.files.map { it.path }, checkActive)
                    PackValidator.validateFiles(manifest, staging, digests).requireOk()
                    PackValidator.validateAudioChapters(manifest, digests).requireOk()
                    if (manifest.type == PackType.DICTIONARY) {
                        DesktopDictionaryDatabase.validateSchema(File(staging, manifest.dictionaryEntryFile!!))
                    }
                    checkActive()
                    val relative = PackPaths.directoryFor(manifest.type, manifest.packId, manifest.version)
                    val installed = InstalledPack(relative, System.currentTimeMillis(), manifest)
                    val next = previous.upsert(installed).let {
                        if (manifest.type == PackType.DICTIONARY && it.activeDictionary == null)
                            it.withActiveDictionary(manifest.packId) else it
                    }
                    val destination = installed.root(packsRoot)
                    check(destination.parentFile.isDirectory || destination.parentFile.mkdirs())
                    val prepared = prepareRegistry(next)
                    val backup = File(temporaryRoot, "backup-${System.nanoTime()}")
                    var oldMoved = false
                    var newMoved = false
                    var committed = false
                    synchronized(this@DesktopPackRepository) {
                        try {
                            checkActive()
                            if (destination.exists()) {
                                check(destination.renameTo(backup)) { "无法保留原资源包" }
                                oldMoved = true
                            }
                            check(staging.renameTo(destination)) { "无法保存资源包" }
                            newMoved = true
                            beforePublication()
                            checkActive()
                            publishRegistry(prepared)
                            committed = true
                        } finally {
                            if (!committed) {
                                if (newMoved) check(destination.deleteRecursively()) { "无法清理未登记资源包" }
                                if (oldMoved) check(backup.renameTo(destination)) { "无法恢复原资源包，备份仍在暂存目录" }
                            } else {
                                backup.deleteRecursively()
                                previous.byId(manifest.packId)?.takeIf { it.dir != relative }
                                    ?.root(packsRoot)?.deleteRecursively()
                            }
                            prepared.delete()
                        }
                    }
                    installed
                } finally {
                    copy.delete()
                    staging.deleteRecursively()
                }
            }
        }

    suspend fun setActiveDictionary(id: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val registry = registryForMutation()
            if (id != null) {
                val pack = registry.byId(id)
                require(pack?.type == PackType.DICTIONARY) { "词典包不存在" }
                DesktopDictionaryDatabase.validateSchema(requireNotNull(pack.dictionaryFile(packsRoot)) { "词典包文件缺失" })
            }
            synchronized(this@DesktopPackRepository) { writeRegistry(registry.withActiveDictionary(id)) }
        }
    }

    suspend fun uninstall(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val registry = registryForMutation()
            val pack = registry.byId(id) ?: return@withLock
            coroutineContext.ensureActive()
            synchronized(this@DesktopPackRepository) {
                writeRegistry(registry.remove(id))
                check(pack.root(packsRoot).deleteRecursively()) { "资源包已停用，但文件未能全部删除" }
            }
        }
    }

    suspend fun verify(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pack = registryForMutation().byId(id) ?: error("资源包不存在")
            PackValidator.validateManifest(pack.manifest, appVersion).requireOk()
            val context = coroutineContext
            val digests = PackValidator.digests(pack.root(packsRoot), pack.manifest.files.map { it.path }) { context.ensureActive() }
            PackValidator.validateFiles(pack.manifest, pack.root(packsRoot), digests).requireOk()
            PackValidator.validateAudioChapters(pack.manifest, digests).requireOk()
            if (pack.type == PackType.DICTIONARY) DesktopDictionaryDatabase.validateSchema(
                requireNotNull(pack.dictionaryFile(packsRoot)) { "词典包文件缺失" })
        }
    }

    private fun prepareRegistry(registry: PackRegistry): File {
        check(packsRoot.isDirectory || packsRoot.mkdirs())
        val file = File.createTempFile("registry-", ".tmp", packsRoot)
        try {
            file.outputStream().use { output -> output.write(registry.toJson().toByteArray()); output.fd.sync() }
            return file
        } catch (error: Exception) { file.delete(); throw error }
    }

    private fun publishRegistry(prepared: File) {
        Files.move(prepared.toPath(), registryFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun writeRegistry(registry: PackRegistry) {
        val prepared = prepareRegistry(registry)
        try { publishRegistry(prepared) } finally { prepared.delete() }
    }

    private fun readManifest(file: File, checkActive: () -> Unit): PackManifest = ZipFile(file).use { zip ->
        val entry = zip.getEntry(PackManifest.FILE_NAME) ?: error("资源包缺少根目录 manifest.json")
        require(entry.size <= 1024 * 1024) { "资源包清单过大" }
        val bytes = ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 1024 * 1024) { "资源包清单过大" }
                bytes.write(buffer, 0, count)
            }
        }
        PackManifest.parse(bytes.toString(Charsets.UTF_8.name()))
    }

    private fun PackValidationResult.requireOk() {
        if (this is PackValidationResult.Rejected) throw PackFormatException(reason)
    }

    private fun isDowngrade(old: String, new: String): Boolean {
        val a = old.split('.').map { it.toIntOrNull() }
        val b = new.split('.').map { it.toIntOrNull() }
        if (a.any { it == null } || b.any { it == null }) return new < old
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(index) ?: 0
            val y = b.getOrNull(index) ?: 0
            if (x != y) return y < x
        }
        return false
    }

    companion object {
        // Pack compatibility uses the shared Android release's numeric version, not desktop packageVersion.
        const val APP_VERSION = 26
    }
}
