package com.linguareader.app.packs

import android.content.Context
import android.net.Uri
import android.util.Log
import com.linguareader.shared.packs.AudioPackSource
import com.linguareader.shared.packs.DictionarySource
import com.linguareader.shared.packs.InstalledPack
import com.linguareader.shared.packs.PackFormatException
import com.linguareader.shared.packs.PackLimits
import com.linguareader.shared.packs.PackManifest
import com.linguareader.shared.packs.PackPaths
import com.linguareader.shared.packs.PackRegistry
import com.linguareader.shared.packs.PackType
import com.linguareader.shared.packs.PackValidationResult
import com.linguareader.shared.packs.PackValidator
import com.linguareader.shared.packs.SafeZip
import com.linguareader.shared.tts.TtsPipelineContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * 资源包的磁盘所有者：安装 / 卸载 / 启用 / 登记表读写（方案-资源包系统 §3）。
 *
 * 为什么单独成类：`filesDir/packs` 的路径、registry 的读写纪律（`*.tmp` + rename）、
 * 解压护栏的调用参数都只应该有一份。词典读取方（`DictionaryRepository`）与听书链路
 * 各自只问一句「当前该用哪个文件」，不碰安装细节。
 *
 * **线程模型**：`install` / `uninstall` / `setActiveDictionary` 都在 [mutex] 内串行；
 * 读取方（[registry] / [dictionarySource]）走内存快照，不加锁。安装过程中读取方看到的
 * 是「旧 registry」，这正是我们要的 —— 半装完的包永远不可见。
 */
