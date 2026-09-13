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
import com.linguareader.shared.packs.PackVoice
import com.linguareader.shared.packs.SafeZip
import com.linguareader.shared.packs.VoicePackSource
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

    /** 音色包的 metadata 音色：目标音色 id → 元数据。 */
    fun voiceMetadata(): Map<String, PackVoice> = VoicePackSource.metadata(registry())

    /** 音色包的克隆音色（含已解析的样本文件）。 */
    fun cloneVoices(): List<VoicePackSource.CloneVoice> =
        VoicePackSource.cloneVoices(registry(), packsRoot)

    /** `pack:<packId>/<key>` 的克隆样本；不是音色包 id 时返回 null。 */
    fun packVoiceSample(voiceId: String): File? =
        VoicePackSource.sampleFile(registry(), packsRoot, voiceId)

    fun totalBytes(): Long = PackValidator.directoryBytes(packsRoot)

    /**
     * 未被登记表认领的残留（第四轮审查 7-9）。
     *
     * 报告原文：未登记目录与崩溃残留的 `.tmp` **既不在包列表、也不进孤儿对账**，却被
     * [totalBytes] 计入占用 —— 用户看到占用却找不到东西可删。
     *
     * 判定是**推导式**而非猜目录名：登记表里每个包按
     * [PackPaths.directoryFor] 得到一个应有的相对路径，`packsRoot/<type>/` 下即
     * `<packId>/<version>/`；不在该集合里的目录、以及 `.tmp` 下的任何临时物，
     * 都是残留。
     */
    fun residuals(): List<PackResidual> {
        val out = mutableListOf<PackResidual>()
        if (!packsRoot.isDirectory) return out
        // 已登记的目录（相对 packsRoot），例如 dictionary/ecdict-zh/1.0.0
        val registered = registry().packs.map {
            PackPaths.directoryFor(it.type, it.packId, it.version)
        }.toSet()
        for (type in PackType.entries) {
            val typeDir = File(packsRoot, type.wire)
            if (!typeDir.isDirectory) continue
            for (packDir in typeDir.listFiles().orEmpty().filter { it.isDirectory }) {
                for (versionDir in packDir.listFiles().orEmpty().filter { it.isDirectory }) {
                    val rel = "${type.wire}/${packDir.name}/${versionDir.name}"
                    if (rel !in registered) {
                        out += PackResidual(
                            path = versionDir,
                            kind = PackResidual.Kind.UNREGISTERED,
                            bytes = PackValidator.directoryBytes(versionDir)
                        )
                    }
                }
            }
        }
        // 安装/写登记表崩溃留下的临时区（正常收尾时会被消费掉）
        if (tempRoot.isDirectory) {
            for (leftover in tempRoot.listFiles().orEmpty()) {
                out += PackResidual(
                    path = leftover,
                    kind = PackResidual.Kind.TEMP,
                    bytes = PackValidator.directoryBytes(leftover)
                )
            }
        }
        return out
    }

    /** 删除 [residuals] 列出的全部残留，返回**实际**释放的字节数（删成功才计入）。 */
    fun cleanResiduals(): Long {
        var freed = 0L
        for (residual in residuals()) {
            val bytes = residual.bytes
            if (runCatching { residual.path.deleteRecursively() }.getOrDefault(false)) freed += bytes
        }
        return freed
    }

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
    /**
     * 安装一个 `.lrpack`。
     *
     * 集合包（Q2-c02 / [PackType.BUNDLE]）分两步：先把集合包本身装好并登记，
     * 再把它捆绑的成员包逐个交给同一条安装管线。成员安装放在**锁外**——
     * [installSingle] 自己会取 `mutex`，而 Kotlin 的 Mutex 不可重入，锁内递归会死锁。
     */
    suspend fun install(uri: Uri): InstalledPack {
        val installed = installSingle(uri)
        if (installed.type == PackType.BUNDLE) {
            installBundleMembers(installed)
        }
        return installed
    }

    /**
     * 逐个安装集合包的成员包。
     *
     * 成员失败不回滚已成功的部分（回滚会把用户已经拿到的词典/音频删掉），
     * 但**绝不静默**：只要有失败就抛错，把失败清单交回调用方展示。
     */
    private suspend fun installBundleMembers(bundle: InstalledPack) {
        val members = bundle.manifest.bundlePayload?.members.orEmpty()
        if (members.isEmpty()) return
        val root = bundle.root(packsRoot)
        val failed = mutableListOf<String>()
        for (member in members) {
            val file = File(root, member.path)
            if (!file.isFile) {
                failed += "${member.packId}（成员文件缺失）"
                continue
            }
            runCatching { installSingle(Uri.fromFile(file)) }
                .onFailure { failed += "${member.packId}：${it.message ?: "安装失败"}" }
        }
        if (failed.isNotEmpty()) {
            throw PackFormatException(
                "集合包已安装，但有 ${failed.size} 个成员失败：" + failed.joinToString("；")
            )
        }
    }

    private suspend fun installSingle(uri: Uri): InstalledPack = withContext(Dispatchers.IO) {
        mutex.withLock {
            packsRoot.mkdirs()
            tempRoot.mkdirs()
            val sourceZip = File.createTempFile("pack-", ".zip", tempRoot)
            val staging = File(tempRoot, "staging-" + System.nanoTime())
            try {
                // 解析前用最宽上限（AUDIO 2GB）预检并拷贝；解析出类型后再卡严格上限。
                copySource(uri, sourceZip, PackLimits.of(PackType.AUDIO).maxSourceBytes)
                val manifest = readManifest(sourceZip)
                val limits = PackLimits.of(manifest.type)
                require(sourceZip.length() <= limits.maxSourceBytes) {
                    "资源包文件超过 ${limits.maxSourceBytes / 1024 / 1024}MB 限制"
                }
                PackValidator.validateManifest(manifest, appVersionCode).requireOk()
                // 审查 7-12：同 packId 装**更低版本**先拒（原来直接 upsert 覆盖 = 静默降级）；
                // 升级与同版本重装仍放行。放在提取之前，被拒的包不会先解到临时区。
                registry().byId(manifest.packId)?.let { existing ->
                    require(!isVersionDowngrade(existing.version, manifest.version)) {
                        "已安装「${existing.manifest.nameZh.ifBlank { existing.packId }}」" +
                            "v${existing.version}，所选包是更旧的 v${manifest.version}。" +
                            "若确实要装这个版本，请先卸载已安装的包。"
                    }
                }
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
                // 同 packId 的旧版本目录：先记下来，**落位并写表之后再删**（审查 7-3）。
                // 旧顺序「删旧目录 → 落位 → 写表」在落位/写表失败或进程被杀时，registry
                // 会指向已被删除的旧目录 —— 活动词典静默回内置、新包成孤儿。
                val previousDir = registry().byId(manifest.packId)
                    ?.takeIf { it.dir != PackPaths.directoryFor(manifest.type, manifest.packId, manifest.version) }
                    ?.let { it.root(packsRoot) }
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
                // 登记表已不再引用旧目录，此时删除才是安全的；返回值即便失败也只是占空间。
                previousDir?.deleteRecursively()
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

    /**
     * 把 SAF 选中的包拷进临时区。
     *
     * 审查 7-6：**解析 manifest 之前先按元数据预检体积**，超上限直接拒、不拷贝。
     * 原实现在解析前无条件整份拷入（内部存储先被占满才报错）；严格上限要等 manifest
     * 解析出类型后才卡。这里先用 [ContentResolver] 的 `SIZE` 问一次，能拿到就提前拒。
     *
     * 拿不到 SIZE 时（部分 provider 不提供）回退：拷贝流程里仍保留逐块 `maxSourceBytes`
     * 兜底，因此不会无界写入。
     */
    private fun copySource(uri: Uri, target: File, maxSourceBytes: Long) {
        declaredSizeBytes(uri)?.let { declared ->
            require(declared <= maxSourceBytes) {
                "资源包文件过大：${declared / 1024 / 1024}MB 超过 ${maxSourceBytes / 1024 / 1024}MB 上限"
            }
        }
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
                    require(written <= maxSourceBytes) { "资源包文件过大" }
                    output.write(buffer, 0, read)
                }
            }
        }
        require(target.length() > 0) { "文件内容为空" }
    }

    /** SAF 文档声明的字节数；provider 不提供时返回 null（调用方需自行兜底）。 */
    private fun declaredSizeBytes(uri: Uri): Long? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                } else null
            }
    }.getOrNull()

    private fun readManifest(zip: File): PackManifest {
        val text = try {
            ZipFile(zip).use { archive ->
                val entry = archive.getEntry(PackManifest.FILE_NAME)
                    ?: archive.entries().asSequence().firstOrNull {
                        it.name.substringAfterLast('/') == PackManifest.FILE_NAME
                    }
                    ?: throw PackFormatException("这个文件不是资源包（缺少 manifest.json）")
                // 上限先于读取生效（审查 7-5）：`readBytes()` 无上限会被恶意超大条目打爆内存，
                // 而此时 SafeZip 的解压阈值还没轮到生效。
                val declared = entry.size
                if (declared > MAX_MANIFEST_BYTES) {
                    throw PackFormatException("资源包的 manifest.json 过大（${declared / 1024}KB），已拒绝")
                }
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                if (bytes.size > MAX_MANIFEST_BYTES) {
                    throw PackFormatException("资源包的 manifest.json 过大（${bytes.size / 1024}KB），已拒绝")
                }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (error: java.util.zip.ZipException) {
            // 非 zip / 畸形 zip：包成 PackFormatException 以复用本地化文案（审查 7-11），
            // 否则 ZipException 的英文 message 会原样进对话框。
            throw PackFormatException("这个文件不是有效的资源包（压缩包无法读取）")
        }
        return PackManifest.parse(text)
    }

    private fun stampOf(file: File): Long = if (file.isFile) file.lastModified() else -1L

    /**
     * 登记表读取结果：始终给出可用登记表（失败回退空表），另带一条**失败原因**。
     *
     * [error] 只在「文件存在但读不动」时非空——这是必须让用户看见的降级：包列表会
     * 整片变空、活动词典静默回内置，仅写 `Log.w` 等于让用户以为一切正常（第四轮
     * 审查 7-1）。查词本身仍按空表降级，不受影响。
     */
    data class RegistrySnapshot(val registry: PackRegistry, val error: String?)

    /**
     * 读登记表并**显式报告**失败原因（供 UI 展示降级提示）。
     *
     * 空文件/0 字节也算损坏：正常写出的登记表至少含 `{"version":…}`，截断成 0 字节
     * 只可能是写到一半失败，静默当空表会让全部已装包从界面消失。
     */
    fun reloadWithError(): RegistrySnapshot = synchronized(this) {
        val snapshot = readRegistrySnapshot()
        cached = snapshot.registry
        cachedStamp = stampOf(registryFile)
        snapshot
    }

    private fun readRegistry(): PackRegistry = readRegistrySnapshot().registry

    private fun readRegistrySnapshot(): RegistrySnapshot = runCatching {
        if (!registryFile.isFile) RegistrySnapshot(PackRegistry.EMPTY, null)
        else {
            val text = registryFile.readText()
            if (text.isBlank()) {
                RegistrySnapshot(PackRegistry.EMPTY, "登记表为空文件（0 字节或全空白）")
            } else {
                RegistrySnapshot(PackRegistry.parse(text), null)
            }
        }
    }.getOrElse { error ->
        Log.w(TAG, "资源包登记表读取失败，按空表处理：${error.message}")
        RegistrySnapshot(PackRegistry.EMPTY, error.message ?: error.javaClass.simpleName)
    }

    /**
     * 活动词典包是否**真的落盘**。
     *
     * `registry.activeDictionary` 只是登记表里的一句话；目录被外部清理、卸载失败残留、
     * 或 registry 与磁盘漂移时，[dictionarySource] 会静默回内置，而界面仍把它显示为
     * 已启用（第四轮审查 7-2）。这里给 UI 一个只读校验点，不改查词降级行为。
     */
    fun activeDictionaryFileMissing(): Boolean =
        registry().activeDictionaryPack()
            ?.let { pack -> pack.dictionaryFile(packsRoot)?.isFile != true }
            ?: false

    /** 原子写：`*.tmp` + rename，失败回退直写（与项目其余落盘纪律一致）。 */
    /**
     * 原子写登记表：`*.tmp` + `renameTo`。
     *
     * 审查 7-4：原回退分支直接覆盖 `registryFile`（非原子）——写一半被打断就会留下半截
     * 登记表，下次读成空表（放大 7-1 的静默失败）。现在两级都走"先写临时文件再 rename"：
     * 先把 tmp 写好；rename 失败时**重写一次 tmp**（tmp 可能已被 rename 消费）再 rename；
     * 两次都失败才直写最终文件作为最后手段。任一环节抛错都**不更新内存缓存**，避免
     * 「内存以为写成了、磁盘其实没有」。
     */
    private fun writeRegistry(registry: PackRegistry) {
        packsRoot.mkdirs()
        val json = registry.toJson()
        val temp = File(packsRoot, "$REGISTRY_NAME.tmp")
        temp.writeText(json)
        var wrote = temp.renameTo(registryFile)
        if (!wrote) {
            // rename 失败：tmp 可能仍存在也可能已被消费，重建一份再试一次。
            runCatching { temp.writeText(json) }
            wrote = temp.renameTo(registryFile)
        }
        if (!wrote) {
            // 最后手段：直写最终文件（非原子，但总比完全不写强）。
            registryFile.writeText(json)
            temp.delete()
        }
        cached = registry
        cachedStamp = stampOf(registryFile)
    }

    private fun PackValidationResult.requireOk() {
        if (this is PackValidationResult.Rejected) throw PackFormatException(reason)
    }

    /**
     * 待装版本是否**低于**已装版本（审查 7-12）。
     *
     * 比较按点分段的数字做（`1.10.0 > 1.9.0`，而不是字符串比较的 `"1.10.0" < "1.9.0"`）；
     * 段数不同时缺位当 0；出现非数字段（如 `1.0.0-beta`）时退化为字符串比较，
     * 宁可放行也不要误拒升级。
     */
    private fun isVersionDowngrade(installed: String, incoming: String): Boolean {
        val a = installed.split('.')
        val b = incoming.split('.')
        val numeric = a.all { it.toIntOrNull() != null } && b.all { it.toIntOrNull() != null }
        if (!numeric) return incoming < installed
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i)?.toIntOrNull() ?: 0
            val y = b.getOrNull(i)?.toIntOrNull() ?: 0
            if (y != x) return y < x
        }
        return false
    }

    companion object {
        const val DIR_NAME = "packs"
        const val REGISTRY_NAME = "registry.json"
        const val TEMP_DIR = ".tmp"

        /**
         * `manifest.json` 的读取上限。
         *
         * 正常清单是几十 KB 量级（音频包按章列文件）；这里给 1 MB 留足余量，同时挡住
         * 「导入一个声明了超大 manifest 条目的 zip」把内存打爆（第四轮审查 7-5）。
         */
        const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024

        private const val TAG = "PackRepository"
    }
}
