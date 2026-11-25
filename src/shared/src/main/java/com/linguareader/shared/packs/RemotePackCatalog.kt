package com.linguareader.shared.packs

import org.json.JSONObject
import java.net.URI
import java.util.Locale

data class RemotePack(
    val name: String,
    val downloadUrl: String,
    val bytes: Long,
    val sha256: String? = null,
    val mirrors: List<String> = emptyList(),
    val packId: String? = null,
    val version: String? = null,
    val type: PackType? = null,
    val minAppVersion: Int = 0
)

object RemotePackCatalog {
    const val DEFAULT_URL = "https://62.234.28.97:8443/packs/index.json"
    const val MAX_CATALOG_BYTES = 1024 * 1024
    const val MAX_PACK_BYTES = 2L * 1024 * 1024 * 1024
    private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.lrpack")
    private val digest = Regex("[0-9a-f]{64}")
    // Same path-safe identity contract as PackManifest; install still verifies the inner manifest.
    private val packId = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val version = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")

    fun validate(pack: RemotePack) {
        require(safeName.matches(pack.name)) { "资源包文件名不合法" }
        val maximum = pack.type?.let { PackLimits.of(it).maxSourceBytes } ?: MAX_PACK_BYTES
        require(pack.bytes in 1..maximum) { "资源包大小不合法或超过类型上限" }
        require(pack.sha256 == null || digest.matches(pack.sha256)) { "资源包 SHA-256 不合法" }
        require(pack.minAppVersion >= 0) { "资源包兼容版本不合法" }
        require((pack.packId == null) == (pack.version == null)) { "资源包标识和版本必须一起提供" }
        require(pack.packId == null || packId.matches(pack.packId)) { "资源包标识不合法" }
        require(pack.version == null || version.matches(pack.version)) { "资源包版本不合法" }
        require(pack.mirrors.size <= 4) { "资源包镜像过多" }
        val urls = listOf(pack.downloadUrl) + pack.mirrors
        urls.forEach { validateUrl(it) }
        require(urls.map { URI(it).normalize() }.distinct().size == urls.size) { "资源包下载地址重复" }
    }

    fun validateUrl(url: String): String {
        val parsed = URI(url)
        require(parsed.scheme.equals("https", true) && !parsed.host.isNullOrBlank() && parsed.rawUserInfo == null &&
            parsed.rawFragment == null && (parsed.port == -1 || parsed.port in 1..65535)) {
            "资源下载必须使用 HTTPS 地址"
        }
        return url
    }

    fun parse(body: String, appVersion: Int): List<RemotePack> {
        require(appVersion >= 0) { "应用版本不合法" }
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_CATALOG_BYTES) { "资源目录过大" }
        val json = JSONObject(body)
        require(integer(json, "formatVersion") == 1L) { "资源目录版本不受支持" }
        val entries = json.getJSONArray("packs")
        require(entries.length() <= 1000) { "资源目录条目过多" }
        val identities = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        return (0 until entries.length()).mapNotNull { index ->
            val item = entries.getJSONObject(index)
            val type = PackType.entries.firstOrNull { it.wire == string(item, "type") }
                ?: error("资源包类型不受支持")
            val packId = string(item, "packId")
            val version = string(item, "version")
            require(identities.add("$packId/$version")) { "资源目录包含重复版本" }
            val minimum = integer(item, "minAppVersion").also { require(it <= Int.MAX_VALUE) }.toInt()
            val urls = item.getJSONArray("urls")
            require(urls.length() in 1..5) { "资源包缺少下载地址" }
            val pack = RemotePack(
                string(item, "name"), (urls.get(0) as? String) ?: error("资源下载地址类型不合法"), integer(item, "bytes"),
                string(item, "sha256").lowercase(),
                (1 until urls.length()).map { (urls.get(it) as? String) ?: error("资源下载地址类型不合法") },
                packId, version, type, minimum
            )
            validate(pack)
            require(names.add(pack.name.lowercase(Locale.ROOT))) { "资源目录包含重复文件名" }
            pack.takeIf { minimum <= appVersion }
        }
    }

    private fun string(json: JSONObject, key: String): String =
        (json.get(key) as? String) ?: error("资源目录字段类型不合法：$key")

    /** getLong/getInt coerce strings and truncate fractions; wire integers must be exact. */
    private fun integer(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Number && value.toString().matches(Regex("[0-9]+"))) { "资源目录整数不合法：$key" }
        return requireNotNull(value.toString().toLongOrNull()) { "资源目录整数超出范围：$key" }
    }
}