class PackRepository(
    context: Context,
    /** 注入是为了可测；生产走 [BuildConfig.VERSION_CODE]。 */
    private val appVersionCode: Int = com.linguareader.app.BuildConfig.VERSION_CODE
) {

    private val appContext = context.applicationContext

    /** `filesDir/packs`。所有包都住这里，绝不外溢到别的目录。 */
    val packsRoot: File = File(appContext.filesDir, DIR_NAME)

    private val registryFile = File(packsRoot, REGISTRY_NAME)

    /** 解压/校验的临时区：与最终目录同一文件系统，rename 才是原子的。 */
    private val tempRoot = File(packsRoot, TEMP_DIR)

    private val mutex = Mutex()

    @Volatile
    private var cached: PackRegistry? = null

    @Volatile
    private var cachedStamp: Long = -1L

    /**
     * 登记表快照。首次读盘，之后走缓存；损坏时回退空表（查词不能因此哑掉）。
     *
     * 每次调用比一次 `lastModified`：听书链路的合成器与 UI 是两个实例，装了新包
     * 必须让另一侧**立刻**看见，否则会出现「页面里有了、听书还在按老包找文件」。
     */
    fun registry(): PackRegistry {
        val stamp = stampOf(registryFile)
        cached?.takeIf { stamp == cachedStamp }?.let { return it }
        return synchronized(this) {
            if (cached != null && stamp == cachedStamp) cached!!
            else readRegistry().also {
                cached = it
                cachedStamp = stamp
            }
        }
    }

    /** 强制重读（「验证完整性」与外部改动后）。 */
    fun reload(): PackRegistry = synchronized(this) {
        readRegistry().also {
            cached = it
            cachedStamp = stampOf(registryFile)
        }
    }

    /** 当前生效的词典文件；null = 用内置 assets。 */
    fun dictionarySource(): File? = DictionarySource.resolve(registry(), packsRoot)

    /** 音频包里的句子文件；null = 继续走缓存/现场合成。 */
    fun audioPackFile(bookId: String, relativePath: String): File? =
        AudioPackSource.resolve(registry(), packsRoot, bookId, relativePath)

    fun totalBytes(): Long = PackValidator.directoryBytes(packsRoot)

    fun verify(packId: String): PackValidationResult {
        val pack = registry().byId(packId)
            ?: return PackValidationResult.reject("资源包不存在：$packId")
        val manifestCheck = PackValidator.validateManifest(pack.manifest, appVersionCode)
        if (!manifestCheck.ok) return manifestCheck
        val root = pack.root(packsRoot)
        val digests = PackValidator.digests(root, pack.manifest.files.map { it.path })
        val filesCheck = PackValidator.validateFiles(pack.manifest, root, digests)
        if (!filesCheck.ok) return filesCheck
        return PackValidator.validateAudioChapters(pack.manifest, digests)
    }

    /**
     * 安装一个 `.lrpack`。
     *
     * 全流程 fail-closed：任一步失败都清掉临时文件/目录，磁盘上不会留下半装的包，
     * registry 也不会被改。返回落位后的登记项。
     */
    suspend fun install(uri: Uri): InstalledPack = withContext(Dispatchers.IO) {
        mutex.withLock {
            packsRoot.mkdirs()
            tempRoot.mkdirs()
            val sourceZip = File.createTempFile("pack-", ".zip", tempRoot)
            val staging = File(tempRoot, "staging-" + System.nanoTime())
            try {
                copySource(uri, sourceZip)
                val manifest = readManifest(sourceZip)
                val limits = PackLimits.of(manifest.type)
                require(sourceZip.length() <= limits.maxSourceBytes) {
                    "资源包文件超过 ${limits.maxSourceBytes / 1024 / 1024}MB 限制"
                }
                PackValidator.validateManifest(manifest, appVersionCode).requireOk()
                staging.mkdirs()
                SafeZip.extract(
                    zip = sourceZip,
                    destination = staging,
                    maxEntries = limits.maxEntries,
                    maxBytes = limits.maxUnzippedBytes,
                    label = "资源包"
                )
                if (manifest.type == PackType.AUDIO) {
                    val audio = manifest.audioPayload
                        ?: throw PackFormatException("音频包缺少 audio 载荷")
                    if (audio.pipelineVersion != TtsPipelineContract.VERSION) {
                        throw PackFormatException(
                            "音频包由不同版本的朗读管线生成（包 v${audio.pipelineVersion}，" +
                                "应用 v${TtsPipelineContract.VERSION}）。装了会播出与正文对不上的音频，已拒绝。"
                        )
                    }
                }
                val digests = PackValidator.digests(staging, manifest.files.map { it.path })
                PackValidator.validateFiles(manifest, staging, digests).requireOk()
                PackValidator.validateAudioChapters(manifest, digests).requireOk()
                if (manifest.type == PackType.DICTIONARY) {
                    val entry = manifest.dictionaryEntryFile
                        ?: throw PackFormatException("词典包缺少 entryFile")
                    DictionaryPackSchema.probe(File(staging, entry))?.let {
                        throw PackFormatException(it)
                    }
                }

                val destination = File(packsRoot, PackPaths.directoryFor(manifest.type, manifest.packId, manifest.version))
                destination.parentFile?.mkdirs()
                if (destination.exists() && !destination.deleteRecursively()) {
                    throw IllegalStateException("无法替换已存在的资源包目录")
                }
                // 同 packId 的旧版本目录（换了版本号就是另一个目录）一并清掉，避免同一包多份占空间。
                registry().byId(manifest.packId)
                    ?.takeIf { it.dir != PackPaths.directoryFor(manifest.type, manifest.packId, manifest.version) }
                    ?.let { old -> old.root(packsRoot).deleteRecursively() }
                if (!staging.renameTo(destination)) {
                    throw IllegalStateException("资源包落位失败（可能是存储空间不足）")
                }
                val installed = InstalledPack(
                    dir = PackPaths.directoryFor(manifest.type, manifest.packId, manifest.version),
                    installedAt = System.currentTimeMillis(),
                    manifest = manifest
                )
                // 第一个词典包装上就直接生效：用户装词典包的目的就是用它；已有活动包时
                // 不抢（可能正在听书/查词），由用户在资源包页面显式切换。
                val next = registry().upsert(installed).let { current ->
                    if (manifest.type == PackType.DICTIONARY && current.activeDictionary == null) {
                        current.withActiveDictionary(manifest.packId)
                    } else {
                        current
                    }
                }
                writeRegistry(next)
                installed
            } finally {
                sourceZip.delete()
                staging.deleteRecursively()
            }
        }
    }

    /** 卸载：删目录 + 从登记表移除；若它是当前词典包，自动回退内置。 */
    suspend fun uninstall(packId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pack = registry().byId(packId) ?: return@withLock
            pack.root(packsRoot).deleteRecursively()
            writeRegistry(registry().remove(packId))
        }
    }

    /**
     * 切换当前词典包（null = 恢复内置）。
     *
     * **调用方必须在之后让 `DictionaryRepository.invalidate()` 生效**，否则已打开的
     * 旧库句柄与词条 LRU 还在，切了等于没切。
     */
    suspend fun setActiveDictionary(packId: String?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeRegistry(registry().withActiveDictionary(packId))
        }
    }

    private fun copySource(uri: Uri, target: File) {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取所选文件")
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    written += read
                    // 类型未知时按全类型上限兜底；具体类型的上限在解析 manifest 后再卡。
                    require(written <= PackLimits.of(PackType.AUDIO).maxSourceBytes) {
                        "资源包文件过大"
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        require(target.length() > 0) { "文件内容为空" }
    }

    private fun readManifest(zip: File): PackManifest {
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry(PackManifest.FILE_NAME)
                ?: archive.entries().asSequence().firstOrNull {
                    it.name.substringAfterLast('/') == PackManifest.FILE_NAME
                }
                ?: throw PackFormatException("这个文件不是资源包（缺少 manifest.json）")
            val text = archive.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            return PackManifest.parse(text)
        }
    }

    private fun stampOf(file: File): Long = if (file.isFile) file.lastModified() else -1L

    private fun readRegistry(): PackRegistry = runCatching {
        if (!registryFile.isFile) PackRegistry.EMPTY
        else PackRegistry.parse(registryFile.readText())
    }.getOrElse { error ->
        Log.w(TAG, "资源包登记表读取失败，按空表处理：${error.message}")
        PackRegistry.EMPTY
    }

    /** 原子写：`*.tmp` + rename，失败回退直写（与项目其余落盘纪律一致）。 */
    private fun writeRegistry(registry: PackRegistry) {
        packsRoot.mkdirs()
        val temp = File(packsRoot, "$REGISTRY_NAME.tmp")
        temp.writeText(registry.toJson())
        if (!temp.renameTo(registryFile)) {
            registryFile.writeText(registry.toJson())
            temp.delete()
        }
        cached = registry
        cachedStamp = stampOf(registryFile)
    }

    private fun PackValidationResult.requireOk() {
        if (this is PackValidationResult.Rejected) throw PackFormatException(reason)
    }

    companion object {
        const val DIR_NAME = "packs"
        const val REGISTRY_NAME = "registry.json"
        const val TEMP_DIR = ".tmp"
        private const val TAG = "PackRepository"
    }
}
